package com.winlator.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Google apps style indeterminate circular loader.
 * Static Winlator@Frost-blue rotating arc with growing/shrinking sweep.
 */
public class GoogleCircularProgressView extends View {
    private static final int STATIC_BLUE = 0xFF0288D1; // Winlator@Frost colorAccent
    private static final float MIN_SWEEP = 30f;
    private static final float MAX_SWEEP = 300f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
    private float rotation = 0f;
    private float sweep = MIN_SWEEP;
    private ValueAnimator rotationAnimator;
    private ValueAnimator sweepAnimator;
    private float strokeWidth;

    public GoogleCircularProgressView(Context context) {
        super(context);
        init(context);
    }

    public GoogleCircularProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public GoogleCircularProgressView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        strokeWidth = 5f * density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(STATIC_BLUE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) start();
        else stop();
    }

    public void start() {
        if (rotationAnimator != null && rotationAnimator.isRunning()) return;
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f);
        rotationAnimator.setDuration(1568);
        rotationAnimator.setInterpolator(new LinearInterpolator());
        rotationAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rotationAnimator.addUpdateListener((anim) -> {
            rotation = (float) anim.getAnimatedValue();
            invalidate();
        });
        rotationAnimator.start();

        sweepAnimator = ValueAnimator.ofFloat(MIN_SWEEP, MAX_SWEEP, MIN_SWEEP);
        sweepAnimator.setDuration(1332);
        sweepAnimator.setInterpolator(new LinearInterpolator());
        sweepAnimator.setRepeatCount(ValueAnimator.INFINITE);
        sweepAnimator.addUpdateListener((anim) -> {
            sweep = (float) anim.getAnimatedValue();
            invalidate();
        });
        sweepAnimator.start();
    }

    public void stop() {
        if (rotationAnimator != null) {
            rotationAnimator.cancel();
            rotationAnimator = null;
        }
        if (sweepAnimator != null) {
            sweepAnimator.cancel();
            sweepAnimator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float padding = strokeWidth / 2f + 2f;
        arcBounds.set(padding, padding, getWidth() - padding, getHeight() - padding);
        // Start at top (-90) plus continuous rotation.
        // Offset sweep growth backwards so the arc head stays smooth.
        float startAngle = -90f + rotation - (sweep - MIN_SWEEP) * 0.5f;
        canvas.drawArc(arcBounds, startAngle, sweep, false, paint);
    }
}
