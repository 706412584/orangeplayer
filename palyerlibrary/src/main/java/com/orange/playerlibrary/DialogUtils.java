package com.orange.playerlibrary;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;

public class DialogUtils {
    
    // 位置枚举
    public enum DialogPosition {
        LEFT, RIGHT, TOP, BOTTOM, CENTER
    }
    
    // 位置默认值变量
    private static float DEFAULT_RIGHT_DIALOG_WIDTH_PORTRAIT = 0.5f; // 竖屏默认50%
    private static float DEFAULT_RIGHT_DIALOG_WIDTH_LANDSCAPE = 0.3f; // 横屏默认30%
    
    /**
     * 使用布局资源ID创建弹窗
     */
    public static AlertDialog showAlertDialog(Activity context, int layoutResId, DialogPosition position) {
        return createDialog(context, 
                           context.getLayoutInflater().inflate(layoutResId, null, false), 
                           position, 
                           null, // 宽度比例 - 使用智能默认值
                           null); // 高度比例 - 使用位置默认值
    }
    
    /**
     * 使用自定义View创建弹窗
     */
    public static AlertDialog showDialogFromView(Activity context,
                                                View view,
                                                DialogPosition position) {
        return createDialog(context, 
                           view, 
                           position, 
                           null, // 宽度比例 - 使用智能默认值
                           null); // 高度比例 - 使用位置默认值
    }
    
    /**
     * 完整参数创建弹窗（基于视图对象）
     */
    public static AlertDialog showCustomDialog(Activity context,
                                              View view,
                                              DialogPosition position,
                                              Float widthRatio,
                                              Float heightRatio) {
        return createDialog(context, view, position, widthRatio, heightRatio);
    }
    
    /**
     * 完整参数创建弹窗（基于布局ID）
     */
    public static AlertDialog showCustomDialog(Activity context,
                                              int layoutResId,
                                              DialogPosition position,
                                              Float widthRatio,
                                              Float heightRatio) {
        View view = context.getLayoutInflater().inflate(layoutResId, null, false);
        return createDialog(context, view, position, widthRatio, heightRatio);
    }
    
    /**
     * 设置右弹窗默认宽度比例
     */
    public static void setDefaultRightDialogWidth(float portraitRatio, float landscapeRatio) {
        DEFAULT_RIGHT_DIALOG_WIDTH_PORTRAIT = portraitRatio;
        DEFAULT_RIGHT_DIALOG_WIDTH_LANDSCAPE = landscapeRatio;
    }
    
    /**
     * 内部创建对话框的通用方法
     */
    private static AlertDialog createDialog(Activity context,
                                          View view,
                                          DialogPosition position,
                                          Float widthRatio,
                                          Float heightRatio) {
        // 统一使用自定义 View 覆盖层方式，确保所有版本行为一致
        // 优点：
        // 1. 点击外部区域关闭功能在所有版本上行为一致
        // 2. 避免 Android 4.4 的 Dialog 透明度问题
        // 3. 避免 AlertDialog 的窗口大小和位置限制
        return createDialogAsView(context, view, position, widthRatio, heightRatio);
    }
    
    /**
     * 使用自定义 View 覆盖层创建"对话框"
     * 统一在所有 Android 版本上使用此方式，确保行为一致
     */
    private static AlertDialog createDialogAsView(Activity context,
                                                 View view,
                                                 DialogPosition position,
                                                 Float widthRatio,
                                                 Float heightRatio) {
        // 获取 DecorView（Activity 的根视图）
        android.view.ViewGroup decorView = (android.view.ViewGroup) context.getWindow().getDecorView();
        
        // 设置布局参数
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        );
        view.setLayoutParams(params);
        
        // 添加到 DecorView
        decorView.addView(view);

        // 右侧贴边面板：对背后内容施加毛玻璃（API31+，低版本自动降级为半透明）
        if (position == DialogPosition.RIGHT) {
            applyPanelBlur(context);
        }
        
        // 创建一个自定义的 AlertDialog 子类，重写 dismiss() 方法
        AlertDialog fakeDialog = new AlertDialog(context, R.style.TransparentDialog) {
            private boolean isDismissed = false;
            
            @Override
            public void dismiss() {
                if (!isDismissed) {
                    isDismissed = true;
                    decorView.removeView(view);
                    removeBlurEffect(context);
                    // 调用父类的 dismiss，触发 OnDismissListener
                    try {
                        super.dismiss();
                    } catch (Exception e) {
                        // 忽略异常，因为对话框可能没有被 show()
                    }
                }
            }
        };
        
        // 查找布局中的根元素（通常是 id 为 layout 的 FrameLayout）
        // 设置点击监听器以关闭弹窗
        View layout = view.findViewById(R.id.layout);
        if (layout != null) {
            layout.setOnClickListener(v -> fakeDialog.dismiss());
        }
        
        // 查找内容面板，设置点击监听器消费事件（防止传递到根布局）
        View contentLayout = view.findViewById(R.id.content_layout);
        if (contentLayout != null) {
            contentLayout.setOnClickListener(v -> {
                // 不做任何事情，只是消费点击事件
            });
        }

        // 右侧贴边面板：统一从右侧滑入动效
        if (position == DialogPosition.RIGHT && contentLayout != null) {
            final View panel = contentLayout;
            panel.post(() -> {
                panel.setTranslationX(panel.getWidth());
                panel.animate()
                        .translationX(0f)
                        .setDuration(220)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            });
        }
        
