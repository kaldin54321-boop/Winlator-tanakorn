package com.winlator.contentdialog;

import android.view.View;

import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.core.AppUtils;
import com.winlator.winhandler.TaskManagerDialog;

/**
 * Grid-style XServer menu matching the classic Winlator@Frost clone layout:
 * row 1 = Keyboard, Input Controls, Screen effects, Toggle Fullscreen,
 * Toggle Orientation; row 2 = Task Manager, Magnifier, PiP Mode,
 * Touchpad Help, Exit; row 3 = Wine Apps, Soft Stretch.
 */
public class XServerMenuDialog extends ContentDialog {
    private final XServerDisplayActivity activity;

    public XServerMenuDialog(XServerDisplayActivity activity) {
        super(activity, R.layout.xserver_menu_dialog);
        this.activity = activity;
        setTitle(R.string.app_name);
        setIcon(R.drawable.icon_wine);

        // The reference layout only has a single OK button.
        View cancelButton = getContentView().findViewById(R.id.BTCancel);
        if (cancelButton != null) cancelButton.setVisibility(View.GONE);

        findViewById(R.id.MenuKeyboard).setOnClickListener((v) -> {
            dismiss();
            AppUtils.showKeyboard(activity);
        });
        findViewById(R.id.MenuInputControls).setOnClickListener((v) -> {
            dismiss();
            activity.showInputControlsDialog();
        });
        findViewById(R.id.MenuScreenEffect).setOnClickListener((v) -> {
            dismiss();
            (new ScreenEffectDialog(activity)).show();
        });
        findViewById(R.id.MenuToggleFullscreen).setOnClickListener((v) -> {
            dismiss();
            if (activity.getXServerView() != null) activity.getXServerView().getRenderer().toggleFullscreen();
        });
        findViewById(R.id.MenuToggleOrientation).setOnClickListener((v) -> {
            dismiss();
            activity.toggleOrientation();
        });
        findViewById(R.id.MenuTaskManager).setOnClickListener((v) -> {
            dismiss();
            (new TaskManagerDialog(activity)).show();
        });
        findViewById(R.id.MenuMagnifier).setOnClickListener((v) -> {
            dismiss();
            activity.showMagnifier();
        });
        findViewById(R.id.MenuPipMode).setOnClickListener((v) -> {
            dismiss();
            activity.enterPipMode();
        });
        findViewById(R.id.MenuTouchpadHelp).setOnClickListener((v) -> {
            dismiss();
            activity.showTouchpadHelp();
        });
        findViewById(R.id.MenuExit).setOnClickListener((v) -> {
            dismiss();
            activity.exit();
        });
        findViewById(R.id.MenuWineApps).setOnClickListener((v) -> {
            dismiss();
            (new WineAppsDialog(activity)).show();
        });
        findViewById(R.id.MenuSoftStretch).setOnClickListener((v) -> {
            dismiss();
            if (activity.getXServerView() != null) {
                activity.getXServerView().getRenderer().toggleSoftStretch();
                boolean enabled = activity.getXServerView().getRenderer().isSoftStretch();
                AppUtils.showToast(activity, activity.getString(R.string.soft_stretch) + ": " +
                    (enabled ? activity.getString(R.string.enable) : activity.getString(R.string.disable)));
            }
        });
    }
}
