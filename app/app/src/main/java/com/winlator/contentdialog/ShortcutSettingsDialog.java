package com.winlator.contentdialog;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.view.Menu;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Spinner;

import androidx.preference.PreferenceManager;

import com.winlator.ContainerDetailFragment;
import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.ShortcutsFragment;
import com.winlator.box64.Box64PresetManager;
import com.winlator.box64.rc.RCManager;
import com.winlator.container.GraphicsDrivers;
import com.winlator.container.Shortcut;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.GeneralComponents;
import com.winlator.container.DXWrapperPicker;
import com.winlator.core.EnvVars;
import com.winlator.container.GraphicsDriverPicker;
import com.winlator.core.FileUtils;
import com.winlator.core.ImageUtils;
import com.winlator.core.StringUtils;
import com.winlator.core.WineUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.widget.EnvVarsView;
import com.winlator.win32.MSLink;
import com.winlator.win32.PEParser;
import com.winlator.winhandler.GamepadHandler;

import java.io.File;
import java.util.ArrayList;

public class ShortcutSettingsDialog extends ContentDialog {
    private final ShortcutsFragment fragment;
    private final Shortcut shortcut;
    private InputControlsManager inputControlsManager;

    public ShortcutSettingsDialog(ShortcutsFragment fragment, Shortcut shortcut) {
        super(fragment.getContext(), R.layout.shortcut_settings_dialog);
        this.fragment = fragment;
        this.shortcut = shortcut;
        setTitle(shortcut.name);
        setIcon(R.drawable.icon_settings);

        createContentView();
    }

    private void createContentView() {
        final Context context = fragment.getContext();
        inputControlsManager = new InputControlsManager(context);
        LinearLayout llContent = findViewById(R.id.LLContent);
        llContent.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);

        final EditText etName = findViewById(R.id.ETName);
        etName.setText(shortcut.name);

        final Bitmap[] selectedIcon = {null};
        final ImageView ivShortcutIcon = findViewById(R.id.IVShortcutIcon);
        if (shortcut.icon != null) {
            ivShortcutIcon.setImageBitmap(shortcut.icon);
        }
        else {
            ivShortcutIcon.setImageResource(shortcut.file.isDirectory() ? R.drawable.container_folder : R.drawable.container_file_link);
        }

        final View btResetIcon = findViewById(R.id.BTResetIcon);
        View.OnClickListener selectIconListener = (v) -> {
            MainActivity activity = (MainActivity) AppUtils.getActivity(context);
            if (activity == null) return;

            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            activity.setOpenFileCallback((uri) -> {
                if (uri == null) return;
                Bitmap bitmap = ImageUtils.getBitmapFromUri(context, uri, 256);
                if (bitmap != null) {
                    selectedIcon[0] = bitmap;
                    ivShortcutIcon.setImageBitmap(bitmap);
                    btResetIcon.setVisibility(View.VISIBLE);
                }
            });
            activity.startActivityForResult(intent, MainActivity.OPEN_FILE_REQUEST_CODE);
        };

        findViewById(R.id.BTSelectIcon).setOnClickListener(selectIconListener);
        ivShortcutIcon.setOnClickListener(selectIconListener);

        btResetIcon.setOnClickListener((v) -> {
            selectedIcon[0] = null;
            if (shortcut.icon != null) {
                ivShortcutIcon.setImageBitmap(shortcut.icon);
            }
            else {
                ivShortcutIcon.setImageResource(shortcut.file.isDirectory() ? R.drawable.container_folder : R.drawable.container_file_link);
            }
            btResetIcon.setVisibility(View.GONE);
        });

        final EditText etExecArgs = findViewById(R.id.ETExecArgs);
        etExecArgs.setText(shortcut.getExtra("execArgs"));

        ContainerDetailFragment.loadScreenSizeSpinner(getContentView(), shortcut.getExtra("screenSize", shortcut.container.getScreenSize()));

