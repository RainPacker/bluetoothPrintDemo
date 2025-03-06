package com.weifu.app;

import android.app.Application;
import android.util.Log;

import com.weifu.app.utils.SecurityUtils;

public class MyApplication extends Application {
    private static final String TAG = "MyApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        
        // 初始化安全工具类
        try {
            SecurityUtils.init(getApplicationContext());
            Log.d(TAG, "SecurityUtils 初始化成功");
        } catch (Exception e) {
            Log.e(TAG, "SecurityUtils 初始化失败: " + e.getMessage());
        }
    }
} 