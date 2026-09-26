package com.winlator.contentdialog;

import android.view.View;

import com.winlator.R;
import com.winlator.XServerDisplayActivity;

public class WineAppsDialog extends ContentDialog {
    public WineAppsDialog(XServerDisplayActivity activity) {
        super(activity, R.layout.wine_apps_dialog);
        setTitle(R.string.wine_apps);
        setIcon(R.drawable.icon_wine);
        findViewById(R.id.LLBottomBar).setVisibility(View.GONE);

        findViewById(R.id.BTWineCmd).setOnClickListener((v) -> {
            activity.getWinHandler().exec("cmd.exe", "");
            dismiss();
        });
        findViewById(R.id.BTWineTaskmgr).setOnClickListener((v) -> {
            activity.getWinHandler().exec("taskmgr.exe", "");
            dismiss();
        });
        findViewById(R.id.BTWineWinecfg).setOnClickListener((v) -> {
            activity.getWinHandler().exec("winecfg.exe", "");
            dismiss();
        });
        findViewById(R.id.BTWineRegedit).setOnClickListener((v) -> {
            activity.getWinHandler().exec("regedit.exe", "");
            dismiss();
        });
        findViewById(R.id.BTWineIexplore).setOnClickListener((v) -> {
            activity.getWinHandler().exec("iexplore.exe", "");
            dismiss();
        });
        findViewById(R.id.BTWineWordpad).setOnClickListener((v) -> {
            activity.getWinHandler().exec("wordpad.exe", "");
            dismiss();
        });
    }
}
