package com.weifu.app;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
//import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
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
import com.symbol.emdk.EMDKManager;
import com.symbol.emdk.EMDKResults;
import com.symbol.emdk.barcode.BarcodeManager;
import com.symbol.emdk.barcode.ScanDataCollection;
import com.symbol.emdk.barcode.Scanner;
import com.symbol.emdk.barcode.ScannerException;
import com.symbol.emdk.barcode.ScannerInfo;
import com.symbol.emdk.barcode.ScannerResults;
import com.symbol.emdk.barcode.StatusData;
import com.weifu.app.js.JsBridge;

import net.posprinter.posprinterface.IMyBinder;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public class MainActivity extends AppCompatActivity implements Scanner.DataListener, EMDKManager.EMDKListener {
     private static final int REQUEST_OPEN = 0X01;

    private EMDKManager emdkManager = null;
    private BarcodeManager barcodeManager = null;
    private Scanner scanner = null;
    private List<ScannerInfo> deviceList = null;
    private int scannerIndex = 0; // Keep the selected scanner
    private static final int PROCESS_BAR_MAX = 100;

    String TAG = getClass().getSimpleName();
  //  private static final String LOADRL ="http://10.1.4.147:9001/" ;
    private static final String LOADRL ="http://10.94.31.149:31223/" ;
    private WebView webView;
    private final int PICK_REQUEST = 10001;
    ValueCallback<Uri> mFilePathCallback;
    ValueCallback<Uri[]> mFilePathCallbackArray;
    private IMyBinder printerBinder;
    private BluetoothStateReceiver mBluetoothStateReceiver;
    private ProgressBar progressBar;
    BluetoothClient mClient;
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
        super.onCreate(savedInstanceState);
        initReceiver();
        getPermission();
        createClient();
        try {
            EMDKResults results = EMDKManager.getEMDKManager(getApplicationContext(), this);
            if (results.statusCode != EMDKResults.STATUS_CODE.SUCCESS) {
                Log.e(TAG,"EMDKManager object request failed!");
                return;
            }
        }catch (Exception e){

        }

        //隐藏ActionBar
        Objects.requireNonNull(getSupportActionBar()).hide();
        setContentView(R.layout.activity_main);
        //WebView加载页面
        webView = findViewById(R.id.web_view);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        // code from https://blog.csdn.net/qq_21138819/article/details/56676007 by 欢子-3824
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
        // code from https://github.com/madhan98/Android-webview-upload-download/blob/master/app/src/main/java/com/my/newproject/MainActivity.java by Madhan
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
        webView.addJavascriptInterface(new JsBridge(this), "JsBridge");

        webView.setWebViewClient(new MyClient());
        webView.setWebChromeClient(new MyWebChromeClient());

        // 这里填你需要打包的 H5 页面链接
        webView.loadUrl(LOADRL);
        progressBar = findViewById(R.id.progress_bar);
        progressBar.setVisibility(View.VISIBLE);

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
        super.onDestroy();
        webView.destroy();
        webView = null;
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


    private void initReceiver() {
        mBluetoothStateReceiver = new BluetoothStateReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mBluetoothStateReceiver, filter);
    }

    @Override
    public void onOpened(EMDKManager emdkManager) {
        this.emdkManager = emdkManager;

        // Acquire the barcode manager resources
        initBarcodeManager();
        // Enumerate scanner devices
        enumerateScannerDevices();
        // Set default scanner
//        spinnerScannerDevices.setSelection(defaultIndex);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The application is in foreground
        if (emdkManager != null) {
            // Acquire the barcode manager resources
            initBarcodeManager();
            // Enumerate scanner devices
            enumerateScannerDevices();
            // Initialize scanner
            initScanner();
        }
    }

    private void initScanner() {
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
    }
    private void deInitScanner() {
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

    @Override
    public void onClosed() {
        if (emdkManager != null) {
            emdkManager.release();
            emdkManager = null;
        }
    }

    @Override
    public void onData(ScanDataCollection scanDataCollection) {
        if ((scanDataCollection != null) && (scanDataCollection.getResult() == ScannerResults.SUCCESS)) {
            ArrayList <ScanDataCollection.ScanData> scanData = scanDataCollection.getScanData();
            for(ScanDataCollection.ScanData data : scanData) {
                Log.d(TAG, "onData: "+data.getData());
                Toast.makeText (MainActivity.this,data.getData(),Toast.LENGTH_LONG);
            }
        }
    }

//    @Override
//    public void onStatus(StatusData statusData) {
//
//    }

    /**
     * 监听蓝牙状态变化的系统广播
     */
     class BluetoothStateReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            switch (state) {
                case BluetoothAdapter.STATE_TURNING_ON:
                    Toast.makeText (MainActivity.this,"蓝牙已开启",Toast.LENGTH_LONG);
                    break;

                case BluetoothAdapter.STATE_TURNING_OFF:
                    Toast.makeText (MainActivity.this,"蓝牙已关闭",Toast.LENGTH_LONG);
                    break;
            }
            onBluetoothStateChanged(intent);
        }
    }

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
                progressBar.setVisibility(View.GONE);
            }
            progressBar.setProgress(newProgress);
            // 如果想展示加载动画，则增加一个drawable布局后，在onCreate时展示，在progress=100时View.GONE即可
        }
    }

}
