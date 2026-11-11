package com.weifu.scanplugin;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.weifu.scanplugin.camera.ScanCameraActivity;
import com.weifu.scanplugin.device.DeviceScanner;
import com.weifu.scanplugin.device.IDataScanner;
import com.weifu.scanplugin.device.ScannerGunScanner;
import com.weifu.scanplugin.device.ZebraDataWedgeScanner;

import java.util.HashMap;
import java.util.Map;

/**
 * 扫码插件门面（F-01 / F-02 / F-05 / F-16 / F-35）
 * <p>
 * 唯一 JS 入口：webView.addJavascriptInterface(scanPlugin, "ScanPlugin")，
 * H5 通过 window.ScanPlugin.* 调用。与旧 JsBridge 零耦合，可整体替换升级。
 * <p>
 * JS API：
 * - ScanPlugin.softScan()                 设备扫码（斑马/iData 自动适配），结果回调 window.onScaned(flag, result)（兼容旧版）
 * - ScanPlugin.scan(jsonConfig, cbFnName) 相机扫码（DPM 增强解码），结果回调 window[cbFnName](json)
 * - ScanPlugin.setCallback(fnName)        设置全局监听函数（事件风格）
 * - ScanPlugin.setFlag(flag)              设置旧版回调 flag 标识
 * - ScanPlugin.stop()                     停止当前扫码（设备/相机）
 * - ScanPlugin.getVersion()               插件版本
 * - ScanPlugin.getScannerInfo()           设备探测信息（JSON 字符串）
 */
public class ScanPlugin {

    private static final String TAG = "ScanPlugin";

    /** 插件版本（F-35） */
    public static final String VERSION = "1.0.0";

    private final Activity activity;
    private final ScanResultDispatcher dispatcher;
    private final Gson gson = new Gson();

    // 设备适配器（F-20 预初始化，调用零开销）
    private final ZebraDataWedgeScanner zebraScanner;
    private final IDataScanner iDataScanner;
    private final ScannerGunScanner gunScanner;

    /** 旧版回调 flag 标识（兼容 onScaned(flag, result)） */
    private volatile String legacyFlag = "";

    /** 当前相机扫码的 callbackId（用于相机结果路由） */
    private volatile String cameraCallbackId;

    public ScanPlugin(Activity activity, WebView webView) {
        this.activity = activity;
        this.dispatcher = new ScanResultDispatcher(webView);
        this.zebraScanner = new ZebraDataWedgeScanner(activity);
        this.iDataScanner = new IDataScanner(activity);
        this.gunScanner = new ScannerGunScanner();
        initDeviceListeners();
        Log.i(TAG, "ScanPlugin v" + VERSION + " attached");
    }

    /**
     * 设备扫码结果统一监听（F-05）：双通道回调
     * 1. 旧版 window.onScaned(flag, result)（兼容存量 H5）
     * 2. 新版全局监听函数 / callbackId（JSON 结构化数据）
     */
    private void initDeviceListeners() {
        DeviceScanner.OnDeviceScanListener deviceListener = (text, format) -> {
            Log.d(TAG, "device scan result: " + text);
            // 旧版兼容回调（F-32）
            dispatcher.dispatchLegacy(legacyFlag, text);
            // 新版结构化回调
            dispatcher.dispatch(ScanCallback.result(null, text, format, "device"));
        };
        zebraScanner.setListener(deviceListener);
        iDataScanner.setListener(deviceListener);
        gunScanner.setListener(deviceListener);
    }

    // ==================== JS API ====================

    /**
     * 设备扫码（斑马 DataWedge / iData 同时软触发，硬件自适应）
     * 结果回调 window.onScaned(flag, result) —— 兼容旧版 H5（F-32）
     */
    @JavascriptInterface
    public void softScan() {
        Log.d(TAG, "softScan");
        zebraScanner.start();
        iDataScanner.start();
    }

    /**
     * 相机扫码（DPM 增强解码，无旋转跳转）
     *
     * @param jsonConfig JSON 字符串配置（见 ScanPluginConfig）
     * @param callbackId H5 全局回调函数名，收到 JSON：
     *                   {"callbackId":"xx","event":"result|cancel|error",
     *                    "data":{"text":"码值","format":"DATA_MATRIX","source":"camera"}}
     */
    @JavascriptInterface
    public void scan(String jsonConfig, String callbackId) {
        Log.d(TAG, "scan: " + jsonConfig + " cb=" + callbackId);
        ScanPluginConfig config;
        try {
            JsonObject obj = jsonConfig == null ? null
                    : JsonParser.parseString(jsonConfig).getAsJsonObject();
            config = ScanPluginConfig.fromJson(obj);
        } catch (Exception e) {
            Log.w(TAG, "invalid config json, use default", e);
            config = new ScanPluginConfig();
        }
        final String cbId = (callbackId == null ? "" : callbackId.trim());
        cameraCallbackId = cbId;
        final ScanPluginConfig finalConfig = config;

        activity.runOnUiThread(() -> startCameraScan(finalConfig));
    }

