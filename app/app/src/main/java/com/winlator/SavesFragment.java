package com.winlator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.AppUtils;
import com.winlator.core.Callback;
import com.winlator.core.FileUtils;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.TarCompressorUtils;
import com.winlator.saves.Save;
import com.winlator.saves.SaveManager;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SavesFragment extends Fragment {
    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private SaveManager saveManager;
    private List<Save> savesList = new ArrayList<>();
    private ContainerManager containerManager;

    private static final int REQUEST_CODE_IMPORT_ARCHIVE = 1001;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        containerManager = new ContainerManager(getContext());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.saves);
        saveManager = new SaveManager(getContext());
        loadSavesList();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FrameLayout frameLayout = (FrameLayout) inflater.inflate(R.layout.saves_fragment, container, false);
        recyclerView = frameLayout.findViewById(R.id.RecyclerView);
        emptyTextView = frameLayout.findViewById(R.id.TVEmptyText);
        Context context = recyclerView.getContext();
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        DividerItemDecoration itemDecoration = new DividerItemDecoration(context, DividerItemDecoration.VERTICAL);
        itemDecoration.setDrawable(ContextCompat.getDrawable(context, R.drawable.list_item_divider));
        recyclerView.addItemDecoration(itemDecoration);
        return frameLayout;
    }

    private void loadSavesList() {
        if (saveManager == null) saveManager = new SaveManager(getContext());
        savesList = saveManager.getSaves();
        if (recyclerView != null) recyclerView.setAdapter(new SavesAdapter(savesList));
        if (emptyTextView != null) {
            emptyTextView.setVisibility(savesList.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.saves_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (menuItem.getItemId() == R.id.saves_menu_add) {
            ((MainActivity) getActivity()).showSaveSettingsDialog(null);
            return true;
        } else if (menuItem.getItemId() == R.id.saves_menu_import) {
            selectArchiveForImport();
            return true;
        } else {
            return super.onOptionsItemSelected(menuItem);
        }
    }

    private void selectArchiveForImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQUEST_CODE_IMPORT_ARCHIVE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_IMPORT_ARCHIVE && resultCode == Activity.RESULT_OK && data != null) {
            Uri archiveUri = data.getData();
            if (archiveUri != null) {
                importSave(archiveUri);
            }
        }
    }

    private void showContainerSelectionDialog(Callback<Container> onContainerSelected, Runnable onCancel) {
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.container_selection_dialog, null);
        Spinner spinner = dialogView.findViewById(R.id.spinner_container_selection);

        List<Container> containers = containerManager.getContainers();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        for (Container container : containers) {
            adapter.add(container.getName());
        }
        spinner.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle(R.string.import_save)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, (dialogInterface, which) -> {
                    int selectedPosition = spinner.getSelectedItemPosition();
                    if (selectedPosition >= 0 && selectedPosition < containers.size()) {
                        Container selectedContainer = containers.get(selectedPosition);
                        onContainerSelected.call(selectedContainer);
                    }
                })
                .setNegativeButton(android.R.string.cancel, (dialogInterface, which) -> {
                    onCancel.run();
                })
                .create();

        dialog.show();
    }

    private void importSave(Uri archiveUri) {
        PreloaderDialog preloaderDialog = new PreloaderDialog(getActivity());
        preloaderDialog.showOnUiThread(R.string.importing_save);

        new Thread(() -> {
            try {
                File tempDir = new File(getContext().getCacheDir(), "import_temp");
                if (tempDir.exists()) {
                    FileUtils.delete(tempDir);
                }
                if (!tempDir.mkdirs()) {
                    AppUtils.showToast(getContext(), "Failed to create temporary directory.");
                    preloaderDialog.closeOnUiThread();
                    return;
                }

                boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, getContext(), archiveUri, tempDir);
                if (!success) {
                    AppUtils.showToast(getContext(), "Failed to decompress archive.");
                    preloaderDialog.closeOnUiThread();
                    return;
                }

                File[] extractedFiles = tempDir.listFiles();
                if (extractedFiles == null || extractedFiles.length != 1 || !extractedFiles[0].isDirectory()) {
                    AppUtils.showToast(getContext(), "Unexpected archive structure.");
                    preloaderDialog.closeOnUiThread();
                    return;
                }

                File extractedDir = extractedFiles[0];

                File[] jsonFiles = extractedDir.listFiles((dir, name) -> name.endsWith(".json"));
                if (jsonFiles == null || jsonFiles.length != 1) {
                    AppUtils.showToast(getContext(), "JSON file not found in the archive.");
                    preloaderDialog.closeOnUiThread();
                    return;
                }

                File jsonFile = jsonFiles[0];

                String jsonString = FileUtils.readString(jsonFile);
                JSONObject saveData = new JSONObject(jsonString);
                String title = saveData.getString("Title");
                String savePath = saveData.getString("Path");

                getActivity().runOnUiThread(() -> showContainerSelectionDialog((selectedContainer) -> {
                    try {
                        File destRootDir = new File(selectedContainer.getRootDir(), ".wine/drive_c");

                        String relativeSavePath;
                        int driveCIndex = savePath.indexOf("drive_c");
                        if (driveCIndex != -1) {
                            relativeSavePath = savePath.substring(driveCIndex + "drive_c/".length());
                        } else {
                            relativeSavePath = savePath;
                        }

                        File destSaveDir = new File(destRootDir, relativeSavePath);

                        if (!destSaveDir.getParentFile().exists() && !destSaveDir.getParentFile().mkdirs()) {
                            AppUtils.showToast(getContext(), "Failed to create directories for save path.");
                            preloaderDialog.closeOnUiThread();
                            return;
                        }

                        File saveDirectoryToCopy = new File(extractedDir, new File(savePath).getName());
                        if (!FileUtils.copy(saveDirectoryToCopy, destSaveDir)) {
                            AppUtils.showToast(getContext(), "Failed to copy save files.");
                            preloaderDialog.closeOnUiThread();
                            return;
                        }

                        saveManager.addSave(title, destSaveDir.getAbsolutePath(), selectedContainer);

                        AppUtils.showToast(getContext(), "Save imported successfully.");
                        loadSavesList();
                    } catch (IOException e) {
                        AppUtils.showToast(getContext(), "Failed to import save: " + e.getMessage());
                    } finally {
                        FileUtils.delete(tempDir);
                        preloaderDialog.closeOnUiThread();
                    }
                }, () -> {
                    preloaderDialog.closeOnUiThread();
                }));
            } catch (Exception e) {
                AppUtils.showToast(getContext(), "Import failed: " + e.getMessage());
                e.printStackTrace();
                preloaderDialog.closeOnUiThread();
            }
        }).start();
    }

    public void refreshSavesList() {
        loadSavesList();
    }

    private class SavesAdapter extends RecyclerView.Adapter<SavesAdapter.ViewHolder> {
        private final List<Save> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageButton menuButton;
            private final ImageView imageView;
            private final TextView title;
            private final TextView containerName;

            private ViewHolder(View view) {
                super(view);
                this.imageView = view.findViewById(R.id.ImageView);
                this.title = view.findViewById(R.id.TVTitle);
                this.containerName = view.findViewById(R.id.TVContainerName);
                this.menuButton = view.findViewById(R.id.BTMenu);
            }
        }

        public SavesAdapter(List<Save> data) {
            this.data = data;
        }

        @Override
        public final ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.saves_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(final ViewHolder holder, int position) {
            final Save item = data.get(position);
            holder.imageView.setImageResource(R.drawable.icon_container);
            holder.title.setText(item.getTitle());
            holder.containerName.setText(item.container != null ? item.container.getName() : "");
            holder.menuButton.setOnClickListener((view) -> showListItemMenu(view, item));
        }

        @Override
        public final int getItemCount() {
            return data.size();
        }

        private void showListItemMenu(View anchorView, Save save) {
            final Context context = getContext();
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            listItemMenu.inflate(R.menu.save_popup_menu);

            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.save_edit) {
                    ((MainActivity) getActivity()).showSaveEditDialog(save);
                    return true;
                } else if (itemId == R.id.save_transfer) {
                    showContainerSelectionDialog(save);
                    return true;
                } else if (itemId == R.id.save_export) {
                    exportSave(save, false);
                    return true;
                } else if (itemId == R.id.save_share) {
                    exportSave(save, true);
                    return true;
                } else if (itemId == R.id.save_unregister) {
                    saveManager.removeSave(save);
                    loadSavesList();
                    return true;
                }
                return false;
            });
            listItemMenu.show();
        }

        private File getExportDirectory() {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File winlatorSavesDir = new File(downloadsDir, "Winlator/Saves");

            if (!winlatorSavesDir.exists()) {
                winlatorSavesDir.mkdirs();
            }

            return winlatorSavesDir;
        }

        private void exportSave(Save save, boolean shareAfterExport) {
            PreloaderDialog preloaderDialog = new PreloaderDialog(getActivity());
            preloaderDialog.showOnUiThread(R.string.exporting_save);

            new Thread(() -> {
                try {
                    File saveDirectory = new File(save.path);
                    if (!saveDirectory.exists() || !saveDirectory.isDirectory()) {
                        AppUtils.showToast(getContext(), "Save directory is invalid.");
                        return;
                    }

                    File saveJsonFile = saveManager.getSaveJsonFile(save);
                    if (!saveJsonFile.exists()) {
                        AppUtils.showToast(getContext(), "Save .json file is missing.");
                        return;
                    }

                    File exportDirectory = getExportDirectory();
                    if (!exportDirectory.exists() && !exportDirectory.mkdirs()) {
                        AppUtils.showToast(getContext(), "Failed to create export directory.");
                        return;
                    }

                    String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                    String archiveName = save.getTitle() + "_" + timestamp + ".tar.xz";
                    File exportFile = new File(exportDirectory, archiveName);

                    File tempExportDir = new File(exportDirectory, "temp_" + save.getTitle() + "_" + timestamp);
                    if (!tempExportDir.exists() && !tempExportDir.mkdirs()) {
                        AppUtils.showToast(getContext(), "Failed to create temporary directory.");
                        return;
                    }

                    File copiedJsonFile = new File(tempExportDir, saveJsonFile.getName());
                    if (!FileUtils.copy(saveJsonFile, copiedJsonFile)) {
                        AppUtils.showToast(getContext(), "Failed to copy .json file.");
                        return;
                    }

                    File copiedSaveDirectory = new File(tempExportDir, saveDirectory.getName());
                    if (!FileUtils.copy(saveDirectory, copiedSaveDirectory)) {
                        AppUtils.showToast(getContext(), "Failed to copy save directory.");
                        return;
                    }

                    TarCompressorUtils.compress(TarCompressorUtils.Type.XZ, tempExportDir, exportFile, 3);

                    FileUtils.delete(tempExportDir);

                    AppUtils.showToast(getContext(), "Save exported to " + exportFile.getAbsolutePath());

                    makeFileVisible(exportFile);

                    if (shareAfterExport) {
                        shareExportedFile(exportFile);
                    }
                } catch (Exception e) {
                    AppUtils.showToast(getContext(), "Failed to export save.");
                } finally {
                    preloaderDialog.closeOnUiThread();
                }
            }).start();
        }

        private void makeFileVisible(File file) {
            MediaScannerConnection.scanFile(getContext(),
                    new String[]{file.getAbsolutePath()},
                    null,
                    (path, uri) -> {
                    });

            file.setReadable(true, false);
            file.setWritable(true, false);
        }

        private void shareExportedFile(File exportFile) {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/octet-stream");
            Uri fileUri = FileProvider.getUriForFile(getContext(), "com.winlator.FileProvider", exportFile);
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(shareIntent, "Share Save Archive"));
        }

        private void showContainerSelectionDialog(Save save) {
            View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.container_selection_dialog, null);
            Spinner spinner = dialogView.findViewById(R.id.spinner_container_selection);

            List<Container> containers = containerManager.getContainers();
            ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

            for (Container container : containers) {
                adapter.add(container.getName());
            }
            spinner.setAdapter(adapter);

            new AlertDialog.Builder(getContext())
                    .setTitle(R.string.save_transfer)
                    .setView(dialogView)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        int selectedPosition = spinner.getSelectedItemPosition();
                        if (selectedPosition < 0 || selectedPosition >= containers.size()) return;
                        Container selectedContainer = containers.get(selectedPosition);

                        try {
                            saveManager.transferSave(save, selectedContainer);
                            loadSavesList();
                            AppUtils.showToast(getContext(), "Transfer complete");
                        } catch (IOException e) {
                            AppUtils.showToast(getContext(), "Transfer failed: " + e.getMessage());
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    private void showTransferDialog(Save save) {
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.container_selection_dialog, null);
        Spinner spinner = dialogView.findViewById(R.id.spinner_container_selection);

        List<Container> containers = containerManager.getContainers();
        ArrayAdapter<Container> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, containers);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        new androidx.appcompat.app.AlertDialog.Builder(getContext())
                .setTitle(R.string.select_container)
                .setView(dialogView)
                .setPositiveButton(R.string.transfer, (dialog, which) -> {
                    Container selectedContainer = (Container) spinner.getSelectedItem();
                    transferSaveFiles(save, selectedContainer);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void transferSaveFiles(Save save, Container container) {
        File sourceDir = new File(save.path);
        File targetDir = new File(container.getRootDir(), "xuser-" + container.id);

        boolean success = FileUtils.copy(sourceDir, targetDir);
        if (success) {
            AppUtils.showToast(getContext(), R.string.transfer_complete);
        } else {
            AppUtils.showToast(getContext(), R.string.transfer_failed);
        }
    }
}
