package com.weifu.scanplugin;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 扫码插件配置（Builder 模式）
 * <p>
 * 由 H5 传入的 JSON 参数解析而来，控制相机扫码行为与解码策略。
 */
public class ScanPluginConfig {

    // ---- 解码格式常量 ----
    public static final String FORMAT_DATA_MATRIX = "DATA_MATRIX";
    public static final String FORMAT_QR_CODE = "QR_CODE";
    public static final String FORMAT_CODE_128 = "CODE_128";
    public static final String FORMAT_CODE_39 = "CODE_39";
    public static final String FORMAT_EAN_13 = "EAN_13";
    public static final String FORMAT_AZTEC = "AZTEC";
    public static final String FORMAT_PDF_417 = "PDF_417";
    public static final String FORMAT_CODE_93 = "CODE_93";
    public static final String FORMAT_EAN_8 = "EAN_8";
    public static final String FORMAT_UPC_A = "UPC_A";
    public static final String FORMAT_UPC_E = "UPC_E";
    public static final String FORMAT_ITF = "ITF";
    public static final String FORMAT_CODABAR = "CODABAR";

    /** 默认全格式（F-23） */
    public static final Set<String> DEFAULT_FORMATS = new HashSet<>(Arrays.asList(
            FORMAT_DATA_MATRIX, FORMAT_QR_CODE, FORMAT_CODE_128, FORMAT_CODE_39,
            FORMAT_EAN_13, FORMAT_AZTEC, FORMAT_PDF_417,
            // 一维码补全：缺格式会直接导致对应条码无法解码
            FORMAT_CODE_93, FORMAT_EAN_8, FORMAT_UPC_A, FORMAT_UPC_E,
            FORMAT_ITF, FORMAT_CODABAR));

    // ---- 默认值 ----
    private static final boolean DEFAULT_DPM = true;
    private static final boolean DEFAULT_CONTINUOUS = false;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final boolean DEFAULT_TORCH = false;
    private static final boolean DEFAULT_ALBUM = true;
    private static final boolean DEFAULT_BEEP = true;
    /** 帧节流：快速路径（降采样 640 宽）单次解码 <20ms，100ms 节流可支撑 10fps 连续解码 */
    private static final int DEFAULT_FRAME_INTERVAL_MS = 100;

    /**
     * DPM 增强模式（F-31）：开启自适应二值化/反色/旋转/多帧融合全管线
     */
    private boolean dpm = DEFAULT_DPM;
    /**
     * 连续扫码模式（F-09）
     */
    private boolean continuous = DEFAULT_CONTINUOUS;
    /**
     * 超时秒数（F-14）
     */
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    /**
     * 补光灯（F-11）
     */
    private boolean torch = DEFAULT_TORCH;
    /**
     * 相册选图（F-12）
     */
    private boolean album = DEFAULT_ALBUM;
    /**
     * 成功提示音（F-15）
     */
    private boolean beep = DEFAULT_BEEP;
    /**
     * 解码格式集合（F-23）
     */
    private Set<String> formats = DEFAULT_FORMATS;
    /**
     * 帧节流间隔（F-22）
     */
    private int frameIntervalMs = DEFAULT_FRAME_INTERVAL_MS;
    /**
     * 相机 front/back（F-10）
     */
    private boolean frontCamera = false;
    /**
     * 一次性回调 ID（Promise 风格）
     */
    private String callbackId;
    /**
     * 方向：auto = 继承当前页面方向（F-19 无旋转）
     */
    private String orientation = "auto";

    public static ScanPluginConfig fromJson(com.google.gson.JsonObject obj) {
        ScanPluginConfig config = new ScanPluginConfig();
        if (obj == null) {
            return config;
        }
        if (obj.has("dpm")) config.dpm = obj.get("dpm").getAsBoolean();
        if (obj.has("continuous")) config.continuous = obj.get("continuous").getAsBoolean();
        if (obj.has("timeout")) config.timeoutSeconds = Math.max(1, obj.get("timeout").getAsInt());
        if (obj.has("torch")) config.torch = obj.get("torch").getAsBoolean();
        if (obj.has("album")) config.album = obj.get("album").getAsBoolean();
        if (obj.has("beep")) config.beep = obj.get("beep").getAsBoolean();
        if (obj.has("frontCamera")) config.frontCamera = obj.get("frontCamera").getAsBoolean();
        if (obj.has("callbackId")) config.callbackId = obj.get("callbackId").getAsString();
        if (obj.has("orientation")) config.orientation = obj.get("orientation").getAsString();
        if (obj.has("formats") && obj.get("formats").isJsonArray()) {
            Set<String> formats = new HashSet<>();
            for (com.google.gson.JsonElement el : obj.getAsJsonArray("formats")) {
                formats.add(el.getAsString());
            }
            if (!formats.isEmpty()) {
                config.formats = formats;
            }
        }
        return config;
    }

    public boolean isDpm() {
        return dpm;
    }

    public boolean isContinuous() {
        return continuous;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public boolean isTorch() {
        return torch;
    }

    public boolean isAlbum() {
        return album;
    }

    public boolean isBeep() {
        return beep;
    }

    public Set<String> getFormats() {
        return formats;
    }

    public int getFrameIntervalMs() {
        return frameIntervalMs;
    }

    public boolean isFrontCamera() {
        return frontCamera;
    }

    public String getCallbackId() {
        return callbackId;
    }

    public String getOrientation() {
        return orientation;
    }

    /**
     * 设置解码格式集合（Intent 传参反序列化用）
     */
    public void setFormats(Set<String> formats) {
        if (formats != null && !formats.isEmpty()) {
            this.formats = formats;
        }
    }
}
