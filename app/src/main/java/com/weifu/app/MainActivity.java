package com.weifu.app;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
//import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;


import com.inuker.bluetooth.library.BluetoothClient;
import com.mingle.widget.LoadingView;
import com.mingle.widget.ShapeLoadingDialog;
import com.symbol.emdk.EMDKManager;
import com.symbol.emdk.EMDKResults;
import com.symbol.emdk.barcode.BarcodeManager;
import com.symbol.emdk.barcode.ScanDataCollection;
import com.symbol.emdk.barcode.Scanner;
import com.symbol.emdk.barcode.ScannerException;
import com.symbol.emdk.barcode.ScannerInfo;
import com.symbol.emdk.barcode.ScannerResults;
import com.symbol.emdk.barcode.StatusData;
import com.weifu.action.PermissionsResultAction;
import com.weifu.app.js.JsBridge;
import com.weifu.utils.PermissionsManager;

import net.posprinter.posprinterface.IMyBinder;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public class MainActivity extends AppCompatActivity /**implements Scanner.DataListener, EMDKManager.EMDKListener**/ {
     private static final int REQUEST_OPEN = 0X01;

    private EMDKManager emdkManager = null;
    private BarcodeManager barcodeManager = null;
    private Scanner scanner = null;
    private List<ScannerInfo> deviceList = null;
    private int scannerIndex = 0; // Keep the selected scanner
    private static final int PROCESS_BAR_MAX = 100;

    String TAG = getClass().getSimpleName();
//        private static final String LOADRL ="http://10.1.4.139:9001/" ;
    private static final String LOADRL ="http://10.94.31.150:31223/" ;
    private WebView webView;
    private final int PICK_REQUEST = 10001;
    ValueCallback<Uri> mFilePathCallback;
    ValueCallback<Uri[]> mFilePathCallbackArray;
    private IMyBinder printerBinder;
//    private BluetoothStateReceiver mBluetoothStateReceiver;
    private ProgressBar progressBar;
    BluetoothClient mClient;
    private ShapeLoadingDialog shapeLoadingDialog;
    private LoadingView loadingView;
    private JsBridge jsBridge;


    public final IMyBinder getPrinterBinder() {
        return printerBinder;
    }

    public WebView getWebView() {
        return webView;
    }

    public Scanner getScanner() {
        return scanner;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
//        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
//        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
//                WindowManager.LayoutParams.FLAG_FULLSCREEN);
  //      this.makeStatusBarTransparent(this);
     //   setFullscreen(true, true);
       // setAndroidNativeLightStatusBar(this, true);
        getWindow().setNavigationBarColor(Color.parseColor("#004098"));
        super.onCreate(savedInstanceState);
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
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            // Andorid 4.1----4.4
            public void openFileChooser(ValueCallback<Uri> uploadFile, String acceptType, String capture) {

                mFilePathCallback = uploadFile;
                handle(uploadFile);
            }

            // for 5.0+
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (mFilePathCallbackArray != null) {
                    mFilePathCallbackArray.onReceiveValue(null);
                }
                mFilePathCallbackArray = filePathCallback;
                handleup(filePathCallback);
                return true;
            }

            private void handle(ValueCallback<Uri> uploadFile) {
                Intent intent = new Intent(Intent.ACTION_PICK);
                // 设置允许上传的文件类型
                intent.setType("*/*");
                startActivityForResult(intent, PICK_REQUEST);
            }

            private void handleup(ValueCallback<Uri[]> uploadFile) {
                Intent intent = new Intent(Intent.ACTION_PICK);
                intent.setType("*/*");
                startActivityForResult(intent, PICK_REQUEST);
            }
        });

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
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });
        jsBridge = new JsBridge(this);
        // 注册配置文件 斑马专用
        jsBridge.createProfile();
        // 注册广播
        IntentFilter actionFilters = new IntentFilter();
        actionFilters.addAction(JsBridge.ACTION_IDATA_SCANRESULT);
        actionFilters.addAction(JsBridge.ACTION_ZEBRA_SCANRESULT);
        actionFilters.addAction(Intent.ACTION_SCREEN_ON);
        actionFilters.addAction( BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(jsBridge,actionFilters);

        webView.addJavascriptInterface(new JsBridge(this), "JsBridge");

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
        webView.setOnKeyListener((view, keyCode,  event)-> this.onKeyDown(keyCode,event));
    }

    //设置回退页面
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_BACK) && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
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


    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_REQUEST) {
            if (null != data) {
                Uri uri = data.getData();
                handleCallback(uri);
            } else {
                // 取消了照片选取的时候调用
                handleCallback(null);
            }
        } else {
            // 取消了照片选取的时候调用
            handleCallback(null);
        }
        if(REQUEST_OPEN==requestCode){
            if(resultCode==RESULT_CANCELED){
                Log.i(TAG, "onActivityResult: 用户拒绝请求");
            }else{
                Log.i(TAG, "onActivityResult: 用户允许请求");
                getPermission();
            }
        }
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
        // The application is in foreground
//        if (emdkManager != null) {
//            // Acquire the barcode manager resources
//            initBarcodeManager();
//            // Enumerate scanner devices
//            enumerateScannerDevices();
//            // Initialize scanner
//            initScanner();
//        }
    }

/*    private void initScanner() {
        if (scanner == null) {
            if ((deviceList != null) && (deviceList.size() != 0)) {
                if (barcodeManager != null)
                    scanner = barcodeManager.getDevice(deviceList.get(scannerIndex));
            }
            else {
                Log.d(TAG, "Failed to get the specified scanner device! Please close and restart the application.");
                return;
            }
            if (scanner != null) {
                scanner.addDataListener(this);
              //  scanner.addStatusListener(this);
                try {
                    scanner.enable();
                } catch (ScannerException e) {

                    deInitScanner();
                }
            }else{
                Log.d(TAG,"Failed to initialize the scanner device.");
            }
        }
    }*/
/*    private void deInitScanner() {
        if (scanner != null) {
            try{
                scanner.disable();
            } catch (Exception e) {
                Log.d(TAG,e.getMessage());
            }

            try {
                scanner.removeDataListener(this);
              //  scanner.removeStatusListener(this);
            } catch (Exception e) {
                Log.d(TAG,e.getMessage());
            }

            try{
                scanner.release();
            } catch (Exception e) {
                Log.d(TAG,e.getMessage());
            }
            scanner = null;
        }
    }*/

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
    }
    class MyWebChromeClient extends WebChromeClient {
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





}
