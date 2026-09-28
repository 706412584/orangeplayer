/*
 * 多语言 shim。
 *
 * 原版 animeko 的 Lang 是编译期生成的整套多语言系统（app-lang 模块），
 * 播放器 UI 以「顶层属性」形式导入（如 `import ...lang.video_player_play`）。
 * 这里按同样的形态提供精简实现，使复制的 UI 代码无需改动即可编译。
 *
 * 字符串取 animeko 中文文案；如需完整多语言，后续替换为真实资源系统。
 */
@file:Suppress("unused")

package me.him188.ani.app.ui.lang

// ===== 播放控制 =====
const val video_player_play = "播放"
const val video_player_pause = "暂停"
const val video_player_send = "发送"
const val video_player_cancel = "取消"
const val video_player_mute = "静音"
const val video_player_volume = "音量"
const val video_player_speed = "倍速"
const val video_player_next_episode = "下一集"
const val video_player_select_episode = "选集"
const val video_player_enable_danmaku = "开启弹幕"
const val video_player_disable_danmaku = "关闭弹幕"
const val video_player_skip_op_ed = "跳过片头片尾"
const val video_player_stats_title_show = "显示播放统计"
const val video_player_stats_title_hide = "隐藏播放统计"

// ===== 画面比例 =====
const val video_player_aspect_fit = "适应"
const val video_player_aspect_stretch = "拉伸"
const val video_player_aspect_crop = "裁剪"

// ===== 弹幕输入框轮换占位（原版 20 条）=====
const val video_player_danmaku_placeholder_1 = "发条友善的弹幕吧～"
const val video_player_danmaku_placeholder_2 = "弹幕护体！"
const val video_player_danmaku_placeholder_3 = "前方高能"
const val video_player_danmaku_placeholder_4 = "此处应有弹幕"
const val video_player_danmaku_placeholder_5 = "打卡"
const val video_player_danmaku_placeholder_6 = "来了来了"
const val video_player_danmaku_placeholder_7 = "哈哈哈哈哈"
const val video_player_danmaku_placeholder_8 = "名场面"
const val video_player_danmaku_placeholder_9 = "泪目"
const val video_player_danmaku_placeholder_10 = "笑死"
const val video_player_danmaku_placeholder_11 = "好耶"
const val video_player_danmaku_placeholder_12 = "考古"
const val video_player_danmaku_placeholder_13 = "二刷"
const val video_player_danmaku_placeholder_14 = "三刷"
const val video_player_danmaku_placeholder_15 = "循环播放"
const val video_player_danmaku_placeholder_16 = "这里必看"
const val video_player_danmaku_placeholder_17 = "太强了"
const val video_player_danmaku_placeholder_18 = "心疼"
const val video_player_danmaku_placeholder_19 = "不敢看"
const val video_player_danmaku_placeholder_20 = "awsl"

// ===== 弹幕样式（DanmakuStylePicker）=====
const val danmaku_send_style = "弹幕样式"
const val danmaku_send_style_color = "颜色"
const val danmaku_send_style_custom_color = "自定义颜色"
const val danmaku_send_style_location = "位置"
const val subject_episode_video_settings_top = "顶部"
const val subject_episode_video_settings_bottom = "底部"
const val subject_episode_video_settings_floating = "滚动"

/**
 * 对象形式的访问入口。
 *
 * 原版 `Lang` 是一个把资源 id 转成字符串的访问器（`val Lang get() = Res.string`），
 * 两种调用形态并存：`Lang.video_player_play` 与裸 `video_player_play`。
 * 这里提供 object 形态，成员与顶层属性同名同值。
 */
object Lang {
    val video_player_play get() = me.him188.ani.app.ui.lang.video_player_play
    val video_player_pause get() = me.him188.ani.app.ui.lang.video_player_pause
    val video_player_send get() = me.him188.ani.app.ui.lang.video_player_send
    val video_player_cancel get() = me.him188.ani.app.ui.lang.video_player_cancel
    val video_player_mute get() = me.him188.ani.app.ui.lang.video_player_mute
    val video_player_volume get() = me.him188.ani.app.ui.lang.video_player_volume
    val video_player_speed get() = me.him188.ani.app.ui.lang.video_player_speed
    val video_player_next_episode get() = me.him188.ani.app.ui.lang.video_player_next_episode
    val video_player_select_episode get() = me.him188.ani.app.ui.lang.video_player_select_episode
    val video_player_enable_danmaku get() = me.him188.ani.app.ui.lang.video_player_enable_danmaku
    val video_player_disable_danmaku get() = me.him188.ani.app.ui.lang.video_player_disable_danmaku
    val video_player_skip_op_ed get() = me.him188.ani.app.ui.lang.video_player_skip_op_ed
    val video_player_stats_title_show get() = me.him188.ani.app.ui.lang.video_player_stats_title_show
    val video_player_stats_title_hide get() = me.him188.ani.app.ui.lang.video_player_stats_title_hide
    val video_player_aspect_fit get() = me.him188.ani.app.ui.lang.video_player_aspect_fit
    val video_player_aspect_stretch get() = me.him188.ani.app.ui.lang.video_player_aspect_stretch
    val video_player_aspect_crop get() = me.him188.ani.app.ui.lang.video_player_aspect_crop
    val video_player_danmaku_placeholder_1 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_1
    val video_player_danmaku_placeholder_2 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_2
    val video_player_danmaku_placeholder_3 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_3
    val video_player_danmaku_placeholder_4 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_4
    val video_player_danmaku_placeholder_5 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_5
    val video_player_danmaku_placeholder_6 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_6
    val video_player_danmaku_placeholder_7 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_7
    val video_player_danmaku_placeholder_8 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_8
    val video_player_danmaku_placeholder_9 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_9
    val video_player_danmaku_placeholder_10 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_10
    val video_player_danmaku_placeholder_11 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_11
    val video_player_danmaku_placeholder_12 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_12
    val video_player_danmaku_placeholder_13 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_13
    val video_player_danmaku_placeholder_14 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_14
    val video_player_danmaku_placeholder_15 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_15
    val video_player_danmaku_placeholder_16 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_16
    val video_player_danmaku_placeholder_17 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_17
    val video_player_danmaku_placeholder_18 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_18
    val video_player_danmaku_placeholder_19 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_19
    val video_player_danmaku_placeholder_20 get() = me.him188.ani.app.ui.lang.video_player_danmaku_placeholder_20

    val danmaku_send_style get() = me.him188.ani.app.ui.lang.danmaku_send_style
    val danmaku_send_style_color get() = me.him188.ani.app.ui.lang.danmaku_send_style_color
    val danmaku_send_style_custom_color get() = me.him188.ani.app.ui.lang.danmaku_send_style_custom_color
    val danmaku_send_style_location get() = me.him188.ani.app.ui.lang.danmaku_send_style_location
    val subject_episode_video_settings_top get() = me.him188.ani.app.ui.lang.subject_episode_video_settings_top
    val subject_episode_video_settings_bottom get() = me.him188.ani.app.ui.lang.subject_episode_video_settings_bottom
    val subject_episode_video_settings_floating get() = me.him188.ani.app.ui.lang.subject_episode_video_settings_floating
}
