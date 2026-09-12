package com.orange.playerlibrary;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.LinearLayout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 视频嗅探工具类
 * 通过 WebView 拦截网络请求，识别视频资源
 * 
 * Requirements: 6.1 - THE OrangevideoView SHALL 支持视频嗅探功能 (startSniffing)
 */
public class VideoSniffing {
    
    private static final String TAG = "VideoSniffing";
    
    /** 调试模式 */
    public static boolean isDebug = true;  // 启用调试日志
    
    @SuppressLint("StaticFieldLeak")
    private static WebView webView;
    
    /** 视频信息集合（去重存储）*/
    private static Set<VideoInfo> videoInfoSet = new HashSet<>();
    
    /** 主线程 Handler */
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    /** 结束延迟 Handler */
    private static Handler finishHandler = new Handler(Looper.getMainLooper());
    
    /** 结束延迟判断时间（毫秒）*/
    private static final long FINISH_DELAY = 3000;
    
    /** 存储正在进行的网络连接，用于 stop 时取消 */
    private static Set<HttpURLConnection> activeConnections = new HashSet<>();
    
    /** 当前回调 */
    private static Call currentCall;
    
    /** 网络请求线程池（最多 1 个并发请求，最大限度减少资源占用）*/
    private static ExecutorService networkExecutor = Executors.newFixedThreadPool(1);
    
    /** 并发控制信号量（最多 2 个并发网络请求，进一步限制）*/
    private static Semaphore concurrentRequestSemaphore = new Semaphore(2);
    
    /** 已处理的 URL 集合（避免重复请求）*/
    private static Set<String> processedUrls = new HashSet<>();

    /** 当前嗅探 WebViewClient（JS 桥接回调需要复用它的校验与结果分发）*/
    private static VideoWebViewClient currentClient;

    /**
     * 注入页面的媒体嗅探脚本（借鉴 cat-catch 思路，自实现）。
     * shouldInterceptRequest 能看到所有网络请求，但只能按 URL 形态猜测；
     * 而 hls.js/dash.js 等 MSE 播放器的分片与清单 URL 往往没有可辨识后缀，
     * 或响应 Content-Type 为 octet-stream。页面里的 JS 知道"播放器拿哪个
     * URL 当媒体用了"，语义更准：
     * - hook XHR/fetch：拿到响应后按 content-type + 扩展名判定媒体 URL 上报
     * - 扫描 <video> 元素：播放列表指定的直链（非 blob:/data:）也上报
     * 通过 OrangeSniff 接口回调到 Java 侧，复用与网络拦截路径相同的
     * 二次验证与去重逻辑。重复注入由 __orangeSniffOn 幂等守卫挡住。
     */
    private static final String SNIFF_JS =
            "(function(){"
            + "if (window.__orangeSniffOn) return; window.__orangeSniffOn = true;"
            + "var seen = {};"
            + "function abs(u){ try { return new URL(u, location.href).href; } catch(e){ return null; } }"
            + "function mediaLike(u, ct){"
            + "  if (ct) { var c = ct.toLowerCase();"
            + "    if (c.indexOf('video/') === 0 || c.indexOf('audio/') === 0 || c.indexOf('mpegurl') >= 0) return true; }"
            + "  var path = u.split('?')[0].split('#')[0].toLowerCase();"
            // .ts/.m4s 是分片，逐个上报只会淹没结果列表——只报清单与完整容器
            + "  return /\\.(m3u8|mp4|flv|avi|mov|mkv|webm|mpd)$/.test(path);"
            + "}"
            + "function rep(u, ct, src){ try {"
            + "  if (!u || seen[u]) return; seen[u] = 1;"
            + "  if (window.OrangeSniff) window.OrangeSniff.report(u, ct || '', src);"
            + "} catch(e){} }"
            + "var XO = XMLHttpRequest.prototype.open, XS = XMLHttpRequest.prototype.send;"
            + "XMLHttpRequest.prototype.open = function(m, u){ try { this.__su = u; } catch(e){}"
            + "  return XO.apply(this, arguments); };"
            + "XMLHttpRequest.prototype.send = function(){ var x = this; try { x.addEventListener('load', function(){ try {"
            + "  if (x.status >= 200 && x.status < 400) { var ct = ''; try { ct = x.getResponseHeader('content-type') || ''; } catch(e){}"
            + "    var au = abs(x.__su); if (au && mediaLike(au, ct)) rep(au, ct, 'xhr'); } } catch(e){} }); } catch(e){}"
            + "  return XS.apply(this, arguments); };"
            + "var OF = window.fetch;"
            + "if (OF) { window.fetch = function(input, init){ var u = ''; try {"
            + "  u = (typeof input === 'string') ? input : (input && input.url) || ''; } catch(e){}"
            + "  var p = OF.apply(this, arguments); try { p.then(function(r){ try {"
            + "    var ct = ''; try { ct = r.headers.get('content-type') || ''; } catch(e){}"
            + "    var au = abs(r.url || u); if (au && mediaLike(au, ct)) rep(au, ct, 'fetch'); } catch(e){} }); } catch(e){}"
            + "  return p; }; }"
            + "function scanMedia(){ try { var vids = document.querySelectorAll('video');"
            + "  for (var i = 0; i < vids.length; i++) { var s = vids[i].currentSrc || vids[i].src;"
            + "    if (s && s.indexOf('blob:') !== 0 && s.indexOf('data:') !== 0) {"
            + "      var au = abs(s); if (au && mediaLike(au, '')) rep(au, '', 'video'); } } } catch(e){} }"
            + "try { new MutationObserver(scanMedia).observe(document.documentElement,"
            + "  { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] }); } catch(e){}"
            + "try { document.addEventListener('play', scanMedia, true); } catch(e){}"
            + "scanMedia();"
            + "})();";

