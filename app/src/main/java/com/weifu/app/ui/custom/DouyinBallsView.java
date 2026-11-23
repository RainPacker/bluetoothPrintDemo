package com.weifu.app.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 抖音风"两颗小球"加载动画（主屏 WebView 加载指示）：
 * 抖音青/红两颗小球紧靠，绕公共中心在垂直于屏幕的轨道平面内
 * 相互旋转——绕屏幕内竖直轴互绕，前球放大在前、后球缩小在后，
 * 交替从彼此前面/后面掠过（后画的前球遮挡先画的后球），呈现 3D 互绕轨道效果。
 * 生命周期自管理：附着窗口自动播放、脱离自动取消、
 * VISIBLE/GONE 切换自动启停——使用方只需控制 visibility。
 */
public class DouyinBallsView extends View {

    /** 抖音品牌色 */
    private static final int COLOR_CYAN = 0xFF25F4EE;
    private static final int COLOR_RED = 0xFFFE2C55;
    /** 互绕一圈时长 */
    private static final long CYCLE_MS = 1000;
    /** 轨道半径 = 球半径 × 该系数（两球紧靠，仅留窄缝） */
    private static final float ORBIT_FACTOR = 1.2f;
    /** 轨道平面前倾量：前球略低、后球略高，增强立体感 */
    private static final float TILT = 0.35f;
    /** 透视强度：靠近观察者的球放大、远离的缩小 */
    private static final float PERSPECTIVE = 0.40f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator animator;
    /** 旋转相位 0..1（一圈） */
    private float phase;

    public DouyinBallsView(Context context) {
        super(context);
        init();
    }

    public DouyinBallsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DouyinBallsView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(CYCLE_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                phase = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float r = Math.min(h / 6f, w / 12f); // 小球半径
        float cx = w / 2f;
        float cy = h / 2f;
        float orbit = r * ORBIT_FACTOR;

        float theta = (float) (phase * Math.PI * 2);
        float thetaB = theta + (float) Math.PI; // 两球相位恒差 π（对径）
        float cosA = (float) Math.cos(theta);

        // 后球（远离观察者）先画垫底，前球后画覆盖——穿越时前球在上
        if (cosA <= -cosA) {
            drawBall(canvas, cx, cy, r, orbit, theta, COLOR_CYAN);
            drawBall(canvas, cx, cy, r, orbit, thetaB, COLOR_RED);
        } else {
            drawBall(canvas, cx, cy, r, orbit, thetaB, COLOR_RED);
            drawBall(canvas, cx, cy, r, orbit, theta, COLOR_CYAN);
        }
    }

    /**
     * 画轨道上的一颗球。轨道平面垂直于屏幕（绕屏幕内竖直轴互绕）：
     * 三维位置 x = orbit·sinθ（水平）、z = orbit·cosθ（朝观察者深度），
     * 投影到屏幕：前球（z&gt;0）放大且略低，后球缩小且略高。
     */
    private void drawBall(Canvas canvas, float cx, float cy, float r,
                          float orbit, float theta, int color) {
        float sin = (float) Math.sin(theta);
        float cos = (float) Math.cos(theta);
        float x = cx + orbit * sin;
        float y = cy + TILT * orbit * cos;
        float scale = 1f + PERSPECTIVE * cos;
        paint.setColor(color);
        canvas.drawCircle(x, y, r * scale, paint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animator != null && getVisibility() == VISIBLE && !animator.isStarted()) {
            animator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        // 无限循环动画持有 View 引用，脱离窗口必须取消防泄漏
        if (animator != null) {
            animator.cancel();
        }
        super.onDetachedFromWindow();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        // GONE（加载完成）即停，VISIBLE（开始加载/后续页面跳转）即播
        if (animator != null) {
            if (visibility == VISIBLE) {
                if (!animator.isStarted()) {
                    animator.start();
                }
            } else if (animator.isStarted()) {
                animator.cancel();
            }
        }
    }
}
