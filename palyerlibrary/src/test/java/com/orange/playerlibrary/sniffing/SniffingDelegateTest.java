package com.orange.playerlibrary.sniffing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * SniffingDelegate 状态管理单元测试。
 * 只验证不触发 VideoSniffing 静态调用的路径（状态标记、空 URL 保护）。
 */
@RunWith(MockitoJUnitRunner.class)
public class SniffingDelegateTest {

    @Mock
    Context mockContext;

    private SniffingDelegate delegate;

    @Before
    public void setUp() {
        delegate = new SniffingDelegate(mockContext);
    }

    @Test
    public void 初始状态_未在嗅探() {
        assertFalse(delegate.isSniffing());
    }

    @Test
    public void setSniffing_可更新状态() {
        delegate.setSniffing(true);
        assertTrue(delegate.isSniffing());

        delegate.setSniffing(false);
        assertFalse(delegate.isSniffing());
    }

    @Test
    public void startSniffing_nullURL_不改变状态() {
        delegate.startSniffing(null, null);
        assertFalse(delegate.isSniffing());
    }

    @Test
    public void startSniffing_空URL_不改变状态() {
        delegate.startSniffing("", null);
        assertFalse(delegate.isSniffing());
    }
}
