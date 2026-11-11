package com.weifu.scanplugin.device;

import android.view.KeyEvent;

import com.weifu.app.scan.ScannerGunManager;

/**
 * 扫码枪（键盘模拟输入）适配器（F-13）
 * <p>
 * 复用项目已有 ScannerGunManager 的按键拼码逻辑，
 * 由宿主 Activity 在 dispatchKeyEvent 中转发按键事件到本适配器。
 */
public class ScannerGunScanner implements DeviceScanner {

    private final ScannerGunManager gunManager = ScannerGunManager.getInstance();

    @Override
    public String getType() {
        return "gun";
    }

    @Override
    public void start() {
        // 扫码枪为硬件触发（扫枪按键/扳机），无需软触发
    }

    @Override
    public void stop() {
        // 无需停止
    }

    @Override
    public void setListener(final OnDeviceScanListener listener) {
        gunManager.setScanListener(code -> listener.onResult(code, null));
    }

    /**
     * 宿主 Activity 的 dispatchKeyEvent 转发入口。
     * 返回 true 表示事件已被扫码枪消费。
     */
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getDeviceId() == -1 || event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        // 仅消费字母数字等可打印键与回车，避免吞掉系统按键
        int keyCode = event.getKeyCode();
        boolean printable = keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9
                || keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z
                || keyCode == KeyEvent.KEYCODE_ENTER;
        if (!printable) {
            return false;
        }
        return gunManager.dispatchKeyEvent(keyCode, event);
    }

    @Override
    public void release() {
        gunManager.setScanListener(null);
    }
}
