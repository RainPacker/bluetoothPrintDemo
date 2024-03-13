package com.weifu.app.ui.custom;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.widget.AppCompatImageView;

import com.weifu.app.R;
import com.yzq.zxinglibrary.android.CaptureActivity;
import com.yzq.zxinglibrary.view.ViewfinderView;

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
        WindowManager windowManager = (WindowManager) this.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);

//        int width = displayMetrics.widthPixels;
//        int height = displayMetrics.heightPixels;
        TelephonyManager telephonyManager = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
         SurfaceView surfaceView = findViewById(R.id.preview_view);
//        AppCompatImageView imageView = findViewById(R.id.backIv);
        ViewfinderView viewfinderView = findViewById(R.id.viewfinder_view);
        if (telephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_NONE) {
//            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
//            WindowManager manager = (WindowManager) this.getSystemService(Context.WINDOW_SERVICE);
//            Display display = manager.getDefaultDisplay();
//           Point    screenResolution = new Point(display.getWidth(), display.getHeight());
//
//
//
//            int screenResolutionX = screenResolution.x;
//
//            int width = (int) (screenResolutionX * 0.6);
//            int height = width;
//
//
//            /*水平居中  偏上显示*/
//            int leftOffset = (screenResolution.x - width) /4;
//            int topOffset = (screenResolution.y - height) / 5;
//
//         Rect   framingRect = new Rect(leftOffset, topOffset, leftOffset + width,
//                    topOffset + height);
////            surfaceView.setRotation(90);
////
////            getCameraManager().setManualFramingRect(width,height);



        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
//        Log.w(TAG, "onCreate: "+ width+"::"+height);

        getWindow().setNavigationBarColor(Color.parseColor("#004098"));

    }
}
