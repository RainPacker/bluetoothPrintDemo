package com.weifu.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.telephony.TelephonyManager;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import com.inuker.bluetooth.library.BluetoothClient;
import com.mingle.widget.LoadingView;
import com.mingle.widget.ShapeLoadingDialog;
import com.symbol.emdk.EMDKManager;
import com.symbol.emdk.barcode.BarcodeManager;
import com.symbol.emdk.barcode.Scanner;
import com.symbol.emdk.barcode.ScannerInfo;
import com.weifu.action.PermissionsResultAction;
import com.weifu.app.js.JsBridge;
import com.weifu.app.ui.custom.CustomDialog;
import com.weifu.app.ui.home.AndroidBug5497Workaround;
import com.weifu.app.version.UpdateManager;
import com.weifu.utils.PermissionsManager;
import com.yzq.zxinglibrary.common.Constant;

import net.posprinter.posprinterface.IMyBinder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

public class MainActivity extends AppCompatActivity /**implements Scanner.DataListener, EMDKManager.EMDKListener**/ {
     private static final int REQUEST_OPEN = 0X01;
    private static final String COM_WEIFU_IWMS_FILEPROVIDE = "com.weifu.iwms.fileprovider";
    private static final String WATERMARK_TEXT = "安全生产";
    private static final String CHANNEL_ID ="wps" ;
    private static final int NOTICE_PERMISSION_REQUEST_CODE = 3 ;

    private EMDKManager emdkManager = null;
    private BarcodeManager barcodeManager = null;
    private Scanner scanner = null;
    private List<ScannerInfo> deviceList = null;
    private int scannerIndex = 0; // Keep the selected scanner
    private static final int PROCESS_BAR_MAX = 100;

    String TAG = getClass().getSimpleName();
    // prod
//    private static final String LOADRL ="http://10.1.4.141:81/" ;
//    private static final String LOADRL ="http://121.225.97.57:18443/" ;
    private static final String LOADRL ="http://10.94.31.170:31354/" ;
//    private static final String LOADRL ="http://10.1.4.145" ;
//    private static final String LOADRL ="file:///android_asset/test.html" ;
//    private static final String LOADRL ="http://10.94.31.150:31223/" ;
    private WebView webView;
    private final int PICK_REQUEST = 10011;
    ValueCallback<Uri> mFilePathCallback;
    ValueCallback<Uri[]> mFilePathCallbackArray;
    private IMyBinder printerBinder;
//    private BluetoothStateReceiver mBluetoothStateReceiver;
    private ProgressBar progressBar;
    BluetoothClient mClient;
    private ShapeLoadingDialog shapeLoadingDialog;
    private LoadingView loadingView;
    private JsBridge jsBridge;

    private long exitTime;

    private String cameraPhotoPath;
    private ValueCallback<Uri> mUploadCallbackBelow;
    private Uri imageUri;
    private ValueCallback<Uri[]> mUploadCallbackAboveL;


    public final IMyBinder getPrinterBinder() {
        return printerBinder;
    }

    public WebView getWebView() {
        return webView;
    }

    public Scanner getScanner() {
        return scanner;
    }

    @SuppressLint({"SetJavaScriptEnabled", "UnspecifiedRegisterReceiverFlag"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        this.makeStatusBarTransparent(this);
        setFullscreen(true, true);
        setAndroidNativeLightStatusBar(this, true);
//        getWindow().setNavigationBarColor(Color.parseColor("#004098"));
        super.onCreate(savedInstanceState);
        new Thread(()->{
            Looper.prepare();
            updateApk();
            Looper.loop();
        }).start();

       // initReceiver();
     //   getPermission();
        requestPermissions();
        createClient();
//        try {
//            EMDKResults results = EMDKManager.getEMDKManager(MainActivity.this, this);
//            if (results.statusCode != EMDKResults.STATUS_CODE.SUCCESS) {
//                Log.e(TAG,"EMDKManager object request failed!");
//               // return;
//            }
//        }catch (Exception e){
//            Log.e(TAG, "onCreate: "+e.getMessage(),e);
//        }

        //隐藏ActionBar
        Objects.requireNonNull(getSupportActionBar()).hide();

        setContentView(R.layout.activity_main);
        //WebView加载页面
        webView = findViewById(R.id.web_view);
        webView.getSettings().setJavaScriptEnabled(true);


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
//        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // 横屏
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);

        // wevView监听 H5 页面的下载事件
        webView.setDownloadListener(new DownloadListener() {

            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {

                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));

                String cookies = CookieManager.getInstance().getCookie(url);

                request.addRequestHeader("cookie", cookies);

                request.addRequestHeader("User-Agent", userAgent);

                request.setDescription("下载中...");

                request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype));

