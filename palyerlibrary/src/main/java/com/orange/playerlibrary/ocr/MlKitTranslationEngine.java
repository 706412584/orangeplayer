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
    
    @Override
    public void init(Context context, String sourceLanguage, String targetLanguage) {
        if (!OcrAvailabilityChecker.isMlKitTranslateAvailable()) {
            Log.e(TAG, "ML Kit Translation not available");
            return;
        }
        
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
    
    @Override
    public void downloadModel(ModelDownloadCallback callback) {
        if (!mInitialized || mTranslator == null) {
            if (callback != null) {
                callback.onError("Translator not initialized");
            }
            return;
        }
        
        try {
            // DownloadConditions.Builder
            Class<?> conditionsBuilderClass = Class.forName("com.google.mlkit.common.model.DownloadConditions$Builder");
            Object conditionsBuilder = conditionsBuilderClass.newInstance();
            
            // requireWifi() - 可选
            // java.lang.reflect.Method requireWifiMethod = conditionsBuilderClass.getMethod("requireWifi");
            // requireWifiMethod.invoke(conditionsBuilder);
            
            // build
            java.lang.reflect.Method buildMethod = conditionsBuilderClass.getMethod("build");
            Object conditions = buildMethod.invoke(conditionsBuilder);
            
            // translator.downloadModelIfNeeded(conditions)
            Class<?> translatorClass = mTranslator.getClass();
            java.lang.reflect.Method downloadMethod = translatorClass.getMethod("downloadModelIfNeeded",
                Class.forName("com.google.mlkit.common.model.DownloadConditions"));
            Object task = downloadMethod.invoke(mTranslator, conditions);
            
            // 添加监听器
            addTaskListeners(task, callback);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to download model", e);
            if (callback != null) {
                callback.onError(e.getMessage());
            }
        }
    }
    
    private void addTaskListeners(Object task, ModelDownloadCallback callback) {
        try {
            Class<?> taskClass = task.getClass();
            
            // addOnSuccessListener
            Class<?> successListenerClass = Class.forName("com.google.android.gms.tasks.OnSuccessListener");
            Object successListener = java.lang.reflect.Proxy.newProxyInstance(
                successListenerClass.getClassLoader(),
                new Class[]{successListenerClass},
                (proxy, method, args) -> {
                    if ("onSuccess".equals(method.getName())) {
                        mModelDownloaded = true;
                        Log.d(TAG, "Model downloaded successfully");
                        if (callback != null) {
                            callback.onSuccess();
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
                        Log.e(TAG, "Model download failed", e);
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
            Log.e(TAG, "Failed to add task listeners", e);
        }
    }
    
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
     * MLKit 按「语言↔英语」存模型（en_zh / en_ja / en_ko…），翻译任意两语言
     * 经英语中转，故某语言可用 ⇔ 其 en_&lt;语言&gt; 目录存在；英语作为中转
     * 语言恒可用。设置界面据此标注「已装/未装」，避免用户盲选后首播等下载。
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
                String name = child.getName();
                // 仅认 en_<语言> 形态；temp 等中间目录天然不匹配
                if (name.startsWith(PIVOT_LANGUAGE + "_") && name.length() > 3) {
                    installed.add(name.substring(PIVOT_LANGUAGE.length() + 1));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取已装本地翻译模型失败", t);
        }
        return installed;
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
