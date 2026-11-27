package com.nixer.carunlocker;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** 解锁按钮外圈的扫描进度环，对应 HTML 设计里的 SVG 旋转进度环。 */
public class ProgressRingView extends View {
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress;
    private ValueAnimator anim;

    public ProgressRingView(Context c) {
        this(c, null);
    }

    public ProgressRingView(Context c, AttributeSet a) {
        super(c, a);
        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setColor(Color.parseColor("#14FFFFFF"));
        bgPaint.setStrokeWidth(dp(4));
        fgPaint.setStyle(Paint.Style.STROKE);
        fgPaint.setColor(Color.parseColor("#10B981"));
        fgPaint.setStrokeWidth(dp(4));
        fgPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fgPaint.setShader(new SweepGradient(w / 2f, h / 2f,
                Color.parseColor("#6EE7B7"), Color.parseColor("#10B981")));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - dp(2);
        canvas.drawCircle(cx, cy, r, bgPaint);
        if (progress > 0f) {
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r,
                    -90f, 360f * progress, false, fgPaint);
        }
    }

    public void startSweep() {
        if (anim != null) anim.cancel();
        progress = 0f;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1600);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    public void resetSweep() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        progress = 0f;
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