                request.allowScanningByMediaScanner(); request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype));

                DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

                manager.enqueue(request);

                showMessage("下载中...");

                //Notif if success

                BroadcastReceiver onComplete = new BroadcastReceiver() {

                    public void onReceive(Context ctxt, Intent intent) {

                        showMessage("下载完成");

                        unregisterReceiver(this);

                    }};

                registerReceiver(onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

            }

        });

        //该方法解决的问题是打开浏览器不调用系统浏览器，直接用 webView 打开
        jsBridge = new JsBridge(this);
        // 注册配置文件 斑马专用
        jsBridge.createProfile();
        // 注册广播
        IntentFilter actionFilters = new IntentFilter();
        actionFilters.addAction(JsBridge.ACTION_IDATA_SCANRESULT);
        actionFilters.addAction(JsBridge.ACTION_ZEBRA_SCANRESULT);
        actionFilters.addAction(Intent.ACTION_SCREEN_ON);
        actionFilters.addAction( BluetoothAdapter.ACTION_STATE_CHANGED);
        actionFilters.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(jsBridge,actionFilters, Context.RECEIVER_EXPORTED);
        }else {
            registerReceiver(jsBridge,actionFilters);
        }

        webView.addJavascriptInterface(jsBridge, "JsBridge");

        webView.setWebViewClient(new MyClient());
        webView.setWebChromeClient(new MyWebChromeClient());

        // 这里填你需要打包的 H5 页面链接
        webView.loadUrl(LOADRL);
//        shapeLoadingDialog = new ShapeLoadingDialog(this);
//        shapeLoadingDialog.setLoadingText("加载中...");
        progressBar = findViewById(R.id.progress_bar);
        progressBar.setVisibility(View.VISIBLE);
        loadingView = findViewById(R.id.loadView);



        //显示一些小图片（头像）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webView.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        // 允许使用 localStorage sessionStorage
        webView.getSettings().setDomStorageEnabled(true);
        // 是否支持 html 的 meta 标签
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().getAllowUniversalAccessFromFileURLs();
        webView.getSettings().getAllowFileAccessFromFileURLs();
        // 禁用缓存
        webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
//        webView.getSettings().setAppCacheEnabled(false);
       // webView.setOnKeyListener((view, keyCode,  event)-> this.onKeyDown(keyCode,event));
