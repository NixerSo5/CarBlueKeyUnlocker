package com.nixer.carunlocker;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 给「守护中」胶囊加外圈泛光：在胶囊轮廓外做多层模糊描边，形成柔和的绿色 bloom。
 */
public class GlowPillLayout extends FrameLayout {

    private final Paint broadGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint midGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tightGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public GlowPillLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        broadGlow.setStyle(Paint.Style.STROKE);
        broadGlow.setStrokeWidth(dp(2.6f));
        broadGlow.setColor(Color.argb(120, 59, 227, 131));
        broadGlow.setMaskFilter(new BlurMaskFilter(dp(15f), BlurMaskFilter.Blur.NORMAL));

        midGlow.setStyle(Paint.Style.STROKE);
        midGlow.setStrokeWidth(dp(2f));
        midGlow.setColor(Color.argb(170, 59, 227, 131));
        midGlow.setMaskFilter(new BlurMaskFilter(dp(8f), BlurMaskFilter.Blur.NORMAL));

        tightGlow.setStyle(Paint.Style.STROKE);
        tightGlow.setStrokeWidth(dp(1.6f));
        tightGlow.setColor(Color.argb(220, 110, 231, 183));
        tightGlow.setMaskFilter(new BlurMaskFilter(dp(3f), BlurMaskFilter.Blur.NORMAL));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getChildCount() == 0) return;
        View child = getChildAt(0);
        if (child.getWidth() <= 0 || child.getHeight() <= 0) return;

        RectF r = new RectF(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
        float radius = r.height() / 2f;
        canvas.drawRoundRect(r, radius, radius, broadGlow);
        canvas.drawRoundRect(r, radius, radius, midGlow);
        canvas.drawRoundRect(r, radius, radius, tightGlow);
    }

    private float dp(float value) {
        return value * density;
    }
}
