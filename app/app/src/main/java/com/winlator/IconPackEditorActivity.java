package com.winlator;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.winlator.contentdialog.ContentDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.LocaleHelper;
import com.winlator.core.UnitUtils;
import com.winlator.inputcontrols.IconPack;
import com.winlator.inputcontrols.IconPackManager;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;

public class IconPackEditorActivity extends AppCompatActivity {
    private static final int ADD_ICONS_REQUEST_CODE = 1002;
    private String packId;
    private IconPack pack;
    private GridLayout iconsGrid;
    private TextView packNameView;
    private TextView packInfoView;
    private TextView emptyView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.icon_pack_editor_activity);

        packId = getIntent().getStringExtra("pack_id");
        packNameView = findViewById(R.id.TVPackName);
        packInfoView = findViewById(R.id.TVPackInfo);
        iconsGrid = findViewById(R.id.GLIcons);
        emptyView = findViewById(R.id.TVEmpty);

        findViewById(R.id.BTAddIcons).setOnClickListener((v) -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, ADD_ICONS_REQUEST_CODE);
        });

        findViewById(R.id.BTRename).setOnClickListener((v) -> {
            if (pack == null) return;
            ContentDialog.prompt(this, R.string.icon_pack_name, pack.getName(), (name) -> {
                if (IconPackManager.renamePack(this, pack, name)) {
                    pack.setName(name);
                    refresh();
                }
            });
        });

        findViewById(R.id.BTExport).setOnClickListener((v) -> {
            if (pack == null) return;
            File exported = IconPackManager.exportPack(this, IconPackManager.getPack(this, pack.getId()));
            if (exported != null) {
                String path = exported.getPath();
                int idx = path.indexOf(Environment.DIRECTORY_DOWNLOADS);
                AppUtils.showToast(this, getString(R.string.icon_pack_exported_to) + " " + (idx >= 0 ? path.substring(idx) : path));
            }
            else AppUtils.showToast(this, R.string.unable_to_export_icon_pack);
        });
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ADD_ICONS_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            ArrayList<Uri> uris = new ArrayList<>();
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                for (int i = 0; i < clipData.getItemCount(); i++) uris.add(clipData.getItemAt(i).getUri());
            }
            else if (data.getData() != null) {
                uris.add(data.getData());
            }
            int added = 0;
            for (Uri uri : uris) {
                try {
                    String displayName = FileUtils.getNameFromUri(this, uri);
                    if (displayName.isEmpty()) displayName = "icon";
                    try (InputStream is = getContentResolver().openInputStream(uri)) {
                        String stored = IconPackManager.addIconFromStream(this, packId, displayName, is);
                        if (stored != null) added++;
                    }
                }
                catch (Exception ignored) {}
            }
            if (added == 0) {
                AppUtils.showToast(this, R.string.unable_to_add_icons);
            }
            else {
                AppUtils.showToast(this, getString(R.string.icons_added, added));
            }
            refresh();
        }
    }

    private void refresh() {
        pack = IconPackManager.getPack(this, packId);
        if (pack == null) {
            finish();
            return;
        }
        packNameView.setText(pack.getName());
        packInfoView.setText(getString(R.string.icon_count_format, pack.getIconCount()));
        iconsGrid.removeAllViews();
        emptyView.setVisibility(pack.getIconNames().isEmpty() ? View.VISIBLE : View.GONE);

        int size = (int)UnitUtils.dpToPx(64);
        int margin = (int)UnitUtils.dpToPx(4);
        int padding = (int)UnitUtils.dpToPx(6);
        for (final String iconName : pack.getIconNames()) {
            Bitmap bitmap = IconPackManager.loadIconBitmap(this, pack.getId(), iconName);
            if (bitmap == null) continue;
            final ImageView imageView = new ImageView(this);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = size;
            params.height = size;
            params.setMargins(margin, margin, margin, margin);
            imageView.setLayoutParams(params);
            imageView.setPadding(padding, padding, padding, padding);
            imageView.setBackgroundResource(R.drawable.icon_background);
            imageView.setImageBitmap(bitmap);
            imageView.setContentDescription(iconName);
            imageView.setOnClickListener((v) -> {
                ContentDialog dialog = new ContentDialog(this);
                dialog.setCancelable(false);
                dialog.setMessage(getString(R.string.do_you_want_to_remove_icon, iconName));
                dialog.setOnConfirmCallback(() -> {
                    IconPackManager.deleteIcon(this, pack.getId(), iconName);
                    refresh();
                });
                dialog.show();
            });
            iconsGrid.addView(imageView);
        }
    }
}
