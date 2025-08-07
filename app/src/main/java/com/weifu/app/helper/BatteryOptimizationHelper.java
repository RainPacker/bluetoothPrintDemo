package com.weifu.app.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.annotation.RequiresApi;

public class BatteryOptimizationHelper {
    private static final int REQUEST_IGNORE_BATTERY_OPTIMIZATION = 1001;
    
    @RequiresApi(api = Build.VERSION_CODES.M)
    public static boolean isIgnoringBatteryOptimizations(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }
    
    @RequiresApi(api = Build.VERSION_CODES.M)
    public static void requestIgnoreBatteryOptimization(Activity activity) {
        if (!isIgnoringBatteryOptimizations(activity)) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivityForResult(intent, REQUEST_IGNORE_BATTERY_OPTIMIZATION);
            } catch (ActivityNotFoundException e) {
                openAppSettings(activity);
            }
        }
    }
    
    public static void openAppSettings(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }
    
    public static void openManufacturerBatterySettings(Activity activity) {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        Intent intent = new Intent();
        
        try {
            if (manufacturer.contains("xiaomi")) {
                intent.setComponent(new ComponentName(
                    "com.miui.securitycenter", 
                    "com.miui.powercenter.PowerSettings"
                ));
            } else if (manufacturer.contains("huawei")) {
                intent.setComponent(new ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.power.ui.HuaweiPowerActivity"
                ));
            } else if (manufacturer.contains("oppo")) {
                intent.setComponent(new ComponentName(
                    "com.coloros.oppoguardelf", 
                    "com.coloros.powermanager.PowerConsumptionActivity"
                ));
            } else if (manufacturer.contains("vivo")) {
                intent.setComponent(new ComponentName(
                    "com.vivo.abe", 
                    "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"
                ));
            } else if (manufacturer.contains("samsung")) {
                intent.setComponent(new ComponentName(
                    "com.samsung.android.lool", 
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                ));
            } else {
                openAppSettings(activity);
                return;
            }
            
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            openAppSettings(activity);
        }
    }
    
    public static void showOptimizationGuide(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isIgnoringBatteryOptimizations(activity)) {
                showBatteryOptimizationDialog(activity);
            }
        } else {
            showManufacturerGuide(activity);
        }
    }
    
    private static void showBatteryOptimizationDialog(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            new AlertDialog.Builder(activity)
                .setTitle("电池优化设置")
                .setMessage("请禁用电池优化以确保后台运行")
                .setPositiveButton("设置", (d, w) -> requestIgnoreBatteryOptimization(activity))
                .setNegativeButton("手动设置", (d, w) -> openManufacturerBatterySettings(activity))
                .show();
        }
    }
    
    private static void showManufacturerGuide(Activity activity) {
        new AlertDialog.Builder(activity)
            .setTitle("后台运行设置")
            .setMessage("请在系统设置中允许应用后台运行")
            .setPositiveButton("去设置", (d, w) -> openManufacturerBatterySettings(activity))
            .show();
    }
}