    /**
     * 嗅探回调接口
     */
    public interface Call {
        /**
         * 接收到视频资源
         * @param contentType 内容类型
         * @param headers 响应头
         * @param title 视频标题
         * @param url 视频地址
         */
        void received(String contentType, HashMap<String, String> headers, String title, String url);
        
        /**
         * 嗅探完成
         * @param videoList 视频列表
         * @param videoSize 视频数量
         */
        void onFinish(List<VideoInfo> videoList, int videoSize);
    }

    /**
     * 创建空资源响应
     */
    public static WebResourceResponse createEmptyResource() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
    }

    /**
     * 初始化 WebView
     */
    @SuppressLint("SetJavaScriptEnabled")
    private static void initWebView(WebView webView2) {
        // 始终使用 0x0 尺寸，即使在调试模式下也最小化浏览器控件
        webView2.setLayoutParams(new ViewGroup.LayoutParams(0, 0));

        WebSettings settings = webView2.getSettings();
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);  // 禁用弹窗
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);  // 禁用文件访问
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setBlockNetworkImage(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setLoadsImagesAutomatically(false);  // 禁用图片加载
        settings.setGeolocationEnabled(false);  // 禁用地理位置
        settings.setDatabaseEnabled(false);  // 禁用数据库

        if (isDebug && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }


    /**
     * 判断是否为视频资源
     * @param str 内容类型或 URL
     * @return true 是视频资源
     */
    public static Boolean isVideoSource(String str) {
        if (str == null) return false;
        if (str.contains("text/plain; charset=utf-8") || str.contains("text/html")
                || str.contains("application/json") || str.contains("application/javascript")) {
            return false;
        }
        return str.contains("video/")
                || str.contains("application/vnd.apple.mpegurl")
                || str.contains("application/x-mpegurl")
                || str.contains("application/octet-stream")
                || str.contains(".mp4") || str.contains(".m3u8")
                || str.contains(".flv") || str.contains(".avi")
                || str.contains(".mov") || str.contains(".mkv");
    }

    /**
     * 开始视频嗅探（带自定义请求头）
     * @param context 上下文
     * @param url 网页地址
     * @param customHeaders 自定义请求头
     * @param call 回调
     */
    @SuppressLint("SetJavaScriptEnabled")
    public static void startSniffing(Context context, String url, Map<String, String> customHeaders, Call call) {
        // 初始化：清空历史数据、取消未完成任务
        stop(false);
        videoInfoSet.clear();
        finishHandler.removeCallbacksAndMessages(null);
        activeConnections.clear();
        processedUrls.clear();  // 清空已处理 URL 集合
        currentCall = call;

        if (!(context instanceof Activity)) {
            if (call != null) {
                call.onFinish(new ArrayList<>(), 0);
            }
            return;
        }
        Activity activity = (Activity) context;

        WebView webView2 = new WebView(activity);
        webView = webView2;
        initWebView(webView2);

        // 始终使用 0x0 尺寸，即使在调试模式下也最小化浏览器控件
        activity.addContentView(webView, new LinearLayout.LayoutParams(0, 0));

        // 加载 URL 时携带自定义请求头
        VideoWebViewClient client = new VideoWebViewClient(activity, call, customHeaders);
        currentClient = client;
        webView.setWebViewClient(client);

        // JS 侧媒体嗅探桥接：注入脚本 hook fetch/XHR，捕获 MSE 播放器
        // （hls.js/dash.js）使用的、无扩展名可辨的媒体 URL。上报后走与网络
        // 拦截相同的验证 + 去重路径。允许第三方页访问此接口是嗅探的必要前提。
        webView.addJavascriptInterface(new SniffBridge(), "OrangeSniff");
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView2, true);

        if (customHeaders != null && !customHeaders.isEmpty()) {
            webView.loadUrl(url, customHeaders);
        } else {
            webView.loadUrl(url);
        }
    }

    /**
     * 开始视频嗅探
     * @param context 上下文
     * @param url 网页地址
     * @param call 回调
     */
    public static void startSniffing(Context context, String url, Call call) {
        startSniffing(context, url, null, call);
    }

    /**
     * 停止嗅探
     * @param z 是否完成
     */
    public static void stop(boolean z) {
        // 1. 取消所有未完成的网络请求
        synchronized (activeConnections) {
            for (HttpURLConnection conn : activeConnections) {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Exception e) {
                        // 忽略断开连接时的异常
                    }
                }
            }
            activeConnections.clear();
        }

        // 2. 清理延迟任务和回调引用
        finishHandler.removeCallbacksAndMessages(null);
        currentCall = null;
        currentClient = null;

        // 3. 释放所有信号量许可
        concurrentRequestSemaphore.drainPermits();
        concurrentRequestSemaphore.release(2);  // 修改为 2 个许可
        
        // 4. 清空已处理 URL 集合
        processedUrls.clear();

        // 5. 销毁 WebView
        WebView webView2 = webView;
        if (webView2 != null) {
            webView2.stopLoading();
            webView2.loadUrl("about:blank");
            ViewGroup parent = (ViewGroup) webView2.getParent();
            if (parent != null) {
                parent.removeView(webView2);
            }
            webView2.destroy();
            webView = null;
        }
    }

    /**
     * 触发完成回调
     */
    private static void triggerFinishCallback() {
        if (currentCall == null || webView == null) {
            return;
        }
        List<VideoInfo> videoList = new ArrayList<>(videoInfoSet);
        currentCall.onFinish(videoList, videoList.size());
        stop(true);
    }

    /**
     * 注入脚本回传到 Java 侧的桥。回调运行在 WebView 的 JavaBridge 线程，
     * scheduleVerification 内部已做线程安全处理。
     */
    private static class SniffBridge {
        @JavascriptInterface
        public void report(String url, String contentType, String source) {
            final VideoWebViewClient client = currentClient;
            if (client == null || url == null || url.isEmpty()) {
                return;
            }
            // JS 侧已按"播放器真正使用过"筛出候选媒体 URL，这里仍复用同一套
            // 二次验证（HEAD 请求确认响应），避免误报；页面请求方法未知，用 GET
            client.scheduleVerification(url, "GET", "js:" + source);
        }
    }


    /**
     * 视频 WebView 客户端
     */
    private static class VideoWebViewClient extends WebViewClient {
        private final Activity activity;
        private final Call call;
        private String currentTitle;
        private final Map<String, String> customHeaders;

        public VideoWebViewClient(Activity activity, Call call, Map<String, String> customHeaders) {
            this.activity = activity;
            this.call = call;
            this.customHeaders = customHeaders;
            mainHandler.post(() -> {
                if (webView != null) {
                    currentTitle = webView.getTitle();
                }
            });
        }

        private void handleResponse(String contentType, HashMap<String, String> headers,
                                    String title, String url) {
            VideoInfo videoInfo = new VideoInfo(url, contentType, title, headers);
            videoInfoSet.add(videoInfo);
            if (call != null) {
                call.received(contentType, headers, title, url);
            }
            resetFinishDelay();
        }

        private void resetFinishDelay() {
            finishHandler.removeCallbacksAndMessages(null);
            finishHandler.postDelayed(VideoSniffing::triggerFinishCallback, FINISH_DELAY);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            mainHandler.post(() -> {
                if (view == webView) {
                    currentTitle = view.getTitle();
                }
            });
            // 尽早注入：此时初始 HTML 已可访问、外部脚本通常尚未执行完，
            // fetch/XHR 的 hook 赶在播放器发起媒体请求之前。脚本自带幂等守卫。
            try {
                view.evaluateJavascript(SNIFF_JS, null);
            } catch (Exception e) {
                if (isDebug) {
                    Log.e(TAG, "嗅探脚本注入失败: " + url, e);
                }
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            // 兜底再注入一次（动态渲染的站点可能主文档没有 video/播放器）
            try {
                view.evaluateJavascript(SNIFF_JS, null);
            } catch (Exception e) {
                if (isDebug) {
                    Log.e(TAG, "嗅探脚本补注入失败: " + url, e);
                }
            }
            mainHandler.post(() -> {
                if (view != webView) {
                    return;
                }
                currentTitle = view.getTitle();
                resetFinishDelay();
            });
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            String reqContentType = request.getRequestHeaders().get("content-type");
            String lowerUrl = url.toLowerCase();

            // 1. 过滤图片资源（所有常见格式）
            if (lowerUrl.contains(".jpg") || lowerUrl.contains(".jpeg") 
                    || lowerUrl.contains(".png") || lowerUrl.contains(".gif")
                    || lowerUrl.contains(".webp") || lowerUrl.contains(".bmp")
                    || lowerUrl.contains(".svg") || lowerUrl.contains(".ico")
                    || lowerUrl.contains(".avif") || lowerUrl.contains(".heic")) {
                return createEmptyResource();
            }

            // 2. 过滤样式和字体资源
            if (lowerUrl.contains(".css") || lowerUrl.contains(".ttf")
                    || lowerUrl.contains(".woff") || lowerUrl.contains(".woff2")
                    || lowerUrl.contains(".eot") || lowerUrl.contains(".otf")) {
                return createEmptyResource();
            }

            // 3. 过滤广告和统计资源
            if (lowerUrl.contains("analytics") || lowerUrl.contains("tracking")
                    || lowerUrl.contains("advertisement") || lowerUrl.contains("/ads/")
                    || lowerUrl.contains("google-analytics") || lowerUrl.contains("doubleclick")
                    || lowerUrl.contains("facebook.com/tr") || lowerUrl.contains("baidu.com/hm.js")
                    || lowerUrl.contains("cnzz.com") || lowerUrl.contains("umeng.com")) {
                return createEmptyResource();
            }

            // 4. 过滤社交媒体插件
            if (lowerUrl.contains("facebook.com/plugins") || lowerUrl.contains("twitter.com/widgets")
                    || lowerUrl.contains("platform.linkedin") || lowerUrl.contains("connect.qq.com")
                    || lowerUrl.contains("weibo.com/aj/") || lowerUrl.contains("share.baidu.com")) {
                return createEmptyResource();
            }

            // 5. 过滤 Content-Type 为非视频的资源
            if (reqContentType != null) {
                if (reqContentType.contains("text/css") || reqContentType.contains("text/html")
                        || reqContentType.contains("image/") || reqContentType.contains("font/")
                        || reqContentType.contains("application/javascript")
                        || reqContentType.contains("application/json")
                        || reqContentType.contains("text/javascript")) {
                    return createEmptyResource();
                }
            }

            // 6. 过滤 JS 和 JSON 文件（但不阻止它们加载，只是不检查）
            if (lowerUrl.contains(".js") || lowerUrl.contains(".json")
                    || "application/json".equals(reqContentType)) {
                return super.shouldInterceptRequest(view, request);  // 让它们正常加载
            }

            // 快速检查：如果 URL 明显不是视频，直接跳过
            if (!isPotentialVideoUrl(url)) {
                return super.shouldInterceptRequest(view, request);
            }

            if (isDebug) {
                Log.d(TAG, "检查潜在视频 URL: " + url);
            }

            // 异步二次验证。请求方法与页面请求保持一致，由 shouldInterceptRequest
            // 与 JS 桥接（SniffBridge）两条路径共用。
            scheduleVerification(url, request.getMethod(), "net");

            // 关键修复：让 WebView 正常加载资源，我们只是在后台异步检查
            return super.shouldInterceptRequest(view, request);
        }

        /**
         * 对候选 URL 做异步二次验证：独立发起请求确认响应是视频资源，
         * 通过则连同响应头一起上报。去重、并发限制、结果分发与旧网络拦截
         * 路径完全一致（两条路径共用，行为不变）。
         *
         * @param method 请求方法（GET/HEAD）
         * @param source 来源标记，仅用于日志（net=网络拦截，js=页面注入）
         */
        void scheduleVerification(String url, String method, String source) {
            // 去重检查：如果已经处理过这个 URL，直接跳过
            synchronized (processedUrls) {
                if (processedUrls.contains(url)) {
                    if (isDebug) {
                        Log.d(TAG, "URL 已处理，跳过: " + url);
                    }
                    return;
                }
                processedUrls.add(url);
            }

            // 尝试获取信号量许可（非阻塞）
            if (!concurrentRequestSemaphore.tryAcquire()) {
                // 并发请求过多，跳过此请求
                if (isDebug) {
                    Log.d(TAG, "并发请求过多，跳过: " + url);
                }
                synchronized (processedUrls) {
                    processedUrls.remove(url);  // 移除标记，允许下次重试
                }
                return;
            }

            if (isDebug) {
                Log.d(TAG, "开始异步检查视频(" + source + "): " + url);
            }

            final WebView sniffView = webView;
            // 在线程池中异步执行网络请求
            networkExecutor.execute(() -> {
                HttpURLConnection connection = null;
                try {
                    URL requestUrl = new URL(url);
                    connection = (HttpURLConnection) requestUrl.openConnection();

                    synchronized (activeConnections) {
                        activeConnections.add(connection);
                    }

                    connection.setRequestMethod(method);
                    if (customHeaders != null) {
                        for (Map.Entry<String, String> header : customHeaders.entrySet()) {
                            connection.setRequestProperty(header.getKey(), header.getValue());
                        }
                    }
                    // 进一步减少超时时间：从 3 秒降到 2 秒
                    connection.setConnectTimeout(2000);
                    connection.setReadTimeout(2000);
                    connection.setInstanceFollowRedirects(true);
                    connection.connect();

                    int responseCode = connection.getResponseCode();
                    if (isDebug) {
                        Log.d(TAG, "响应码: " + responseCode + " URL: " + url);
                    }

                    if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                        if (isDebug) {
                            Log.d(TAG, "响应码不匹配，跳过: " + url);
                        }
                        return;
                    }

                    String respContentType = connection.getHeaderField("Content-Type");
                    if (isDebug) {
                        Log.d(TAG, "Content-Type: " + respContentType + " URL: " + url);
                    }

                    // 只处理视频资源
                    if (isVideoSource(respContentType) || isVideoSource(url)) {
                        if (isDebug) {
                            Log.d(TAG, "发现视频资源！Content-Type: " + respContentType + " URL: " + url);
                        }

                        HashMap<String, String> headers = new HashMap<>();
                        for (int i = 0; ; i++) {
                            String key = connection.getHeaderFieldKey(i);
                            String value = connection.getHeaderField(i);
                            if (key == null && value == null) break;
                            if (key != null) {
                                headers.put(key, value);
                            }
                        }

                        mainHandler.post(() -> {
                            if (webView == null || sniffView != webView) {
                                return;
                            }
                            handleResponse(respContentType, headers, currentTitle, url);
                        });
                    } else {
                        if (isDebug) {
                            Log.d(TAG, "不是视频资源，跳过: " + url);
                        }
                    }

                } catch (Exception e) {
                    if (isDebug) {
                        Log.e(TAG, "网络请求异常: " + url, e);
                    }
                } finally {
                    // 从已处理集合中移除（如果请求失败，允许重试）
                    synchronized (processedUrls) {
                        processedUrls.remove(url);
                    }
                    if (connection != null) {
                        synchronized (activeConnections) {
                            activeConnections.remove(connection);
                        }
                        try {
                            connection.disconnect();
                        } catch (Exception e) {
                            // 忽略断开连接异常
                        }
                    }
                    // 释放信号量许可
                    concurrentRequestSemaphore.release();
                }
            });
        }
        
        /**
         * 快速检查 URL 是否可能是视频资源
         */
        private boolean isPotentialVideoUrl(String url) {
            if (url == null) return false;
            String lowerUrl = url.toLowerCase();
            return lowerUrl.contains(".mp4") || lowerUrl.contains(".m3u8")
                    || lowerUrl.contains(".flv") || lowerUrl.contains(".avi")
                    || lowerUrl.contains(".mov") || lowerUrl.contains(".mkv")
                    || lowerUrl.contains("video") || lowerUrl.contains("stream")
                    || lowerUrl.contains("play");
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            return !(url.startsWith("http://") || url.startsWith("https://"));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return !(url.startsWith("http://") || url.startsWith("https://"));
        }
    }


    /**
     * 视频信息类
     */
    public static class VideoInfo {
        /** 视频地址 */
        public String url;
        /** 内容类型 */
        public String contentType;
        /** 视频标题 */
        public String title;
        /** 响应头信息 */
        public HashMap<String, String> headers;

        public VideoInfo(String url, String contentType, String title, HashMap<String, String> headers) {
            this.url = url;
            this.contentType = contentType;
            this.title = title;
            this.headers = headers;
        }

        /**
         * 清洗 URL（去重关键：忽略查询参数）
         */
        private String cleanUrl() {
            if (url == null) return "";
            int queryIndex = url.indexOf("?");
            return queryIndex != -1 ? url.substring(0, queryIndex) : url;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            VideoInfo videoInfo = (VideoInfo) o;
            return cleanUrl().equals(videoInfo.cleanUrl());
        }

        @Override
        public int hashCode() {
            return cleanUrl().hashCode();
        }
    }
}
