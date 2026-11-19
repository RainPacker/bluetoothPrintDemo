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

    // 取景框 ROI（相对预览视口的归一化 rect + 视口宽高比）：
    // {nx0, ny0, nx1, ny1, viewAR}。前摄镜像对居中取景框无影响（x 翻转后不变）
    private volatile float[] roiNormalized;
    private volatile int[] resolvedRoi;
    private volatile String resolvedRoiKey;

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
            // 取景框 → 帧像素 ROI（帧尺寸变化时自动重映射）
            final int[] roi = resolveRoi(width, height, rotationDegrees);

            decoding.set(true);
            decodeHandler.post(() -> {
                try {
                    Result result = decodeEngine.decodeYuv(yPlane, width, height, rowStride, rotationDegrees, roi);
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
     * <p>
     * 契约：无论成功、失败还是异常，监听器必须被调用（失败传 null）——
     * 调用方（ScanCameraActivity）依赖回调关闭进度框并恢复相机；
     * 原实现仅 result != null 时回调，识别失败时进度框永久卡死
     * （真机 DotPeen 测试图必现）。
     */
    void decodeBitmap(android.graphics.Bitmap bitmap, OnDecodeListener oneShotListener) {
        decodeHandler.post(() -> {
            Result result = null;
            try {
                result = decodeEngine.decodeBitmap(bitmap);
            } catch (Exception e) {
                Log.e(TAG, "decodeBitmap error", e);
            }
            if (oneShotListener != null) {
                oneShotListener.onDecoded(result);
            }
        });
    }

    /**
     * 暂停/恢复帧分析（扫码成功后暂停，避免连续重复回调）
     */
    void setActive(boolean active) {
        this.active = active;
    }

    /**
     * 设置取景框 ROI（屏幕像素坐标，相对预览视口）。
     * 只识别取景框内区域：DPM 全管线耗时按裁剪面积近似线性下降（真机竖屏方形框
     * 映射后约 1/9 面积），且框外噪声/无关刻印不再进入解码（误读来源减少）。
     */
    void setRoiViewRect(float left, float top, float right, float bottom, float viewW, float viewH) {
        if (viewW <= 0 || viewH <= 0 || right <= left || bottom <= top) {
            return;
        }
        roiNormalized = new float[]{
                left / viewW, top / viewH, right / viewW, bottom / viewH, viewW / viewH};
        resolvedRoi = null; // 视口变化，重建缓存
    }

    /**
     * 取景框（预览视口归一化 rect）→ 竖屏显示帧像素 rect。
     * PreviewView FILL_CENTER：帧等比缩放覆盖视口，超出部分裁剪——
     * 帧可见比例 visW/visH 由视口宽高比与帧宽高比决定（Preview 与
     * ImageAnalysis 同视场，zoom 同步应用，映射关系不变）。
     * 返回 null = 无 ROI（全图识别）。
     */
    private int[] resolveRoi(int sensorW, int sensorH, int rotation) {
        float[] nr = roiNormalized;
        if (nr == null) {
            return null;
        }
        boolean swap = rotation == 90 || rotation == 270;
        int fw = swap ? sensorH : sensorW;
        int fh = swap ? sensorW : sensorH;
        String key = fw + "x" + fh;
        int[] cached = resolvedRoi;
        if (cached != null && key.equals(resolvedRoiKey)) {
            return cached;
        }
        float frameAR = (float) fw / fh;
        float viewAR = nr[4];
        float visW = Math.min(1f, viewAR / frameAR);
        float visH = Math.min(1f, frameAR / viewAR);
        int x0 = Math.round(((nr[0] - 0.5f) * visW + 0.5f) * fw);
        int x1 = Math.round(((nr[2] - 0.5f) * visW + 0.5f) * fw);
        int y0 = Math.round(((nr[1] - 0.5f) * visH + 0.5f) * fh);
        int y1 = Math.round(((nr[3] - 0.5f) * visH + 0.5f) * fh);
        // 外扩 20% 冗余：码略出框（视觉对齐误差）仍可识别
        int padX = Math.round((x1 - x0) * 0.2f);
        int padY = Math.round((y1 - y0) * 0.2f);
        x0 = Math.max(0, x0 - padX);
        y0 = Math.max(0, y0 - padY);
        x1 = Math.min(fw, x1 + padX);
        y1 = Math.min(fh, y1 + padY);
        if (x1 - x0 < 64 || y1 - y0 < 64) {
            return null; // 极端小框保护：退回全图识别
        }
        int[] roi = new int[]{x0, y0, x1 - x0, y1 - y0};
        resolvedRoi = roi;
        resolvedRoiKey = key;
        ScanFileLog.log("viewRoi frame " + fw + "x" + fh + " -> "
                + roi[0] + "," + roi[1] + " " + roi[2] + "x" + roi[3]);
        return roi;
    }

    void release() {
        active = false;
        decodeThread.quitSafely();
        decodeEngine.release();
    }
}
