package com.orange.playerlibrary.ui;

/**
 * 可被播放器主动刷新状态的控制栏 View。
 *
 * <p>{@link IOrangeControlUiProvider#createControlView} 返回的 View 若实现本接口，
 * 播放器会在进度/状态变化时调用 {@link #onRefresh()}，让它把最新的播放状态读进 UI。
 * 未实现则播放器不做任何刷新调用（适用于自持状态的实现）。</p>
 *
 * <p>之所以用「View 侧接口」而不是让 {@link IOrangeControlUiProvider.ControlUiHost} 承担：
 * 后者由播放器实现、生命周期长于单个 View，拿不到具体 View 类型；
 * 而 {@code palyerlibrary} 也不能反向依赖 UI 实现模块。</p>
 */
public interface IOrangeControlUiView {

    /** 播放器通知控制栏刷新显示状态（进度、播放/暂停、弹幕开关等）。 */
    void onRefresh();
}
