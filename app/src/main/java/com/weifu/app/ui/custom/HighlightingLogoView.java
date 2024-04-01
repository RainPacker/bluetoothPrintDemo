package com.weifu.app.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;

import androidx.appcompat.widget.AppCompatImageView;

public class HighlightingLogoView extends AppCompatImageView {

    private static final long LOADING_ANIMATION_DURATION = 2000 ;
    // 初始化所需变量，例如高亮Paint、动画相关参数等
       private Paint highlightPaint;
       private ValueAnimator highlightAnimator;

       public HighlightingLogoView(Context context) {
           super(context);
           init();
       }

       public HighlightingLogoView(Context context, AttributeSet attrs) {
           super(context, attrs);
           init();
       }

       public HighlightingLogoView(Context context, AttributeSet attrs, int defStyleAttr) {
           super(context, attrs, defStyleAttr);
           init();
       }

       private void init() {
           // 初始化高亮Paint，设置其颜色、透明度等属性
           highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
           highlightPaint.setColor(Color.WHITE);
           highlightPaint.setAlpha(0); // 初始透明度为0

           // 初始化动画
           highlightAnimator = ValueAnimator.ofInt(0, 255);
           highlightAnimator.addUpdateListener(animation -> {
               // 获取当前的透明度值
               int alpha = (int) animation.getAnimatedValue();
               highlightPaint.setAlpha(alpha);

               // 强制重绘以更新高亮效果
               invalidate();
           });
           highlightAnimator.setDuration(LOADING_ANIMATION_DURATION);
           highlightAnimator.setInterpolator(new LinearInterpolator());
           highlightAnimator.setRepeatCount(ValueAnimator.INFINITE);
       }

       public void startLoadingAnimation() {
           highlightAnimator.start();
       }

       public void stopLoadingAnimation() {
           highlightAnimator.cancel();
           highlightPaint.setAlpha(0);
           invalidate();
       }

       @Override
       protected void onDraw(Canvas canvas) {
           super.onDraw(canvas);

           // 绘制原Logo
           Drawable drawable = getDrawable();
           if (drawable != null) {
               drawable.draw(canvas);
           }

           // 绘制高亮层
           Rect bounds = drawable.getBounds();
           canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, highlightPaint);
       }
   }
   