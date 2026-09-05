package com.orange.playerlibrary.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * OkHttpDataSourceHelper 单元测试（P6）。
 * JVM 环境（returnDefaultValues）下 Class.forName 对真实类不可用，
 * 核心断言围绕 header 清洗与降级语义。
 */
public class OkHttpDataSourceHelperTest {

    @Test
    public void headers清洗_剥离GSY控制key() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "http://example.com");
        headers.put("User-Agent", "test");
        headers.put("allowCrossProtocolRedirects", "true");

        Map<String, String> clean = OkHttpDataSourceHelper.sanitizeHeaders(headers);
        assertEquals("http://example.com", clean.get("Referer"));
        assertEquals("test", clean.get("User-Agent"));
        assertFalse("GSY 控制 key 不应透传为 HTTP 头", clean.containsKey("allowCrossProtocolRedirects"));
    }

    @Test
    public void headers为null时返回null走默认栈() {
        assertNull(OkHttpDataSourceHelper.sanitizeHeaders(null));
    }

    @Test
    public void 跨协议重定向开关读取() {
        Map<String, String> on = new HashMap<>();
        on.put("allowCrossProtocolRedirects", "TRUE");
        assertTrue(OkHttpDataSourceHelper.shouldAllowCrossProtocolRedirects(on));

        Map<String, String> off = new HashMap<>();
        off.put("allowCrossProtocolRedirects", "false");
        assertFalse(OkHttpDataSourceHelper.shouldAllowCrossProtocolRedirects(off));

        assertFalse(OkHttpDataSourceHelper.shouldAllowCrossProtocolRedirects(null));
        assertFalse(OkHttpDataSourceHelper.shouldAllowCrossProtocolRedirects(new HashMap<>()));
    }

    @Test
    public void 工厂构建_null客户端返回null降级() {
        // classpath 无 okhttp-datasource 或 client 为 null → null（调用方回退默认栈）
        assertNull(OkHttpDataSourceHelper.createFactory(null, new HashMap<String, String>()));
    }

    @Test
    public void 工厂构建_非CallFactory客户端安全降级() {
        Object client = new Object();
        assertNull(OkHttpDataSourceHelper.createFactory(client, new HashMap<String, String>()));
    }

    @Test
    public void 工厂构建_使用CallFactory接口构造器() {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder().build();
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "http://example.com");
        headers.put("allowCrossProtocolRedirects", "true");

        Object factory = OkHttpDataSourceHelper.createFactory(client, headers);

        assertNotNull(factory);
        assertEquals("androidx.media3.datasource.okhttp.OkHttpDataSource$Factory",
                factory.getClass().getName());
    }

    @Test
    public void 全局开关默认关闭且reset复位() {
        com.orange.playerlibrary.OrangePlayerConfig.setOkHttpDataSourceEnabled(true);
        assertTrue(com.orange.playerlibrary.OrangePlayerConfig.isOkHttpDataSourceEnabled());
        com.orange.playerlibrary.OrangePlayerConfig.reset();
        assertFalse("reset 后应回到默认关闭", com.orange.playerlibrary.OrangePlayerConfig.isOkHttpDataSourceEnabled());
    }

    @Test
    public void 跨协议重定向开关作为控制key被剥离() {
        // headers 中的 allowCrossProtocolRedirects 是网络栈控制字段，
        // 不得以 HTTP 头形式透传给远端
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://example.com");
        headers.put("allowCrossProtocolRedirects", "true");

        assertTrue(OkHttpDataSourceHelper.shouldAllowCrossProtocolRedirects(headers));
        Map<String, String> clean = OkHttpDataSourceHelper.sanitizeHeaders(headers);
        assertFalse(clean.containsKey("allowCrossProtocolRedirects"));
        assertEquals("https://example.com", clean.get("Referer"));
    }
}