        final String oldGraphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig());
        String selectedGraphicsDriver = shortcut.getExtra("graphicsDriver", shortcut.container.getGraphicsDriver());
        GraphicsDriverPicker graphicsDriverPicker = new GraphicsDriverPicker(findViewById(R.id.LLGraphicsDriver), selectedGraphicsDriver, oldGraphicsDriverConfig);

        String oldDXWrapperConfig = shortcut.getExtra("dxwrapperConfig", shortcut.container.getDXWrapperConfig());
        String selectedDXWrapper = shortcut.getExtra("dxwrapper", shortcut.container.getDXWrapper());
        DXWrapperPicker dxwrapperPicker = new DXWrapperPicker(findViewById(R.id.LLDXWrapper), graphicsDriverPicker, selectedDXWrapper, oldDXWrapperConfig);

        findViewById(R.id.BTHelpDXWrapper).setOnClickListener((v) -> AppUtils.showHelpBox(context, v, R.string.dxwrapper_help_content));

        final Spinner sAudioDriver = findViewById(R.id.SAudioDriver);
        AppUtils.setSpinnerSelectionFromIdentifier(sAudioDriver, shortcut.getExtra("audioDriver", shortcut.container.getAudioDriver()));

        final View vAudioDriverConfig = findViewById(R.id.BTAudioDriverConfig);
        vAudioDriverConfig.setTag(shortcut.getExtra("audioDriverConfig", shortcut.container.getAudioDriverConfig()));
        vAudioDriverConfig.setOnClickListener((v) -> (new AudioDriverConfigDialog(v)).show());

        final CheckBox cbForceFullscreen = findViewById(R.id.CBForceFullscreen);
        cbForceFullscreen.setChecked(shortcut.getExtra("forceFullscreen", "0").equals("1"));

        final CheckBox cbFullscreenStretched = findViewById(R.id.CBFullscreenStretched);
        cbFullscreenStretched.setChecked(shortcut.getExtra("fullscreenStretched", "0").equals("1"));

        final Spinner sBox64Preset = findViewById(R.id.SBox64Preset);
        Box64PresetManager.loadSpinner(sBox64Preset, shortcut.getExtra("box64Preset", shortcut.container.getBox64Preset()));

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final String containerBox64Version = shortcut.container.getBox64Version() != null ? shortcut.container.getBox64Version() : preferences.getString("box64_version", DefaultVersion.BOX64);
        final Spinner sBox64Version = findViewById(R.id.SBox64Version);
        GeneralComponents.initViews(GeneralComponents.Type.BOX64, findViewById(R.id.Box64VersionToolbox), sBox64Version,
            shortcut.getExtra("box64Version", containerBox64Version), DefaultVersion.BOX64);

        final Spinner sRCFile = findViewById(R.id.SRCFile);
        final int[] rcfileIds = {0};
        RCManager rcManager = new RCManager(context);
        String rcfileId = shortcut.getExtra("rcfileId", String.valueOf(shortcut.container.getRCFileId()));
        RCManager.loadRCFileSpinner(rcManager, RCManager.parseRCFileId(rcfileId, shortcut.container.getRCFileId()), sRCFile, id -> {
            rcfileIds[0] = id;
        });

        final Spinner sControlsProfile = findViewById(R.id.SControlsProfile);
        loadControlsProfileSpinner(sControlsProfile, shortcut.getExtra("controlsProfile", "0"));

        final Spinner sDInputMapperType = findViewById(R.id.SDInputMapperType);
        sDInputMapperType.setSelection(Byte.parseByte(shortcut.getExtra("dinputMapperType", String.valueOf(GamepadHandler.DINPUT_MAPPER_TYPE_XINPUT))));

        final EditText etLCAll = findViewById(R.id.ETLCAll);
        if (etLCAll != null) {
            // Empty means "inherit container value" (no override).
            etLCAll.setText(shortcut.getExtra("lcAll"));
            etLCAll.setHint(containerDefaultHint(context, shortcut.container.getLCAll()));
            View btPicker = findViewById(R.id.BTLCAllPicker);
            if (btPicker != null) btPicker.setOnClickListener((v) ->
                com.winlator.core.WineLocales.showPickerDialog(context, etLCAll.getText().toString(), etLCAll::setText));
        }

        final EditText etTimezone = findViewById(R.id.ETTimezone);
        if (etTimezone != null) {
            etTimezone.setText(shortcut.getExtra("timezone"));
            etTimezone.setHint(containerDefaultHint(context, shortcut.container.getTimezone()));
            View btPicker = findViewById(R.id.BTTimezonePicker);
            if (btPicker != null) btPicker.setOnClickListener((v) ->
                com.winlator.core.WineTimezones.showPickerDialog(context, etTimezone.getText().toString(), etTimezone::setText));
        }

        ContainerDetailFragment.createWinComponentsTab(getContentView(), shortcut.getExtra("wincomponents", shortcut.container.getWinComponents()));
        final EnvVarsView envVarsView = createEnvVarsTab();

        AppUtils.setupTabLayout(getContentView(), R.id.TabLayout, R.id.LLTabWinComponents, R.id.LLTabEnvVars, R.id.LLTabAdvanced);

        findViewById(R.id.BTNameMenu).setOnClickListener((v) -> {
            File peFile = null;
            MSLink.LinkInfo linkInfo = MSLink.extractLinkInfo(shortcut.getLinkFile());
            if (linkInfo != null) peFile = new File(WineUtils.dosToUnixPath(linkInfo.targetPath, shortcut.container));
            if (peFile == null) return;

            PEParser.FileVersionInfo fileVersionInfo = PEParser.getFileVersionInfo(peFile);
            if (fileVersionInfo != null && !fileVersionInfo.FileDescription.isEmpty() &&
                                           !fileVersionInfo.OriginalFilename.isEmpty()) {
                PopupMenu popupMenu = new PopupMenu(context, v);
                Menu menu = popupMenu.getMenu();
                menu.add(fileVersionInfo.FileDescription);
                menu.add(FileUtils.getBasename(fileVersionInfo.OriginalFilename));
                popupMenu.setOnMenuItemClickListener((menuItem) -> {
                    etName.setText(String.valueOf(menuItem.getTitle()));
                    return true;
                });
                popupMenu.show();
            }
        });

        findViewById(R.id.BTExtraArgsMenu).setOnClickListener((v) -> {
            PopupMenu popupMenu = new PopupMenu(context, v);
            popupMenu.inflate(R.menu.extra_args_popup_menu);
            popupMenu.setOnMenuItemClickListener((menuItem) -> {
                String value = String.valueOf(menuItem.getTitle());
                String execArgs = etExecArgs.getText().toString();
                if (!execArgs.contains(value)) etExecArgs.setText(!execArgs.isEmpty() ? execArgs+" "+value : value);
                return true;
            });
            popupMenu.show();
        });

        setOnConfirmCallback(() -> {
            String name = etName.getText().toString().trim();
            String graphicsDriver = graphicsDriverPicker.getGraphicsDriver();
            String dxwrapper = dxwrapperPicker.getDXWrapper();
            String dxwrapperConfig = dxwrapperPicker.getDXWrapperConfig();
            String graphicsDriverConfig = graphicsDriverPicker.getGraphicsDriverConfig();
            String audioDriverConfig = vAudioDriverConfig.getTag().toString();
            String audioDriver = StringUtils.parseIdentifier(sAudioDriver.getSelectedItem());
            String screenSize = ContainerDetailFragment.getScreenSize(getContentView());

            String execArgs = etExecArgs.getText().toString();
            shortcut.putExtra("execArgs", !execArgs.isEmpty() ? execArgs : null);
            shortcut.putExtra("screenSize", !screenSize.equals(shortcut.container.getScreenSize()) ? screenSize : null);
            shortcut.putExtra("graphicsDriver", !graphicsDriver.equals(shortcut.container.getGraphicsDriver()) ? graphicsDriver : null);
            shortcut.putExtra("dxwrapper", !dxwrapper.equals(shortcut.container.getDXWrapper()) ? dxwrapper : null);
            shortcut.putExtra("dxwrapperConfig", !dxwrapperConfig.equals(shortcut.container.getDXWrapperConfig()) ? dxwrapperConfig : null);
            shortcut.putExtra("graphicsDriverConfig", !graphicsDriverConfig.equals(shortcut.container.getGraphicsDriverConfig()) ? graphicsDriverConfig : null);
            shortcut.putExtra("audioDriver", !audioDriver.equals(shortcut.container.getAudioDriver())? audioDriver : null);
            shortcut.putExtra("audioDriverConfig", !audioDriverConfig.equals(shortcut.container.getAudioDriverConfig()) ? audioDriverConfig : null);
            shortcut.putExtra("forceFullscreen", cbForceFullscreen.isChecked() ? "1" : null);
            shortcut.putExtra("fullscreenStretched", cbFullscreenStretched.isChecked() ? "1" : null);

            String wincomponents = ContainerDetailFragment.getWinComponents(getContentView());
            shortcut.putExtra("wincomponents", !wincomponents.equals(shortcut.container.getWinComponents()) ? wincomponents : null);

            String envVars = envVarsView.getEnvVars();
            shortcut.putExtra("envVars", !envVars.isEmpty() ? envVars : null);

            String box64Preset = Box64PresetManager.getSpinnerSelectedId(sBox64Preset);
            shortcut.putExtra("box64Preset", !box64Preset.equals(shortcut.container.getBox64Preset()) ? box64Preset : null);

            String box64Version = StringUtils.parseIdentifier(sBox64Version.getSelectedItem());
            shortcut.putExtra("box64Version", !box64Version.equals(containerBox64Version) ? box64Version : null);

            String containerRCFileId = String.valueOf(shortcut.container.getRCFileId());
            shortcut.putExtra("rcfileId", !String.valueOf(rcfileIds[0]).equals(containerRCFileId) ? String.valueOf(rcfileIds[0]) : null);

            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
            int controlsProfile = sControlsProfile.getSelectedItemPosition() > 0 ? profiles.get(sControlsProfile.getSelectedItemPosition()-1).id : 0;
            shortcut.putExtra("controlsProfile", controlsProfile > 0 ? String.valueOf(controlsProfile) : null);

            int dinputMapperType = sDInputMapperType.getSelectedItemPosition();
            shortcut.putExtra("dinputMapperType", dinputMapperType != GamepadHandler.DINPUT_MAPPER_TYPE_XINPUT ? String.valueOf(dinputMapperType) : null);

            final EditText etLCAllView = findViewById(R.id.ETLCAll);
            if (etLCAllView != null) {
                String lcAll = etLCAllView.getText().toString().trim();
                shortcut.putExtra("lcAll", !lcAll.isEmpty() ? lcAll : null);
            }
            final EditText etTimezoneView = findViewById(R.id.ETTimezone);
            if (etTimezoneView != null) {
                String timezone = etTimezoneView.getText().toString().trim();
                shortcut.putExtra("timezone", !timezone.isEmpty() ? timezone : null);
            }

            shortcut.saveData();
            if (selectedIcon[0] != null) {
                shortcut.setIcon(selectedIcon[0]);
                fragment.refreshContent();
            }
            if (!shortcut.name.equals(name) && !name.isEmpty()) renameShortcut(name);

            boolean requireRestart = graphicsDriver.equals(GraphicsDrivers.VORTEK) && VortekConfigDialog.isRequireRestart(oldGraphicsDriverConfig, graphicsDriverConfig);
            if (requireRestart) ContentDialog.confirm(context, R.string.the_settings_have_been_changed_do_you_want_to_restart_the_app, () -> AppUtils.restartApplication(context));
        });
    }

    private static String containerDefaultHint(Context context, String containerValue) {
        String noOverride = context.getString(R.string.default_no_override);
        if (containerValue == null || containerValue.isEmpty()) return noOverride;
        return noOverride + " (" + containerValue + ")";
    }

    private void renameShortcut(String newName) {
        newName = StringUtils.clearReservedChars(newName);
        File parent = shortcut.file.getParentFile();
        File newFile = new File(parent, newName+".desktop");
        if (!newFile.isFile()) shortcut.file.renameTo(newFile);

        File linkFile = new File(parent, shortcut.name+".lnk");
        if (linkFile.isFile()) {
            newFile = new File(parent, newName+".lnk");
            if (!newFile.isFile()) linkFile.renameTo(newFile);
        }
        fragment.refreshContent();
    }

    private EnvVarsView createEnvVarsTab() {
        final View view = getContentView();
        final Context context = view.getContext();
        final EnvVarsView envVarsView = view.findViewById(R.id.EnvVarsView);
        EnvVars envVars = new EnvVars(shortcut.getExtra("envVars"));
        // DXVK_HUD, TU_DEBUG, LC_ALL, TZ, and MESA_VK_WSI_PRESENT_MODE are managed by their configuration dialogs or settings.
        envVars.remove("DXVK_HUD");
        envVars.remove("TU_DEBUG");
        envVars.remove("LC_ALL");
        envVars.remove("TZ");
        envVars.remove("MESA_VK_WSI_PRESENT_MODE");
        envVarsView.setEnvVars(envVars);
        view.findViewById(R.id.BTAddEnvVar).setOnClickListener((v) -> (new AddEnvVarDialog(context, envVarsView)).show());
        return envVarsView;
    }

    private void loadControlsProfileSpinner(Spinner spinner, String selectedValue) {
        final Context context = fragment.getContext();
        final ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
        ArrayList<String> values = new ArrayList<>();
        values.add(context.getString(R.string.none));

        int selectedPosition = 0;
        int selectedId = Integer.parseInt(selectedValue);
        for (int i = 0; i < profiles.size(); i++) {
            ControlsProfile profile = profiles.get(i);
            if (profile.id == selectedId) selectedPosition = i + 1;
            values.add(profile.getName());
        }

        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setSelection(selectedPosition, false);
    }
}
