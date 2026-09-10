package com.orange.playerlibrary.cache;

import android.content.Context;
import android.util.Log;

import com.danikula.videocache.HttpProxyCacheServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地 HLS 代理服务器：让非 Exo 内核（ijk/mpv/系统）也能复用磁盘缓存。
 *
 * 数据流（播放器 ← /hls/{encodedUrl}）：
 *   playlist：抓取原 playlist（透传 Referer/UA，跟随重定向取最终 URL），
 *     用 {@link HlsPlaylistRewriter} 逐行重写——
 *     分片/密钥/init 直接映射为 danikula 代理 URL（磁盘 LRU + Range，
 *     二次播放零流量）；子 playlist 映射回本路由（按需重取，直播也对）。
 *
 * 复用说明：分片缓存与 Range 完全由 danikula HttpProxyCacheServer 承担，
 * 本类只做 playlist 文本重写；与去广告（内容级替换占位）管线正交，
 * 可在数据源层叠加。分片用「直接重写」而非 302 跳转——ffmpeg/ijk 对
 * 302 的跟随行为不保证，直接给出最终地址最稳。
 */
public final class HlsProxyServer {

    private static final String TAG = "HlsProxyServer";

    private static final String PATH_HLS = "hls/";

    private static final String CONTENT_TYPE_M3U8 = "application/vnd.apple.mpegurl";

    private static final int FETCH_TIMEOUT_MS = 10000;

    private static volatile HlsProxyServer sInstance;

    private final Context mContext;
    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private final ExecutorService mClientExecutor = Executors.newCachedThreadPool();

