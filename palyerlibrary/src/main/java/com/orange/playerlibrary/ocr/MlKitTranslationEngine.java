package com.orange.playerlibrary.ocr;

import android.content.Context;
import android.util.Log;

/**
 * ML Kit 翻译引擎实现
 * 使用反射调用，避免强依赖
 */
public class MlKitTranslationEngine implements TranslationEngine {
    
    private static final String TAG = "MlKitTranslation";
    
    private Object mTranslator;
    private boolean mInitialized = false;
    private boolean mModelDownloaded = false;
    private String mSourceLanguage;
    private String mTargetLanguage;
    /** 用于观测下载字节（见 {@link #downloadedBytes()}），持 Application Context 不泄漏 */
    private Context mContext;

    @Override
    public void init(Context context, String sourceLanguage, String targetLanguage) {
        if (!OcrAvailabilityChecker.isMlKitTranslateAvailable()) {
            Log.e(TAG, "ML Kit Translation not available");
            return;
        }

        mContext = context == null ? null : context.getApplicationContext();
        mSourceLanguage = sourceLanguage;
        mTargetLanguage = targetLanguage;
        
        try {
            // TranslateLanguage
            Class<?> translateLanguageClass = Class.forName("com.google.mlkit.nl.translate.TranslateLanguage");
            
            // 获取语言代码
            String sourceLangCode = getLanguageCode(translateLanguageClass, sourceLanguage);
            String targetLangCode = getLanguageCode(translateLanguageClass, targetLanguage);
            
            if (sourceLangCode == null || targetLangCode == null) {
                Log.e(TAG, "Invalid language code");
                return;
            }
            
            // TranslatorOptions.Builder
            Class<?> optionsBuilderClass = Class.forName("com.google.mlkit.nl.translate.TranslatorOptions$Builder");
            Object builder = optionsBuilderClass.newInstance();
            
            // setSourceLanguage
            java.lang.reflect.Method setSourceMethod = optionsBuilderClass.getMethod("setSourceLanguage", String.class);
            setSourceMethod.invoke(builder, sourceLangCode);
            
            // setTargetLanguage
            java.lang.reflect.Method setTargetMethod = optionsBuilderClass.getMethod("setTargetLanguage", String.class);
            setTargetMethod.invoke(builder, targetLangCode);
            
            // build
            java.lang.reflect.Method buildMethod = optionsBuilderClass.getMethod("build");
            Object options = buildMethod.invoke(builder);
            
            // Translation.getClient(options)
            Class<?> translationClass = Class.forName("com.google.mlkit.nl.translate.Translation");
            java.lang.reflect.Method getClientMethod = translationClass.getMethod("getClient", 
                Class.forName("com.google.mlkit.nl.translate.TranslatorOptions"));
            mTranslator = getClientMethod.invoke(null, options);
            
            mInitialized = true;
            Log.d(TAG, "ML Kit Translation initialized: " + sourceLanguage + " -> " + targetLanguage);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to init ML Kit Translation", e);
        }
    }
    
    private String getLanguageCode(Class<?> translateLanguageClass, String language) {
        // 先按语言码取常量名：MLKit 里非中日韩语种只有全称常量
        // （"fr" → FRENCH，而非 FR），靠 toUpperCase 猜字段名会失败，
        // 导致选法语等目标语言时本地兜底与预装静默跳过。
        try {
            java.lang.reflect.Field field = translateLanguageClass.getField(
                    mlKitFieldName(language));
            return (String) field.get(null);
        } catch (Exception ignored) {
            // 非语言码（用户填的显示名等）：走下面的别名匹配
        }
        try {
            String fieldName;
            switch (language.toLowerCase()) {
                case "zh":
                case "chinese":
                case "chi_sim":
                    fieldName = "CHINESE";
                    break;
                case "en":
                case "english":
                case "eng":
                    fieldName = "ENGLISH";
                    break;
                case "ja":
                case "japanese":
                case "jpn":
                    fieldName = "JAPANESE";
                    break;
                case "ko":
                case "korean":
                case "kor":
                    fieldName = "KOREAN";
                    break;
                default:
                    fieldName = language.toUpperCase();
            }
            
            java.lang.reflect.Field field = translateLanguageClass.getField(fieldName);
            return (String) field.get(null);
        } catch (Exception e) {
            Log.e(TAG, "Unknown language: " + language, e);
            return null;
        }
    }
    
