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

import com.orange.playerlibrary.sniffing.MseRecorder;

import java.io.ByteArrayInputStream;
import java.io.File;
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

    /**
     * 硬截止的独立 Handler。
     *
     * <p>必须与「空闲 3 秒」的 {@link #finishHandler} 分开：空闲链每次媒体命中都会
     * {@code removeCallbacksAndMessages(null)} 重排 3 秒，结果持续到达时那条链永远
     * 重排，原本写在链里的 60 秒兜底**永远没机会执行**（fMP4 VOD 上稳定复现）。
     * 硬截止用独立 Handler + 绝对时间，不会被重排推后。
     */
    private static final Handler deadlineHandler = new Handler(Looper.getMainLooper());

    /** 本轮嗅探是否已收尾（防止收尾回调重入） */
    private static volatile boolean sniffFinished = false;
    
    /** 存储正在进行的网络连接，用于 stop 时取消 */
    private static Set<HttpURLConnection> activeConnections = new HashSet<>();
    
    /** 当前回调 */
    private static Call currentCall;
    
    /** 网络请求线程池（最多 1 个并发请求，最大限度减少资源占用）*/
    private static ExecutorService networkExecutor = Executors.newFixedThreadPool(1);
    
    /**
     * 在途校验任务的许可数（2）。
     *
     * <p>**不等于「2 个并发网络请求」**：许可在任务提交给 {@link #networkExecutor}
     * 之前就已占住（{@link #scheduleVerification} 里先 {@code tryAcquire} 再
     * {@code execute}），而那个线程池只有 **1** 个工作线程。稳态因此是
     * 「1 个在跑 + 1 个排队」把 2 个许可占满，**实际同时进行的网络请求始终只有 1 个**。
     * 第 3 个候选起拿不到许可，被记成「并发请求过多」跳过。
     */
    private static Semaphore concurrentRequestSemaphore = new Semaphore(2);
    
    /** 已处理的 URL 集合（避免重复请求）*/
    private static Set<String> processedUrls = new HashSet<>();

    /**
     * 在途二次验证计数。收尾（{@link #triggerFinishCallback}）要等它归零，
     * 否则 {@link #stop} 会把还没上报的慢校验连同连接一起掐掉。
     */
    private static final java.util.concurrent.atomic.AtomicInteger inFlightVerifications =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * 嗅探轮次号。{@link #stop} 会把在途计数清零，但上一轮的校验线程可能随后才
     * 走到 finally——若不区分轮次，这些「迟到」的递减会把**新一轮**的计数减掉，
     * 让新一轮提前收尾、重现「慢校验被掐掉」的老问题。校验线程在派发时记下
     * 当时的轮次，只有轮次未变才允许递减。
     */
    private static final java.util.concurrent.atomic.AtomicInteger sniffEpoch =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 当前嗅探 WebViewClient（JS 桥接回调需要复用它的校验与结果分发）*/
    private static VideoWebViewClient currentClient;

    /**
     * 当前 MSE 录制器。非 null 表示用户已开启录制模式——
     * 页面脚本只在 {@code __orangeRecActive} 为真时回传分片。
     */
    private static volatile MseRecorder currentRecorder;

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
     * MSE 录制脚本：包住 {@code MediaSource.addSourceBuffer} 与
     * {@code SourceBuffer.appendBuffer}，把播放器已经拿到的分片拷出来回传 Java。
     *
     * <p>为什么需要它：站点用 {@code <video src="blob:...">} 时，真实媒体地址只在
     * JS 内存里，网络层看不到可用 URL（SNIFF_JS 也显式跳过了 blob:）。
     * 这里不再解析协议，直接吃播放器解密/拼装好的缓冲——这正是 FetchV 的
     * 「录制模式」思路。
     *
     * <p>实现要点：
     * <ul>
     *   <li>按 sourceBuffer 的 mime 分轨，交由 Java 侧 {@code MseRecorder} 落盘；
     *       init 段（ftyp/moov）单独标记，便于诊断产物是否可播。</li>
     *   <li>字节转 base64 才能过 JavascriptInterface（只支持字符串）。</li>
     *   <li>幂等守卫 {@code __orangeRecOn}：onPageStarted/onPageFinished 会注入两次。</li>
     *   <li>只在 {@code __orangeRecActive} 为真时才回传，避免用户没开录制时白白拷贝数据。</li>
     * </ul>
     */
    static final String RECORD_JS =
            "(function(){"
            + "if (window.__orangeRecOn) return; window.__orangeRecOn = true;"
            // 注入即代表要录：本脚本只在 isRecording() 为真时被注入
            // （见 injectRecordScriptIfNeeded / startRecording）。
            // 不能只依赖外部单独置位的 __orangeRecActive —— 整页重载后新文档里
            // 该变量是 undefined，播放器在 onPageFinished 之前 append 的分片
            // （含 init 段 ftyp/moov）会被静默丢弃，产物从 moof 开头、不可播，
            // 而 UI 仍报「产物 1 个」。
            + "window.__orangeRecActive = true;"
            + "function b64(buf){ try {"
            + "  var b = new Uint8Array(buf); var s = ''; var CH = 0x8000;"
            + "  for (var i = 0; i < b.length; i += CH) {"
            + "    s += String.fromCharCode.apply(null, b.subarray(i, Math.min(i + CH, b.length))); }"
            + "  return btoa(s);"
            + "} catch(e){ return ''; } }"
            + "function send(id, mime, buf, isInit){ try {"
            + "  if (!window.__orangeRecActive) return;"
            + "  if (!buf || !buf.byteLength) return;"
            + "  var d = b64(buf); if (!d) return;"
            + "  if (window.OrangeRecord) window.OrangeRecord.onChunk(id || '', mime || '', d, !!isInit);"
            + "} catch(e){} }"
            + "var MS = window.MediaSource;"
            + "var sbSeq = 0;"
            + "if (MS && MS.prototype && MS.prototype.addSourceBuffer) {"
            + "  var ASB = MS.prototype.addSourceBuffer;"
            + "  MS.prototype.addSourceBuffer = function(mime){"
            + "    var sb = ASB.apply(this, arguments);"
            + "    try {"
            + "      if (sb && sb.__orangePatched === undefined) {"
            + "        sb.__orangePatched = true;"
            + "        sb.__orangeMime = mime;"
            // 按 SourceBuffer **实例**编号，而不是按 mime 分轨：
            // 换集/换码率会新建 SourceBuffer，若按 mime 归并，前后两段视频
            // 会被交叉写进同一个文件 → 产物损坏。
            + "        sb.__orangeId = 'sb' + (++sbSeq);"
            + "        var AB = sb.appendBuffer;"
            + "        if (typeof AB === 'function') {"
            + "          sb.appendBuffer = function(data){"
            + "            try {"
            + "              var isInit = (this.__orangeSawInit !== true);"
            + "              this.__orangeSawInit = true;"
            + "              send(this.__orangeId, this.__orangeMime, data, isInit);"
            + "            } catch(e){}"
            + "            return AB.apply(this, arguments); };"
            + "        }"
            + "      }"
            + "    } catch(e){}"
            + "    return sb; };"
            + "}"
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
     * 判断是否为视频资源（旧版宽松判定，保留供外部调用）。
     *
     * @deprecated 内部链路已改用 {@link #classifyMedia}。本方法把「内容类型」与
     * 「URL」混在同一串子串判断里，会把路径含 {@code /video/} 的**网页**判成视频
     * （如 {@code https://host/video/play/123.html}），也会把 {@code .ts} 分片与
     * {@code .key} 密钥文件收进结果。新代码不要再用。
     */
    @Deprecated
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

    /** 非媒体 */
    static final int MEDIA_NONE = 0;
    /** 清单（m3u8 / mpd）——可直接交给播放器或下载器，优先级最高 */
    static final int MEDIA_MANIFEST = 1;
    /** 完整容器（mp4 / webm / flv / mkv ...） */
    static final int MEDIA_CONTAINER = 2;
    /** 分片与密钥（.ts / .m4s / .key / audio-only）——逐个上报只会淹没列表 */
    static final int MEDIA_SEGMENT = 3;

    /**
     * 按「响应内容类型 + URL」判定媒体种类。
     *
     * <p>关键规则：**内容类型优先**。只要响应头给出了明确类型，就不再退回 URL 后缀
     * 猜测——否则 {@code /video/play/123.html} 这类路径子串会把网页判成视频，
     * 而误报的网页请求通常又快又小，会抢先完成并启动收尾倒计时，把仍在途的
     * m3u8 校验一起 {@code disconnect} 掉（PJA110 实测结果只剩 .html）。
     *
     * <p>只有内容类型缺信息（空 / octet-stream）时才看 URL 后缀。
     *
     * @param contentType 响应 Content-Type，可为 null
     * @param url         资源 URL
     * @return {@link #MEDIA_NONE} / {@link #MEDIA_MANIFEST} / {@link #MEDIA_CONTAINER} / {@link #MEDIA_SEGMENT}
     */
    static int classifyMedia(String contentType, String url) {
        String ct = contentType == null ? "" : contentType.toLowerCase().trim();

        // 清单类型必须**先**判：dash 的类型是 application/dash+xml，
        // 会被下面的 "xml" 非媒体规则误杀。
        if (ct.contains("mpegurl") || ct.contains("dash+xml")) {
            return MEDIA_MANIFEST;
        }
        // 部分 CDN 用 text/plain 提供 m3u8（伪装成文本以绕开网关规则）。
        // 必须在 text/* 的非媒体规则**之前**按后缀识别，否则会被判成 NONE。
        if (ct.startsWith("text/plain") && classifyMediaByUrl(url) == MEDIA_MANIFEST) {
            return MEDIA_MANIFEST;
        }
        // 明确非媒体：网页、脚本、样式、图片、字体、结构化数据
        if (ct.startsWith("text/") || ct.contains("html") || ct.contains("json")
                || ct.contains("javascript") || ct.contains("css")
                || ct.contains("image/") || ct.contains("font/")
                || ct.contains("xml") || ct.contains("wasm")) {
            return MEDIA_NONE;
        }
        // application/mp2t 是 TS 分片的另一种写法（不在 video/* 里，容易漏）
        if (ct.contains("mp2t")) {
            return MEDIA_SEGMENT;
        }
        if (ct.contains("video/") || ct.contains("audio/")) {
            // 先看内容类型里的分片特征：mp2t（TS）、iso.segment（fMP4）
            if (ct.contains("mp2t") || ct.contains("iso.segment")) {
                return MEDIA_SEGMENT;
            }
            // 再看 URL 后缀：fMP4 分片常以 video/mp4 提供（内容类型与可播的
            // 整段 mp4 无法区分），只有后缀能区分。若不查这一层，`.m4s`/`.ts`
            // 会被判成 CONTAINER 进结果列表 —— 「一堆分片淹没列表」原样复现。
            int byUrl = classifyMediaByUrl(url);
            if (byUrl == MEDIA_SEGMENT) {
                return MEDIA_SEGMENT;
            }
            return MEDIA_CONTAINER;
        }
        // 内容类型给出了明确答案但不在媒体白名单里 → 就是非媒体，不再猜 URL
        if (!ct.isEmpty() && !ct.contains("octet-stream")) {
            return MEDIA_NONE;
        }
        return classifyMediaByUrl(url);
    }

    /**
     * 仅按 URL 后缀判定媒体种类（内容类型无信息时的兜底）。
     */
    static int classifyMediaByUrl(String url) {
        if (url == null) return MEDIA_NONE;
        String path = url.toLowerCase();
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);

        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return MEDIA_MANIFEST;
        if (path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".flv")
                || path.endsWith(".mkv") || path.endsWith(".mov") || path.endsWith(".avi")
                || path.endsWith(".m4v") || path.endsWith(".3gp")) {
            return MEDIA_CONTAINER;
        }
        // 分片与密钥：显式排除，避免淹没结果列表
        if (path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".key")
                || path.endsWith(".aac") || path.endsWith(".vtt")) {
            return MEDIA_SEGMENT;
        }
        return MEDIA_NONE;
    }

    /**
     * 结果列表上限：超过只保留优先级更高的条目。
     */
    private static final int MAX_SNIFF_RESULTS = 30;

    /**
     * 单次嗅探的最长时长。用于给「等所有在途校验收尾」兜底，
     * 防止某个卡住的连接让嗅探永不结束。
     */
    private static final long MAX_SNIFF_DURATION = 60_000L;

    /** 本次嗅探的起始时刻（配合 {@link #MAX_SNIFF_DURATION} 兜底） */
    private static volatile long sniffStartTime = 0L;

    /**
     * 主文档是否已加载完成。未完成时不允许收尾——否则会销毁 WebView 腰斩页面，
     * 让「几秒后才初始化播放器」的站点彻底拿不到媒体。
     */
    private static volatile boolean pageLoadFinished = false;

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
        deadlineHandler.removeCallbacksAndMessages(null);
        // 必须加锁：worker 线程在 synchronized(activeConnections) 里 add()，
        // 裸 clear() 与 add() 交错会损坏非线程安全的 HashSet 内部结构。
        // stop(false) 已在第 1 步加锁清过，这里是二次保险。
        synchronized (activeConnections) {
            activeConnections.clear();
        }
        processedUrls.clear();  // 清空已处理 URL 集合
        sniffStartTime = System.currentTimeMillis();
        pageLoadFinished = false;
        sniffFinished = false;
        currentCall = call;

        // 排首次收尾检查。此前这里不排：链只在媒体命中或 onPageFinished 时才启动，
        // 若主文档不触发 onPageFinished（长轮询 / 流式 HTML / 部分重定向）且无媒体命中，
        // 链根本没启动 → 面板一直「正在嗅探」、WebView 与 Activity 常驻不释放。
        finishHandler.postDelayed(VideoSniffing::triggerFinishCallback, FINISH_DELAY);

        // 硬截止：绝对时间，独立于空闲链，不会被重排推后。
        deadlineHandler.postDelayed(VideoSniffing::onHardDeadline, MAX_SNIFF_DURATION);

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
        // MSE 录制桥接（录制模式）：捕获 blob: 场景下播放器已拿到的分片
        webView.addJavascriptInterface(new RecordBridge(), "OrangeRecord");
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
     * 开启 MSE 录制模式。
     *
     * <p>适用场景：站点用 {@code <video src="blob:...">} + MediaSource，网络层拿不到
     * 可用地址（嗅探结果为空或只有分片）。此时录制模式改吃播放器已解密/已拼装的
     * 缓冲——见 {@link MseRecorder}。
     *
     * <p>与嗅探的关系：两者可同时开启。录制**不依赖**嗅探结果，但页面必须真的
     * 播放起来才有数据（不播 = 没数据）。开启后需要**重新加载页面**，因为
     * MSE 的 hook 必须赶在播放器创建 MediaSource 之前完成。
     *
     * @param context 上下文
     * @param outDir  录制产物目录
     */
    public static void startRecording(Context context, File outDir) {
        if (outDir == null) {
            Log.w(TAG, "startRecording: outDir is null");
            return;
        }
        MseRecorder recorder = new MseRecorder(outDir);
        recorder.start();
        currentRecorder = recorder;
        setRecordingActive(true);
        // 页面已加载时补注入；未加载时会由 onPageStarted/onPageFinished 注入
        final WebView view = webView;
        if (view != null) {
            mainHandler.post(() -> {
                try {
                    view.evaluateJavascript(RECORD_JS, null);
                } catch (Exception e) {
                    Log.e(TAG, "录制脚本注入失败", e);
                }
            });
        }
    }

    /**
     * 结束录制并返回产物文件。
     *
     * @return 落盘文件列表（可能为空）。**video/audio 是分开的两条轨道**，
     *         合并音视频需要额外的 mux 步骤，本方法不做。
     */
    public static List<File> stopRecording() {
        MseRecorder recorder = currentRecorder;
        currentRecorder = null;
        setRecordingActive(false);
        // 录制期间 WebView 被有意保留（见 triggerFinishCallback），
        // 这里负责最终拆除：先停录制再销毁，避免销毁时还有分片在途。
        stop(true);
        if (recorder == null) {
            return new ArrayList<>();
        }
        return recorder.stop();
    }

    /** 当前是否正在录制 */
    public static boolean isRecording() {
        MseRecorder recorder = currentRecorder;
        return recorder != null && recorder.isRecording();
    }

    /** 已录制的字节数（UI 展示用） */
    public static long getRecordedBytes() {
        MseRecorder recorder = currentRecorder;
        return recorder == null ? 0 : recorder.getTotalBytes();
    }

    /**
     * 若正在录制则把 MSE hook 脚本注入页面（脚本自带幂等守卫，重复注入无副作用）。
     */
    private static void injectRecordScriptIfNeeded(WebView view) {
        if (!isRecording() || view == null) {
            return;
        }
        try {
            view.evaluateJavascript(RECORD_JS, null);
        } catch (Exception e) {
            if (isDebug) {
                Log.e(TAG, "录制脚本注入失败", e);
            }
        }
    }

    /**
     * 同步「是否回传分片」到页面。页面脚本用 {@code __orangeRecActive} 做开关，
     * 未开启时完全不做 base64 拷贝，避免无谓开销。
     */
    private static void setRecordingActive(boolean active) {
        final WebView view = webView;
        if (view == null) {
            return;
        }
        final String js = "(function(){ try { window.__orangeRecActive = "
                + (active ? "true" : "false") + "; } catch(e){} })();";
        mainHandler.post(() -> {
            try {
                view.evaluateJavascript(js, null);
            } catch (Exception ignored) {
            }
        });
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

        // 2. 清理延迟任务和回调引用（含硬截止，否则它会在下一轮嗅探里误触发）
        finishHandler.removeCallbacksAndMessages(null);
        deadlineHandler.removeCallbacksAndMessages(null);
        sniffFinished = true;
        currentCall = null;
        currentClient = null;
        // currentRecorder 有意**不**在这里清：stop() 会被 sniffing 的收尾路径调用，
        // 若在此清空会让正在进行的录制丢失数据。录制的生命周期由
        // startRecording/stopRecording 自己管。

        // 3. 释放所有信号量许可
        concurrentRequestSemaphore.drainPermits();
        concurrentRequestSemaphore.release(2);  // 修改为 2 个许可

        // 4. 清空已处理 URL 集合；轮次 +1 让上一轮仍在途的校验线程失去递减资格，
        //    再把在途计数归零，避免它们把下一轮的计数减掉。
        processedUrls.clear();
        sniffEpoch.incrementAndGet();
        inFlightVerifications.set(0);

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
     *
     * <p>若有二次验证仍在途，**不能**收尾：{@link #stop} 会 disconnect 所有在途连接，
     * 把还没上报的结果掐掉。PJA110 实测就是「html 页（快）先完成 → 起 3s 倒计时 →
     * 196KB 的 m3u8 校验仍在途 → 被 disconnect → 结果只剩 html」。
     * 因此这里改为：在途未清零就顺延，等最后一个校验落地后再收尾。
     */
    /**
     * 硬截止到点：无条件收尾。
     *
     * <p>刻意**不复用** {@link #triggerFinishCallback}：后者会在「页面未加载完」或
     * 「仍有校验在途」时顺延，而这两个条件在异常场景下可以一直成立（页面永不触发
     * onPageFinished、连接挂起不超时），正是「嗅探永不结束」的成因。
     * 硬截止的语义就是「无论如何都收」，否则它没有存在的意义。
     */
    private static void onHardDeadline() {
        if (currentCall == null || webView == null || sniffFinished) {
            return;
        }
        Log.w(TAG, "达到最大嗅探时长 " + MAX_SNIFF_DURATION + "ms，强制收尾"
                + "（pageLoadFinished=" + pageLoadFinished
                + ", inFlight=" + inFlightVerifications.get() + "）");
        sniffFinished = true;
        List<VideoInfo> videoList = sortAndCap(new ArrayList<>(videoInfoSet));
        currentCall.onFinish(videoList, videoList.size());
        // 录制进行中时保留 WebView（由 stopRecording 负责拆），否则销毁
        if (isRecording()) {
            finishHandler.removeCallbacksAndMessages(null);
            return;
        }
        stop(true);
    }

    /**
     * 顺延原因；{@link #DEFER_NONE} 表示应当收尾。
     *
     * <p>抽成纯函数是为了让「什么情况下顺延」这条逻辑能被单测直接验证——
     * 它出过一次事故：60 秒兜底写在空闲链里，而空闲链每次媒体命中都会重排，
     * 结果兜底永不执行（嗅探永不结束）。
     */
    static final int DEFER_NONE = 0;
    static final int DEFER_PAGE_LOADING = 1;
    static final int DEFER_IN_FLIGHT = 2;

    /**
     * 判定本次收尾检查是否应当顺延。
     *
     * @param pageLoadFinished 主文档是否加载完成
     * @param inFlight         在途二次校验数
     * @param elapsedMs        本轮嗅探已进行时长
     * @return {@link #DEFER_NONE} 或某个 DEFER_* 原因
     */
    static int decideDeferral(boolean pageLoadFinished, int inFlight, long elapsedMs) {
        // 硬截止优先：超过上限无条件收尾，不再看任何顺延条件。
        // 否则「页面永不 onPageFinished」「连接挂起不超时」这两个条件可以一直成立，
        // 嗅探就永远结束不了。
        if (elapsedMs >= MAX_SNIFF_DURATION) {
            return DEFER_NONE;
        }
        if (!pageLoadFinished) {
            return DEFER_PAGE_LOADING;
        }
        if (inFlight > 0) {
            return DEFER_IN_FLIGHT;
        }
        return DEFER_NONE;
    }

    private static void triggerFinishCallback() {
        if (currentCall == null || webView == null || sniffFinished) {
            return;
        }
        // 顺延不能无上限：万一某个校验卡住（连接不超时地挂起），嗅探会永不结束。
        // 超过 MAX_SNIFF_DURATION 就带着已有结果收尾。
        long elapsed = System.currentTimeMillis() - sniffStartTime;

        // 页面尚未加载完时不许收尾：不少站点（17c.tw 实测）要等几秒倒计时才初始化
        // 播放器，而嗅探的「3 秒空闲即完成」会抢先 destroy WebView，页面被腰斩，
        // 播放器根本没机会起播 → 录制无数据、嗅探也拿不到分片。
        // 表现为 cr_AwContents「Application attempted to call on a destroyed WebView」
        // （栈顶是 loadingStateChanged），正是页面加载中被销毁。
        int deferral = decideDeferral(pageLoadFinished, inFlightVerifications.get(), elapsed);
        if (deferral != DEFER_NONE) {
            if (isDebug) {
                Log.d(TAG, deferral == DEFER_PAGE_LOADING
                        ? "页面尚未加载完成，顺延收尾"
                        : "仍有 " + inFlightVerifications.get() + " 个校验在途，顺延收尾");
            }
            finishHandler.removeCallbacksAndMessages(null);
            finishHandler.postDelayed(VideoSniffing::triggerFinishCallback, FINISH_DELAY);
            return;
        }
        List<VideoInfo> videoList = sortAndCap(new ArrayList<>(videoInfoSet));
        sniffFinished = true;
        currentCall.onFinish(videoList, videoList.size());
        // 录制进行中时不能收尾：stop() 会销毁 WebView，录制随之静默中断。
        // 嗅探的「空闲 3 秒即完成」与录制所需的「长时间驻留页面」天然冲突，
        // 所以录制期间只回报结果、保留 WebView，由 stopRecording() 负责拆。
        if (isRecording()) {
            if (isDebug) {
                Log.d(TAG, "录制进行中，保留 WebView 不收尾");
            }
            finishHandler.removeCallbacksAndMessages(null);
            return;
        }
        stop(true);
    }

    /**
     * 结果排序与限流：清单（m3u8/mpd）优先，其次完整容器；同档保持原有相对顺序。
     * 超过 {@link #MAX_SNIFF_RESULTS} 的部分丢弃——结果列表是给人挑的，
     * 不是越多越好（站点上几十条推荐位会把真正的播放源挤下去）。
     */
    private static List<VideoInfo> sortAndCap(List<VideoInfo> list) {
        List<VideoInfo> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> Integer.compare(
                classifyMedia(a.contentType, a.url), classifyMedia(b.contentType, b.url)));
        if (sorted.size() > MAX_SNIFF_RESULTS) {
            sorted = new ArrayList<>(sorted.subList(0, MAX_SNIFF_RESULTS));
        }
        return sorted;
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
     * MSE 录制回传到 Java 侧的桥。
     *
     * <p>分片经 base64 过桥（JavascriptInterface 只支持字符串），运行在
     * WebView 的 JavaBridge 线程；{@link MseRecorder} 内部按轨道加锁写盘。
     */
    private static class RecordBridge {
        @JavascriptInterface
        public void onChunk(String sourceBufferId, String mime, String base64Data, boolean isInit) {
            MseRecorder recorder = currentRecorder;
            if (recorder == null) {
                return;
            }
            recorder.onChunk(sourceBufferId, mime, base64Data, isInit);
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
            // 录制脚本必须同样赶在播放器创建 MediaSource 之前注入，
            // 否则 addSourceBuffer 已被调用过，包不住 appendBuffer。
            injectRecordScriptIfNeeded(view);
        }

        /**
         * 主文档加载失败时也要放行收尾，否则 {@code pageLoadFinished} 永远为 false，
         * 嗅探会一直拖到 {@link #MAX_SNIFF_DURATION} 才结束——用户感觉像卡死。
         */
        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    android.webkit.WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request != null && request.isForMainFrame()) {
                pageLoadFinished = true;
                if (isDebug) {
                    Log.w(TAG, "主文档加载失败，允许收尾: " + request.getUrl());
                }
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            pageLoadFinished = true;
            // 页面（重新）加载后恢复录制开关，否则刷新一次就不回传了
            if (isRecording()) {
                setRecordingActive(true);
            }
            // 兜底再注入一次（动态渲染的站点可能主文档没有 video/播放器）
            try {
                view.evaluateJavascript(SNIFF_JS, null);
            } catch (Exception e) {
                if (isDebug) {
                    Log.e(TAG, "嗅探脚本补注入失败: " + url, e);
                }
            }
            injectRecordScriptIfNeeded(view);
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
            final int myEpoch = sniffEpoch.get();
            inFlightVerifications.incrementAndGet();
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
                    // 超时不能太紧：connect() 含 TLS 握手与响应头读取，移动网络下
                    // 2 秒会把 196KB 的 m3u8 校验直接打成 Read timed out（PJA110 实测），
                    // 结果就是「只在别的请求恰好成功时才碰运气拿到播放源」。
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(6000);
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

                    // 内容类型优先判定，URL 仅作兜底：
                    // - MEDIA_NONE    → 网页/脚本/图片等，直接丢弃（不再被 /video/ 子串误伤）
                    // - MEDIA_SEGMENT → .ts/.m4s 分片与 .key 密钥，逐个上报只会淹没结果列表
                    // - MEDIA_MANIFEST / MEDIA_CONTAINER → 收进结果
                    final int mediaKind = classifyMedia(respContentType, url);
                    if (mediaKind == MEDIA_MANIFEST || mediaKind == MEDIA_CONTAINER) {
                        if (isDebug) {
                            Log.d(TAG, "发现视频资源(" + (mediaKind == MEDIA_MANIFEST ? "manifest" : "container")
                                    + ")！Content-Type: " + respContentType + " URL: " + url);
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
                            Log.d(TAG, "非目标媒体(" + mediaKind + ")，跳过: " + url);
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
                    // 在途计数递减。收尾倒计时会在 triggerFinishCallback 里
                    // 自行检查该计数并顺延，这里不需要额外触发。
                    // 仅当轮次未变才递减——否则上一轮的迟到线程会把新一轮的计数减掉。
                    if (myEpoch == sniffEpoch.get()) {
                        inFlightVerifications.decrementAndGet();
                        // 许可也要同轮次才还：stop() 已 drain+release(2) 重置过，
                        // 上一轮的迟到线程若再无脑 release，许可会逐轮只增不减，
                        // 「最多 2 并发」失效 → 回到当年 ANR 的老路。
                        concurrentRequestSemaphore.release();
                    }
                }
            });
        }
        
        /**
         * 快速检查 URL 是否可能是视频资源。
         *
         * <p>这是廉价前置门，只用来少发无用请求；真正的判定在 {@link #classifyMedia}。
         *
         * <p>分片与密钥显式排除：一个 VOD 站点有上千个 {@code .ts}，而它们的 URL 常带
         * {@code /video/} 或 {@code /stream/} 这类路径（能骗过下面的子串判断）。
         * 排除它们是为了**少发上千次无用的校验请求**——这才是这里的动机。
         *
         * <p><b>更正</b>（此前注释写的是「分片把 2 个许可占满、真正的清单反被丢弃」，
         * 与实测不符）：
         * <ul>
         *   <li>实测被跳过的只有**分片**与一条统计 URL，**清单从未被跳过**——
         *       它是这批候选里最早到达的，先拿到许可、正常完成校验（黑鲨
         *       2026-09-19 04:09 那轮日志：5 次跳过全落在
         *       {@code video.18j2026.com/videos/.../index0-3.ts} 与 {@code mc.yandex.ru/watch} 上）。</li>
         *   <li>「2 个并发许可」也不等于 2 个并发请求：线程池只有 1 个线程，
         *       见 {@link #concurrentRequestSemaphore}。跳过是「这次没排上」，
         *       不是「永久拉黑」——拿不到许可时 URL 会被移出 {@code processedUrls}
         *       以允许下次重试。</li>
         * </ul>
         */
        private boolean isPotentialVideoUrl(String url) {
            if (url == null) return false;
            if (classifyMediaByUrl(url) == MEDIA_SEGMENT) {
                return false;
            }
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