        return fakeDialog;
    }
    
    /**
     * 配置窗口属性
     */
    private static void setupWindow(Window window, DialogPosition position,
                                  Float widthRatio, Float heightRatio, Context context) {
        if (window == null) return;
        
        DisplayMetrics metrics = new DisplayMetrics();
        window.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        
        // 弹窗窗口始终占满全屏，点击外部区域由布局内部处理
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        window.setGravity(Gravity.CENTER);
        
        // Android 4.4 需要使用 BitmapDrawable 来实现真正的透明背景
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
            window.setBackgroundDrawable(new android.graphics.drawable.BitmapDrawable());
            // 禁用 dim 效果，避免黑色背景
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        } else {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        
        window.setWindowAnimations(getAnimationStyle(position));
        
        setImmersiveMode(window);
    }
    
    /**
     * 设置弹窗边距
     */
    private static void setDialogMargins(Window window, DialogPosition position, Context context) {
        if (window == null) return;
        
        // 暂时不设置边距，避免点击区域偏移问题
        // 边距通过布局文件的 padding 来实现
    }
    
    /**
     * 计算对话框尺寸
     */
    private static int calculateDialogDimension(boolean isWidth,
                                             DisplayMetrics metrics,
                                             DialogPosition position,
                                             Float ratio,
                                             Context context) {
        // 对于左右位置的弹窗，高度默认使用全屏
        if (!isWidth && (position == DialogPosition.LEFT || position == DialogPosition.RIGHT)) {
            if (ratio == null || ratio <= 0) {
                return ViewGroup.LayoutParams.MATCH_PARENT;
            }
        }
        
        // 对于上下位置的弹窗，宽度默认使用全屏
        if (isWidth && (position == DialogPosition.TOP || position == DialogPosition.BOTTOM)) {
            if (ratio == null || ratio <= 0) {
                return ViewGroup.LayoutParams.MATCH_PARENT;
            }
        }
        
        // 如果ratio为null，表示使用布局默认尺寸
        if (ratio == null) {
            return isWidth ? ViewGroup.LayoutParams.WRAP_CONTENT : 
                    ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        
        // 如果ratio为-1，表示使用MATCH_PARENT
        if (ratio < 0) {
            return ViewGroup.LayoutParams.MATCH_PARENT;
        }
        
        // 特殊处理右弹窗的横竖屏自适应
        if (isWidth && (position == DialogPosition.LEFT || position == DialogPosition.RIGHT)) {
            boolean isPortrait = context.getResources().getConfiguration().orientation == 
                                Configuration.ORIENTATION_PORTRAIT;
            float effectiveRatio = isPortrait ? DEFAULT_RIGHT_DIALOG_WIDTH_PORTRAIT :
                                             DEFAULT_RIGHT_DIALOG_WIDTH_LANDSCAPE;
            return (int) (metrics.widthPixels * effectiveRatio);
        }
        
        // 位置默认值
        float effectiveRatio = ratio != null && ratio > 0 ? ratio :
                getDefaultRatio(isWidth, position);
        
        // 计算尺寸
        return (int) (isWidth ? 
                     metrics.widthPixels * effectiveRatio : 
                     metrics.heightPixels * effectiveRatio);
    }
    
    /**
     * 获取位置默认比例
     */
    private static float getDefaultRatio(boolean isWidth, DialogPosition position) {
        if (isWidth) {
            switch (position) {
                case LEFT:
                case RIGHT:
                    return 0.4f; // 左右位置默认40%
                case CENTER:
                    return 0.8f; // 居中默认80%
                default: // TOP/BOTTOM
                    return 1.0f; // 上下位置全宽
            }
        } else {
            switch (position) {
                case TOP:
                case BOTTOM:
                    return 0.3f; // 上下位置30%
                case CENTER:
                    return 0.0f; // 居中高度使用WRAP_CONTENT
                default: // LEFT/RIGHT
                    return 1.0f; // 左右位置全高
            }
        }
    }
    
    private static int getGravity(DialogPosition position) {
        switch (position) {
            case LEFT: return Gravity.LEFT | Gravity.CENTER_VERTICAL;
            case RIGHT: return Gravity.RIGHT | Gravity.CENTER_VERTICAL;
            case TOP: return Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            case BOTTOM: return Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            default: return Gravity.CENTER;
        }
    }
    
    private static int getAnimationStyle(DialogPosition position) {
        return android.R.style.Animation_Dialog;
    }
    
    private static void setImmersiveMode(Window window) {
        if (window != null) {
            // 设置沉浸式模式，但不使用 FLAG_NOT_FOCUSABLE，以确保能接收触摸事件
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }
    }
    
    /**
     * 对面板背后的 Activity 内容施加毛玻璃模糊（API 31+）。
     * 只模糊 android.R.id.content（承载视频与界面），不影响叠加在 decorView 上的面板本身。
     * API 31 以下自动降级为无模糊，依赖面板半透明表面色（op_surface_*）达成深色玻璃观感。
     */
    public static void applyPanelBlur(Activity activity) {
        setContentBlur(activity, 24f, 24f);
    }

    public static void showBlurEffect(Activity activity, String str, String str2) {
        setContentBlur(activity, Integer.parseInt(str), Integer.parseInt(str2));
    }

    public static void removeBlurEffect(Activity activity) {
        setContentBlur(activity, 0f, 0f);
    }

    /** 统一入口：对 android.R.id.content 施加/清除模糊。半径为 0 时清除。 */
    private static void setContentBlur(Activity activity, float radiusX, float radiusY) {
        if (activity == null || Build.VERSION.SDK_INT < 31) {
            return;
        }
        View content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        if (radiusX <= 0f || radiusY <= 0f) {
            content.setRenderEffect(null);
        } else {
            content.setRenderEffect(
                RenderEffect.createBlurEffect(radiusX, radiusY, Shader.TileMode.CLAMP));
        }
    }
}
