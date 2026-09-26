package com.winlator.contentdialog;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.AppUtils;
import com.winlator.saves.CustomFilePickerActivity;
import com.winlator.saves.Save;
import com.winlator.saves.SaveManager;

import java.io.File;

public class SaveEditDialog extends ContentDialog {
    public static final int REQUEST_CODE_CUSTOM_FILE_PICKER = 1;
    private final SaveManager saveManager;
    private final ContainerManager containerManager;
    private final Activity activity;
    private Container selectedContainer;
    private TextView tvOriginalPath;
    private String selectedPath;
    private EditText etTitle;
    private Save saveToEdit;

    public SaveEditDialog(Activity activity, SaveManager saveManager, ContainerManager containerManager, Save saveToEdit) {
        super(activity, R.layout.save_edit_dialog);
        this.activity = activity;
        this.saveManager = saveManager;
        this.containerManager = containerManager;
        this.saveToEdit = saveToEdit;
        setTitle(R.string.edit_save);
        setIcon(R.drawable.icon_container);

        createContentView();
    }

    private void createContentView() {
        final Context context = getContext();

        LinearLayout llContent = findViewById(R.id.LLContent);
        llContent.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);

        etTitle = findViewById(R.id.ETTitle);

        tvOriginalPath = findViewById(R.id.TVOriginalPath);

        etTitle.setText(saveToEdit.getTitle());
        tvOriginalPath.setText(saveToEdit.path);
        selectedPath = saveToEdit.path;

        final Spinner sContainer = findViewById(R.id.SContainer);

        Container currentContainer = saveToEdit.container;

        setOnConfirmCallback(() -> {
            String title = etTitle.getText().toString().trim();

            if (title.isEmpty()) {
                AppUtils.showToast(getContext(), R.string.name_required);
                return;
            }

            try {
                saveManager.updateSave(saveToEdit, title, selectedPath, currentContainer);
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).onSaveAdded();
                }
            } catch (Exception e) {
                e.printStackTrace();
                AppUtils.showToast(getContext(), e.getMessage() != null ? e.getMessage() : "Failed to update save");
            }
        });
    }

    public void updateSelectedPath(String path) {
        selectedPath = path;
        Log.d("SaveEditDialog", "Selected Path Updated in UI: " + path);
    }

    private void openFolderPicker() {
        if (selectedContainer == null || selectedContainer.getRootDir() == null) {
            AppUtils.showToast(getContext(), R.string.invalid_container);
            return;
        }

        File rootDir = selectedContainer.getRootDir();
        String dynamicPath = new File(rootDir, ".wine/drive_c/").getAbsolutePath();

        Intent intent = new Intent(activity, CustomFilePickerActivity.class);
        intent.putExtra("initialDirectory", dynamicPath);
        intent.putExtra("isEditing", true);
        intent.putExtra("editingPath", saveToEdit.path);

        activity.startActivityForResult(intent, REQUEST_CODE_CUSTOM_FILE_PICKER);
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_CUSTOM_FILE_PICKER && resultCode == Activity.RESULT_OK && data != null) {
            String path = data.getStringExtra("selectedDirectory");
            Log.d("SaveEditDialog", "Returned Path from Picker: " + path);
            if (path != null && isPathValidForContainer(path)) {
                activity.runOnUiThread(() -> updateSelectedPath(path));
                Log.d("SaveEditDialog", "Path selected: " + path);
            } else {
                AppUtils.showToast(getContext(), R.string.invalid_path);
            }
        }
    }

    private boolean isPathValidForContainer(String path) {
        if (selectedContainer == null) return false;
        File rootDir = selectedContainer.getRootDir();
        return rootDir != null && path.startsWith(rootDir.getAbsolutePath());
    }

    public int getSaveId() {
        return saveToEdit.id;
    }
}
