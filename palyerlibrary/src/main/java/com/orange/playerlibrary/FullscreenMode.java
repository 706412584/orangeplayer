package com.orange.playerlibrary;

/**
 * 全屏模式（单一真值）。
 *
 * <p>设计参考 animeko 的 {@code PlayerFullscreenState}：把「当前处于哪种全屏」收敛为<b>唯一</b>状态，
 * 取代原先散落的 {@code mIsFullscreen} + {@code mIsPortraitFullscreen} 两个布尔标志。
 * 后者同时承载「横屏全屏 / 竖屏全屏 / 正常」三种语义，四个切换入口各改各的，
 * 是「全屏/竖屏切换出现多实例、状态错乱」的直接原因。</p>
 *
 * <p>所有切换一律走 {@link CustomFullscreenHelper#request(int)}，该方法<b>幂等</b>：
 * 已处于目标模式时不产生任何副作用，调用方无需自己判断当前状态。</p>
 */
public final class FullscreenMode {

    /** 正常（非全屏）。 */
    public static final int NORMAL = 0;

    /** 横屏全屏（进入时把屏幕转向横屏，可跟随重力感应切换正/反横屏）。 */
    public static final int LANDSCAPE_FULLSCREEN = 1;

    /** 竖屏全屏（不旋转屏幕，仅把播放器铺满竖屏）。 */
    public static final int PORTRAIT_FULLSCREEN = 2;

    private FullscreenMode() {
    }
}
