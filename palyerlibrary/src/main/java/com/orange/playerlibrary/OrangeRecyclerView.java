package com.orange.playerlibrary;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * RecyclerView 辅助类
 * 简化 RecyclerView 的使用
 */
public class OrangeRecyclerView {

    private OrangeRecyclerViewAdapter mAdapter;

    /**
     * 设置线性布局管理器
     */
    public OrangeRecyclerView setLinearLayoutManager(RecyclerView recyclerView, Context context) {
        LinearLayoutManager layoutManager = new LinearLayoutManager(context);
        recyclerView.setLayoutManager(layoutManager);
        return this;
    }

    /**
     * 设置网格布局管理器
     */
    public OrangeRecyclerView setGridLayoutManager(RecyclerView recyclerView, Context context, int spanCount) {
        GridLayoutManager layoutManager = new GridLayoutManager(context, spanCount);
        recyclerView.setLayoutManager(layoutManager);
        return this;
    }
    
    /**
     * 设置适配器
     */
    public OrangeRecyclerView setAdapter(RecyclerView recyclerView, 
                                         int itemLayoutId,
                                         ArrayList<HashMap<String, Object>> data,
                                         OnRecyclerViewAdapter callback) {
        mAdapter = new OrangeRecyclerViewAdapter(itemLayoutId, data, callback);
        recyclerView.setAdapter(mAdapter);
        return this;
    }
    
    /**
     * 通知数据变化
     */
    public void notifyDataSetChanged(RecyclerView recyclerView) {
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }
    
    /**
     * RecyclerView 适配器回调接口
     */
    public interface OnRecyclerViewAdapter {
        void bindView(OrangeRecyclerViewAdapter.ViewHolder holder,
                     ArrayList<HashMap<String, Object>> data,
                     int position);
    }
}
