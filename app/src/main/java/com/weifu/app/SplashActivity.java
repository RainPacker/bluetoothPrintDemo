package com.weifu.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

import com.weifu.action.PermissionsResultAction;
import com.weifu.utils.PermissionsManager;

import java.util.Objects;


public class SplashActivity extends AppCompatActivity {
 
    private static final String TAG = SplashActivity.class.getSimpleName();
 
    Handler handler =new Handler();
 
    boolean isStartMainActivity = false;
 
    @Override
    protected void onCreate(Bundle savedInstanceState) {
//        if ((getIntent().getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) != 0) {
//            finish();
//            return;
//        }


        super.onCreate(savedInstanceState);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        Objects.requireNonNull(getSupportActionBar()).hide();
        setContentView(R.layout.act_spread_layout);


 
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
 
