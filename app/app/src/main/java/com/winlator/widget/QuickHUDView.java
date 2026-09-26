package com.winlator.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.core.AppUtils;

public class QuickHUDView extends FrameLayout {
    private XServerDisplayActivity activity;
    private View floatingBar;
    private float initialX;
    private float initialY;
    private float initialTouchX;
    private float initialTouchY;
    private boolean isDragging;

    public QuickHUDView(Context context) {
        this(context, null);
    }

    public QuickHUDView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public QuickHUDView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        if (context instanceof XServerDisplayActivity) {
            this.activity = (XServerDisplayActivity) context;
        }

        View view = LayoutInflater.from(context).inflate(R.layout.quick_hud_view, this, true);
        floatingBar = view.findViewById(R.id.LLFloatingBar);
        View ballButton = view.findViewById(R.id.IBBall);

        view.findViewById(R.id.IBKeyboard).setOnClickListener(v -> {
            if (activity != null) AppUtils.showKeyboard(activity);
        });

        view.findViewById(R.id.IBInputControls).setOnClickListener(v -> {
            if (activity != null) activity.showInputControlsDialog();
        });

        view.findViewById(R.id.IBExit).setOnClickListener(v -> {
            if (activity != null) activity.exit();
        });

        ballButton.setOnClickListener(v -> {
            if (floatingBar.getVisibility() == VISIBLE) {
                floatingBar.setVisibility(GONE);
            } else {
                floatingBar.setVisibility(VISIBLE);
            }
        });
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                initialX = getX();
                initialY = getY();
                initialTouchX = ev.getRawX();
                initialTouchY = ev.getRawY();
                isDragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getRawX() - initialTouchX;
                float dy = ev.getRawY() - initialTouchY;
                if (Math.hypot(dx, dy) > 8) {
                    isDragging = true;
                    return true;
                }
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_MOVE:
                if (isDragging) {
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    ViewGroup parent = (ViewGroup) getParent();
                    if (parent != null) {
                        int parentWidth = parent.getWidth();
                        int parentHeight = parent.getHeight();
                        int viewWidth = getWidth();
                        int viewHeight = getHeight();

                        float maxX = Math.max(0, parentWidth - viewWidth);
                        float maxY = Math.max(0, parentHeight - viewHeight);

                        setX(Math.max(0, Math.min(maxX, initialX + dx)));
                        setY(Math.max(0, Math.min(maxY, initialY + dy)));
                    }
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isDragging) {
                    isDragging = false;
                    performClick();
                    return true;
                }
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
