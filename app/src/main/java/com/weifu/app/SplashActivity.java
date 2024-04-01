package com.weifu.app;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.weifu.action.PermissionsResultAction;
import com.weifu.app.ui.custom.HighlightingLogoView;
import com.weifu.utils.PermissionsManager;

import java.util.Objects;


public class SplashActivity extends AppCompatActivity {
 
    private static final String TAG = SplashActivity.class.getSimpleName();
 
    Handler handler =new Handler();
 
    boolean isStartMainActivity = false;
    ObjectAnimator revealAnimator;
 
    @Override
    protected void onCreate(Bundle savedInstanceState) {
//        if ((getIntent().getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) != 0) {
//            finish();
//            return;
//        }
        WindowManager windowManager = (WindowManager) this.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);

        int width = displayMetrics.widthPixels;
        int height = displayMetrics.heightPixels;
        TelephonyManager telephonyManager = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_NONE) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }


        Log.w(TAG, "onCreate: "+ width+"::"+height);

        getWindow().setNavigationBarColor(Color.WHITE);


        super.onCreate(savedInstanceState);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        Objects.requireNonNull(getSupportActionBar()).hide();
        setContentView(R.layout.act_spread_layout);
        // 获取ImageView
        ImageView loadingLogo = findViewById(R.id.imageView2);

        // 获取图片的实际宽度
        final Drawable drawable = loadingLogo.getDrawable();
        final int targetWidth = drawable.getIntrinsicWidth();

// 创建属性动画
        ValueAnimator animator = ValueAnimator.ofInt(0, targetWidth);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                // 获取当前动画的值并设置到ImageView的宽度上
                int currentValue = (int) animation.getAnimatedValue();
                ViewGroup.LayoutParams layoutParams = loadingLogo.getLayoutParams();
                layoutParams.width = currentValue;
                loadingLogo.setLayoutParams(layoutParams);
            }
        });

// 设置动画的时长和其他属性
        animator.setDuration(1000);
        animator.setInterpolator(new LinearInterpolator()); // 线性插值器，也可以换成其他你喜欢的插值器

// 开始动画
     //   animator.start();


 
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startMainActivity();
                Log.d(TAG, "run: 当前线程为："+Thread.currentThread().getName());
            }


        },2000);
    }


 
    /**
     * 跳转主界面
     */
    private void startMainActivity() {
        if (isStartMainActivity == false){
 
            isStartMainActivity = true;
            Intent intent = new Intent(this,MainActivity.class);
            startActivity(intent);
 
            finish();
        }
 
 
    }
 
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 点击跳动主界面
        Log.i(TAG, "onTouchEvent: Action "+ event.getAction());
        startMainActivity();
        
        return super.onTouchEvent(event);
        
    }
 
    @Override
    protected void onDestroy() {

        // 移除延迟函数
        handler.removeCallbacks(null);

        super.onDestroy();
    }
}
 
