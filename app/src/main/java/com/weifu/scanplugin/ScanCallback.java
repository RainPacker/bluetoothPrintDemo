package com.weifu.scanplugin;

import com.google.gson.annotations.SerializedName;

/**
 * 扫码回调数据模型（统一回调协议）
 * <p>
 * 序列化为 JSON 后通过 evaluateJavascript 回传 H5：
 * {"callbackId":"xx","event":"result","data":{...}}
 */
public class ScanCallback {

    public static final String EVENT_RESULT = "result";
    public static final String EVENT_CANCEL = "cancel";
    public static final String EVENT_ERROR = "error";

    @SerializedName("callbackId")
    private String callbackId;

    @SerializedName("event")
    private String event;

    @SerializedName("data")
    private Payload data;

    public ScanCallback(String callbackId, String event, Payload data) {
        this.callbackId = callbackId;
        this.event = event;
        this.data = data;
    }

    public static ScanCallback result(String callbackId, String text, String format, String source) {
        return new ScanCallback(callbackId, EVENT_RESULT, new Payload(text, format, source, System.currentTimeMillis()));
    }

    public static ScanCallback cancel(String callbackId) {
        return new ScanCallback(callbackId, EVENT_CANCEL, null);
    }

    public static ScanCallback error(String callbackId, String message) {
        return new ScanCallback(callbackId, EVENT_ERROR, new Payload(message, null, null, System.currentTimeMillis()));
    }

    public String getCallbackId() {
        return callbackId;
    }

    public String getEvent() {
        return event;
    }

    public Payload getData() {
        return data;
    }

    /**
     * 回调负载
     */
    public static class Payload {
        @SerializedName("text")
        private String text;
        @SerializedName("format")
        private String format;
        @SerializedName("source")
        private String source;
        @SerializedName("timestamp")
        private long timestamp;

        public Payload(String text, String format, String source, long timestamp) {
            this.text = text;
            this.format = format;
            this.source = source;
            this.timestamp = timestamp;
        }

        public String getText() {
            return text;
        }

        public String getFormat() {
            return format;
        }

        public String getSource() {
            return source;
        }
    }
}
