package com.nixer.carunlocker;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 车辆区域的“守护中”呼吸辉光：底部绿色柔光 + 缓慢外飘的粒子。
 */
public class GuardGlowView extends View {

    private static final int EMERALD = Color.rgb(16, 185, 129);
    private static final int EMERALD_LIGHT = Color.rgb(110, 231, 183);

    private static class Particle {
        float angle;
        float distance;
        float maxDistance;
        float speed;
        float radius;
        float alpha;
    }

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random();
    private final float density;
    private final int particleCount = 30;

    private float breathPhase;
    private ValueAnimator animator;

    public GuardGlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1f));
        initParticles();

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(3800);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            breathPhase = (float) (Math.sin(f * Math.PI * 2) * 0.5 + 0.5);
            updateParticles();
            invalidate();
        });
    }

    private void initParticles() {
        particles.clear();
        for (int i = 0; i < particleCount; i++) {
            particles.add(createParticle(true));
        }
    }

    private Particle createParticle(boolean randomStart) {
        Particle p = new Particle();
        p.maxDistance = dp(34 + random.nextFloat() * 58);
        p.angle = (float) (random.nextFloat() * Math.PI * 2);
        p.distance = randomStart ? random.nextFloat() * p.maxDistance : 0f;
        p.speed = dp(0.16f + random.nextFloat() * 0.34f);
        p.radius = dp(0.7f + random.nextFloat() * 1.2f);
        p.alpha = 0f;
        return p;
    }

    private void updateParticles() {
        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            p.distance += p.speed;
            float progress = Math.min(1f, p.distance / p.maxDistance);
            p.alpha = progress < 0.2f ? progress / 0.2f : 1f - (progress - 0.2f) / 0.8f;
            if (p.distance >= p.maxDistance) {
                particles.set(i, createParticle(false));
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animator != null && !animator.isStarted()) animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2f;
        float cy = h * 0.50f;
        float minSide = Math.min(w, h);

        // 呼吸辉光
        float glowRadius = minSide * (0.50f + 0.07f * breathPhase);
        int centerAlpha = (int) (58 + 34 * breathPhase);
        glowPaint.setShader(new RadialGradient(cx, cy, glowRadius,
                new int[]{
                        Color.argb(centerAlpha, 16, 185, 129),
                        Color.argb((int) (centerAlpha * 0.42f), 16, 185, 129),
                        Color.TRANSPARENT
                },
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, glowRadius, glowPaint);

        // 呼吸细环
        float ringRadius = minSide * (0.34f + 0.015f * breathPhase);
        ringPaint.setColor(Color.argb((int) (18 + 22 * breathPhase), 110, 231, 183));
        canvas.drawCircle(cx, cy, ringRadius, ringPaint);

        // 外飘粒子
        float baseRadius = minSide * 0.20f;
        for (Particle p : particles) {
            float r = baseRadius + p.distance;
            float px = cx + (float) Math.cos(p.angle) * r;
            float py = cy + (float) Math.sin(p.angle) * r;
            int a = (int) (p.alpha * 190);
            if (a <= 0) continue;
            particlePaint.setColor(Color.argb((int) (a * 0.3f), 52, 211, 153));
            canvas.drawCircle(px, py, p.radius * 2.4f, particlePaint);
            particlePaint.setColor(Color.argb(a, 167, 243, 208));
            canvas.drawCircle(px, py, p.radius, particlePaint);
        }
    }

    private float dp(float value) {
        return value * density;
    }
}
