package com.weifu.scanplugin.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 取景框遮罩视图（F-07）
 * <p>
 * 方向自适应：横屏使用宽扁框（适配长条码与手持姿态），竖屏使用方形框（适配 DM/QR）。
 * 绘制半透明遮罩 + 四角亮色边角 + 扫描线动画。
 */
public class ScanOverlayView extends View {

    private static final int MASK_COLOR = 0x99000000;
    private static final int CORNER_COLOR = 0xFF00E676;
    private static final int LASER_COLOR = 0xCC00E676;
    private static final float CORNER_LENGTH_DP = 22;
    private static final float CORNER_STROKE_DP = 4;
    private static final float SCAN_LINE_PERIOD = 1600;

    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint laserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frameRect = new RectF();
    private float cornerLength;
    private float cornerStroke;
    private long startTime;
    /** 取景框就绪回调（onSizeChanged 计算完成后通知，避免调用方轮询布局） */
    public interface OnFrameReadyListener {
        void onFrameReady(RectF rect);
    }
    private OnFrameReadyListener frameReadyListener;

    public ScanOverlayView(Context context) {
        this(context, null);
    }

    public ScanOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        cornerLength = CORNER_LENGTH_DP * density;
        cornerStroke = CORNER_STROKE_DP * density;
        maskPaint.setColor(MASK_COLOR);
        cornerPaint.setColor(CORNER_COLOR);
        cornerPaint.setStrokeWidth(cornerStroke);
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
        laserPaint.setColor(LASER_COLOR);
        laserPaint.setStrokeWidth(2 * density);
        startTime = System.currentTimeMillis();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeFrame(w, h);
        if (frameReadyListener != null && !frameRect.isEmpty()) {
            frameReadyListener.onFrameReady(frameRect);
        }
    }

    public void setFrameReadyListener(OnFrameReadyListener listener) {
        this.frameReadyListener = listener;
        // 布局可能已完成（onSizeChanged 已发生过）→ 立即补推一次
        if (listener != null && !frameRect.isEmpty()) {
            listener.onFrameReady(frameRect);
        }
    }

    /**
     * 计算取景框（方向自适应）
     */
    private void computeFrame(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        boolean landscape = w > h;
        int frameW, frameH;
        if (landscape) {
            // 横屏：宽扁框（条形码友好，高为短边的 62%）——
            // 为底部图标按钮区留出空间，避免与屏幕居中的取景框下沿重叠
            frameH = (int) (h * 0.62f);
            frameW = (int) (frameH * 1.3f);
            if (frameW > w * 0.8f) {
                frameW = (int) (w * 0.8f);
            }
        } else {
            // 竖屏：方形框（微信风格，边为宽的 64%）；一维条码整帧可解，框仅作视觉引导
            frameW = (int) (w * 0.64f);
            frameH = frameW;
        }
        float left = (w - frameW) / 2f;
        float top = (h - frameH) / 2f;
        frameRect.set(left, top, left + frameW, top + frameH);
    }

    /**
     * 获取取景框（供相机对焦测光区域使用）
     */
    public RectF getFrameRect() {
        return frameRect;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (frameRect.isEmpty()) {
            computeFrame(w, h);
        }

        // 半透明遮罩（挖空取景框）
        canvas.drawRect(0, 0, w, frameRect.top, maskPaint);
        canvas.drawRect(0, frameRect.bottom, w, h, maskPaint);
        canvas.drawRect(0, frameRect.top, frameRect.left, frameRect.bottom, maskPaint);
        canvas.drawRect(frameRect.right, frameRect.top, w, frameRect.bottom, maskPaint);

        // 四角边角
        drawCorners(canvas);

        // 扫描线动画
        float phase = ((System.currentTimeMillis() - startTime) % SCAN_LINE_PERIOD)
                / SCAN_LINE_PERIOD;
        float y = frameRect.top + frameRect.height() * (0.5f - 0.5f * (float) Math.cos(2 * Math.PI * phase));
        canvas.drawLine(frameRect.left + cornerLength, y, frameRect.right - cornerLength, y, laserPaint);

        postInvalidateOnAnimation();
    }

    private void drawCorners(Canvas canvas) {
        float l = frameRect.left, t = frameRect.top;
        float r = frameRect.right, b = frameRect.bottom;
        // 左上
        canvas.drawLine(l, t + cornerLength, l, t, cornerPaint);
        canvas.drawLine(l, t, l + cornerLength, t, cornerPaint);
        // 右上
        canvas.drawLine(r - cornerLength, t, r, t, cornerPaint);
        canvas.drawLine(r, t, r, t + cornerLength, cornerPaint);
        // 左下
        canvas.drawLine(l, b - cornerLength, l, b, cornerPaint);
        canvas.drawLine(l, b, l + cornerLength, b, cornerPaint);
        // 右下
        canvas.drawLine(r - cornerLength, b, r, b, cornerPaint);
        canvas.drawLine(r, b - cornerLength, r, b, cornerPaint);
    }
}
