package com.weifu.scanplugin.camera;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.zxing.Result;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 相机帧分析器（F-21 / F-22 / F-29）
 * <p>
 * 1. 帧节流：按 frameIntervalMs 间隔取帧 + 解码忙时丢帧，避免解码线程过载导致预览卡顿；
 * 2. 独立解码线程（scan-decode-thread），不阻塞 UI 与相机线程；
 * 3. 多帧融合：连续帧持续尝试，任一帧成功即回调（低质量 DPM 码靠概率累积提升成功率）。
 */
class DpmImageAnalyzer implements ImageAnalysis.Analyzer {

    private static final String TAG = "DpmAnalyzer";

    interface OnDecodeListener {
        void onDecoded(Result result);
    }

    private final DecodeEngine decodeEngine;
    private final OnDecodeListener listener;
    private final int frameIntervalMs;

    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private volatile long lastDecodeTime = 0;
    private volatile boolean active = true;

    private final HandlerThread decodeThread;
    private final Handler decodeHandler;

    DpmImageAnalyzer(DecodeEngine decodeEngine, int frameIntervalMs, OnDecodeListener listener) {
        this.decodeEngine = decodeEngine;
        this.frameIntervalMs = frameIntervalMs;
        this.listener = listener;
        this.decodeThread = new HandlerThread("scan-decode-thread");
        this.decodeThread.start();
        this.decodeHandler = new Handler(decodeThread.getLooper());
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        try {
            if (!active || decoding.get()) {
                return; // 解码忙，丢帧
            }
            long now = System.currentTimeMillis();
            if (now - lastDecodeTime < frameIntervalMs) {
                return; // 帧节流
            }
            lastDecodeTime = now;

            final byte[] yPlane;
            final int width = image.getWidth();
            final int height = image.getHeight();
            final int rowStride;
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) {
                return;
            }
            ImageProxy.PlaneProxy y = planes[0];
            rowStride = y.getRowStride();
            java.nio.ByteBuffer buffer = y.getBuffer();
            buffer.rewind();
            int capacity = buffer.remaining();
            // 放宽检查：Y 平面尾行可能不足 rowStride（capacity < rowStride*height 是常态），
            // 原过严检查导致真机大部分帧被静默丢弃（识别慢的元凶之一）；
            // 边界安全由 extractGray 的逐行防护保证
            if (capacity < width) {
                return;
            }
            yPlane = new byte[capacity];
            buffer.get(yPlane);

            // 相机帧旋转角：ImageAnalysis 输出为传感器原始方向，必须矫正后解码
            final int rotationDegrees = image.getImageInfo().getRotationDegrees();

            decoding.set(true);
            decodeHandler.post(() -> {
                try {
                    Result result = decodeEngine.decodeYuv(yPlane, width, height, rowStride, rotationDegrees);
                    if (result != null && listener != null && active) {
                        listener.onDecoded(result);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "decode error", e);
                } finally {
                    decoding.set(false);
                }
            });
        } finally {
            image.close();
        }
    }

    /**
     * 解码 Bitmap（相册选图）
     */
    void decodeBitmap(android.graphics.Bitmap bitmap, OnDecodeListener oneShotListener) {
        decodeHandler.post(() -> {
            try {
                Result result = decodeEngine.decodeBitmap(bitmap);
                if (result != null && oneShotListener != null) {
                    oneShotListener.onDecoded(result);
                }
            } catch (Exception e) {
                Log.e(TAG, "decodeBitmap error", e);
            }
        });
    }

    /**
     * 暂停/恢复帧分析（扫码成功后暂停，避免连续重复回调）
     */
    void setActive(boolean active) {
        this.active = active;
    }

    void release() {
        active = false;
        decodeThread.quitSafely();
        decodeEngine.release();
    }
}