    @Override
    public boolean isModelDownloaded() {
        return mModelDownloaded;
    }
    
    /**
     * 下载无进展多久才判定卡死（毫秒）。
     *
     * 注意这是「**无进展**」上限而非「总时长」上限：只要模型字节还在增长，
     * 无论下多久都不算失败。早期版本用 {@code Tasks.await(task, 180s)} 设总时长
     * 上限，结果一份模型（en_ja 61MB）正常下载就要 3 分钟以上，被自己掐断——
     * 实测 pja110 上进度已到 26% 仍报「下载超时」，纯属误报。
     */
    private static final long DOWNLOAD_STALL_TIMEOUT_MS = 60 * 1000L;

    /** 轮询下载字节的间隔（毫秒） */
    private static final long DOWNLOAD_POLL_MS = 1000;

    @Override
    public void downloadModel(ModelDownloadCallback callback) {
        if (!mInitialized || mTranslator == null) {
            if (callback != null) {
                callback.onError("Translator not initialized");
            }
            return;
        }

        // 必须在后台线程：Tasks.await 会阻塞，主线程等待会 ANR
        new Thread(() -> runDownloadOnBackgroundThread(callback), "mlkit-model-dl").start();
    }

    /**
     * 在后台线程同步等待下载 Task 完成，并叠加「无进展超时」兜底。
     *
     * 不用 addOnSuccessListener/addOnFailureListener 而用 {@code Tasks.await}：部分设备
     * （实测 OnePlus PJA110 / Android 16，ColorOS 会冻结 com.android.providers.downloads）
     * DownloadManager 里的任务不执行，downloadModelIfNeeded 返回的 Task 既不成功也不
     * 失败 —— 监听器版本就完全静默了，UI 会永远停在 0%。这里循环分段等待，每段之间
     * 检查一次已下载字节：只要还在涨就继续等，真正停住 {@link #DOWNLOAD_STALL_TIMEOUT_MS}
     * 才报错，既能拿到真实异常，也不会误杀慢速下载。
     */
    private void runDownloadOnBackgroundThread(ModelDownloadCallback callback) {
        try {
            Class<?> conditionsBuilderClass = Class.forName("com.google.mlkit.common.model.DownloadConditions$Builder");
            Object conditionsBuilder = conditionsBuilderClass.newInstance();
            java.lang.reflect.Method buildMethod = conditionsBuilderClass.getMethod("build");
            Object conditions = buildMethod.invoke(conditionsBuilder);

            Class<?> translatorClass = mTranslator.getClass();
            java.lang.reflect.Method downloadMethod = translatorClass.getMethod("downloadModelIfNeeded",
                Class.forName("com.google.mlkit.common.model.DownloadConditions"));
            Object task = downloadMethod.invoke(mTranslator, conditions);

            Class<?> tasksClass = Class.forName("com.google.android.gms.tasks.Tasks");
            java.lang.reflect.Method awaitMethod = tasksClass.getMethod(
                    "await", Class.forName("com.google.android.gms.tasks.Task"),
                    long.class, java.util.concurrent.TimeUnit.class);

            long lastBytes = downloadedBytes();
            long lastGrowthAt = android.os.SystemClock.elapsedRealtime();
            while (true) {
                try {
                    // 分段等待：单段超时不代表失败，只用来周期性检查字节是否还在涨
                    awaitMethod.invoke(null, task, DOWNLOAD_POLL_MS,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                    break;
                } catch (Exception segment) {
                    if (!(unwrap(segment) instanceof java.util.concurrent.TimeoutException)) {
                        throw segment;
                    }
                }
                long bytes = downloadedBytes();
                long now = android.os.SystemClock.elapsedRealtime();
                if (bytes > lastBytes) {
                    lastBytes = bytes;
                    lastGrowthAt = now;
                } else if (now - lastGrowthAt >= DOWNLOAD_STALL_TIMEOUT_MS) {
                    throw new java.util.concurrent.TimeoutException(
                            "已停滞 " + (DOWNLOAD_STALL_TIMEOUT_MS / 1000) + " 秒无数据（已下载 "
                                    + (bytes / (1024 * 1024)) + "MB）");
                }
            }

            mModelDownloaded = true;
            dispatchSuccess(callback);
        } catch (Exception raw) {
            // 反射调用把真实异常包在 InvocationTargetException 里，必须剥开才看得到原因
            Throwable cause = unwrap(raw);
            if (cause instanceof java.util.concurrent.TimeoutException) {
                Log.e(TAG, "Model download stalled: " + cause.getMessage());
                dispatchError(callback, "下载停滞：" + cause.getMessage()
                        + "。系统的下载服务可能被省电策略限制，请允许「下载管理器」"
                        + "后台运行后重试");
            } else {
                Log.e(TAG, "Model download failed", cause);
                dispatchError(callback, describeFailure(cause));
            }
        }
    }

    /** 剥掉反射包装，拿到真实异常 */
    private static Throwable unwrap(Throwable raw) {
        Throwable cause = raw;
        while (cause instanceof java.lang.reflect.InvocationTargetException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * 当前已下载字节 = 模型目录占用 + 系统 DownloadManager 在途字节。
     *
     * 用增长量而非绝对值判断「有没有进展」，故这里只需单调可比，不必精确。
     */
    private long downloadedBytes() {
        return getModelsDirSize(mContext) + getInFlightDownloadBytes(mContext);
    }

    /** 把 MLKit 的异常翻成用户能看懂的话：模块缺失与网络失败要分开说 */
    private static String describeFailure(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String raw = t.getMessage();
        String name = t.getClass().getSimpleName();
        if (name.contains("MlKitException")) {
            return "MLKit 错误：" + (raw == null ? name : raw);
        }
        if (t instanceof java.io.IOException || name.contains("Network")) {
            return "网络不可用，请检查网络后重试";
        }
        return (raw == null || raw.isEmpty()) ? name : raw;
    }

    private void dispatchSuccess(ModelDownloadCallback callback) {
        if (callback == null) {
            return;
        }
        mMainHandler.post(callback::onSuccess);
    }

    private void dispatchError(ModelDownloadCallback callback, String error) {
        if (callback == null) {
            return;
        }
        mMainHandler.post(() -> callback.onError(error));
    }

    /** 把回调切回主线程：调用方（UI）在回调里直接操作对话框 */
    private final android.os.Handler mMainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    
    @Override
    public void translate(String text, TranslationCallback callback) {
        if (!mInitialized || mTranslator == null) {
            if (callback != null) {
                callback.onError("Translator not initialized");
            }
            return;
        }
        
        if (text == null || text.trim().isEmpty()) {
            if (callback != null) {
                callback.onSuccess("");
            }
            return;
        }
        
        try {
            // translator.translate(text)
            java.lang.reflect.Method translateMethod = mTranslator.getClass().getMethod("translate", String.class);
            Object task = translateMethod.invoke(mTranslator, text);
            
            // 添加监听器
            addTranslateTaskListeners(task, callback);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to translate", e);
            if (callback != null) {
                callback.onError(e.getMessage());
            }
        }
    }
    
    private void addTranslateTaskListeners(Object task, TranslationCallback callback) {
        try {
            Class<?> taskClass = task.getClass();
            
            // addOnSuccessListener
            Class<?> successListenerClass = Class.forName("com.google.android.gms.tasks.OnSuccessListener");
            Object successListener = java.lang.reflect.Proxy.newProxyInstance(
                successListenerClass.getClassLoader(),
                new Class[]{successListenerClass},
                (proxy, method, args) -> {
                    if ("onSuccess".equals(method.getName())) {
                        String result = (String) args[0];
                        if (callback != null) {
                            callback.onSuccess(result);
                        }
                    }
                    return null;
                }
            );
            
            java.lang.reflect.Method addSuccessMethod = taskClass.getMethod("addOnSuccessListener", successListenerClass);
            addSuccessMethod.invoke(task, successListener);
            
            // addOnFailureListener
            Class<?> failureListenerClass = Class.forName("com.google.android.gms.tasks.OnFailureListener");
            Object failureListener = java.lang.reflect.Proxy.newProxyInstance(
                failureListenerClass.getClassLoader(),
                new Class[]{failureListenerClass},
                (proxy, method, args) -> {
                    if ("onFailure".equals(method.getName())) {
                        Exception e = (Exception) args[0];
                        if (callback != null) {
                            callback.onError(e.getMessage());
                        }
                    }
                    return null;
                }
            );
            
            java.lang.reflect.Method addFailureMethod = taskClass.getMethod("addOnFailureListener", failureListenerClass);
            addFailureMethod.invoke(task, failureListener);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to add translate task listeners", e);
        }
    }
    
    @Override
    public void release() {
        if (mTranslator != null) {
            try {
                java.lang.reflect.Method closeMethod = mTranslator.getClass().getMethod("close");
                closeMethod.invoke(mTranslator);
            } catch (Exception e) {
                Log.e(TAG, "Failed to close translator", e);
            }
            mTranslator = null;
        }
        mInitialized = false;
        mModelDownloaded = false;
    }
    
    @Override
    public boolean isInitialized() {
        return mInitialized;
    }

    /** MLKit 翻译模型的应用内存储目录（实测：按「语言↔英语」命名，如 en_zh） */
    private static final String MODELS_DIR_NAME = "com.google.mlkit.translate.models";

    /** 中转语言：MLKit 翻译经英语中转，故英语本身无需模型 */
    private static final String PIVOT_LANGUAGE = "en";

    /**
     * 已下载的本地翻译语言码集合（MLKit 语言码，如 "zh"/"ja"）。
     *
     * MLKit 按「语言↔英语」存模型，目录名为两个语言码**字典序排序**后拼接
     * （见 {@code zzad.zzc}：{@code Arrays.sort} 后 {@code "%s_%s"}）。因此
     * 英文恒排在前面的假设只对 de/fr/ja/ko/zh 等成立，德语、阿拉伯语等
     * 码序在 "en" 之前，目录名是 {@code de_en} / {@code ar_en} 而非
     * {@code en_de}——只认 {@code en_} 前缀会把它们误判成「未装」。
     *
     * 翻译任意两语言经英语中转，故某语言可用 ⇔ 含英语的那份模型目录存在；
     * 英语作为中转语言恒可用。设置界面据此标注「已装/未装」，避免用户盲选后
     * 首播等下载。
     *
     * 说明：MLKit 未提供同步的批量查询 API（isModelDownloaded 是异步 Task 且需
     * 逐语言建实例），设置界面需要即时渲染，故直接读其存储目录。
     */
    public static java.util.Set<String> getInstalledLanguageCodes(Context context) {
        java.util.Set<String> installed = new java.util.HashSet<>();
        installed.add(PIVOT_LANGUAGE);
        if (context == null) {
            return installed;
        }
        try {
            java.io.File dir = new java.io.File(context.getNoBackupFilesDir(), MODELS_DIR_NAME);
            java.io.File[] children = dir.listFiles();
            if (children == null) {
                return installed;
            }
            for (java.io.File child : children) {
                if (child == null || !child.isDirectory()) {
                    continue;
                }
                String other = pivotCompanion(child.getName());
                if (other != null) {
                    installed.add(other);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取已装本地翻译模型失败", t);
        }
        return installed;
    }

    /**
     * 从模型目录名取出「英语之外的那一侧」语言码；不含英语则返回 null。
     *
     * 目录名形如 {@code <码A>_<码B>}（A、B 已排序），两侧都必须匹配
     * {@code [a-z]{2,3}}——temp 等中间目录因此天然被排除。
     */
    static String pivotCompanion(String dirName) {
        if (dirName == null) {
            return null;
        }
        int sep = dirName.indexOf('_');
        if (sep <= 0 || sep != dirName.lastIndexOf('_')) {
            return null;
        }
        String left = dirName.substring(0, sep);
        String right = dirName.substring(sep + 1);
        if (!isLanguageCode(left) || !isLanguageCode(right)) {
            return null;
        }
        if (PIVOT_LANGUAGE.equals(left)) {
            return right;
        }
        if (PIVOT_LANGUAGE.equals(right)) {
            return left;
        }
        return null;
    }

    /** 是否形如 MLKit 语言码（2~3 位小写字母） */
    private static boolean isLanguageCode(String s) {
        if (s.length() < 2 || s.length() > 3) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < 'a' || s.charAt(i) > 'z') {
                return false;
            }
        }
        return true;
    }

    /**
     * 语言码 → MLKit 常量名。
     *
     * 与 {@link com.orange.playerlibrary.ai.ProgressiveTranslator#mapToMlKitCode}
     * 的支持范围保持一致：非中文/日/韩的语种（法/德/西/俄/阿/泰/越）在 MLKit 里
     * 只有全称常量（FRENCH 而非 FR），靠「语言码转大写」猜字段名会全部失败。
     */
    static String mlKitFieldName(String code) {
        switch (code) {
            case "zh": return "CHINESE";
            case "en": return "ENGLISH";
            case "ja": return "JAPANESE";
            case "ko": return "KOREAN";
            case "fr": return "FRENCH";
            case "de": return "GERMAN";
            case "es": return "SPANISH";
            case "ru": return "RUSSIAN";
            case "ar": return "ARABIC";
            case "th": return "THAI";
            case "vi": return "VIETNAMESE";
            default: return code.toUpperCase();
        }
    }

    /** 单个语言模型的典型体积（实测 en_zh 43MB / en_ko 51MB / en_ja 61MB），用于进度分母估算 */
    public static final long TYPICAL_MODEL_BYTES = 50L * 1024 * 1024;

    /**
     * 模型目录当前占用的真实字节数。
     *
     * 每份模型下载完成后落入该目录，故本值随下载推进阶梯式增长（在途的那份不计入，
     * 需叠加 {@link #getInFlightDownloadBytes}）。配合 {@link #TYPICAL_MODEL_BYTES}
     * 估算的分母，即可得到「基于真实字节」的进度，而非编造的百分比。
     */
    public static long getModelsDirSize(Context context) {
        if (context == null) {
            return 0;
        }
        try {
            return dirSize(new java.io.File(context.getNoBackupFilesDir(), MODELS_DIR_NAME));
        } catch (Throwable t) {
            Log.w(TAG, "读取模型目录大小失败", t);
            return 0;
        }
    }

    /**
     * 本应用经系统 DownloadManager 正在下载的字节数。
     *
     * MLKit 的模型下载走系统 DownloadManager：完成前字节落在它的缓存目录，
     * 整份文件下载完才移入 {@link #getModelsDirSize} 统计的模型目录。因此只看
     * 模型目录会在下载的头十几秒恒为 0，看起来像进度卡死——把在途字节一并计入
     * 才能从第 0 秒起反映真实下载量。
     */
    public static long getInFlightDownloadBytes(Context context) {
        if (context == null) {
            return 0;
        }
        try {
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                return 0;
            }
            android.app.DownloadManager.Query query = new android.app.DownloadManager.Query();
            query.setFilterByStatus(android.app.DownloadManager.STATUS_RUNNING
                    | android.app.DownloadManager.STATUS_PENDING);
            android.database.Cursor cursor = dm.query(query);
            if (cursor == null) {
                return 0;
            }
            try {
                int idx = cursor.getColumnIndex(
                        android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                if (idx < 0) {
                    return 0;
                }
                long total = 0;
                while (cursor.moveToNext()) {
                    total += cursor.getLong(idx);
                }
                return total;
            } finally {
                cursor.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取下载中字节失败", t);
            return 0;
        }
    }

    private static long dirSize(java.io.File dir) {
        if (dir == null) {
            return 0;
        }
        java.io.File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        long total = 0;
        for (java.io.File child : children) {
            if (child == null) {
                continue;
            }
            total += child.isDirectory() ? dirSize(child) : child.length();
        }
        return total;
    }
}
