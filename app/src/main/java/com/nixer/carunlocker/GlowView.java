package com.nixer.carunlocker;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 按钮背后的柔光层：用模糊圆角矩形画出类似设计图的辉光。
 */
public class GlowView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private float corner;
    private float insetX;
    private float insetY;

    public GlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        paint.setColor(0x993BE383);
        paint.setMaskFilter(new BlurMaskFilter(14f * density, BlurMaskFilter.Blur.NORMAL));
        corner = 27f * density;
        insetX = 7f * density;
        insetY = 16f * density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        canvas.drawRoundRect(insetX, insetY, w - insetX, h - insetY, corner, corner, paint);
    }
}
