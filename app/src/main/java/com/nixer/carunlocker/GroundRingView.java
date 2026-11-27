package com.nixer.carunlocker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 车底地面光圈：单个椭圆沿车身方向斜置（左下 → 右上），带外圈泛光和地面辉光。
 */
public class GroundRingView extends View {

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public GroundRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2f;
        float cy = h * 0.69f;
        float angle = -13f;

        canvas.save();
        canvas.rotate(angle, cx, cy);

        // 地面绿色泛光
        float glowW = w * 0.76f;
        float glowH = dp(70f);
        RectF glowRect = new RectF(cx - glowW / 2f, cy - glowH / 2f,
                cx + glowW / 2f, cy + glowH / 2f);
        glowPaint.setShader(new RadialGradient(cx, cy, glowW / 2f,
                new int[]{
                        Color.argb(64, 59, 227, 131),
                        Color.argb(24, 59, 227, 131),
                        Color.TRANSPARENT
                },
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawOval(glowRect, glowPaint);

        // 车底接触阴影
        float shadowW = w * 0.58f;
        float shadowH = dp(48f);
        RectF shadowRect = new RectF(cx - shadowW / 2f, cy - shadowH / 2f,
                cx + shadowW / 2f, cy + shadowH / 2f);
        shadowPaint.setShader(new RadialGradient(cx, cy, shadowW / 2f,
                new int[]{
                        Color.argb(115, 0, 0, 0),
                        Color.argb(38, 0, 0, 0),
                        Color.TRANSPARENT
                },
                new float[]{0f, 0.6f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawOval(shadowRect, shadowPaint);

        // 单个斜置椭圆：宽光晕 + 中光晕 + 清晰细线
        float ringW = w * 0.9f;
        float ringH = dp(105f);
        RectF ringRect = new RectF(cx - ringW / 2f, cy - ringH / 2f,
                cx + ringW / 2f, cy + ringH / 2f);

        ringPaint.setStrokeWidth(dp(8f));
        ringPaint.setColor(Color.argb(36, 59, 227, 131));
        canvas.drawOval(ringRect, ringPaint);

        ringPaint.setStrokeWidth(dp(3.5f));
        ringPaint.setColor(Color.argb(78, 59, 227, 131));
        canvas.drawOval(ringRect, ringPaint);

        ringPaint.setStrokeWidth(dp(1.4f));
        ringPaint.setColor(Color.argb(190, 110, 231, 183));
        canvas.drawOval(ringRect, ringPaint);

        canvas.restore();
    }

    private float dp(float value) {
        return value * density;
    }
}
