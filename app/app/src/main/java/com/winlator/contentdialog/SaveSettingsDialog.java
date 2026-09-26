package com.winlator.contentdialog;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
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
import java.util.ArrayList;
import java.util.List;

public class SaveSettingsDialog extends ContentDialog {
    public static final int REQUEST_CODE_CUSTOM_FILE_PICKER = 1;
    private final SaveManager saveManager;
    private final ContainerManager containerManager;
    private final Activity activity;
    private Container selectedContainer;
    private TextView tvSavePath;
    private String selectedPath;
    private EditText etTitle;
    private Save saveToEdit;

    public SaveSettingsDialog(Activity activity, SaveManager saveManager, ContainerManager containerManager) {
        super(activity, R.layout.save_settings_dialog);
        this.activity = activity;
        this.saveManager = saveManager;
        this.containerManager = containerManager;
        setTitle(R.string.new_save);
        setIcon(R.drawable.icon_container);

        createContentView();

        setOnShowListener(dialog -> {
            Spinner sContainer = findViewById(R.id.SContainer);
            loadContainerSpinner(sContainer);

            if (saveToEdit != null && selectedContainer != null) {
                int containerPosition = containerManager.getContainers().indexOf(selectedContainer);
                if (containerPosition >= 0) {
                    sContainer.setSelection(containerPosition);
                }
            }
        });
    }

    public SaveSettingsDialog(Activity activity, SaveManager saveManager, ContainerManager containerManager, Save saveToEdit) {
        this(activity, saveManager, containerManager);
        this.saveToEdit = saveToEdit;

        if (saveToEdit != null) {
            etTitle.setText(saveToEdit.getTitle());
            selectedPath = saveToEdit.path;
            tvSavePath.setText(selectedPath);
            tvSavePath.setVisibility(View.VISIBLE);
            selectedContainer = saveToEdit.container;
        }
    }

    private void createContentView() {
        final Context context = getContext();

        LinearLayout llContent = findViewById(R.id.LLContent);
        llContent.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);

        etTitle = findViewById(R.id.ETTitle);

        tvSavePath = findViewById(R.id.TVPath);
        tvSavePath.setVisibility(View.GONE);

        findViewById(R.id.BTPickPath).setOnClickListener((v) -> {
            if (selectedContainer != null) {
                openFolderPicker();
            } else {
                AppUtils.showToast(context, R.string.select_container_first);
            }
        });

        setOnConfirmCallback(() -> {
            String title = etTitle.getText().toString().trim();
            if (selectedPath != null && !title.isEmpty()) {
                try {
                    saveManager.addSave(title, selectedPath, selectedContainer);

                    if (activity instanceof MainActivity) {
                        ((MainActivity) activity).onSaveAdded();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    AppUtils.showToast(context, e.getMessage() != null ? e.getMessage() : "Failed to add save");
                }
            } else {
                AppUtils.showToast(context, R.string.fill_all_fields);
            }
        });
    }

    public void updateSelectedPath(String path) {
        selectedPath = path;
        tvSavePath.setText(path);
        tvSavePath.setVisibility(View.VISIBLE);
        Log.d("SaveSettingsDialog", "Selected Path Updated in UI: " + path);
    }

    private void loadContainerSpinner(Spinner sContainer) {
        List<Container> containers = containerManager.getContainers();
        List<String> containerNames = new ArrayList<>();
        for (Container container : containers) {
            containerNames.add(container.getName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, containerNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sContainer.setAdapter(adapter);

        sContainer.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < containers.size()) {
                    selectedContainer = containers.get(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedContainer = null;
            }
        });
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
        intent.putExtra("isEditing", saveToEdit != null);
        intent.putExtra("editingPath", saveToEdit != null ? saveToEdit.path : dynamicPath);

        activity.startActivityForResult(intent, REQUEST_CODE_CUSTOM_FILE_PICKER);
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_CUSTOM_FILE_PICKER && resultCode == Activity.RESULT_OK && data != null) {
            String path = data.getStringExtra("selectedDirectory");
            Log.d("SaveSettingsDialog", "Returned Path from Picker: " + path);
            if (path != null && isPathValidForContainer(path)) {
                activity.runOnUiThread(() -> updateSelectedPath(path));
                Log.d("SaveSettingsDialog", "Path selected: " + path);
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
}
