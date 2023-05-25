package com.weifu.app;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;



/**
 * 基础Activity
 * Created by huangyx on 2018/3/5.
 */
public class BaseActivity extends AppCompatActivity {

    public static final String KEY_PAGE_INFO = "page_info";
    public static final String KEY_PAGE_ACT = "page_act";
    public static final String KEY_PARAMS = "page_params";
    public static final String KEY_CALLBACK_ID = "callback_id";

    public static final int REQ_CODE_CAMERA = 1001;
    public static final int REQ_CODE_PHOTO_ALBUM = 1002;
    public static final int REQ_CODE_CAPTURE_VIDEO = 1003;
    public static final int REQ_CODE_ALIY_SHORT_VIDEO = 1004;
    public static final int REQ_CODE_SCAN = 1005;

    protected int mExitAni = -1;

    // web-native交互时用到的callback id
    protected String mCallbackId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mCallbackId = getIntent().getStringExtra(KEY_CALLBACK_ID);
    }



    /**
     * 当状态栏设定的颜色为Color.TRANSPARENT时，需要使用该属性来设定状态栏中图标和文字颜色
     *
     * @return
     */
    protected int getSpareColor() {
        return -1;
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);

    }

    /**
     * 获取页面信息Key
     *
     * @return String
     */
    protected String getPageInfoKey() {
        return getIntent().getStringExtra(KEY_PAGE_INFO);
    }

    protected void onLeftBtnClicked() {
        onBackPressed();
    }







    @Override
    protected void onDestroy() {
        super.onDestroy();

    }

    @Override
    public void startActivity(Intent intent) {
        super.startActivity(intent);
    }
















    protected boolean doBackPressed() {
        return false;
    }
}
