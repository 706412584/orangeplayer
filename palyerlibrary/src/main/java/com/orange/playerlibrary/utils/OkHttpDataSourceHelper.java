package com.orange.playerlibrary.utils;

import android.util.Log;

import java.util.Map;

/**
 * 可选 OkHttp DataSource 帮助类（P6）。
 * <p>
 * palyerlibrary 对 media3-datasource-okhttp 与 okhttp 均为 compileOnly：
 * 用户按需添加依赖后，经 GSY 的 ExoSourceManager 全局拦截器注入
 * （setExoMediaSourceInterceptListener），默认不注入则零行为变化。
 * <p>
 * 本类只做类型反射检查与参数校验；OkHttpDataSource.Factory 的构建
 * 由注入方（app 层）完成，避免 SDK 运行时硬依赖。
 */
public final class OkHttpDataSourceHelper {

    private static final String TAG = "OkHttpDataSource";
    private static final String OKHTTP_DATASOURCE_CLASS =
            "androidx.media3.datasource.okhttp.OkHttpDataSource$Factory";

    private OkHttpDataSourceHelper() {
    }

    /** classpath 是否存在 media3-datasource-okhttp */
    public static boolean isAvailable() {
        try {
            Class.forName(OKHTTP_DATASOURCE_CLASS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 构建请求头透传参数校验（注入方在 Factory 上调用
     * setDefaultRequestProperties(headers)）。
     *
     * @return 校验通过的 headers；headers 为 null 时返回 null（调用方走默认栈）
     */
    public static Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        // 剥离 GSY 特有的控制 key（非 HTTP 头）
        java.util.Map<String, String> result = new java.util.HashMap<>(headers);
        result.remove("allowCrossProtocolRedirects");
        return result;
    }

    /**
     * 读取 GSY 约定的跨协议重定向开关（mapHeadData 中的约定 key）
     */
    public static boolean shouldAllowCrossProtocolRedirects(Map<String, String> headers) {
        return headers != null
                && "true".equalsIgnoreCase(headers.get("allowCrossProtocolRedirects"));
    }

    /**
     * 反射构建 OkHttpDataSource.Factory 并透传 headers。
     *
     * @return 工厂实例；classpath 缺失或构建失败返回 null（调用方回退默认栈）
     */
    public static Object createFactory(Object okHttpClient, Map<String, String> headers) {
        if (!isAvailable() || okHttpClient == null) {
            Log.d(TAG, "OkHttp DataSource 不可用，回退默认网络栈");
            return null;
        }
        try {
            Class<?> factoryClass = Class.forName(OKHTTP_DATASOURCE_CLASS);
            Object factory = factoryClass.getConstructor(okHttpClient.getClass())
                    .newInstance(okHttpClient);
            Map<String, String> clean = sanitizeHeaders(headers);
            if (clean != null && !clean.isEmpty()) {
                factoryClass.getMethod("setDefaultRequestProperties", Map.class)
                        .invoke(factory, clean);
            }
            if (shouldAllowCrossProtocolRedirects(headers)) {
                factoryClass.getMethod("setAllowCrossProtocolRedirects", boolean.class)
                        .invoke(factory, true);
            }
            Log.d(TAG, "OkHttp DataSource 工厂已创建");
            return factory;
        } catch (Throwable t) {
            Log.w(TAG, "创建 OkHttpDataSource.Factory 失败，回退默认网络栈", t);
            return null;
        }
    }
}
