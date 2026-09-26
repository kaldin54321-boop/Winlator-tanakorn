package com.winlator;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.winlator.box64.Box64EditPresetDialog;
import com.winlator.box64.Box64Preset;
import com.winlator.box64.Box64PresetManager;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.Callback;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.StringUtils;
import com.winlator.core.WineInfo;
import com.winlator.core.WineInstaller;
import com.winlator.core.WineReleaseDownloader;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class StartupWizardFragment extends Fragment {
    public static final String PREF_WIZARD_COMPLETED = "startup_wizard_completed";
    private static final String PREF_WIZARD_STEP = "startup_wizard_step";
    private static final int STEP_WELCOME = 0;
    private static final int STEP_PERMISSIONS = 1;
    private static final int STEP_ROOTFS = 2;
    private static final int STEP_BOX64 = 3;
    private static final int STEP_CONTAINER = 4;
    private static final int STEP_INPUT_CONTROLS = 5;
    private static final int STEP_FINISHED = 6;
    private static final int STEP_COUNT = 7;

    private static final int MAIN_STORAGE_PERMISSION_REQUEST_CODE = 101;
    private static final int ALL_FILES_ACCESS_REQUEST_CODE = 102;

    private int currentStep = STEP_WELCOME;
    private final HashMap<Integer, View> pageViews = new HashMap<>();
    private SharedPreferences preferences;
    private boolean rootFSInstalling = false;
    private boolean rootFSInstalled = false;
    private boolean rootFSFailed = false;

    private ContainerDetailFragment containerDetailFragment;
    private InputControlsFragment inputControlsFragment;
    private Callback<Uri> selectWineFileCallback;
    private PreloaderDialog preloaderDialog;

    private TextView tvWizardStep;
    private TextView tvWizardTitle;
    private ViewGroup flWizardContent;
    private Button btPrevious;
    private Button btSkip;
    private Button btNext;

    public static boolean shouldShowWizard(Context context) {
        // Show the wizard on every launch until it has been fully completed
        // (finished or skipped). Exiting the app mid-wizard brings it back,
        // resuming at the last visited step.
        return !PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_WIZARD_COMPLETED, false);
    }

    public static void markWizardCompleted(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_WIZARD_COMPLETED, true)
            .remove(PREF_WIZARD_STEP)
            .apply();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        preloaderDialog = new PreloaderDialog(getActivity());
        preferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
        rootFSInstalled = RootFS.find(requireContext()).isValid();
        if (savedInstanceState != null) {
            currentStep = savedInstanceState.getInt("wizard_step", STEP_WELCOME);
        }
        else {
            currentStep = preferences.getInt(PREF_WIZARD_STEP, STEP_WELCOME);
            if (currentStep < STEP_WELCOME || currentStep >= STEP_COUNT) currentStep = STEP_WELCOME;
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("wizard_step", currentStep);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.startup_wizard_fragment, container, false);
        tvWizardStep = view.findViewById(R.id.TVWizardStep);
        tvWizardTitle = view.findViewById(R.id.TVWizardTitle);
        flWizardContent = view.findViewById(R.id.FLWizardContent);
        btPrevious = view.findViewById(R.id.BTWizardPrevious);
        btSkip = view.findViewById(R.id.BTWizardSkip);
        btNext = view.findViewById(R.id.BTWizardNext);

        btPrevious.setOnClickListener((v) -> goToPrevious());
        btSkip.setOnClickListener((v) -> finishWizard());
        btNext.setOnClickListener((v) -> goToNext());
        recoverChildFragments();
        showStep(currentStep);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        updateTitles();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshCurrentPage();
    }

    public boolean onBackPressed() {
        switch (currentStep) {
            case STEP_PERMISSIONS:
                showStep(STEP_WELCOME);
                return true;
            case STEP_ROOTFS:
                if (!rootFSInstalling) showStep(STEP_PERMISSIONS);
                return true;
            case STEP_CONTAINER:
                showStep(STEP_BOX64);
                return true;
            case STEP_INPUT_CONTROLS:
                showStep(STEP_CONTAINER);
                return true;
            case STEP_FINISHED:
                showStep(STEP_INPUT_CONTROLS);
                return true;
            default:
                return true;
        }
    }

    public void onMainPermissionResult(boolean granted) {
        refreshCurrentPage();
        if (!granted && currentStep == STEP_PERMISSIONS) {
            AppUtils.showToast(getContext(), R.string.wizard_rootfs_need_permission);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MAIN_STORAGE_PERMISSION_REQUEST_CODE) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            onMainPermissionResult(granted);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ALL_FILES_ACCESS_REQUEST_CODE) refreshCurrentPage();
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            try {
                if (selectWineFileCallback != null && data != null) selectWineFileCallback.call(data.getData());
            }
            catch (Exception e) {
                AppUtils.showToast(getContext(), R.string.unable_to_import_profile);
            }
            selectWineFileCallback = null;
        }
    }

    private void goToNext() {
        switch (currentStep) {
            case STEP_WELCOME:
                showStep(STEP_PERMISSIONS);
                break;
            case STEP_PERMISSIONS:
                if (!hasMainStoragePermission()) {
                    AppUtils.showToast(getContext(), R.string.wizard_need_files_permission, Toast.LENGTH_LONG);
                    break;
                }
                showStep(STEP_ROOTFS);
                break;
            case STEP_BOX64:
                saveBox64Settings();
                showStep(STEP_CONTAINER);
                break;
            case STEP_CONTAINER:
                submitContainerPage();
                break;
            case STEP_INPUT_CONTROLS:
                showStep(STEP_FINISHED);
                break;
            case STEP_FINISHED:
                finishWizard();
                break;
            default:
                break;
        }
    }

    private void goToPrevious() {
        switch (currentStep) {
            case STEP_PERMISSIONS:
                showStep(STEP_WELCOME);
                break;
            case STEP_CONTAINER:
                showStep(STEP_BOX64);
                break;
            case STEP_INPUT_CONTROLS:
                showStep(STEP_CONTAINER);
                break;
            case STEP_FINISHED:
                showStep(STEP_INPUT_CONTROLS);
                break;
            default:
                break;
        }
    }

    private void finishWizard() {
        saveBox64Settings();
        markWizardCompleted(requireContext());
        MainActivity activity = (MainActivity)getActivity();
        if (activity != null) activity.finishWizard();
    }

    private void showStep(int step) {
        currentStep = step;
        preferences.edit().putInt(PREF_WIZARD_STEP, currentStep).apply();
        flWizardContent.removeAllViews();
        View page = getPageView(step);
        // The cached page may still be attached to the previous (destroyed)
        // content container when returning via the fragment back stack
        // (e.g. from the Box64 RC Manager) — detach it first, otherwise
        // addView() throws and the app appears to exit on back press.
        if (page.getParent() instanceof ViewGroup && page.getParent() != flWizardContent) {
            ((ViewGroup) page.getParent()).removeView(page);
        }
        flWizardContent.addView(page);
        updateTitles();
        updateFooter();
        btNext.setEnabled(true);
        refreshCurrentPage();
        flWizardContent.post(this::updateTitles);
    }

    private View getPageView(int step) {
        View page = pageViews.get(step);
        if (page != null) return page;

        LayoutInflater inflater = LayoutInflater.from(getContext());
        switch (step) {
            case STEP_WELCOME:
                page = inflater.inflate(R.layout.startup_wizard_page_welcome, flWizardContent, false);
                break;
            case STEP_PERMISSIONS:
                page = inflater.inflate(R.layout.startup_wizard_page_permissions, flWizardContent, false);
                initPermissionsPage(page);
                break;
            case STEP_ROOTFS:
                page = inflater.inflate(R.layout.startup_wizard_page_rootfs, flWizardContent, false);
                initRootFSPage(page);
                break;
            case STEP_BOX64:
                page = inflater.inflate(R.layout.startup_wizard_page_box64, flWizardContent, false);
                initBox64Page(page);
                break;
            case STEP_CONTAINER:
                page = inflater.inflate(R.layout.startup_wizard_page_container, flWizardContent, false);
                initContainerPage(page);
                break;
            case STEP_INPUT_CONTROLS:
                page = inflater.inflate(R.layout.startup_wizard_page_input_controls, flWizardContent, false);
                initInputControlsPage(page);
                break;
            case STEP_FINISHED:
            default:
                page = inflater.inflate(R.layout.startup_wizard_page_finished, flWizardContent, false);
                break;
        }
        pageViews.put(step, page);
        return page;
    }

    private void updateTitles() {
        if (tvWizardStep == null || tvWizardTitle == null) return;
        tvWizardStep.setText(getString(R.string.wizard_step, currentStep + 1, STEP_COUNT));
        int titleResId;
        switch (currentStep) {
            case STEP_WELCOME: titleResId = R.string.wizard_welcome_title; break;
            case STEP_PERMISSIONS: titleResId = R.string.wizard_permissions_title; break;
            case STEP_ROOTFS: titleResId = R.string.wizard_rootfs_title; break;
            case STEP_BOX64: titleResId = R.string.wizard_box64_title; break;
            case STEP_CONTAINER: titleResId = R.string.wizard_container_title; break;
            case STEP_INPUT_CONTROLS: titleResId = R.string.wizard_input_controls_title; break;
            case STEP_FINISHED:
            default: titleResId = R.string.wizard_finished_title; break;
        }
        tvWizardTitle.setText(titleResId);
    }

    private void updateFooter() {
        switch (currentStep) {
            case STEP_WELCOME:
                btPrevious.setVisibility(View.GONE);
                btSkip.setVisibility(View.GONE);
                btNext.setVisibility(View.VISIBLE);
                btNext.setText(R.string.wizard_next);
                break;
            case STEP_PERMISSIONS:
                btPrevious.setVisibility(View.VISIBLE);
                btSkip.setVisibility(View.GONE);
                btNext.setVisibility(View.VISIBLE);
                btNext.setText(R.string.wizard_next);
                break;
            case STEP_ROOTFS:
                btPrevious.setVisibility(View.GONE);
                btSkip.setVisibility(View.GONE);
                btNext.setVisibility(View.GONE);
                break;
            case STEP_BOX64:
                btPrevious.setVisibility(View.GONE);
                btSkip.setVisibility(View.VISIBLE);
                btNext.setVisibility(View.VISIBLE);
                btNext.setText(R.string.wizard_next);
                break;
            case STEP_CONTAINER:
            case STEP_INPUT_CONTROLS:
                btPrevious.setVisibility(View.VISIBLE);
                btSkip.setVisibility(View.VISIBLE);
                btNext.setVisibility(View.VISIBLE);
                btNext.setText(R.string.wizard_next);
                break;
            case STEP_FINISHED:
            default:
                btPrevious.setVisibility(View.VISIBLE);
                btSkip.setVisibility(View.GONE);
                btNext.setVisibility(View.VISIBLE);
                btNext.setText(R.string.ok);
                break;
        }
    }

    private void refreshCurrentPage() {
        View page = pageViews.get(currentStep);
        if (page == null) return;
        if (currentStep == STEP_PERMISSIONS) refreshPermissionStatus(page);
        else if (currentStep == STEP_ROOTFS) refreshRootFSPage(page);
    }

    // Permissions page (main Files access required, All Files access optional)

    private boolean hasMainStoragePermission() {
        Context context = getContext();
        return context != null &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return hasMainStoragePermission();
        try {
            return Environment.isExternalStorageManager();
        }
        catch (Exception e) {
            return false;
        }
    }

    private void initPermissionsPage(View page) {
        page.findViewById(R.id.BTGrantMainStorage).setOnClickListener((v) -> requestMainStoragePermission());
        page.findViewById(R.id.BTGrantAllFiles).setOnClickListener((v) -> requestAllFilesAccess());
    }

    private void refreshPermissionStatus(View page) {
        TextView tvMainStatus = page.findViewById(R.id.TVMainStorageStatus);
        Button btGrantMain = page.findViewById(R.id.BTGrantMainStorage);
        TextView tvAllFilesStatus = page.findViewById(R.id.TVAllFilesStatus);
        Button btGrantAllFiles = page.findViewById(R.id.BTGrantAllFiles);
        if (tvMainStatus == null) return;

        boolean mainGranted = hasMainStoragePermission();
        tvMainStatus.setText(mainGranted ? R.string.wizard_granted : R.string.wizard_not_granted);
        btGrantMain.setEnabled(!mainGranted);

        boolean allFilesGranted = hasAllFilesAccess();
        tvAllFilesStatus.setText(allFilesGranted ? R.string.wizard_granted : R.string.wizard_not_granted);
        btGrantAllFiles.setEnabled(!allFilesGranted);
    }

    private void requestMainStoragePermission() {
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
            MAIN_STORAGE_PERMISSION_REQUEST_CODE);
    }

    private void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestMainStoragePermission();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
            startActivityForResult(intent, ALL_FILES_ACCESS_REQUEST_CODE);
        }
        catch (Exception e) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), ALL_FILES_ACCESS_REQUEST_CODE);
            }
            catch (Exception ignored) {}
        }
    }

    // RootFS page (install starts automatically, blocked without the main Files access permission)

    private void initRootFSPage(View page) {
        page.findViewById(R.id.TVRootFSStatus).setOnClickListener((v) -> {
            if (rootFSFailed) {
                rootFSFailed = false;
                refreshRootFSPage(page);
            }
            else if (!rootFSInstalling && !RootFS.find(requireContext()).isValid() && !hasMainStoragePermission()) {
                showStep(STEP_PERMISSIONS);
            }
        });
    }

    private void refreshRootFSPage(View page) {
        TextView tvStatus = page.findViewById(R.id.TVRootFSStatus);
        if (tvStatus == null) return;
        if (rootFSInstalling) return;

        rootFSInstalled = RootFS.find(requireContext()).isValid();
        if (rootFSInstalled) {
            rootFSFailed = false;
            tvStatus.setText(R.string.wizard_rootfs_installed);
            if (currentStep == STEP_ROOTFS) {
                tvStatus.postDelayed(() -> {
                    if (isAdded() && currentStep == STEP_ROOTFS && RootFS.find(requireContext()).isValid()) showStep(STEP_BOX64);
                }, 800);
            }
        }
        else if (!hasMainStoragePermission()) {
            rootFSFailed = false;
            tvStatus.setText(R.string.wizard_rootfs_need_permission);
        }
        else {
            startRootFSInstall();
        }
    }

    private void startRootFSInstall() {
        View page = pageViews.get(STEP_ROOTFS);
        MainActivity activity = (MainActivity)getActivity();
        if (page == null || activity == null || rootFSInstalling) return;

        if (!hasMainStoragePermission()) {
            refreshRootFSPage(page);
            AppUtils.showToast(getContext(), R.string.wizard_rootfs_need_permission);
            return;
        }

        rootFSInstalling = true;
        rootFSFailed = false;
        TextView tvStatus = page.findViewById(R.id.TVRootFSStatus);
        ProgressBar progressBar = page.findViewById(R.id.PBRootFSProgress);
        TextView tvProgress = page.findViewById(R.id.TVRootFSProgress);
        tvStatus.setText(R.string.installing_system_files);
        progressBar.setProgress(0);
        tvProgress.setText("0%");

        RootFSInstaller.install(activity, (progress) -> {
            if (!isAdded()) return;
            progressBar.setProgress(progress);
            tvProgress.setText(progress + "%");
        }, (success) -> {
            rootFSInstalling = false;
            if (!isAdded()) return;
            if (success) {
                rootFSInstalled = true;
                progressBar.setProgress(100);
                tvProgress.setText("100%");
                tvStatus.setText(R.string.wizard_rootfs_installed);
                tvStatus.postDelayed(() -> {
                    if (isAdded() && currentStep == STEP_ROOTFS) showStep(STEP_BOX64);
                }, 800);
            }
            else {
                rootFSFailed = true;
                tvStatus.setText(getString(R.string.unable_to_install_system_files) + "\n" + getString(R.string.wizard_tap_to_retry));
            }
        });
    }

    // Box64 & Wine page (same Box64 Preset options as Settings, plus Wine install manager like Settings)

    private void initBox64Page(View page) {
        Spinner sBox64Preset = page.findViewById(R.id.SBox64Preset);
        loadBox64PresetSpinner(page, sBox64Preset);

        View btManageRC = page.findViewById(R.id.BTManageBox64RC);
        if (btManageRC != null) {
            btManageRC.setOnClickListener((v) -> {
                saveBox64Settings();
                MainActivity activity = (MainActivity) getActivity();
                if (activity == null) return;
                activity.getSupportFragmentManager().beginTransaction()
                    .replace(R.id.FLFragmentContainer, new Box64RCFragment())
                    .addToBackStack(null)
                    .commit();
            });
        }

        Spinner sWineVersion = page.findViewById(R.id.SWineVersion);
        if (sWineVersion != null) loadWineVersionSpinner(page, sWineVersion);

        Spinner sComponentSource = page.findViewById(R.id.SComponentSource);
        if (sComponentSource != null) {
            String componentSource = preferences.getString("component_source", GeneralComponents.COMPONENT_SOURCE_BRUNO);
            int position = GeneralComponents.COMPONENT_SOURCE_WINHUB.equals(componentSource) ? 1 :
                (GeneralComponents.COMPONENT_SOURCE_AFEI.equals(componentSource) ? 2 : 0);
            if (position < sComponentSource.getAdapter().getCount()) sComponentSource.setSelection(position);
        }
    }

    private void loadBox64PresetSpinner(View page, final Spinner sBox64Preset) {
        final Context context = getContext();
        Runnable updateSpinner = () -> Box64PresetManager.loadSpinner(sBox64Preset,
            preferences.getString("box64_preset", Box64Preset.DEFAULT));

        updateSpinner.run();

        page.findViewById(R.id.BTAddBox64Preset).setOnClickListener((v) -> {
            Box64EditPresetDialog dialog = new Box64EditPresetDialog(context, null);
            dialog.setOnConfirmCallback(updateSpinner);
            dialog.show();
        });
        page.findViewById(R.id.BTEditBox64Preset).setOnClickListener((v) -> {
            Box64EditPresetDialog dialog = new Box64EditPresetDialog(context, Box64PresetManager.getSpinnerSelectedId(sBox64Preset));
            dialog.setOnConfirmCallback(updateSpinner);
            dialog.show();
        });
        page.findViewById(R.id.BTDuplicateBox64Preset).setOnClickListener((v) -> {
            Box64PresetManager.duplicatePreset(context, Box64PresetManager.getSpinnerSelectedId(sBox64Preset));
            updateSpinner.run();
            sBox64Preset.setSelection(sBox64Preset.getCount() - 1);
        });
        page.findViewById(R.id.BTRemoveBox64Preset).setOnClickListener((v) -> {
            final String presetId = Box64PresetManager.getSpinnerSelectedId(sBox64Preset);
            if (!presetId.startsWith(Box64Preset.CUSTOM)) {
                AppUtils.showToast(context, R.string.you_cannot_remove_this_preset);
                return;
            }
            ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_preset, () -> {
                Box64PresetManager.removePreset(context, presetId);
                updateSpinner.run();
            });
        });
    }

    private void saveBox64Settings() {
        View page = pageViews.get(STEP_BOX64);
        if (page == null) return;
        Spinner sBox64Preset = page.findViewById(R.id.SBox64Preset);
        if (sBox64Preset == null || sBox64Preset.getAdapter() == null) return;
        preferences.edit()
            .putString("box64_preset", Box64PresetManager.getSpinnerSelectedId(sBox64Preset))
            .apply();
        Spinner sComponentSource = page.findViewById(R.id.SComponentSource);
        if (sComponentSource != null && sComponentSource.getAdapter() != null) {
            int pos = sComponentSource.getSelectedItemPosition();
            String source = pos == 1 ? GeneralComponents.COMPONENT_SOURCE_WINHUB :
                (pos == 2 ? GeneralComponents.COMPONENT_SOURCE_AFEI : GeneralComponents.COMPONENT_SOURCE_BRUNO);
            preferences.edit().putString("component_source", source).apply();
        }
    }

    // Wine version manager (same install/remove flow as Settings, install-manager only)

    private void loadWineVersionSpinner(final View page, final Spinner sWineVersion) {
        Context context = getContext();
        if (context == null) return;
        final ArrayList<WineInfo> wineInfos = WineInstaller.getInstalledWineInfos(context);
        sWineVersion.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, wineInfos));

        View btInstall = page.findViewById(R.id.BTInstallWine);
        if (btInstall != null) btInstall.setOnClickListener((v) -> showCustomWineInstallPopup(v));
        View btRemove = page.findViewById(R.id.BTRemoveWine);
        if (btRemove != null) btRemove.setOnClickListener((v) -> {
            int position = sWineVersion.getSelectedItemPosition();
            if (position < 0 || position >= wineInfos.size()) return;
            WineInfo wineInfo = wineInfos.get(position);
            if (wineInfo != WineInfo.MAIN_WINE_INFO) {
                ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_wine_version, () -> {
                    removeInstalledWine(wineInfo, () -> {
                        View currentPage = pageViews.get(STEP_BOX64);
                        if (currentPage != null) {
                            Spinner spinner = currentPage.findViewById(R.id.SWineVersion);
                            if (spinner != null) loadWineVersionSpinner(currentPage, spinner);
                        }
                    });
                });
            }
        });
    }

    private void removeInstalledWine(WineInfo wineInfo, Runnable onSuccess) {
        final Activity activity = getActivity();
        if (activity == null) return;
        ContainerManager manager = new ContainerManager(activity);
        ArrayList<Container> containers = manager.getContainers();
        for (Container container : containers) {
            if (container.getWineVersion().equals(wineInfo.identifier())) {
                AppUtils.showToast(activity, R.string.unable_to_remove_this_wine_version);
                return;
            }
        }
        File installedWineDir = RootFS.find(activity).getInstalledWineDir();
        String identifier = wineInfo.identifier();
        String patternSuffix = identifier.startsWith("wine-") ? identifier.substring(5) : wineInfo.fullVersion();
        File wineDir = wineInfo.path != null ? new File(wineInfo.path) : new File(installedWineDir, identifier);
        File containerPatternFile = new File(installedWineDir, "container-pattern-" + patternSuffix + ".tzst");
        File legacyPatternFile = new File(installedWineDir, "container-pattern-" + wineInfo.fullVersion() + ".tzst");

        ArrayList<File> targets = new ArrayList<>();
        if (wineDir.isDirectory()) targets.add(wineDir);
        if (containerPatternFile.isFile()) targets.add(containerPatternFile);
        if (!legacyPatternFile.equals(containerPatternFile) && legacyPatternFile.isFile()) targets.add(legacyPatternFile);
        if (targets.isEmpty()) {
            if (onSuccess != null) onSuccess.run();
            return;
        }
        preloaderDialog.show(R.string.removing_wine);
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean ok = true;
            for (File target : targets) ok &= FileUtils.delete(target);
            for (File target : targets) ok &= !target.exists();
            final boolean success = ok;
            final Activity uiActivity = getActivity();
            preloaderDialog.closeOnUiThread();
            if (uiActivity == null) return;
            uiActivity.runOnUiThread(() -> {
                AppUtils.showToast(uiActivity, success ? R.string.wine_version_removed : R.string.unable_to_remove_this_wine_version);
                if (success && onSuccess != null) onSuccess.run();
            });
        });
    }

    private void showCustomWineInstallPopup(View anchor) {
        Context context = getContext();
        if (context == null) return;
        PopupMenu popupMenu = new PopupMenu(context, anchor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) popupMenu.setForceShowIcon(true);
        popupMenu.inflate(R.menu.open_file_popup_menu);
        popupMenu.setOnMenuItemClickListener((menuItem) -> {
            int itemId = menuItem.getItemId();
            if (itemId == R.id.menu_item_open_file) {
                selectCustomWineFileForInstall();
            }
            else if (itemId == R.id.menu_item_download_file) {
                downloadCustomWineFromReleases(WineReleaseDownloader.SOURCE_OFFICIAL);
            }
            else if (itemId == R.id.menu_item_download_kron4ek) {
                downloadCustomWineFromReleases(WineReleaseDownloader.SOURCE_KRON4EK);
            }
            return true;
        });
        popupMenu.show();
    }

    private void selectCustomWineFileForInstall() {
        final Context context = getContext();
        if (context == null || getActivity() == null) return;
        selectWineFileCallback = (uri) -> {
            if (uri == null) return;
            preloaderDialog.show(R.string.extracting_wine);
            WineInstaller.extractCustomWinePackageAsync(context, uri, null, (stagedInfo) -> {
                final WineInfo result = stagedInfo;
                final Activity activity = getActivity();
                if (activity == null) return;
                activity.runOnUiThread(() -> {
                    preloaderDialog.close();
                    if (result == null) {
                        AppUtils.showToast(context, R.string.unable_to_install_wine);
                        return;
                    }
                    showCustomWineInstallDialog(result);
                });
            });
        };
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_FILE_REQUEST_CODE);
    }

    private void downloadCustomWineFromReleases(int source) {
        final Activity activity = getActivity();
        if (activity == null) return;
        WineReleaseDownloader.showWineDownloadDialog(activity, source, (whpFile, versionHint) -> {
            preloaderDialog.show(R.string.extracting_wine);
            final Context context = getContext();
            if (context == null) return;
            WineInstaller.installCustomWHPFileAsync(context, whpFile, versionHint, (stagedInfo) -> {
                FileUtils.delete(whpFile);
                final WineInfo result = stagedInfo;
                activity.runOnUiThread(() -> {
                    preloaderDialog.close();
                    if (result == null) {
                        AppUtils.showToast(context, R.string.unable_to_install_wine);
                        return;
                    }
                    showCustomWineInstallDialog(result);
                });
            });
        });
    }

    private void installStagedCustomWine(final WineInfo stagedInfo) {
        final Context context = getContext();
        if (context == null) return;
        preloaderDialog.show(R.string.installing_wine);
        WineInstaller.installStagedCustomWineAsync(context, stagedInfo, (installedInfo) -> {
            final WineInfo result = installedInfo;
            final Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                preloaderDialog.close();
                if (result == null) {
                    AppUtils.showToast(context, R.string.unable_to_install_wine);
                    return;
                }
                if (result.path != null && !WineInstaller.hasBuiltInWoW64(new File(result.path), result.identifier())) {
                    AppUtils.showToast(context, R.string.custom_wine_no_32bit_support);
                }
                View page = pageViews.get(STEP_BOX64);
                if (page != null) {
                    Spinner spinner = page.findViewById(R.id.SWineVersion);
                    if (spinner != null) loadWineVersionSpinner(page, spinner);
                }
            });
        });
    }

    private void showCustomWineInstallDialog(final WineInfo stagedInfo) {
        Context context = getContext();
        if (context == null) return;
        if (stagedInfo == null || stagedInfo.path == null
            || !WineInstaller.is64BitCapableWineRoot(new File(stagedInfo.path))) {
            AppUtils.showToast(context, R.string.custom_wine_requires_64bit);
            WineInstaller.discardStagedCustomWine(context);
            return;
        }
        if (!WineInstaller.isInstalledWineRootValid(new File(stagedInfo.path))) {
            AppUtils.showToast(context, R.string.custom_wine_corrupted);
            WineInstaller.discardStagedCustomWine(context);
            return;
        }
        ContentDialog dialog = new ContentDialog(context, R.layout.wine_install_dialog);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setTitle(R.string.install_wine);
        dialog.setIcon(R.drawable.icon_wine);

        EditText etVersion = dialog.findViewById(R.id.ETVersion);
        etVersion.setText("Wine " + stagedInfo.version + (stagedInfo.subversion != null ? " (" + stagedInfo.subversion + ")" : ""));

        final EditText etSize = dialog.findViewById(R.id.ETSize);
        final AtomicLong totalSizeRef = new AtomicLong();
        FileUtils.getSizeAsync(new File(stagedInfo.path), (size) -> {
            totalSizeRef.addAndGet(size);
            etSize.post(() -> etSize.setText(StringUtils.formatBytes(totalSizeRef.get())));
        });

        dialog.setOnConfirmCallback(() -> installStagedCustomWine(stagedInfo));
        dialog.setOnCancelCallback(() -> WineInstaller.discardStagedCustomWine(context));
        dialog.show();
    }

    // Container page (same UI as New Container, embedded as a child fragment)

    private ContainerDetailFragment.OnContainerCreatedListener containerCreatedListener = (container) -> {
        if (!isAdded()) return;
        btNext.setEnabled(true);
        if (container != null && currentStep == STEP_CONTAINER) showStep(STEP_INPUT_CONTROLS);
    };

    private void recoverChildFragments() {
        Fragment existingContainer = getChildFragmentManager().findFragmentById(R.id.FLContainerHost);
        if (existingContainer instanceof ContainerDetailFragment) {
            containerDetailFragment = (ContainerDetailFragment)existingContainer;
            containerDetailFragment.setOnContainerCreatedListener(containerCreatedListener);
            containerDetailFragment.setConfirmButtonVisible(false);
        }
        Fragment existingInputControls = getChildFragmentManager().findFragmentById(R.id.FLInputControlsHost);
        if (existingInputControls instanceof InputControlsFragment) inputControlsFragment = (InputControlsFragment)existingInputControls;
    }

    private void initContainerPage(View page) {
        if (containerDetailFragment == null) {
            containerDetailFragment = new ContainerDetailFragment();
            containerDetailFragment.setOnContainerCreatedListener(containerCreatedListener);
            containerDetailFragment.setConfirmButtonVisible(false);
            getChildFragmentManager().beginTransaction()
                .replace(R.id.FLContainerHost, containerDetailFragment)
                .commit();
        }
    }

    private void submitContainerPage() {
        if (containerDetailFragment != null && containerDetailFragment.isAdded() && containerDetailFragment.submitContainer()) {
            btNext.setEnabled(false);
        }
    }

    // Input controls page (same options as the Input Controls menu, embedded as a child fragment)

    private void initInputControlsPage(View page) {
        if (inputControlsFragment == null) {
            inputControlsFragment = new InputControlsFragment(0);
            getChildFragmentManager().beginTransaction()
                .replace(R.id.FLInputControlsHost, inputControlsFragment)
                .commit();
        }
    }
}