    /** 重写后的 playlist 缓存（LRU 上限 32），避免播放器反复请求重复抓取重写 */
    private final Map<String, byte[]> mPlaylistCache =
            new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > 32;
                }
            };

    /** danikula 代理：分片缓存的唯一承担者 */
    private volatile HttpProxyCacheServer mSegmentProxy;

    private ServerSocket mServerSocket;
    private Thread mAcceptThread;
    private int mPort;

    public static HlsProxyServer getInstance(Context context) {
        if (sInstance == null) {
            synchronized (HlsProxyServer.class) {
                if (sInstance == null) {
                    sInstance = new HlsProxyServer(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private HlsProxyServer(Context context) {
        mContext = context;
    }

    public synchronized void startIfNeeded() {
        if (mRunning.get()) {
            return;
        }
        try {
            mServerSocket = new ServerSocket(0);
            mPort = mServerSocket.getLocalPort();
            mRunning.set(true);
            mAcceptThread = new Thread(this::acceptLoop, "HlsProxyServer");
            mAcceptThread.start();
            Log.d(TAG, "Started on port=" + mPort);
        } catch (IOException e) {
            Log.e(TAG, "start failed", e);
        }
    }

    public void stop() {
        mRunning.set(false);
        synchronized (this) {
            if (mServerSocket != null) {
                try {
                    mServerSocket.close();
                } catch (IOException ignored) {
                }
                mServerSocket = null;
            }
        }
        if (mAcceptThread != null) {
            mAcceptThread.interrupt();
            mAcceptThread = null;
        }
    }

    public int getPort() {
        return mPort;
    }

    /**
     * 把原始 HLS 地址换成本地代理地址。
     * 原始 URL 整体 URLEncoder 后作为路径参数（与 danikula 代理同样的编码约定）。
     * 本地/非 http 地址原样返回。
     */
    public String proxyUrl(String hlsUrl) {
        if (hlsUrl == null || hlsUrl.isEmpty() || isLocalProxyUrl(hlsUrl)) {
            return hlsUrl;
        }
        String lower = hlsUrl.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return hlsUrl;
        }
        startIfNeeded();
        if (!mRunning.get()) {
            return hlsUrl;
        }
        try {
            String encoded = java.net.URLEncoder.encode(hlsUrl, "utf-8");
            return "http://127.0.0.1:" + mPort + "/" + PATH_HLS + encoded;
        } catch (Exception e) {
            Log.w(TAG, "proxyUrl encode failed", e);
            return hlsUrl;
        }
    }

    /** 是否为本代理（或 danikula 代理）的回环地址，防止套娃 */
    public static boolean isLocalProxyUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("127.0.0.1") || lower.contains("localhost");
    }

    private void acceptLoop() {
        while (mRunning.get()) {
            try {
                Socket client = mServerSocket.accept();
                mClientExecutor.execute(() -> handleClient(client));
            } catch (IOException e) {
                if (mRunning.get()) {
                    Log.e(TAG, "accept failed", e);
                }
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            String request = readRequest(client.getInputStream());
            if (request == null || request.isEmpty()) {
                closeQuietly(client);
                return;
            }
            String[] lines = request.split("\r\n");
            String[] first = lines[0].split(" ");
            if (first.length < 2) {
                closeQuietly(client);
                return;
            }
            String method = first[0];
            String path = first[1];

            if (!path.startsWith("/" + PATH_HLS)) {
                writeSimpleResponse(client.getOutputStream(), 404, "not found");
                closeQuietly(client);
                return;
            }
            String encodedUrl = path.substring(1 + PATH_HLS.length());
            String originalUrl = URLDecoder.decode(encodedUrl, "utf-8");
            Map<String, String> headers = parseHeaders(lines);

            Log.d(TAG, "Request " + method + " " + originalUrl);

            if (isPlaylistRequest(originalUrl)) {
                handlePlaylist(client, method, originalUrl, headers);
            } else {
                // 兜底：未被重写捕获的资源（如 EXT-X-MEDIA 之外的边缘 tag），
                // 交给 danikula 代理（同样承担缓存）。allowCachedFileUri=false
                // 避免 302 Location 为 file:// URI。
                String fallback = getSegmentProxy(mContext).getProxyUrl(originalUrl, false);
                Log.d(TAG, "Fallback resource -> " + fallback);
                writeRedirect(client.getOutputStream(), fallback);
                closeQuietly(client);
            }
        } catch (Throwable t) {
            Log.e(TAG, "handleClient failed", t);
            closeQuietly(client);
        }
    }

    private static boolean isPlaylistRequest(String url) {
        String lower = url.toLowerCase(Locale.US);
        int q = lower.indexOf('?');
        if (q >= 0) {
            lower = lower.substring(0, q);
        }
        return lower.endsWith(".m3u8");
    }

    /** 抓取原 playlist → 重写 → 回给播放器 */
    private void handlePlaylist(Socket client, String method, String playlistUrl,
                                Map<String, String> requestHeaders) throws IOException {
        byte[] body = mPlaylistCache.get(playlistUrl);
        if (body == null) {
            Fetched fetched = fetchText(playlistUrl, requestHeaders);
            if (fetched == null) {
                writeSimpleResponse(client.getOutputStream(), 502, "upstream fetch failed");
                closeQuietly(client);
                return;
            }
            body = rewritePlaylist(fetched.text, fetched.finalUrl, requestHeaders)
                    .getBytes(StandardCharsets.UTF_8);
            mPlaylistCache.put(playlistUrl, body);
        }

        OutputStream out = client.getOutputStream();
        String header = String.format(Locale.US,
                "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: %s\r\n"
                        + "Content-Length: %d\r\n"
                        + "Cache-Control: no-cache\r\n"
                        + "Connection: close\r\n\r\n",
                CONTENT_TYPE_M3U8, body.length);
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));
        if (!"HEAD".equalsIgnoreCase(method)) {
            out.write(body);
        }
        out.flush();
        closeQuietly(client);
    }

    /** 重写 playlist：分片→danikula 代理，子 playlist→本路由 */
    private String rewritePlaylist(String content, String finalUrl,
                                   Map<String, String> requestHeaders) {
        boolean master = HlsPlaylistRewriter.isMasterPlaylist(content);
        HttpProxyCacheServer segProxy = getSegmentProxy(mContext);
        HlsProxyServer self = this;

        // master 变体/音轨 playlist → 回本代理路由（按需重取，直播也对）
        HlsPlaylistRewriter.UrlMapper playlistMapper = abs -> self.proxyUrl(abs);
        // 分片/密钥/init → danikula 代理（磁盘 LRU + Range）
        // allowCachedFileUri=false：返回 http 代理 URL 而非 file://，playlist 内必须是 http 地址
        HlsPlaylistRewriter.UrlMapper segmentMapper = abs -> segProxy.getProxyUrl(abs, false);

        String rewritten = HlsPlaylistRewriter.rewrite(content, finalUrl, master,
                playlistMapper, segmentMapper);
        Log.d(TAG, "Rewritten playlist " + finalUrl
                + " master=" + master + " len=" + (rewritten != null ? rewritten.length() : 0));
        return rewritten != null ? rewritten : content;
    }

    /** danikula 代理懒创建（独立实例，承担 HLS 分片磁盘缓存） */
    private HttpProxyCacheServer getSegmentProxy(Context context) {
        HttpProxyCacheServer proxy = mSegmentProxy;
        if (proxy == null) {
            synchronized (this) {
                proxy = mSegmentProxy;
                if (proxy == null) {
                    proxy = new HttpProxyCacheServer.Builder(context)
                            .maxCacheSize(1024L * 1024 * 1024)
                            .maxCacheFilesCount(50)
                            .build();
                    mSegmentProxy = proxy;
                    Log.d(TAG, "Segment proxy created");
                }
            }
        }
        return proxy;
    }

    /** 抓取结果：最终 URL（跟随重定向后）+ 文本 */
    private static final class Fetched {
        final String finalUrl;
        final String text;

        Fetched(String finalUrl, String text) {
            this.finalUrl = finalUrl;
            this.text = text;
        }
    }

    private Fetched fetchText(String url, Map<String, String> requestHeaders) {
        // HttpURLConnection 不跟随 http↔https 跨协议重定向，HLS 源站这种跳转很常见，
        // 这里手动跟随并保留原始请求头（Referer/UA 是多数防盗链源的必需项）。
        String currentUrl = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(currentUrl);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(FETCH_TIMEOUT_MS);
                conn.setReadTimeout(FETCH_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(false);
                applyRequestHeaders(conn, requestHeaders);
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        Log.w(TAG, "fetchText redirect without Location: " + currentUrl);
                        return null;
                    }
                    String next = new URL(new URL(currentUrl), location).toString();
                    Log.d(TAG, "fetchText redirect " + code + " " + currentUrl + " -> " + next);
                    currentUrl = next;
                    continue;
                }
                if (code < 200 || code >= 300) {
                    Log.w(TAG, "fetchText http " + code + " for " + currentUrl);
                    return null;
                }
                // 最终 URL：相对分片必须以它为基准解析
                String finalUrl = conn.getURL().toString();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (InputStream in = conn.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                }
                String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                Log.d(TAG, "fetchText " + url + " -> " + text.length() + " bytes (final=" + finalUrl + ")");
                return new Fetched(finalUrl, text);
            } catch (Exception e) {
                Log.w(TAG, "fetchText failed for " + currentUrl, e);
                return null;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        Log.w(TAG, "fetchText too many redirects for " + url);
        return null;
    }

    private void applyRequestHeaders(HttpURLConnection conn, Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String key = e.getKey();
            if (key == null || e.getValue() == null) {
                continue;
            }
            // 跳过会导致转发错误或环回的逐跳头
            if ("Range".equalsIgnoreCase(key) || "Host".equalsIgnoreCase(key)
                    || "Accept-Encoding".equalsIgnoreCase(key)
                    || "Connection".equalsIgnoreCase(key)
                    || "Content-Length".equalsIgnoreCase(key)) {
                continue;
            }
            try {
                conn.setRequestProperty(key, e.getValue());
            } catch (Exception ignored) {
            }
        }
        if (conn.getRequestProperty("User-Agent") == null) {
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) OrangePlayer");
        }
    }

    private Map<String, String> parseHeaders(String[] lines) {
        Map<String, String> headers = new ConcurrentHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int idx = lines[i].indexOf(':');
            if (idx > 0) {
                headers.put(lines[i].substring(0, idx).trim(),
                        lines[i].substring(idx + 1).trim());
            }
        }
        return headers;
    }

    private String readRequest(InputStream input) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            if (sb.indexOf("\r\n\r\n") >= 0) {
                break;
            }
        }
        return sb.toString();
    }

    private void writeRedirect(OutputStream out, String location) throws IOException {
        String resp = String.format(Locale.US,
                "HTTP/1.1 302 Found\r\nLocation: %s\r\nContent-Length: 0\r\n"
                        + "Connection: close\r\n\r\n", location);
        out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private void writeSimpleResponse(OutputStream out, int code, String message)
            throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        String reason = code == 404 ? "Not Found" : code == 502 ? "Bad Gateway" : "OK";
        String header = String.format(Locale.US,
                "HTTP/1.1 %d %s\r\nContent-Type: text/plain\r\nContent-Length: %d\r\n"
                        + "Connection: close\r\n\r\n", code, reason, body.length);
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
