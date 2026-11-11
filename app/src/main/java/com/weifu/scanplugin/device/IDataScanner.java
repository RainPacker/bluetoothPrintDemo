package com.weifu.scanplugin.device;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

/**
 * iData 扫码广播适配器（F-05 / F-20）
 * <p>
 * 复用项目已有 ScannerInterface 的广播协议，但收敛到插件内：
 * 广播接收器一次性注册，扫码成功不弹 Toast，结果统一走 OnDeviceScanListener。
 */
public class IDataScanner implements DeviceScanner {

    private static final String TAG = "IDataScanner";

    private static final String ACTION_SCAN_RESULT = "android.intent.action.SCANRESULT";
    private static final String ACTION_START_SCAN = "android.intent.action.BARCODESTARTSCAN";
    private static final String ACTION_STOP_SCAN = "android.intent.action.BARCODESTOPSCAN";

    private final Context context;
    private volatile OnDeviceScanListener listener;
    private volatile boolean receiverRegistered = false;

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction() == null ? "" : intent.getAction();
            if (!ACTION_SCAN_RESULT.equals(action)) {
                return;
            }
            String data = intent.getStringExtra("value");
            if (data == null || data.isEmpty()) {
                return;
            }
            Log.d(TAG, "recv: " + data);
            OnDeviceScanListener l = listener;
            if (l != null) {
                l.onResult(data, null);
            }
        }
    };

    public IDataScanner(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String getType() {
        return "idata";
    }

    @Override
    public void start() {
        Intent intent = new Intent(ACTION_START_SCAN);
        context.sendBroadcast(intent);
    }

    @Override
    public void stop() {
        Intent intent = new Intent(ACTION_STOP_SCAN);
        context.sendBroadcast(intent);
    }

    @Override
    public void setListener(OnDeviceScanListener listener) {
        this.listener = listener;
        registerReceiverIfNeeded();
    }

    private synchronized void registerReceiverIfNeeded() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SCAN_RESULT);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.registerReceiver(resultReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(resultReceiver, filter);
            }
            receiverRegistered = true;
            Log.d(TAG, "receiver registered");
        } catch (Exception e) {
            Log.e(TAG, "registerReceiver failed", e);
        }
    }

    @Override
    public void release() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(resultReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        listener = null;
    }
}
