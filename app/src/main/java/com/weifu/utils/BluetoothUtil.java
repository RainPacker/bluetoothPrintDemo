package com.weifu.utils;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.provider.Settings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class BluetoothUtil {

    /**
     * 蓝牙是否打开
     */
    public static boolean isBluetoothOn() {
        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter != null)
            // 蓝牙已打开
            if (mBluetoothAdapter.isEnabled())
                return true;

        return false;
    }

    /**
     * 获取所有已配对的设备
     */
    public static List<BluetoothDevice> getPairedDevices() {
        List deviceList = new ArrayList<>();
        Set<BluetoothDevice> pairedDevices = BluetoothAdapter.getDefaultAdapter().getBondedDevices();
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                deviceList.add(device);
            }
        }
        return deviceList;
    }

    /**
     * 获取所有已配对的打印类设备
     */
    public static List<BluetoothDevice> getPairedPrinterDevices() {
        return getSpecificDevice(BluetoothClass.Device.Major.IMAGING);
    }



    /**
     * 弹出系统对话框，请求打开蓝牙
     */
    public static void openBluetooth(Activity activity) {
        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        activity.startActivityForResult(enableBtIntent, 666);
    }

    public static BluetoothSocket connectDevice(BluetoothDevice device) {
        BluetoothSocket socket = null;
        try {
            BluetoothAdapter defaultAdapter = BluetoothAdapter.getDefaultAdapter();
            socket=   defaultAdapter.getRemoteDevice(device.getAddress()).createRfcommSocketToServiceRecord(
                    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
            socket.connect();
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException closeException) {
                return null;
            }
            return null;
        }
        return socket;
    }

    public static void enableBluetooth() {
        BluetoothAdapter defaultAdapter = BluetoothAdapter.getDefaultAdapter();
        if (defaultAdapter == null) {
            return;
        }
        if (!defaultAdapter.isEnabled()) {
            defaultAdapter.enable();
        }
    }

    public static void disableBluetooth() {
        BluetoothAdapter defaultAdapter = BluetoothAdapter.getDefaultAdapter();
        if (defaultAdapter == null) {
            return;
        }
        if (defaultAdapter.isEnabled()) {
            defaultAdapter.disable();
        }
    }

    /**
     * 打开蓝牙设置，进行配对
     */
    public static void startBluetoothSettings(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }


    /**
     * 获取所有已配对的设备
     */
    public static List<BluetoothDevice> getPairedDevice() {
        List<BluetoothDevice> deviceList = new ArrayList<>();
        BluetoothAdapter defaultAdapter = BluetoothAdapter.getDefaultAdapter();
        if (defaultAdapter == null) {
            //不支持蓝牙，如安卓模拟器
            return deviceList;
        }
        Set<BluetoothDevice> bondedDevices = defaultAdapter.getBondedDevices();
        if (bondedDevices.size() > 0) {
            deviceList.addAll(bondedDevices);
        }
        return deviceList;
    }

    /**
     * 获取所有已配对的打印类设备
     */
    public static List<BluetoothDevice> getPairedPrinter() {
        return getSpecificDevice(BluetoothClass.Device.Major.IMAGING);
    }

    /**
     * 从已配对设配中，删选出某一特定类型的设备展示
     */
    public static List<BluetoothDevice> getSpecificDevice(int deviceClass) {
        List<BluetoothDevice> devices = getPairedDevice();
        List<BluetoothDevice> printerDevices = new ArrayList<>();
        for (BluetoothDevice device : devices) {
            BluetoothClass klass = device.getBluetoothClass();
            // 关于蓝牙设备分类参考 http://stackoverflow.com/q/23273355/4242112
            if (klass.getMajorDeviceClass() == deviceClass)
                printerDevices.add(device);
        }
        return printerDevices;
    }


}