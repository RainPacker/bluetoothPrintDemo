package com.weifu.app.ui.custom;

import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import com.yzq.zxinglibrary.android.CaptureActivity;

public class MyScan  extends CaptureActivity {


    private static final String TAG = "MySan";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 保持Activity处于唤醒状态
        // 隐藏状态栏
//        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
//        getWindow().setNavigationBarColor(Color.parseColor("#004098"));
//        getWindow().setStatusBarColor(Color.parseColor("#004098"));
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        float screenWidth = displayMetrics.widthPixels / displayMetrics.density;
        float screenHeight = displayMetrics.heightPixels / displayMetrics.density;
        Log.w(TAG, "onCreate: "+ screenWidth+"::"+screenHeight);
        if (screenWidth > 890) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }

        getWindow().setNavigationBarColor(Color.parseColor("#004098"));

    }
}