//        updateApk();
//        showInfoDialog("","xxx","取消",null,"ok",null);
        AndroidBug5497Workaround.assistActivity(this);
    }

    @Override
    //设置回退页面
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "canGoBack: "+webView.canGoBack());
        if((keyCode == KeyEvent.KEYCODE_BACK) && ! webView.canGoBack()){

            if ((System.currentTimeMillis() - exitTime) > 2000) {
                Toast.makeText(getApplicationContext(), "再按一次退出程序", Toast.LENGTH_SHORT).show();
                exitTime = System.currentTimeMillis();
            } else {
                new CustomDialog.Builder(this).setTitle("提示").setInfo("确定要退出吗？").setButtonCancel("取消", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {

                    }
                }).setButtonConfirm("确定", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        System.exit(0);
                    }
                }).create().show();
            }
        }
        if ((keyCode == KeyEvent.KEYCODE_BACK) && webView.canGoBack()) {
            webView.goBack();
            return true;
        }else {





            return false;
        }

    }

    @Deprecated
    public void showMessage(String _s) {
        Toast.makeText(getApplicationContext(), _s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(jsBridge);
        webView.destroy();
        webView = null;
        super.onDestroy();

    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_REQUEST) {
            // 经过上边(1)、(2)两个赋值操作，此处即可根据其值是否为空来决定采用哪种处理方法
            if (mUploadCallbackBelow != null) {
                chooseBelow(resultCode, data);
            } else if (mUploadCallbackAboveL != null) {
                chooseAbove(resultCode, data);
            } else {
                Toast.makeText(this, "发生错误", Toast.LENGTH_SHORT).show();
            }
        }

        //  扫一扫
        if (JsBridge.SCAN_QR_REQUEST_CODE == requestCode) {
            if (resultCode == RESULT_OK) {
                if (data != null) {
                    String content = data.getStringExtra(Constant.CODED_CONTENT);
                    Log.i(TAG, "onActivityResult:扫码内容："+content);
                    String method = "javascript:qrResult('" + content + "')";
                    // 这里可能 出现乱码
                 //   webView.loadUrl(method);
                    webView.evaluateJavascript(method,null);
                    String id = "wps";
                    String name = "wpsChann";
                //   this.sendClickableNotification(this,new Intent(this,MainActivity.class),content);
                }

            }
        }
    }


    /**
     * Android API < 21(Android 5.0)版本的回调处理
     * @param resultCode 选取文件或拍照的返回码
     * @param data 选取文件或拍照的返回结果
     */
    private void chooseBelow(int resultCode, Intent data) {
        Log.e("WangJ", "返回调用方法--chooseBelow");

        if (RESULT_OK == resultCode) {
            updatePhotos();

            if (data != null) {
                // 这里是针对文件路径处理
                Uri uri = data.getData();
                if (uri != null) {
                    Log.e("WangJ", "系统返回URI：" + uri.toString());
                    mUploadCallbackBelow.onReceiveValue(uri);
                } else {
                    mUploadCallbackBelow.onReceiveValue(null);
                }
            } else {
                // 以指定图像存储路径的方式调起相机，成功后返回data为空
                Log.e("WangJ", "自定义结果：" + imageUri.toString());
                mUploadCallbackBelow.onReceiveValue(imageUri);
            }
        } else {
            mUploadCallbackBelow.onReceiveValue(null);
        }
        mUploadCallbackBelow = null;
    }

    /**
     * Android API >= 21(Android 5.0) 版本的回调处理
     * @param resultCode 选取文件或拍照的返回码
     * @param data 选取文件或拍照的返回结果
     */
    private void chooseAbove(int resultCode, Intent data) {
        Log.e("WangJ", "返回调用方法--chooseAbove");

        if (RESULT_OK == resultCode) {
            updatePhotos();

            if (data != null) {
                // 这里是针对从文件中选图片的处理
                Uri[] results;
                Uri uriData = data.getData();
                if (uriData != null) {
                    results = new Uri[]{uriData};
                    for (Uri uri : results) {
                        addWatermarkToImage(uri);
                        Log.e("WangJ", "系统返回URI：" + uri.toString());
                    }

                    mUploadCallbackAboveL.onReceiveValue(results);
                } else {
                    mUploadCallbackAboveL.onReceiveValue(null);
                }
            } else {
                Log.e("WangJ", "自定义结果：" + imageUri.toString());
                addWatermarkToImage(imageUri);
                mUploadCallbackAboveL.onReceiveValue(new Uri[]{imageUri});
            }
        } else {
            mUploadCallbackAboveL.onReceiveValue(null);
        }
        mUploadCallbackAboveL = null;
    }

    private void updatePhotos() {
        // 该广播即使多发（即选取照片成功时也发送）也没有关系，只是唤醒系统刷新媒体文件
        Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intent.setData(imageUri);
        sendBroadcast(intent);
    }
    /**
     * 处理WebView的回调
     *
     * @param uri
     */
    private void handleCallback(Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (mFilePathCallbackArray != null) {
                if (uri != null) {
                    mFilePathCallbackArray.onReceiveValue(new Uri[]{uri});
                } else {
                    mFilePathCallbackArray.onReceiveValue(null);
                }
                mFilePathCallbackArray = null;
            }
        } else {
            if (mFilePathCallback != null) {
                if (uri != null) {
                    String url = getFilePathFromContentUri(uri, getContentResolver());
                    Uri u = Uri.fromFile(new File(url));

                    mFilePathCallback.onReceiveValue(u);
                } else {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = null;
            }
        }
    }

    public static String getFilePathFromContentUri(Uri selectedVideoUri, ContentResolver contentResolver) {
        String filePath;
        String[] filePathColumn = {MediaStore.MediaColumns.DATA};

        Cursor cursor = contentResolver.query(selectedVideoUri, filePathColumn, null, null, null);
//      也可用下面的方法拿到cursor
//      Cursor cursor = this.context.managedQuery(selectedVideoUri, filePathColumn, null, null, null);

        cursor.moveToFirst();

        int columnIndex = cursor.getColumnIndex(filePathColumn[0]);
        filePath = cursor.getString(columnIndex);
        cursor.close();
        return filePath;
    }


