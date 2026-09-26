package com.winlator.widget;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.winlator.R;

/**
 * GameHub-style launching overlay.
 *
 * <p>Shown after the shortcut preloader dialog once the XServer / Wine
 * services start initializing, while waiting for the game window to appear.
 * It is dismissed only when the first game window actually maps — never on a
 * timer — so a game that fails to launch keeps the overlay up instead of
 * dropping to a black screen.
 *
 * <p>The top-right controller pill shows the input-controls profile selected
 * in the shortcut settings (first line stays "Device connected", second line
 * is the profile name). When the shortcut has no profile selected the pill is
 * hidden entirely.
 *
 * <p>On show, the overlay plays a staggered entrance: the Windows logo pops
 * in first, then the swap arrow, the game icon, the status text and finally
 * the loading bar, which sweeps continuously until dismiss.
 */
@SuppressLint("ViewConstructor")
public class GameHubLoadingView extends FrameLayout {
    private final View windowsLogoView;
    private final View swapView;
    private final ImageView gameIconView;
    private final TextView statusView;
    private final ProgressBar progressBar;
    private final View controllerPill;
    private final TextView controllerNameView;
    private final String controllerName;
    private ValueAnimator progressAnimator;
    private ValueAnimator statusPulseAnimator;

    public GameHubLoadingView(Context context, Bitmap gameIcon, CharSequence status, String controllerName) {
        super(context);
        setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        LayoutInflater.from(context).inflate(R.layout.gamehub_loading_view, this, true);

        windowsLogoView = findViewById(R.id.IVGameHubWindows);
        swapView = findViewById(R.id.TVGameHubSwap);
        gameIconView = findViewById(R.id.IVGameHubIcon);
        statusView = findViewById(R.id.TVGameHubStatus);
        progressBar = findViewById(R.id.PBGameHubProgress);
        controllerPill = findViewById(R.id.PillGameHubController);
        controllerNameView = findViewById(R.id.TVGameHubControllerName);
        this.controllerName = controllerName != null && !controllerName.isEmpty() ? controllerName : null;

        setGameIcon(gameIcon);
        setStatus(status);
        if (controllerNameView != null && this.controllerName != null) {
            controllerNameView.setText(this.controllerName);
        }
    }

    public void setGameIcon(Bitmap icon) {
        if (gameIconView == null) return;
        if (icon != null) {
            gameIconView.setImageBitmap(icon);
            gameIconView.setVisibility(View.VISIBLE);
        }
        else gameIconView.setVisibility(View.INVISIBLE);
    }

    /** Shows a drawable resource (e.g. the desktop outline for container launches). */
    public void setGameIconResource(int resId) {
        if (gameIconView == null) return;
        gameIconView.setImageResource(resId);
        gameIconView.setVisibility(View.VISIBLE);
    }

    public void setStatus(CharSequence status) {
        if (statusView != null && status != null) statusView.setText(status);
    }

    /**
     * Staggered entrance choreography: Windows logo pops in, then the swap
     * arrow, game icon, status text and loading bar, followed by the
     * controller pill (only when a profile is selected).
     */
    public void playEntranceAnimation() {
        float density = getResources().getDisplayMetrics().density;

        if (windowsLogoView != null) {
            windowsLogoView.setScaleX(0.5f);
            windowsLogoView.setScaleY(0.5f);
            windowsLogoView.setAlpha(0f);
            windowsLogoView.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(450L)
                .setInterpolator(new OvershootInterpolator(1.2f))
                .start();
        }
        if (swapView != null) {
            swapView.setAlpha(0f);
            swapView.animate().alpha(1f)
                .setStartDelay(150L).setDuration(300L)
                .start();
        }
        if (gameIconView != null) {
            gameIconView.setScaleX(0.5f);
            gameIconView.setScaleY(0.5f);
            gameIconView.setAlpha(0f);
            gameIconView.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(280L).setDuration(450L)
                .setInterpolator(new OvershootInterpolator(1.2f))
                .start();
        }
        if (statusView != null) {
            statusView.setAlpha(0f);
            statusView.setTranslationY(12f * density);
            statusView.animate().alpha(1f).translationY(0f)
                .setStartDelay(450L).setDuration(350L)
                .withEndAction(this::startStatusPulse)
                .start();
        }
        if (progressBar != null) {
            progressBar.setAlpha(0f);
            progressBar.animate().alpha(1f)
                .setStartDelay(550L).setDuration(300L)
                .withEndAction(this::startProgressAnimation)
                .start();
        }
        if (controllerPill != null && controllerName != null) {
            controllerPill.setVisibility(View.VISIBLE);
            controllerPill.setAlpha(0f);
            controllerPill.setTranslationY(-20f * density);
            controllerPill.animate().alpha(1f).translationY(0f)
                .setStartDelay(650L).setDuration(350L)
                .withEndAction(() -> controllerPill.postDelayed(() -> controllerPill.animate()
                    .alpha(0f).setDuration(500L)
                    .withEndAction(() -> controllerPill.setVisibility(View.GONE))
                    .start(), 3500L))
                .start();
        }
    }

    /** Continuous 0 -> 100% fill sweep; completes to 100% on dismiss. */
    public void startProgressAnimation() {
        stopProgressAnimation();
        if (progressBar == null) return;
        progressAnimator = ValueAnimator.ofInt(0, 100);
        progressAnimator.setDuration(1400L);
        progressAnimator.setInterpolator(new LinearInterpolator());
        progressAnimator.setRepeatCount(ValueAnimator.INFINITE);
        progressAnimator.setRepeatMode(ValueAnimator.RESTART);
        progressAnimator.addUpdateListener(anim -> {
            if (progressBar != null) progressBar.setProgress((int) anim.getAnimatedValue());
        });
        progressAnimator.start();
    }

    public void stopProgressAnimation() {
        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }
        if (statusPulseAnimator != null) {
            statusPulseAnimator.cancel();
            statusPulseAnimator = null;
        }
    }

    /** Gentle breathing pulse on the status text while waiting. */
    private void startStatusPulse() {
        if (statusView == null || statusPulseAnimator != null) return;
        statusPulseAnimator = ValueAnimator.ofFloat(1f, 0.55f, 1f);
        statusPulseAnimator.setDuration(1600L);
        statusPulseAnimator.setInterpolator(new LinearInterpolator());
        statusPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        statusPulseAnimator.addUpdateListener(anim -> {
            if (statusView != null) statusView.setAlpha((float) anim.getAnimatedValue());
        });
        statusPulseAnimator.start();
    }

    /** Fills the bar, stops the animations and fades the overlay out. */
    public void dismissAnimated() {
        stopProgressAnimation();
        if (progressBar != null) progressBar.setProgress(100);
        animate().alpha(0f).setDuration(350L).withEndAction(() -> {
            ViewParent parent = getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(this);
        }).start();
    }

    /** Fills the bar before the host removes this view. */
    public void completeProgress() {
        stopProgressAnimation();
        if (progressBar != null) progressBar.setProgress(100);
    }
}
