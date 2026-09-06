package com.orange.playerlibrary.tool;

import android.view.*;
import android.widget.*;
import com.orange.playerlibrary.R;
import android.content.*;
import android.graphics.Color;
import android.view.inputmethod.InputMethodManager;
import android.graphics.drawable.GradientDrawable;
import android.text.*;

public class DanmuexitDialog {
    // 发送监听器接口
    public interface DanmuSendListener {
        void onDanmuSend(String text, int color);
    }

    private static DanmuSendListener sendListener;

    public static void setDanmuSendListener(DanmuSendListener listener) {
        sendListener = listener;
    }

    // 颜色顺序：绿色、青色、紫色、黄色、红色、蓝色、白色
    private final int[] COLOR_PALETTE = {
        Color.parseColor("#00FF00"), // 纯绿
        Color.parseColor("#00FFFF"), // 青色
        Color.parseColor("#FF00FF"), // 紫色
        Color.parseColor("#FFFF00"), // 黄色
        Color.parseColor("#FF0000"), // 红色
        Color.parseColor("#0000FF"), // 蓝色
        Color.parseColor("#FFFFFF")  // 白色
    };

    public void show(Context context) {
        // 仅接受 Activity，右侧面板需要挂到 DecorView
        if (!(context instanceof android.app.Activity)) return;
        android.app.Activity activity = (android.app.Activity) context;

        // 1. 创建面板视图（右侧滑入，与设置/选集弹窗同体系）
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.danmu_color_selector, null);

        // 2. 通过 DialogUtils 显示右侧面板（自动处理点击外部关闭与背景模糊）
        final android.app.AlertDialog[] dialogHolder = new android.app.AlertDialog[1];
        dialogHolder[0] = com.orange.playerlibrary.DialogUtils.showCustomDialog(
                activity, dialogView, com.orange.playerlibrary.DialogUtils.DialogPosition.RIGHT, null, null);

        // 3. 初始化UI组件
        GridView gridColor = dialogView.findViewById(R.id.grid_color);
        TextView tvPreview = dialogView.findViewById(R.id.tv_preview);
        final EditText etDanmu = dialogView.findViewById(R.id.et_danmu);
        TextView tvCount = dialogView.findViewById(R.id.tv_danmu_count);
        TextView danmuSend = dialogView.findViewById(R.id.btn_send);

        // 4. 设置颜色选择器适配器
        final ColorAdapter adapter = new ColorAdapter(activity, COLOR_PALETTE);
        gridColor.setAdapter(adapter);

        // 5. 设置默认选中白色
        adapter.setSelectedPosition(6);
        etDanmu.setTextColor(COLOR_PALETTE[6]);
        tvPreview.setTextColor(COLOR_PALETTE[6]);

        // 6. 颜色选择监听
        gridColor.setOnItemClickListener((parent, view, position, id) -> {
            adapter.setSelectedPosition(position);
            etDanmu.setTextColor(COLOR_PALETTE[position]);
            tvPreview.setTextColor(COLOR_PALETTE[position]);
        });

        // 7. 字数统计监听
        etDanmu.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                tvCount.setText(s.length() + "/32");
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // 8. 发送按钮事件
        danmuSend.setOnClickListener(v -> {
            // 1. 获取弹幕文本
            String danmuText = etDanmu.getText().toString().trim();

            // 2. 验证文本非空
            if (TextUtils.isEmpty(danmuText)) {
                Toast.makeText(activity, "弹幕内容不能为空", Toast.LENGTH_SHORT).show();
                return;
            }

            // 3. 获取选中的颜色
            int selectedColor = COLOR_PALETTE[adapter.getSelectedPosition()];

            // 4. 发送弹幕
            if (sendListener != null) {
                sendListener.onDanmuSend(danmuText, selectedColor);
            }

            // 5. 关闭面板
            dialogHolder[0].dismiss();

            // 6. 隐藏键盘
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(etDanmu.getWindowToken(), 0);
            }
        });

        // 9. 自动弹出键盘
        etDanmu.postDelayed(() -> {
            etDanmu.requestFocus();
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etDanmu, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 300);
    }

    // 颜色网格适配器
    private static class ColorAdapter extends BaseAdapter {
        private final Context context;
        private final int[] colors;
        private int selectedPosition = -1;

        public ColorAdapter(Context context, int[] colors) {
            this.context = context;
            this.colors = colors;
        }

        public void setSelectedPosition(int position) {
            this.selectedPosition = position;
            notifyDataSetChanged();
        }

        public int getSelectedPosition() {
            return selectedPosition;
        }

        @Override
        public int getCount() {
            return colors.length;
        }

        @Override
        public Object getItem(int position) {
            return colors[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.color_grid_item, parent, false);
            }

            View colorCircle = convertView.findViewById(R.id.color_circle);
            ImageView checkIcon = convertView.findViewById(R.id.check_icon);

            // 设置颜色
            GradientDrawable bg = (GradientDrawable) colorCircle.getBackground();
            bg.setColor(colors[position]);

            // 设置选中状态
            if (position == selectedPosition) {
                checkIcon.setVisibility(View.VISIBLE);
            } else {
                checkIcon.setVisibility(View.INVISIBLE);
            }

            return convertView;
        }
    }
}