//    private void initReceiver() {
//        mBluetoothStateReceiver = new BluetoothStateReceiver();
//        IntentFilter filter = new IntentFilter();
//        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
//        registerReceiver(mBluetoothStateReceiver, filter);
//    }

/*    @Override
    public void onOpened(EMDKManager emdkManager) {
        this.emdkManager = emdkManager;
         Toast.makeText(MainActivity.this,"opended",Toast.LENGTH_SHORT);
        // Acquire the barcode manager resources
        initBarcodeManager();
        // Enumerate scanner devices
        enumerateScannerDevices();
        // Set default scanner
//        spinnerScannerDevices.setSelection(defaultIndex);
    }*/

    @Override
    protected void onResume() {
        super.onResume();

    }


    private void enumerateScannerDevices() {
        if (barcodeManager != null) {
            deviceList = barcodeManager.getSupportedDevicesInfo();

        }
    }

    private void initBarcodeManager(){
        barcodeManager = (BarcodeManager) emdkManager.getInstance(EMDKManager.FEATURE_TYPE.BARCODE);
        Toast.makeText(MainActivity.this,"barcode"+barcodeManager.toString(),Toast.LENGTH_LONG);
        // Add connection listener
        if (barcodeManager != null) {
            barcodeManager.addConnectionListener(new BarcodeManager.ScannerConnectionListener() {
                @Override
                public void onConnectionChange(ScannerInfo scannerInfo, BarcodeManager.ConnectionState connectionState) {

                }
            });
        }
    }

/*    @Override
    public void onClosed() {
        if (emdkManager != null) {
            emdkManager.release();
            emdkManager = null;
        }
    }*/

/*    @Override
    public void onData(ScanDataCollection scanDataCollection) {
        if ((scanDataCollection != null) && (scanDataCollection.getResult() == ScannerResults.SUCCESS)) {
            ArrayList <ScanDataCollection.ScanData> scanData = scanDataCollection.getScanData();
            for(ScanDataCollection.ScanData data : scanData) {
                Log.d(TAG, "onData: "+data.getData());
                Toast.makeText (MainActivity.this,data.getData(),Toast.LENGTH_LONG);
            }
        }
    }*/



//    @Override
//    public void onStatus(StatusData statusData) {
//
//    }

