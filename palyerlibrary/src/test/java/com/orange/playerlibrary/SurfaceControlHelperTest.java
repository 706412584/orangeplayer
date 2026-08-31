package com.orange.playerlibrary;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * SurfaceControlHelper 单元测试。
 * JVM 环境下无法创建真实 SurfaceControl（init 会走异常分支），
 * 重点验证默认状态、幂等释放和未初始化时的 reparent 回退行为。
 */
public class SurfaceControlHelperTest {

    @Test
    public void 初始状态_未激活且无Surface() {
        SurfaceControlHelper helper = new SurfaceControlHelper();
        assertFalse(helper.isActive());
        assertNull(helper.getVideoSurface());
    }

    @Test
    public void release_未初始化时调用安全() {
        SurfaceControlHelper helper = new SurfaceControlHelper();
        helper.release();
        assertFalse(helper.isActive());
        assertNull(helper.getVideoSurface());
    }

    @Test
    public void reparent_未初始化返回false回退普通方式() {
        SurfaceControlHelper helper = new SurfaceControlHelper();
        assertFalse(helper.reparent(null));
    }

    @Test
    public void release_重复调用安全() {
        SurfaceControlHelper helper = new SurfaceControlHelper();
        helper.release();
        helper.release();
        assertFalse(helper.isActive());
    }
}
