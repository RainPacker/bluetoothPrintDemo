package com.weifu.app.scan;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * iData 专用
 */
public class ScannerInterface {
  public static final String KEY_BARCODE_ENABLE_ACTION = "android.intent.action.BARCODESCAN";
  
  public static final String KEY_BARCODE_STARTSCAN_ACTION = "android.intent.action.BARCODESTARTSCAN";
  
  public static final String KEY_BARCODE_STOPSCAN_ACTION = "android.intent.action.BARCODESTOPSCAN";
  
  public static final String KEY_LOCK_SCAN_ACTION = "android.intent.action.BARCODELOCKSCANKEY";
  
  public static final String KEY_UNLOCK_SCAN_ACTION = "android.intent.action.BARCODEUNLOCKSCANKEY";
  
  public static final String KEY_BEEP_ACTION = "android.intent.action.BEEP";
  
  public static final String KEY_FAILUREBEEP_ACTION = "android.intent.action.FAILUREBEEP";
  
  public static final String KEY_VIBRATE_ACTION = "android.intent.action.VIBRATE";
  
  public static final String KEY_OUTPUT_ACTION = "android.intent.action.BARCODEOUTPUT";
  
  public static final String KEY_CHARSET_ACTION = "android.intent.actionCHARSET";
  
  public static final String KEY_POWER_ACTION = "android.intent.action.POWER";
  
  public static final String KEY_TERMINATOR_ACTION = "android.intent.TERMINATOR";
  
  public static final String KEY_SHOWNOTICEICON_ACTION = "android.intent.action.SHOWNOTICEICON";
  
  public static final String KEY_SHOWICON_ACTION = "android.intent.action.SHOWAPPICON";
  
  public static final String KEY_SHOWISCANUI = "com.android.auto.iscan.show_setting_ui";
  
  public static final String KEY_PREFIX_ACTION = "android.intent.action.PREFIX";
  
  public static final String KEY_SUFFIX_ACTION = "android.intent.action.SUFFIX";
  
  public static final String KEY_TRIMLEFT_ACTION = "android.intent.action.TRIMLEFT";
  
  public static final String KEY_TRIMRIGHT_ACTION = "android.intent.action.TRIMRIGHT";
  
  public static final String KEY_LIGHT_ACTION = "android.intent.action.LIGHT";
  
  public static final String KEY_TIMEOUT_ACTION = "android.intent.action.TIMEOUT";
  
  public static final String KEY_FILTERCHARACTER_ACTION = "android.intent.action.FILTERCHARACTER";
  
  public static final String KEY_CONTINUCESCAN_ACTION = "android.intent.action.CONTINUCESCAN";
  
  public static final String KEY_INTERVALTIME_ACTION = "android.intent.action.INTERVALTIME";
  
  public static final String KEY_DELELCTED_ACTION = "android.intent.action.DELELCTED";
  
  public static final String KEY_RESET_ACTION = "android.intent.action.RESET";
  
  public static final String SCANKEY_CONFIG_ACTION = "android.intent.action.scankeyConfig";
  
  public static final String KEY_FAILUREBROADCAST_ACTION = "android.intent.action.FAILUREBROADCAST";
  
  public static final String KEY_SETMAXMULTIREADCOUNT_ACTION = "android.intent.action.MAXMULTIREADCOUNT";
  
  static final String SET_STATUSBAR_EXPAND = "com.android.set.statusbar_expand";
  
  static final String SET_USB_DEBUG = "com.android.set.usb_debug";
  
  static final String SET_INSTALL_PACKAGE = "com.android.set.install.package";
  
  static final String SET_SCREEN_LOCK = "com.android.set.screen_lock";
  
  static final String SET_CFG_WAKEUP_ANYKEY = "com.android.set.cfg.wakeup.anykey";
  
  static final String SET_UNINSTALL_PACKAGE = "com.android.set.uninstall.package";
  
  static final String SET_SYSTEM_TIME = "com.android.set.system.time";
  