    private void startCameraScan(ScanPluginConfig config) {
        ScanCameraActivity.setPendingListener(new ScanCameraActivity.CameraResultListener() {
            @Override
            public void onCameraResult(String text, String format) {
                String cbId = cameraCallbackId;
                dispatcher.dispatch(ScanCallback.result(cbId, text, format, "camera"));
                // 相机扫码结果同时走旧版 qrResult 回调（F-32 兼容）
                dispatcher.dispatchLegacyQr(text);
            }

            @Override
            public void onCameraCancel() {
                dispatcher.dispatch(ScanCallback.cancel(cameraCallbackId));
            }

            @Override
            public void onCameraError(String message) {
                dispatcher.dispatch(ScanCallback.error(cameraCallbackId, message));
            }
        });

        Intent intent = new Intent(activity, ScanCameraActivity.class);
        // F-19 补充：扫码页打开前锁定宿主当前实际方向。
        // 宿主 SENSOR_LANDSCAPE 允许传感器 180° 翻转——若扫码期间传感器变化，
        // 取消返回时宿主会突然旋转。LOCKED 锁定主页此刻方向（扫码页继承同一方向），
        // 返回全程无旋转。锁定发生在 UI 线程（本方法由 runOnUiThread 调用），方向已稳定无竞态。
        if (activity.getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_LOCKED) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        }
        intent.putExtra(ScanCameraActivity.EXTRA_ORIENTATION,
                resolveOrientation(config.getOrientation()));
        intent.putExtra(ScanCameraActivity.EXTRA_DPM, config.isDpm());
        intent.putExtra(ScanCameraActivity.EXTRA_CONTINUOUS, config.isContinuous());
        intent.putExtra(ScanCameraActivity.EXTRA_TIMEOUT, config.getTimeoutSeconds());
        intent.putExtra(ScanCameraActivity.EXTRA_TORCH, config.isTorch());
        intent.putExtra(ScanCameraActivity.EXTRA_ALBUM, config.isAlbum());
        intent.putExtra(ScanCameraActivity.EXTRA_BEEP, config.isBeep());
        intent.putExtra(ScanCameraActivity.EXTRA_FRONT, config.isFrontCamera());
        intent.putExtra(ScanCameraActivity.EXTRA_FORMATS, gson.toJson(config.getFormats()));
        activity.startActivity(intent);
    }

    /**
     * 解析方向参数：
     * - landscape/portrait：显式指定（SENSOR 变体，允许族内 180° 翻转）
     * - auto：锁定扫码页打开瞬间的实际方向（SCREEN_ORIENTATION_LOCKED）。
     *   不可继承宿主的 SENSOR_LANDSCAPE/SENSOR_PORTRAIT——它们会响应传感器翻转，
     *   模拟器/真机上虚拟传感器延迟初始化或抖动时，扫码页会在几秒后突然 180° 旋转。
     *   LOCKED 锁定当前 display rotation，之后忽略一切传感器变化，扫码期间方向纹丝不动。
     */
    private int resolveOrientation(String orientation) {
        if ("landscape".equalsIgnoreCase(orientation)) {
            return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        }
        if ("portrait".equalsIgnoreCase(orientation)) {
            return ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        }
        // auto：锁定进入扫码页时的实际方向（已继承宿主方向，无启动跳变）
        return ActivityInfo.SCREEN_ORIENTATION_LOCKED;
    }

    /**
     * 设置全局监听函数（事件风格，F-04）：
     * window[fnName](json) 会在每次扫码结果时被调用
     */
    @JavascriptInterface
    public void setCallback(String jsFunctionName) {
        dispatcher.setGlobalListener(jsFunctionName == null ? null : jsFunctionName.trim());
    }

    /**
     * 设置旧版回调 flag 标识（F-32，弥补旧版 flag 从未可设的缺陷）
     */
    @JavascriptInterface
    public void setFlag(String flag) {
        this.legacyFlag = flag == null ? "" : flag;
    }

    /**
     * 停止当前扫码（F-14）：设备停止软触发 + 关闭相机扫码页
     */
    @JavascriptInterface
    public void stop() {
        Log.d(TAG, "stop");
        zebraScanner.stop();
        iDataScanner.stop();
        ScanCameraActivity.finishIfActive();
    }

    /**
     * 插件版本（F-35）
     */
    @JavascriptInterface
    public String getVersion() {
        return VERSION;
    }

    /**
     * 设备探测信息（F-16）
     * 返回 JSON：{"version":"1.0.0","deviceScanners":["zebra","idata","gun"]}
     */
    @JavascriptInterface
    public String getScannerInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("version", VERSION);
        info.put("deviceScanners", new String[]{
                zebraScanner.getType(), iDataScanner.getType(), gunScanner.getType()});
        info.put("dpmSupported", true);
        return gson.toJson(info);
    }

    // ==================== 宿主接入 API（非 JS） ====================

    /**
     * 扫码枪按键转发入口：宿主 Activity 在 dispatchKeyEvent 中调用
     * 返回 true 表示事件已被扫码枪消费
     */
    public boolean dispatchKeyEvent(KeyEvent event) {
        return gunScanner.dispatchKeyEvent(event);
    }

    /**
     * 释放插件资源（宿主 onDestroy 时调用）
     */
    public void release() {
        zebraScanner.release();
        iDataScanner.release();
        gunScanner.release();
        Log.i(TAG, "ScanPlugin released");
    }
}
