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
}