//    /**
//     * 监听蓝牙状态变化的系统广播
//     */
//     class BluetoothStateReceiver extends BroadcastReceiver {
//
//        @Override
//        public void onReceive(Context context, Intent intent) {
//            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
//            switch (state) {
//                case BluetoothAdapter.STATE_TURNING_ON:
//                    Toast.makeText (MainActivity.this,"蓝牙已开启",Toast.LENGTH_LONG);
//                    break;
//
//                case BluetoothAdapter.STATE_TURNING_OFF:
//                    Toast.makeText (MainActivity.this,"蓝牙已关闭",Toast.LENGTH_LONG);
//                    break;
//            }
//            onBluetoothStateChanged(intent);
//        }
//    }

    private void onBluetoothStateChanged(Intent intent) {
    }


    /**
     * 解决：无法发现蓝牙设备的问题
     *
     * 对于发现新设备这个功能, 还需另外两个权限(Android M 以上版本需要显式获取授权,附授权代码):
     */
    private final int ACCESS_LOCATION=1;
    @SuppressLint("WrongConstant")
    private void getPermission() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
            int permissionCheck = 0;
            permissionCheck = this.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION);
            permissionCheck += this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
            permissionCheck += this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION);

            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                //未获得权限
                this.requestPermissions( // 请求授权
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION},
                        ACCESS_LOCATION);// 自定义常量,任意整型
            }
        }
    }

  public void  createClient(){
       mClient  = new BluetoothClient(MainActivity.this);
    }
   public BluetoothClient getClient(){
        return this.mClient;
   }
    class MyClient extends WebViewClient {
        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            // 显示自定义错误页面
            webView.clearHistory();
            webView.loadUrl("file:///android_asset/404.html");
            webView.clearHistory();
         /*   CustomDialog.Builder	 builder = new CustomDialog.Builder(MainActivity.this);
            builder.setTitle("提示");
            builder.setWarning("网络出现错误，请检查网络或联系管理员");
            builder.setButtonConfirm("确定", new View.OnClickListener() {

                @Override
                public void onClick(View customDgv) {
                    System.exit(0);
                }
            });
            CustomDialog customDg =	builder.create();
            customDg.show();*/

        }
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            // 解决webview cangoBack() 失效的问题
            if (Build.VERSION.SDK_INT < 26) {
                view.loadUrl(url);
                return true;
            }

            return false;
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            // 显示自定义错误页面
//            webView.loadUrl("file:///android_asset/404.html");
//            CustomDialog.Builder	 builder = new CustomDialog.Builder(MainActivity.this);
//            builder.setTitle("提示");
//            builder.setWarning("网络出现错误，请检查网络或联系管理员");
//            builder.setButtonConfirm("确定", new View.OnClickListener() {
//
//                @Override
//                public void onClick(View customDgv) {
//                    System.exit(0);
//                }
//            });
//            CustomDialog customDg =	builder.create();
//            customDg.show();
        }
    }
    class MyWebChromeClient extends WebChromeClient {
        /**
         * 8(Android 2.2) <= API <= 10(Android 2.3)回调此方法
         */
        private void openFileChooser(android.webkit.ValueCallback<Uri> uploadMsg) {
            Log.e("WangJ", "运行方法 openFileChooser-1");
            // (2)该方法回调时说明版本API < 21，此时将结果赋值给 mUploadCallbackBelow，使之 != null
            mUploadCallbackBelow = uploadMsg;
            takePhoto();
        }

        /**
         * 11(Android 3.0) <= API <= 15(Android 4.0.3)回调此方法
         */
        public void openFileChooser(android.webkit.ValueCallback<Uri> uploadMsg, String acceptType) {
            Log.e("WangJ", "运行方法 openFileChooser-2 (acceptType: " + acceptType + ")");
            // 这里我们就不区分input的参数了，直接用拍照
            openFileChooser(uploadMsg);
        }

        /**
         * 16(Android 4.1.2) <= API <= 20(Android 4.4W.2)回调此方法
         */
        public void openFileChooser(android.webkit.ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
            Log.e("WangJ", "运行方法 openFileChooser-3 (acceptType: " + acceptType + "; capture: " + capture + ")");
            // 这里我们就不区分input的参数了，直接用拍照
            openFileChooser(uploadMsg);
        }

        /**
         * API >= 21(Android 5.0.1)回调此方法
         */
        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> valueCallback, FileChooserParams fileChooserParams) {
            Log.e("WangJ", "运行方法 onShowFileChooser");
            // (1)该方法回调时说明版本API >= 21，此时将结果赋值给 mUploadCallbackAboveL，使之 != null
            mUploadCallbackAboveL  = valueCallback;
            takePhoto();
            return true;
        }

        /**
         * 调用相机
         */
        private void takePhoto() {
            // 指定拍照存储位置的方式调起相机
            String filePath =
                     Environment.DIRECTORY_DOWNLOADS;
            String fileName = "IMG_" + DateFormat.format("yyyyMMdd_hhmmss", Calendar.getInstance(Locale.CHINA)) + ".jpg";


            if (Build.VERSION.SDK_INT >= 24) {
                imageUri = FileProvider.getUriForFile(MainActivity.this, COM_WEIFU_IWMS_FILEPROVIDE ,new File(getExternalFilesDir(filePath),fileName));
            }else {
                imageUri = Uri.fromFile(new File(filePath + fileName));
            }



//        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
//        intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
//        startActivityForResult(intent, REQUEST_CODE);

            // 选择图片（不包括相机拍照）,则不用成功后发刷新图库的广播
//        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
//        i.addCategory(Intent.CATEGORY_OPENABLE);
//        i.setType("image/*");
//        startActivityForResult(Intent.createChooser(i, "Image Chooser"), REQUEST_CODE);

            Intent captureIntent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
//
//            Intent Photo = new Intent(Intent.ACTION_PICK,
//                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
//
//            Intent chooserIntent = Intent.createChooser(Photo, "Image Chooser");
//            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Parcelable[]{captureIntent});

            startActivityForResult(captureIntent, PICK_REQUEST);
        }

    

        // for 5.0+
