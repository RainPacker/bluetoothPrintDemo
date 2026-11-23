package com.weifu.app.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;

import androidx.appcompat.widget.AppCompatImageView;

import com.weifu.app.R;

/**
 * "刷油漆" logo 加载动画（开屏/主屏统一加载指示）：
 * 三层叠加——底漆（logo 黑色剪影）+ 湿漆层（灰白半透明，恒铺满
 * 整图）+ 成品层（全彩 logo，单条边界从左向右推进）。
 * 未刷区域 = 黑剪影 + 灰白湿漆 = 灰白剪影（非彩色）；
 * 视觉上是"整面墙已上灰白湿漆，刷子滚过的地方变干变彩色"——
 * 单光源单刷子，成品边界即唯一的光源边界。
 * 生命周期自管理：附着窗口自动开刷、脱离自动取消、VISIBLE/GONE
 * 切换自动启停——使用方只需控制 visibility，无需动画代码。
 */
public class PaintLogoView extends AppCompatImageView {

    private ValueAnimator animator;

    public PaintLogoView(Context context) {
        super(context);
        init();
    }

    public PaintLogoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PaintLogoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setImageResource(R.drawable.logo_reveal);
        Drawable d = getDrawable();
        if (!(d instanceof LayerDrawable)) {
            return;
        }
        LayerDrawable layers = (LayerDrawable) d;
        // 底漆：PNG 透明背景（角点 alpha=0 已验证），tint 只染 logo 形状——
        // 黑白底色（未刷区域呈黑色剪影版 logo），彩色成品刷上去对比鲜明
        Drawable dim = layers.findDrawableByLayerId(R.id.layer_dim);
        dim.setTint(0xFF000000);
        dim.setAlpha(90);
        // 湿漆层染灰白、恒铺满整图（level 固定 10000）：未刷区域 =
        // 黑剪影底漆 + 灰白半透明湿漆 = 灰白剪影（非彩色），
        // 成品层单条边界从左向右推进——"刷子滚过的地方变干变彩色"
        Drawable half = layers.findDrawableByLayerId(R.id.layer_half);
        half.setTint(0xFF9E9E9E);
        half.setAlpha(140);
        half.setLevel(10000);

        animator = ValueAnimator.ofFloat(0f, 1f);
        // 1900ms 一轮：略短于开屏 2s 停留时长——动画从 onAttachedToWindow
        // 起跳，比 SplashActivity 的 2s 跳转计时晚几十 ms，留出余量
        // 确保 2s 跳转时刻油漆已 100% 刷完（全彩定格）
        animator.setDuration(1900);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float f = (float) animation.getAnimatedValue();
                layers.findDrawableByLayerId(R.id.layer_full)
                        .setLevel((int) (f * 10000));
            }
        });
    }

    /**
     * 单轮模式（广告屏用）：动画只播一轮、结束停在全彩（level=10000），
     * 不再无限循环——与 SplashActivity 2s 停留时长对齐，跳转瞬间
     * 油漆恰好刷完，不会闪回重头。须在动画启动前（附着窗口前）调用。
     */
    public void setOneShot(boolean oneShot) {
        if (animator != null) {
            animator.setRepeatCount(oneShot ? 0 : ValueAnimator.INFINITE);
        }
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
        // GONE（加载完成）即停刷，VISIBLE（开始加载/后续页面跳转）即开刷
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
