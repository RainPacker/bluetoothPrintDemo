package com.weifu.app.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

// Java 版本
public class AppInfoUtils {
    
    /**
     * 获取当前应用的包名（应用ID）
     */
    public static String getCurrentPackageName(Context context) {
        return context.getPackageName();
    }
    
    /**
     * 获取当前应用名称
     */
    public static String getCurrentAppName(Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            ApplicationInfo appInfo = packageManager.getApplicationInfo(
                context.getPackageName(), 0);
            return packageManager.getApplicationLabel(appInfo).toString();
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return "";
        }
    }
    
    /**
     * 获取当前应用版本名称
     */
    public static String getCurrentVersionName(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return "";
        }
    }
    
    /**
     * 获取当前应用版本号
     */
    public static int getCurrentVersionCode(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return 0;
        }
    }
}