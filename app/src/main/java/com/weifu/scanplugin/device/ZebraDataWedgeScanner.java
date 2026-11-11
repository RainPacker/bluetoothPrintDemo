package com.weifu.scanplugin.device;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * 斑马 DataWedge 扫码适配器（F-05 / F-20）
 * <p>
 * 改进点（相对旧 JsBridge 实现）：
 * 1. 广播接收器在 attach 时一次性注册（预初始化），softScan 调用零开销；
 * 2. DataWedge Profile 配置收敛到插件内自动完成（移植自旧 JsBridge.createProfile），
 *    宿主无需再显式调用；
 * 3. 扫码成功不再弹 Toast（P-08）；
 * 4. 结果带码制 label_type 信息。
 */
public class ZebraDataWedgeScanner implements DeviceScanner {

    private static final String TAG = "ZebraScanner";

    private static final String ACTION_DATAWEDGE = "com.symbol.datawedge.api.ACTION";
    private static final String EXTRA_SOFT_SCAN_TRIGGER = "com.symbol.datawedge.api.SOFT_SCAN_TRIGGER";
    private static final String EXTRA_SET_CONFIG = "com.symbol.datawedge.api.SET_CONFIG";
    private static final String EXTRA_CREATE_PROFILE = "com.symbol.datawedge.api.CREATE_PROFILE";
    private static final String PROFILE_NAME = "WPS";

    /** DataWedge 扫码结果广播 action（与旧版一致） */
    public static final String ACTION_SCAN_RESULT = "default";
    private static final String DATA_STRING = "com.symbol.datawedge.data_string";
    private static final String LABEL_TYPE = "com.symbol.datawedge.label_type";

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
            String data = intent.getStringExtra(DATA_STRING);
            if (data == null || data.isEmpty()) {
                return;
            }
            String labelType = intent.getStringExtra(LABEL_TYPE);
            Log.d(TAG, "recv: " + data + " type=" + labelType);
            OnDeviceScanListener l = listener;
            if (l != null) {
                l.onResult(data, labelType);
            }
        }
    };

    public ZebraDataWedgeScanner(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String getType() {
        return "zebra";
    }

    @Override
    public void start() {
        // 软触发：START_SCANNING（旧实现用 TOGGLE_SCANNING，连续两次调用会取消扫码）
        Intent dwIntent = new Intent();
        dwIntent.setAction(ACTION_DATAWEDGE);
        dwIntent.putExtra(EXTRA_SOFT_SCAN_TRIGGER, "START_SCANNING");
        context.sendBroadcast(dwIntent);
    }

    @Override
    public void stop() {
        Intent dwIntent = new Intent();
        dwIntent.setAction(ACTION_DATAWEDGE);
        dwIntent.putExtra(EXTRA_SOFT_SCAN_TRIGGER, "STOP_SCANNING");
        context.sendBroadcast(dwIntent);
    }

    @Override
    public void setListener(OnDeviceScanListener listener) {
        this.listener = listener;
        registerReceiverIfNeeded();
        ensureProfileConfigured();
    }

    /**
     * 配置 DataWedge Profile（移植自旧 JsBridge.createProfile，幂等）：
     * 创建 profile 关联本应用，扫码结果以 ACTION_SCAN_RESULT 广播输出，并开启回车 BDF。
     * 参考: http://techdocs.zebra.com/datawedge/latest/guide/api/setconfig/
     */
    private void ensureProfileConfigured() {
        try {
            // 1. 创建 profile
            Intent create = new Intent();
            create.setAction(ACTION_DATAWEDGE);
            create.putExtra(EXTRA_CREATE_PROFILE, PROFILE_NAME);
            context.sendBroadcast(create);

            // 2. profile 关联本应用（CREATE_IF_NOT_EXIST 幂等）
            Bundle profileConfig = new Bundle();
            profileConfig.putString("PROFILE_NAME", PROFILE_NAME);
            profileConfig.putString("PROFILE_ENABLED", "true");
            profileConfig.putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST");

            Bundle appConfig = new Bundle();
            appConfig.putString("PACKAGE_NAME", context.getPackageName());
            appConfig.putStringArray("ACTIVITY_LIST", new String[]{"*"});
            profileConfig.putParcelableArray("APP_LIST", new Bundle[]{appConfig});
            sendConfig(profileConfig);

            // 3. 扫码结果以 intent 广播输出到本应用
            Bundle intentConfig = new Bundle();
            intentConfig.putString("PLUGIN_NAME", "INTENT");
            intentConfig.putString("RESET_CONFIG", "true");
            Bundle intentProps = new Bundle();
            intentProps.putString("intent_output_enabled", "true");
            intentProps.putString("intent_action", ACTION_SCAN_RESULT);
            intentProps.putString("intent_delivery", "2");
            intentConfig.putBundle("PARAM_LIST", intentProps);
            profileConfig.putBundle("PLUGIN_CONFIG", intentConfig);
            sendConfig(profileConfig);

            // 4. 回车 BDF（保持旧版行为）
            Bundle bdfConfig = new Bundle();
            bdfConfig.putString("PLUGIN_NAME", "BDF");
            bdfConfig.putString("RESET_CONFIG", "true");
            bdfConfig.putString("OUTPUT_PLUGIN_NAME", "KEYSTROKE");
            Bundle bdfProps = new Bundle();
            bdfProps.putString("bdf_enabled", "true");
            bdfProps.putString("bdf_send_enter", "true");
            bdfConfig.putBundle("PARAM_LIST", bdfProps);
            profileConfig.putBundle("PLUGIN_CONFIG", bdfConfig);
            sendConfig(profileConfig);

            Log.d(TAG, "datawedge profile configured");
        } catch (Exception e) {
            Log.e(TAG, "configure profile failed", e);
        }
    }

    private void sendConfig(Bundle profileConfig) {
        Intent intent = new Intent();
        intent.setAction(ACTION_DATAWEDGE);
        intent.putExtra(EXTRA_SET_CONFIG, profileConfig);
        context.sendBroadcast(intent);
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
