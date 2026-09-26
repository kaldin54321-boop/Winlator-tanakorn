package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.winlator.contentdialog.ContentDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.LocaleHelper;
import com.winlator.inputcontrols.IconPack;
import com.winlator.inputcontrols.IconPackManager;

import java.io.File;
import java.util.ArrayList;

public class IconPacksActivity extends AppCompatActivity {
    private static final int IMPORT_PACK_REQUEST_CODE = 1001;
    private LinearLayout packsContainer;
    private TextView emptyView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.icon_packs_activity);

        packsContainer = findViewById(R.id.LLPacks);
        emptyView = findViewById(R.id.TVEmpty);

        findViewById(R.id.BTCreatePack).setOnClickListener((v) -> ContentDialog.prompt(this, R.string.icon_pack_name, null, (name) -> {
            IconPack pack = IconPackManager.createPack(this, name);
            if (pack != null) {
                openEditor(pack);
            }
            else AppUtils.showToast(this, R.string.unable_to_create_icon_pack);
        }));

        findViewById(R.id.BTImportPack).setOnClickListener((v) -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, IMPORT_PACK_REQUEST_CODE);
        });
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPacks();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == IMPORT_PACK_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            catch (Exception ignored) {}
            IconPack imported = IconPackManager.importPack(this, uri, null);
            if (imported != null) {
                AppUtils.showToast(this, getString(R.string.icon_pack_imported, imported.getName()));
                loadPacks();
            }
            else AppUtils.showToast(this, R.string.unable_to_import_icon_pack);
        }
    }

    private void loadPacks() {
        packsContainer.removeAllViews();
        ArrayList<IconPack> packs = IconPackManager.getPacks(this);
        emptyView.setVisibility(packs.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final IconPack pack : packs) {
            View itemView = inflater.inflate(R.layout.icon_pack_list_item, packsContainer, false);
            ((TextView)itemView.findViewById(R.id.TVTitle)).setText(pack.getName());
            ((TextView)itemView.findViewById(R.id.TVSubtitle)).setText(getString(R.string.icon_count_format, pack.getIconCount()));

            itemView.setOnClickListener((v) -> openEditor(pack));
            itemView.findViewById(R.id.BTEdit).setOnClickListener((v) -> openEditor(pack));
            itemView.findViewById(R.id.BTExport).setOnClickListener((v) -> {
                File exported = IconPackManager.exportPack(this, IconPackManager.getPack(this, pack.getId()));
                if (exported != null) {
                    String path = exported.getPath();
                    int idx = path.indexOf(Environment.DIRECTORY_DOWNLOADS);
                    AppUtils.showToast(this, getString(R.string.icon_pack_exported_to) + " " + (idx >= 0 ? path.substring(idx) : path));
                }
                else AppUtils.showToast(this, R.string.unable_to_export_icon_pack);
            });
            itemView.findViewById(R.id.BTRemove).setOnClickListener((v) -> ContentDialog.confirm(this, R.string.do_you_want_to_remove_this_icon_pack, () -> {
                IconPackManager.deletePack(this, pack);
                loadPacks();
            }));
            packsContainer.addView(itemView);
        }
    }

    private void openEditor(IconPack pack) {
        Intent intent = new Intent(this, IconPackEditorActivity.class);
        intent.putExtra("pack_id", pack.getId());
        startActivity(intent);
    }
}
