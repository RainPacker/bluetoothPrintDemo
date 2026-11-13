package com.weifu.scanplugin;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import com.google.gson.Gson;

import java.util.regex.Pattern;

/**
 * 统一回调分发器（F-03 / F-17 / F-18）
 * <p>
 * 核心改进：
 * 1. 使用 evaluateJavascript 替代 loadUrl("javascript:...")，回调走脚本执行通道，延迟低且不干扰页面加载队列；
 * 2. 参数经 Gson 序列化，天然完成 JSON 转义，码值含引号/换行/反斜杠/中文时回调不丢失；
 * 3. 支持两种回调风格：callbackId 一次性回调（Promise 风格）与全局函数监听（兼容旧 onScaned）。
 */
public class ScanResultDispatcher {

    private static final String TAG = "ScanDispatcher";

    /** 合法 JS 函数名（防注入校验） */
    private static final Pattern FUNCTION_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z_$][a-zA-Z0-9_$]*$");

    private final WebView webView;
    private final Gson gson = new Gson();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 全局监听函数名（事件风格），为空则仅走 callbackId
     */
    private volatile String globalListener;

    public ScanResultDispatcher(WebView webView) {
        this.webView = webView;
    }

    /**
     * 设置全局监听函数（F-04 事件风格）
     */
    public void setGlobalListener(String jsFunctionName) {
        this.globalListener = jsFunctionName;
    }

    /**
     * 分发扫码结果
     *
     * @param callback 回调数据
     */
    public void dispatch(ScanCallback callback) {
        if (callback == null) {
            return;
        }
        final String json = gson.toJson(callback);
        Log.d(TAG, "dispatch: " + json);
        postToMain(() -> {
            if (webView == null) {
                return;
            }
            // 1. callbackId 一次性回调：callbackId 即 H5 定义的全局函数名（Promise 风格）
            invokeGlobalFunction(callback.getCallbackId(), json);
            // 2. 全局监听函数（事件风格）
            invokeGlobalFunction(globalListener, json);
        });
    }

    /**
     * 调用 H5 全局函数（函数名经标识符校验，防注入）
     */
    private void invokeGlobalFunction(String functionName, String jsonArg) {
        if (functionName == null || functionName.isEmpty()) {
            return;
        }
        if (!FUNCTION_NAME_PATTERN.matcher(functionName).matches()) {
            Log.w(TAG, "invalid function name: " + functionName);
            return;
        }
        String script = "javascript:window[\"" + functionName + "\"]&&window[\"" + functionName + "\"](" + jsonArg + ")";
        evaluate(script);
    }

    /**
     * 兼容旧版 onScaned(flag, result) 双参数回调（F-32）
     * <p>
     * 旧 H5 页面依赖 window.onScaned(flag, result)，此方法保持原签名，
     * 但内部同样使用 evaluateJavascript + JSON 转义。
     */
    public void dispatchLegacy(String flag, String result) {
        final String flagJson = gson.toJson(flag == null ? "" : flag);
        final String resultJson = gson.toJson(result == null ? "" : result);
        postToMain(() -> {
            if (webView == null) {
                return;
            }
            String script = "javascript:window.onScaned&&window.onScaned(" + flagJson + "," + resultJson + ")";
            evaluate(script);
        });
    }

    /**
     * 兼容旧版 qrResult(content) 回调（F-32）
     */
    public void dispatchLegacyQr(String content) {
        final String contentJson = gson.toJson(content == null ? "" : content);
        postToMain(() -> {
            if (webView == null) {
                return;
            }
            String script = "javascript:window.qrResult&&window.qrResult(" + contentJson + ")";
            evaluate(script);
        });
    }

    private void evaluate(String script) {
        try {
            // evaluateJavascript 无需 javascript: 前缀；部分 WebView 版本带前缀解析异常
            webView.evaluateJavascript(script.startsWith("javascript:")
                    ? script.substring("javascript:".length()) : script, null);
        } catch (Exception e) {
            Log.e(TAG, "evaluate failed, fallback to loadUrl", e);
            // 极端兜底：页面正在跳转时 evaluateJavascript 可能抛异常
            try {
                webView.loadUrl(script.startsWith("javascript:")
                        ? script : "javascript:" + script);
            } catch (Exception ignored) {
            }
        }
    }

    private void postToMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }
}