  static final String SET_KEYBOARD_CHANGE = "com.android.disable.keyboard.change";
  
  static final String SET_INSTALL_PACKAGE_WITH_SILENCE = "com.android.set.install.packege.with.silence";
  
  static final String SET_INSTALL_PACKAGE_EXTRA_APK_PATH = "com.android.set.install.packege.extra.apk.path";
  
  static final String SET_INSTALL_PACKAGE_EXTRA_TIPS_FORMAT = "com.android.set.install.packege.extra.tips.format";
  
  static final String SET_SIMULATION_KEYBOARD = "com.android.simulation.keyboard";
  
  static final String SET_SIMULATION_KEYBOARD_STRING = "com.android.simulation.keyboard.string";
  
  private Context mContext;
  
  private static ScannerInterface androidjni;
  
  public ScannerInterface(Context context) {
    this.mContext = context;
  }
  
  public void ShowUI() {
    if (this.mContext != null) {
      Intent intent = new Intent("com.android.auto.iscan.show_setting_ui");
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void open() {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.BARCODESCAN");
      intent.putExtra("android.intent.action.BARCODESCAN", true);
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void close() {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.BARCODESCAN");
      intent.putExtra("android.intent.action.BARCODESCAN", false);
      this.mContext.sendBroadcast(intent);
      Log.d(">>>", "send close");
    } 
  }
  
  public void scan_start() {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.BARCODESTARTSCAN");
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void scan_stop() {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.BARCODESTOPSCAN");
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void lockScanKey() {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.BARCODELOCKSCANKEY");
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void unlockScanKey() {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.BARCODEUNLOCKSCANKEY");
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void setOutputMode(int mode) {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.BARCODEOUTPUT");
      intent.putExtra("android.intent.action.BARCODEOUTPUT", mode);
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void enablePlayBeep(boolean enable) {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.BEEP");
      intent.putExtra("android.intent.action.BEEP", enable);
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void enableFailurePlayBeep(boolean enable) {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.FAILUREBEEP");
      intent.putExtra("android.intent.action.FAILUREBEEP", enable);
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void enablePlayVibrate(boolean enable) {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.VIBRATE");
      intent.putExtra("android.intent.action.VIBRATE", enable);
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void enableAddKeyValue(int value) {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.TERMINATOR");
      intent.putExtra("android.intent.TERMINATOR", value);
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void addPrefix(String text) {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.PREFIX");
      intent.putExtra("android.intent.action.PREFIX", text);
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void addSuffix(String text) {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.SUFFIX");
      intent.putExtra("android.intent.action.SUFFIX", text);
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void interceptTrimleft(int num) {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.TRIMLEFT");
      intent.putExtra("android.intent.action.TRIMLEFT", num);
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void interceptTrimright(int num) {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.TRIMRIGHT");
      intent.putExtra("android.intent.action.TRIMRIGHT", num);
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void lightSet(boolean enable) {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.LIGHT");
      intent.putExtra("android.intent.action.LIGHT", enable);
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void timeOutSet(int value) {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.TIMEOUT");
      intent.putExtra("android.intent.action.TIMEOUT", value);
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void filterCharacter(String text) {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.FILTERCHARACTER");
      intent.putExtra("android.intent.action.FILTERCHARACTER", text);
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void continceScan(boolean enable) {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.CONTINUCESCAN");
      intent.putExtra("android.intent.action.CONTINUCESCAN", enable);
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void intervalSet(int value) {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.INTERVALTIME");
      intent.putExtra("android.intent.action.INTERVALTIME", value);
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void SetErrorBroadCast(boolean enable) {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.FAILUREBROADCAST");
      intent.putExtra("android.intent.action.FAILUREBROADCAST", enable);
      this.mContext.sendBroadcast(intent);
    } 
  }
  
  public void resultScan() {
    if (this.mContext != null) {
      Intent intent = new Intent("android.intent.action.RESET");
      this.mContext.sendBroadcast(intent);
    } 
  }
}
