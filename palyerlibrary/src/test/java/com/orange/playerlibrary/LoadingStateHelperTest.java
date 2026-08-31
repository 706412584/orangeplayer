package com.orange.playerlibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * LoadingStateHelper 单元测试。
 * TrafficStats / GSYVideoManager 在 JVM 环境下返回默认值（0），
 * 因此网速计算走 TrafficStats 路径时返回 0，重点验证状态管理与调度逻辑。
 */
@RunWith(MockitoJUnitRunner.class)
public class LoadingStateHelperTest {

    private LoadingStateHelper helper;

    @Before
    public void setUp() {
        helper = new LoadingStateHelper();
    }

    @Test
    public void 初始状态_未运行且无自定义文本() {
        assertFalse(helper.isRunning());
        assertNull(helper.getCustomText());
    }

    @Test
    public void setCustomText_保存并读取() {
        helper.setCustomText("解析磁力链接中 5/60s (8%)");
        assertEquals("解析磁力链接中 5/60s (8%)", helper.getCustomText());

        helper.setCustomText(null);
        assertNull(helper.getCustomText());
    }

    @Test
    public void calculateSpeed_JVM环境无流量返回0() {
        // TrafficStats.getUidRxBytes 在 JVM 下返回默认值 0
        assertEquals(0, helper.calculateSpeed());
    }

    @Test
    public void calculateSpeed_首次采样返回0并记录基准() {
        // 第一次调用记录基准，第二次无流量差时返回 0
        assertEquals(0, helper.calculateSpeed());
        assertEquals(0, helper.calculateSpeed());
    }

    @Test
    public void calculateSpeed_重复调用不崩溃且非负() {
        helper.calculateSpeed();
        helper.calculateSpeed();
        long speed = helper.calculateSpeed();
        assertTrue(speed >= 0);
    }

    @Test
    public void scheduleNext_未运行时不调度任务() {
        final boolean[] ran = {false};
        Runnable task = new Runnable() {
            @Override
            public void run() {
                ran[0] = true;
            }
        };

        helper.scheduleNext(task);

        // 未 start 时调度被忽略（同步验证：任务不应立即执行）
        assertFalse(ran[0]);
    }

    @Test
    public void scheduleNext_null任务不崩溃() {
        helper.scheduleNext(null);
    }

    @Test
    public void stop_未启动时调用安全() {
        helper.stop(null);
        assertFalse(helper.isRunning());
    }
}
