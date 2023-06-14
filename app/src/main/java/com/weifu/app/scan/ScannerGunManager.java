package com.weifu.app.scan;

import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;

/**
 * 扫码
 */
public class ScannerGunManager {
    private ArrayList<Integer> scannedCodes = new ArrayList<Integer>();

    private final static long MESSAGE_DELAY = 1000;
    private final Handler mHandler;

    private Runnable mScanningFinishRunnable = new Runnable() {
        @Override
        public void run() {
            handleKeyCodes();
        }
    };

    public interface OnScanListener {
        void onResult(String code);
    }

    private OnScanListener listener;

    private volatile static ScannerGunManager sInstance;

    private ScannerGunManager() {
        mHandler = new Handler();
    }

    public static ScannerGunManager getInstance() {
        if (sInstance == null) {
            synchronized (ScannerGunManager.class) {
                if (sInstance == null) {
                    sInstance = new ScannerGunManager();
                }
            }
        }

        return sInstance;
    }

    public String keyCodeToChar(int code, boolean isShift) {
        switch (code) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP:
                return "";

            case KeyEvent.KEYCODE_0:
                return isShift ? ")" : "0";
            case KeyEvent.KEYCODE_1:
                return isShift ? "!" : "1";
            case KeyEvent.KEYCODE_2:
                return isShift ? "@" : "2";
            case KeyEvent.KEYCODE_3:
                return isShift ? "#" : "3";
            case KeyEvent.KEYCODE_4:
                return isShift ? "$" : "4";
            case KeyEvent.KEYCODE_5:
                return isShift ? "%" : "5";
            case KeyEvent.KEYCODE_6:
                return isShift ? "^" : "6";
            case KeyEvent.KEYCODE_7:
                return isShift ? "&" : "7";
            case KeyEvent.KEYCODE_8:
                return isShift ? "*" : "8";
            case KeyEvent.KEYCODE_9:
                return isShift ? "(" : "9";

            case KeyEvent.KEYCODE_A:
                return isShift ? "A" : "a";
            case KeyEvent.KEYCODE_B:
                return isShift ? "B" : "b";
            case KeyEvent.KEYCODE_C:
                return isShift ? "C" : "c";
            case KeyEvent.KEYCODE_D:
                return isShift ? "D" : "d";
            case KeyEvent.KEYCODE_E:
                return isShift ? "E" : "e";
            case KeyEvent.KEYCODE_F:
                return isShift ? "F" : "f";
            case KeyEvent.KEYCODE_G:
                return isShift ? "G" : "g";
            case KeyEvent.KEYCODE_H:
                return isShift ? "H" : "h";
            case KeyEvent.KEYCODE_I:
                return isShift ? "I" : "i";
            case KeyEvent.KEYCODE_J:
                return isShift ? "J" : "j";
            case KeyEvent.KEYCODE_K:
                return isShift ? "K" : "k";
            case KeyEvent.KEYCODE_L:
                return isShift ? "L" : "l";
            case KeyEvent.KEYCODE_M:
                return isShift ? "M" : "m";
            case KeyEvent.KEYCODE_N:
                return isShift ? "N" : "n";
            case KeyEvent.KEYCODE_O:
                return isShift ? "O" : "o";
            case KeyEvent.KEYCODE_P:
                return isShift ? "P" : "p";
            case KeyEvent.KEYCODE_Q:
                return isShift ? "Q" : "q";
            case KeyEvent.KEYCODE_R:
                return isShift ? "R" : "r";
            case KeyEvent.KEYCODE_S:
                return isShift ? "S" : "s";
            case KeyEvent.KEYCODE_T:
                return isShift ? "T" : "t";
            case KeyEvent.KEYCODE_U:
                return isShift ? "U" : "u";
            case KeyEvent.KEYCODE_V:
                return isShift ? "V" : "v";
            case KeyEvent.KEYCODE_W:
                return isShift ? "W" : "w";
            case KeyEvent.KEYCODE_X:
                return isShift ? "X" : "x";
            case KeyEvent.KEYCODE_Y:
                return isShift ? "Y" : "y";
            case KeyEvent.KEYCODE_Z:
                return isShift ? "Z" : "z";

            case KeyEvent.KEYCODE_COMMA:
                return isShift ? "<" : ",";
            case KeyEvent.KEYCODE_PERIOD:
                return isShift ? ">" : ".";
            case KeyEvent.KEYCODE_SLASH:
                return isShift ? "?" : "/";
            case KeyEvent.KEYCODE_BACKSLASH:
                return isShift ? "|" : "\\";
            case KeyEvent.KEYCODE_APOSTROPHE:
                return isShift ? "\"" : "'";
            case KeyEvent.KEYCODE_SEMICOLON:
                return isShift ? ":" : ";";
            case KeyEvent.KEYCODE_LEFT_BRACKET:
                return isShift ? "{" : "[";
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
                return isShift ? "}" : "]";
            case KeyEvent.KEYCODE_GRAVE:
                return isShift ? "~" : "`";
            case KeyEvent.KEYCODE_EQUALS:
                return isShift ? "+" : "=";
            case KeyEvent.KEYCODE_MINUS:
                return isShift ? "_" : "-";
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
                return "-";
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE:
                return "/";
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY:
                return "*";
            case KeyEvent.KEYCODE_NUMPAD_DOT:
                return ".";
            case KeyEvent.KEYCODE_NUMPAD_ADD:
                return "+";
            case KeyEvent.KEYCODE_NUMPAD_COMMA:
                return ",";
            case KeyEvent.KEYCODE_NUMPAD_EQUALS:
                return "=";
            case KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN:
                return "(";
            case KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN:
                return ")";
            default:
                return "?";
        }
    }

    private void handleKeyCodes() {
        int count = scannedCodes.size();
        if (count <= 0) {
            return;
        }

        String result = "";

        boolean hasShift = false;
        for (int keyCode : scannedCodes) {
            result += keyCodeToChar(keyCode, hasShift);
            hasShift = (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT);
        }

        if (!TextUtils.isEmpty(result) && listener != null) {
            listener.onResult(result);
        }

        scannedCodes.clear();
    }

    public boolean dispatchKeyEvent(int keyCode, KeyEvent event) {
        Log.d("dispatchKeyEvent", "dispatchKeyEvent: "+keyCode);
        if (event.getDeviceId() == -1) {
            return false;
        }

        if (keyCode != KeyEvent.KEYCODE_ENTER) {
            scannedCodes.add(keyCode);

            mHandler.removeCallbacks(mScanningFinishRunnable);
            mHandler.postDelayed(mScanningFinishRunnable, MESSAGE_DELAY);
        } else {
            mHandler.removeCallbacks(mScanningFinishRunnable);

            handleKeyCodes();
        }

        return true;
    }

    public void setScanListener(OnScanListener listener) {
        this.listener = listener;
    }
}