/*        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
            if (mFilePathCallbackArray != null) {
                mFilePathCallbackArray.onReceiveValue(null);
            }
            mFilePathCallbackArray = filePathCallback;
            handleup(filePathCallback);
            return true;
        }*/

//        private void handle(ValueCallback<Uri> uploadFile) {
//            Intent intent = new Intent(Intent.ACTION_PICK);
//            // 设置允许上传的文件类型
//            intent.setType("*/*");
//            startActivityForResult(intent, PICK_REQUEST);
//        }

/*        private void handleup(ValueCallback<Uri[]> uploadFile) {

//            mFilePathCallbackArray = filePath;

            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

//  选择
//            Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
//            contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
//            contentSelectionIntent.setType("image/*");
//
//            Intent[] intentArray;
//            if (takePictureIntent != null) {
//                intentArray = new Intent[]{takePictureIntent};
//            } else {
//                intentArray = new Intent[0];
//            }
//
//            Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
//            chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
//            chooserIntent.putExtra(Intent.EXTRA_TITLE, "Image Chooser");
//            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray);

            startActivityForResult(takePictureIntent, PICK_REQUEST);

        }*/

        // 监听网页进度 newProgress进度值在0-100
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            Log.d(TAG, "newProgress:" + newProgress);
            // 进行进度条更新
            if (newProgress == PROCESS_BAR_MAX) {
              //  shapeLoadingDialog.dismiss();

                progressBar.setVisibility(View.GONE);
                loadingView.setVisibility(View.GONE);
            }
            progressBar.setProgress(newProgress);
            // 如果想展示加载动画，则增加一个drawable布局后，在onCreate时展示，在progress=100时View.GONE即可
        }
    }

    private void addWatermarkToImage(Uri imageUri) {
        try {

            Bitmap originalBitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
            originalBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true);
            long currentTimeMillis = System.currentTimeMillis();

            // 创建一个SimpleDateFormat实例，并设置日期/时间格式
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            // 根据当前时间毫秒值创建Date对象
            Date currentDate = new Date(currentTimeMillis);

            // 将Date对象格式化为字符串
            String formattedTime = sdf.format(currentDate);

            // 添加文字水印
            Bitmap watermarkedBitmap = addTextWatermark(originalBitmap, WATERMARK_TEXT+formattedTime);

            // 保存带有水印的图片
            saveWatermarkedImage(watermarkedBitmap, imageUri);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveWatermarkedImage(Bitmap bitmap, Uri destinationUri) {
        try (OutputStream out = getContentResolver().openOutputStream(destinationUri)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private Bitmap addTextWatermark(Bitmap src, String watermarkText) {
        Canvas canvas = new Canvas(src);
        Paint paint = new Paint();
        paint.setColor(Color.BLUE);
        paint.setTextSize(100);
        paint.setAlpha(128); // 设置透明度

        Rect bounds = new Rect();
        paint.getTextBounds(watermarkText, 0, watermarkText.length(), bounds);

        int x = src.getWidth() - bounds.width() - 20;
        int y = src.getHeight() - bounds.height() - 20;

        canvas.drawText(watermarkText, x, y, paint);

        return src;
    }

    private void requestPermissions() {
        PermissionsManager.getInstance().requestAllManifestPermissionsIfNecessary(this, permissionsResultAction);
    }

    private PermissionsResultAction permissionsResultAction = new PermissionsResultAction() {
        @Override
        public void onGranted() {

        }

        @Override
        public void onDenied(String permission) {

        }

        @Override
        public void onEnd() {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
//                    doStartCompleted();
//                    startMainActivity();
                }
            }, 3000);
        }
    };

    public static void makeStatusBarTransparent(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return;
        }
        Window window = activity.getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            int option = window.getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            window.getDecorView().setSystemUiVisibility(option);
            window.setStatusBarColor(Color.TRANSPARENT);
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        }
    }

    public void setFullscreen(boolean isShowStatusBar, boolean isShowNavigationBar) {
        int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

        if (!isShowStatusBar) {
            uiOptions |= View.SYSTEM_UI_FLAG_FULLSCREEN;
        }
        if (!isShowNavigationBar) {
            uiOptions |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        }
        getWindow().getDecorView().setSystemUiVisibility(uiOptions);

        //隐藏标题栏
        // getSupportActionBar().hide();
        setNavigationStatusColor(Color.TRANSPARENT);
    }

    public void setNavigationStatusColor(int color) {
        //VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setNavigationBarColor(Color.parseColor("#004098"));
            getWindow().setStatusBarColor(color);
        }
    }

    private static void setAndroidNativeLightStatusBar(Activity activity, boolean dark) {
        View decor = activity.getWindow().getDecorView();
        if (dark) {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }


    /**
     * 更新应用
     */
    private void updateApk(){
        if (android.os.Build.VERSION.SDK_INT > 9) {
            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);
        }

        // 版本更新检查
        UpdateManager um = new UpdateManager(MainActivity.this);
        try {
            um.checkUpdate(this.getString(R.string.version_url));
        } catch (IOException e) {
            e.printStackTrace();
        }

    }


    protected void showInfoDialog(String waring, String info, String cancelText, View.OnClickListener cancelOnClick, String confirmText, View.OnClickListener confirmOnClick) {
        CustomDialog.Builder builder = new CustomDialog.Builder(this);
        builder.setTitle("提示");
        builder.setWarning(waring);
        builder.setInfo(info);
        builder.setButtonCancel(cancelText, cancelOnClick);
        builder.setButtonConfirm(confirmText, confirmOnClick);

        CustomDialog customDialog = builder.create();
        customDialog.show();
    }


    public void sendClickableNotification(Context context, Intent intent,String content) {
        // 创建通知渠道 (对于Android Oreo及以上版本)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "My Notifications", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Description of my notifications");
            channel.enableLights(true);
            channel.setLightColor(Color.BLUE);
            channel.enableVibration(true);
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
        }

        // 创建意图 PendingIntent，用于点击通知后启动目标Activity
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        // 创建通知构建器
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.logo) // 设置小图标
                .setContentTitle("安全生产") // 设置通知标题
                .setContentText(content) // 设置通知内容
                .setAutoCancel(false)
                .setGroup("wps")
                .setChannelId(CHANNEL_ID)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT) // 设置优先级
                .setContentIntent(contentIntent); // 设置点击后的意图

        // 获取通知管理器实例
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        // 发送通知
        int notificationId = new Random().nextInt(); // 可以自定义通知ID
        Log.d(TAG, "sendClickableNotification: "+notificationId);
        manager.notify(notificationId, builder.build());
    }


}





