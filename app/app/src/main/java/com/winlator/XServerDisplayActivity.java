package com.winlator;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.alsaserver.ALSAClient;
import com.winlator.box64.rc.RCManager;
import com.winlator.container.AudioDrivers;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ActiveWindowsDialog;
import com.winlator.contentdialog.AudioDriverConfigDialog;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.DXVKConfigDialog;
import com.winlator.contentdialog.VegasConfigDialog;
import com.winlator.contentdialog.DebugDialog;
import com.winlator.contentdialog.ScreenEffectDialog;
import com.winlator.contentdialog.TurnipConfigDialog;
import com.winlator.contentdialog.VKD3DConfigDialog;
import com.winlator.contentdialog.XServerMenuDialog;
import com.winlator.container.HUDConfig;
import com.winlator.contentdialog.HUDConfigDialog;
import com.winlator.contentdialog.VirGLConfigDialog;
import com.winlator.contentdialog.VortekConfigDialog;
import com.winlator.contentdialog.WineAppsDialog;
import com.winlator.contentdialog.WineD3DConfigDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.KeyValueSet;
import com.winlator.core.LocaleHelper;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.ProcessHelper;
import com.winlator.core.StringUtils;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.Win32AppWorkarounds;
import com.winlator.core.WineInfo;
import com.winlator.core.WineInstaller;
import com.winlator.core.WineServerDirPatcher;
import com.winlator.core.WineRegistryEditor;
import com.winlator.core.WineStartMenuCreator;
import com.winlator.core.WineThemeManager;
import com.winlator.core.WineUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.math.Mathf;
import com.winlator.box64.Box64Utils;
import com.winlator.renderer.GLRenderer;
import com.winlator.services.ForegroundService;
import com.winlator.widget.FrameRating;
import com.winlator.widget.GameHubLoadingView;
import com.winlator.widget.InputControlsView;
import com.winlator.widget.MagnifierView;
import com.winlator.widget.QuickHUDView;
import com.winlator.widget.TouchpadView;
import com.winlator.widget.XServerView;
import com.winlator.winhandler.TaskManagerDialog;
import com.winlator.winhandler.WinHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.ALSAServerComponent;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.xenvironment.components.NetworkInfoUpdateComponent;
import com.winlator.xenvironment.components.PulseAudioComponent;
import com.winlator.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.xenvironment.components.VirGLRendererComponent;
import com.winlator.xenvironment.components.VortekRendererComponent;
import com.winlator.xenvironment.components.XServerComponent;
import com.winlator.xserver.Atom;
import com.winlator.xserver.Property;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.Executors;

public class XServerDisplayActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    private FrameLayout rootView;
    private XServerView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private RootFS rootFS;
    private FrameRating frameRating;
    private Runnable editInputControlsCallback;
    private Shortcut shortcut;
    private String[] graphicsDriver = {GraphicsDrivers.DEFAULT_VULKAN_DRIVER, GraphicsDrivers.DEFAULT_OPENGL_DRIVER};
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private ScreenInfo screenInfo = new ScreenInfo(Container.DEFAULT_SCREEN_SIZE);
    private KeyValueSet[] dxwrapperConfig;
    private KeyValueSet[] graphicsDriverConfig = {new KeyValueSet(), new KeyValueSet()};
    private KeyValueSet audioDriverConfig;
    private String wincomponents;
    private WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private EnvVars overrideEnvVars;
    private ClipboardManager clipboardManager;
    private SharedPreferences preferences;
    private final WinHandler winHandler = new WinHandler(this);
    private float globalCursorSpeed = 1.0f;
    private boolean capturePointerOnExternalMouse = true;
    private MagnifierView magnifierView;
    private DebugDialog debugDialog;
    public int frameRatingWindowId = -1;
    private Win32AppWorkarounds win32AppWorkarounds;
    private String screenEffectProfile;
    public com.winlator.widget.FrameGenerationView frameGenerationView;
    private GameHubLoadingView gameHubLoadingView;
    private boolean isPortraitOrientation = false;
    private static final long TOUCH_CONTROLS_TIMEOUT_MS = 5000;
    private final Handler touchControlsTimeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable touchControlsTimeoutRunnable;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
        setContentView(R.layout.xserver_display_activity);
        ForegroundService.startSession(this);

        final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useAndroidClipboardOnWine = preferences.getBoolean("use_android_clipboard_on_wine", false);
        clipboardManager = useAndroidClipboardOnWine ? (ClipboardManager)getSystemService(CLIPBOARD_SERVICE) : null;

        drawerLayout = findViewById(R.id.DrawerLayout);
        drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        NavigationView navigationView = findViewById(R.id.NavigationView);
        ProcessHelper.removeAllDebugCallbacks();
        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false) || preferences.getInt("box64_logs", 0) >= 1;
        if (enableLogs) ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
        Menu menu = navigationView.getMenu();
        menu.findItem(R.id.menu_item_logs).setVisible(enableLogs);
        navigationView.setNavigationItemSelectedListener(this);

        rootFS = RootFS.find(this);

        if (!isGenerateWineprefix()) {
            ContainerManager containerManager = new ContainerManager(this);
            container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));
            containerManager.activateContainer(container);

            boolean wineprefixNeedsUpdate = container.getExtra("wineprefixNeedsUpdate").equals("t");
            if (wineprefixNeedsUpdate) {
                preloaderDialog.show(R.string.updating_system_files);
                WineUtils.updateWineprefix(this, (status) -> {
                    if (status == 0) {
                        container.putExtra("wineprefixNeedsUpdate", null);
                        container.putExtra("wincomponents", null);
                        container.saveData();
                        AppUtils.restartActivity(this);
                    }
                    else finish();
                });
                return;
            }

            win32AppWorkarounds = new Win32AppWorkarounds(this);

            String wineVersion = container.getWineVersion();
            wineInfo = WineInfo.fromIdentifier(this, wineVersion);

            if (wineInfo != WineInfo.MAIN_WINE_INFO) rootFS.setWinePath(wineInfo.path);

            String shortcutPath = getIntent().getStringExtra("shortcut_path");
            if (shortcutPath != null && !shortcutPath.isEmpty()) shortcut = new Shortcut(container, new File(shortcutPath));

            String graphicsDriver = container.getGraphicsDriver();
            audioDriver = container.getAudioDriver();
            String dxwrapper = container.getDXWrapper();
            wincomponents = container.getWinComponents();
            String dxwrapperConfig = container.getDXWrapperConfig();
            String graphicsDriverConfig = container.getGraphicsDriverConfig();
            audioDriverConfig = new KeyValueSet(container.getAudioDriverConfig());
            screenInfo = new ScreenInfo(container.getScreenSize());

            if (shortcut != null) {
                graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
                audioDriver = shortcut.getExtra("audioDriver", container.getAudioDriver());
                dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
                wincomponents = shortcut.getExtra("wincomponents", container.getWinComponents());
                dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
                graphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
                audioDriverConfig = new KeyValueSet(shortcut.getExtra("audioDriverConfig", container.getAudioDriverConfig()));
                screenInfo = new ScreenInfo(shortcut.getExtra("screenSize", container.getScreenSize()));

                String dinputMapperType = shortcut.getExtra("dinputMapperType");
                if (!dinputMapperType.isEmpty()) winHandler.gamepadHandler.setDInputMapperType(Byte.parseByte(dinputMapperType));

                win32AppWorkarounds.applyStartupWorkarounds(!shortcut.wmClass.isEmpty() ? shortcut.wmClass : shortcut.path);
            }
            else {
                Intent intent = getIntent();
                if (intent.hasExtra("exec_path")) win32AppWorkarounds.applyStartupWorkarounds(FileUtils.getName(intent.getStringExtra("exec_path")));
            }

            this.graphicsDriver = GraphicsDrivers.parseIdentifiers(graphicsDriver);
            this.graphicsDriverConfig = GraphicsDrivers.parseConfigs(graphicsDriver, graphicsDriverConfig);
            if (shortcut != null) {
                // A per-shortcut graphicsDriverConfig override is stored as one
                // whole string and shadows the container value completely. An
                // override saved before a new config key existed (e.g. the
                // VirGL "version" key added with the 23.1.9/25.0.7 picker)
                // would otherwise pin the old selection forever: editing the
                // container's VirGL version would never take effect for that
                // shortcut and the guest would keep running the previous Mesa
                // with no hint why. Merge per-key instead: shortcut-diverged
                // keys win, keys the shortcut predates inherit the container.
                // Merging is scoped to matching drivers per slot so keys can
                // never leak across different drivers sharing a slot.
                String[] containerDrivers = GraphicsDrivers.parseIdentifiers(container.getGraphicsDriver());
                KeyValueSet[] containerConfigs = GraphicsDrivers.parseConfigs(
                    container.getGraphicsDriver(), container.getGraphicsDriverConfig());
                for (int i = 0; i < this.graphicsDriverConfig.length && i < containerConfigs.length
                        && i < this.graphicsDriver.length && i < containerDrivers.length; i++) {
                    if (!this.graphicsDriver[i].equals(containerDrivers[i])) continue;
                    KeyValueSet merged = new KeyValueSet(containerConfigs[i].toString());
                    for (String[] kv : this.graphicsDriverConfig[i]) {
                        String key = kv[0] != null ? kv[0] : "";
                        String value = kv.length > 1 && kv[1] != null ? kv[1] : "";
                        if (!key.isEmpty()) {
                            if (key.equals("version") && (value.equals(DefaultVersion.VIRGL) || value.equals(DefaultVersion.TURNIP))) continue;
                            merged.put(key, value);
                        }
                    }
                    this.graphicsDriverConfig[i] = merged;
                }
            }
            this.dxwrapper = DXWrappers.parseIdentifier(dxwrapper);
            this.dxwrapperConfig = DXWrappers.parseConfigs(dxwrapper, dxwrapperConfig);
        }

        // Both shortcut and container launches use the GameHub-style launching
        // overlay (shown after setupUI below) instead of the preloader dialog.
        // The preloader remains only for prefix generation / system-file update.
        if (shortcut == null && container == null) {
            preloaderDialog.show(R.string.starting_up);
        }

        inputControlsManager = new InputControlsManager(this);
        xServer = new XServer(this, screenInfo);
        xServer.setWinHandler(winHandler);
        final boolean[] flags = {false, shortcut != null || getIntent().hasExtra("exec_path")};
        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {
                if (window.id == frameRatingWindowId) frameRating.update();
            }

            @Override
            public void onMapWindow(Window window) {
                if (!flags[0] && window.isRenderable() && !window.getClassName().isEmpty()) {
                    xServerView.getRenderer().setCursorVisible(true);
                    preloaderDialog.closeOnUiThread();
                    hideGameHubLoadingOnUiThread();
                    flags[0] = true;
                }

                if (flags[1] && window.attributes.isViewable() && window.isDesktopWindow()) {
                    window.attributes.setViewable(false);
                    if (window.attributes.isEnabled()) window.disableAllDescendants();
                }

                if (win32AppWorkarounds != null) win32AppWorkarounds.applyWindowWorkarounds(window);
                changeFrameRatingVisibility(window, true);
            }

            @Override
            public void onUnmapWindow(Window window) {
                changeFrameRatingVisibility(window, false);
            }
        });

        setupUI();

        // Both launch types are covered by the GameHub overlay from here on:
        // it stays up while the XServer / Wine services initialize and is
        // dismissed only when the first window actually maps (see onMapWindow
        // above), so a launch that never produces a window keeps it up instead
        // of going black. Shortcuts show their icon + name; container launches
        // show the desktop outline icon with the container-desktop text.
        if (shortcut != null) {
            showGameHubLoading(shortcut.icon,
                getString(R.string.launching_game_name, shortcut.name),
                resolveLaunchControllerName());
        }
        else if (container != null) {
            GameHubLoadingView overlay = showGameHubLoading(null,
                getString(R.string.launching_container_desktop), null);
            if (overlay != null) overlay.setGameIconResource(R.drawable.ic_desktop_logo);
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            if (!isGenerateWineprefix()) {
                setupWineSystemFiles();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
            }
            setupXEnvironment();
        });
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        if (container == null) return;

        int containerId = intent.getIntExtra("container_id", 0);
        if (container.id == containerId) {
            String shortcutPath = intent.getStringExtra("shortcut_path");
            Shortcut newShortcut = (shortcutPath != null && !shortcutPath.isEmpty()) ? new Shortcut(container, new File(shortcutPath)) : null;

            String execPath = null;
            String execArgs = "";

            if (newShortcut != null) {
                execArgs = newShortcut.getExtra("execArgs");
                execArgs = !execArgs.isEmpty() ? " " + execArgs : "";

                if (newShortcut.isLinkPath()) {
                    if (winHandler != null) winHandler.exec(newShortcut.path + execArgs);
                    return;
                }
                else execPath = newShortcut.path;
            }
            else if (intent.hasExtra("exec_path")) {
                execPath = WineUtils.unixToDOSPath(intent.getStringExtra("exec_path"), container);
            }

            if (execPath != null && !execPath.isEmpty()) {
                String execDir = FileUtils.getDirname(execPath);
                String filename = FileUtils.getName(execPath);
                int dotIndex, spaceIndex;
                if ((dotIndex = filename.lastIndexOf(".")) != -1 && (spaceIndex = filename.indexOf(" ", dotIndex)) != -1) {
                    execArgs = filename.substring(spaceIndex + 1) + execArgs;
                    filename = filename.substring(0, spaceIndex);
                }

                if (winHandler != null) {
                    if (!execDir.isEmpty()) {
                        winHandler.exec("C:\\windows\\winhandler.exe", "/dir \"" + StringUtils.removeEndSlash(execDir) + "\" \"" + execPath + "\"" + execArgs);
                    }
                    else {
                        winHandler.exec("C:\\windows\\winhandler.exe", "\"" + filename + "\"" + execArgs);
                    }
                }
            }
        }
        else {
            AppUtils.restartActivity(this);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (editInputControlsCallback != null) {
                editInputControlsCallback.run();
                editInputControlsCallback = null;
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            if (capturePointerOnExternalMouse) touchpadView.requestPointerCapture();

            if (winHandler != null && clipboardManager != null && clipboardManager.hasPrimaryClip()) {
                ClipData primaryClip = clipboardManager.getPrimaryClip();
                if (primaryClip != null && primaryClip.getItemCount() > 0) {
                    winHandler.setClipboardData(primaryClip.getItemAt(0).getText().toString());
                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (environment != null) {
            xServerView.onResume();
            environment.onResume();
        }
        ForegroundService.onResumeSession(this);
    }

    @Override
    public void onPause() {
        ForegroundService.onPauseSession(this);
        super.onPause();
        if (environment != null && !isInPictureInPictureMode()) {
            environment.onPause();
            xServerView.onPause();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        ForegroundService.setPipMode(isInPictureInPictureMode);
    }

    @Override
    protected void onDestroy() {
        hideGameHubLoading();
        winHandler.stop();
        if (environment != null) environment.stopEnvironmentComponents();
        ForegroundService.stopSession(this);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (environment != null) {
            showXServerMenuDialog();
        }
    }

    public void showXServerMenuDialog() {
        (new XServerMenuDialog(this)).show();
    }

    /**
     * Shows the GameHub-style launching overlay (Windows logo <-> game icon,
     * "Launching game..." status, progress bar) on top of the XServer surface.
     * Called on the UI thread after the shortcut preloader dialog, while the
     * XServer / Wine services initialize and the game window has not appeared
     * yet. Safe to call when the overlay is already showing (keeps the first).
     *
     * @param controllerName name of the input-controls profile selected in the
     *     shortcut settings; the controller pill is hidden when null/empty.
     * @return the overlay, or null if it could not be shown.
     */
    public GameHubLoadingView showGameHubLoading(Bitmap gameIcon, CharSequence status, String controllerName) {
        if (rootView == null || gameHubLoadingView != null) return gameHubLoadingView;
        try {
            gameHubLoadingView = new GameHubLoadingView(this, gameIcon,
                status != null ? status : getString(R.string.launching_game),
                controllerName != null && !controllerName.isEmpty() ? controllerName : null);
            rootView.addView(gameHubLoadingView);
            gameHubLoadingView.playEntranceAnimation();
        }
        catch (Exception e) {
            gameHubLoadingView = null;
        }
        return gameHubLoadingView;
    }

    /**
     * The Box64 version this session will actually install and run, resolved
     * shortcut override -&gt; container -&gt; global and then passed through
     * the Wine-version compatibility guard. Centralized so the launcher, the
     * wineserver-patcher smoke test and the HUD never disagree about the
     * effective version.
     *
     * @param notify show a toast when the compatibility guard replaces an
     *     explicitly requested version (launcher path); silent otherwise
     *     (HUD paths, which run before/after the launcher toast).
     */
    private String resolveSessionBox64Version(boolean notify) {
        String containerBox64Version = container != null && container.getBox64Version() != null
            ? container.getBox64Version() : preferences.getString("box64_version", DefaultVersion.BOX64);
        String requested = shortcut != null ? shortcut.getExtra("box64Version", containerBox64Version) : containerBox64Version;
        WineInfo info = wineInfo;
        if (info == null && container != null) {
            try {
                info = WineInfo.fromIdentifier(this, container.getWineVersion());
            }
            catch (Exception ignored) {}
        }
        int wineMajor = info != null ? WineInstaller.wineMajorVersion(info.version) : -1;
        String requestedNorm = Box64Utils.normalizeBox64Version(requested);
        String compatible = Box64Utils.resolveWineCompatibleBox64Version(requested, wineMajor);
        // Wine 11.x loaders crash box64 0.4.4 at startup (instant bounce back
        // to the main menu with no guest output): the guard above falls back
        // to the bundled 0.4.0 for those sessions so the container boots.
        if (notify && !requestedNorm.isEmpty() && !compatible.equals(requestedNorm)) {
            AppUtils.showToast(this, getString(R.string.box64_wine11_fallback,
                requestedNorm, info != null ? info.version : "?", compatible));
        }
        return compatible;
    }

    /**
     * Returns the name of the input-controls profile chosen in the shortcut
     * settings, or null when none is selected (pill stays hidden then).
     */
    private String resolveLaunchControllerName() {
        if (shortcut == null || inputControlsManager == null) return null;
        try {
            String profileId = shortcut.getExtra("controlsProfile");
            if (profileId == null || profileId.isEmpty()) return null;
            int id = Integer.parseInt(profileId);
            if (id <= 0) return null;
            com.winlator.inputcontrols.ControlsProfile profile = inputControlsManager.getProfile(id);
            return profile != null ? profile.getName() : null;
        }
        catch (Exception e) {
            return null;
        }
    }

    /** Dismisses the launching overlay; only called once a game window maps. */
    public void hideGameHubLoading() {
        if (gameHubLoadingView == null) return;
        try {
            gameHubLoadingView.dismissAnimated();
        }
        catch (Exception e) {}
        gameHubLoadingView = null;
    }

    public void hideGameHubLoadingOnUiThread() {
        runOnUiThread(this::hideGameHubLoading);
    }

    public void toggleOrientation() {
        isPortraitOrientation = !isPortraitOrientation;
        setRequestedOrientation(isPortraitOrientation
            ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        AppUtils.showToast(this, getString(R.string.toggle_orientation) + ": " +
            (isPortraitOrientation ? getString(R.string.vertical) : getString(R.string.horizontal)));
    }

    public void showMagnifier() {
        final GLRenderer renderer = xServerView != null ? xServerView.getRenderer() : null;
        if (renderer == null) return;
        if (magnifierView == null) {
            final FrameLayout container = findViewById(R.id.FLXServerDisplay);
            magnifierView = new MagnifierView(this);
            magnifierView.setZoomButtonCallback((value) -> {
                renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                magnifierView.setZoomValue(renderer.getMagnifierZoom());
            });
            magnifierView.setZoomValue(renderer.getMagnifierZoom());
            magnifierView.setHideButtonCallback(() -> {
                container.removeView(magnifierView);
                magnifierView = null;
            });
            container.addView(magnifierView);
        }
    }

    public void enterPipMode() {
        try {
            PictureInPictureParams pipParams = (new PictureInPictureParams.Builder())
                .setAspectRatio(screenInfo.aspectRatio())
                .build();
            enterPictureInPictureMode(pipParams);
        }
        catch (Exception e) {
            android.util.Log.e("XServerDisplayActivity", "Unable to enter PiP mode", e);
        }
    }

    public void showTouchpadHelp() {
        showTouchpadHelpDialog();
    }

    private void scheduleTouchControlsTimeout() {
        cancelTouchControlsTimeout();
        if (!isTouchControlsTimeoutEnabled()) return;
        touchControlsTimeoutRunnable = () -> {
            // Temporarily hide only the controller overlay; the selected
            // profile stays active so it can reappear on the next screen press.
            if (inputControlsView != null && inputControlsView.getProfile() != null
                && inputControlsView.getVisibility() == View.VISIBLE) hideInputControlsTemporarily();
        };
        touchControlsTimeoutHandler.postDelayed(touchControlsTimeoutRunnable, TOUCH_CONTROLS_TIMEOUT_MS);
    }

    private void cancelTouchControlsTimeout() {
        if (touchControlsTimeoutRunnable != null) {
            touchControlsTimeoutHandler.removeCallbacks(touchControlsTimeoutRunnable);
            touchControlsTimeoutRunnable = null;
        }
    }

    private boolean isTouchControlsTimeoutEnabled() {
        return preferences != null && preferences.getBoolean("touch_controls_timeout", false);
    }

    private void hideInputControlsTemporarily() {
        if (inputControlsView != null && inputControlsView.getProfile() != null) {
            inputControlsView.setVisibility(View.GONE);
        }
    }

    private void showInputControlsTemporarily() {
        if (inputControlsView != null && inputControlsView.getProfile() != null
            && inputControlsView.getVisibility() != View.VISIBLE) {
            inputControlsView.setVisibility(View.VISIBLE);
            inputControlsView.invalidate();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // Touch Controls Timeout: any screen press brings the hidden
        // controller back and restarts the idle countdown. The selected
        // profile is kept, so controls are never switched to Disabled.
        if (event.getAction() == MotionEvent.ACTION_DOWN && isTouchControlsTimeoutEnabled()
            && inputControlsView != null && inputControlsView.getProfile() != null) {
            showInputControlsTemporarily();
            scheduleTouchControlsTimeout();
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        final GLRenderer renderer = xServerView.getRenderer();
        switch (item.getItemId()) {
            case R.id.menu_item_keyboard:
                AppUtils.showKeyboard(this);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_input_controls:
                showInputControlsDialog();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_toggle_fullscreen:
                renderer.toggleFullscreen();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_toggle_orientation:
                toggleOrientation();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_soft_stretch:
                renderer.toggleSoftStretch();
                AppUtils.showToast(this, getString(R.string.soft_stretch) + ": " +
                    (renderer.isSoftStretch() ? getString(R.string.enable) : getString(R.string.disable)));
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_wine_apps:
                (new WineAppsDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_task_manager:
                (new TaskManagerDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_active_windows:
                (new ActiveWindowsDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_magnifier:
                if (magnifierView == null) {
                    final FrameLayout container = findViewById(R.id.FLXServerDisplay);
                    magnifierView = new MagnifierView(this);
                    magnifierView.setZoomButtonCallback((value) -> {
                        renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                        magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    });
                    magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    magnifierView.setHideButtonCallback(() -> {
                        container.removeView(magnifierView);
                        magnifierView = null;
                    });
                    container.addView(magnifierView);
                }
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_screen_effect:
                (new ScreenEffectDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_pip_mode:
                PictureInPictureParams pipParams = (new PictureInPictureParams.Builder())
                    .setAspectRatio(screenInfo.aspectRatio())
                    .build();
                enterPictureInPictureMode(pipParams);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_logs:
                debugDialog.show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_touchpad_help:
                showTouchpadHelpDialog();
                break;
            case R.id.menu_item_exit:
                exit();
                break;
        }
        return true;
    }

    public SharedPreferences getPreferences() {
        return preferences;
    }

    public void exit() {
        winHandler.stop();
        if (environment != null) environment.stopEnvironmentComponents();

        Intent intent = getIntent();
        if (intent.hasExtra("exec_path")) {
            AppUtils.RestartApplicationOptions options = new AppUtils.RestartApplicationOptions();
            options.containerId = container.id;
            options.startPath = FileUtils.getDirname(intent.getStringExtra("exec_path"));
            AppUtils.restartApplication(this, options);
        }
        else AppUtils.restartApplication(this);
        ForegroundService.stopSession(this);
    }

    private void setupWineSystemFiles() {
        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String rfsVersion = String.valueOf(rootFS.getVersion());
        boolean containerDataChanged = false;

        // A prefix whose registries are missing/empty/invalid makes wine abort
        // instantly ("not a valid registry file" / "32-bit installation").
        // Re-extract the container pattern to heal it, then force the regular
        // system-file patch pass below so tweaks are re-applied onto the
        // pristine registries.
        if (ensureValidWineprefix()) {
            container.putExtra("appVersion", null);
            container.putExtra("rfsVersion", null);
            containerDataChanged = true;
        }

        boolean wineprefixWasUpdated = WineUtils.isWineprefixWasUpdated(container);
        if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("rfsVersion").equals(rfsVersion) || wineprefixWasUpdated) {
            applyGeneralPatches(container);
            container.putExtra("appVersion", appVersion);
            container.putExtra("rfsVersion", rfsVersion);
            containerDataChanged = true;
        }

        if (verifyUserRegistry()) containerDataChanged = true;
        // The virtual gamepad is enumerated by wine's HID stack, so repair
        // anything that hides it: disabled winebus/winehid/PlugPlay services
        // (ESSENTIAL/AGGRESSIVE startup used to disable PlugPlay), stale
        // DirectInput "override" values, and a missing winhandler.exe (the
        // bundled container pattern does not ship it).
        try {
            // A corrupted custom Wine install (e.g. a Kron4ek tarball extracted
            // without hardlink support: 0-byte DLLs) makes box64 die instantly
            // with SIGSEGV and drops back to the main menu with no guest
            // output. Name the culprit instead of failing silently.
            if (!WineInfo.isMainWineVersion(container.getWineVersion())
                    && wineInfo != null && wineInfo.path != null
                    && !WineInstaller.isInstalledWineRootValid(new File(wineInfo.path))) {
                android.util.Log.e("XServerDisplayActivity", "Wine install failed validation: " + container.getWineVersion());
                AppUtils.showToast(this, getString(R.string.custom_wine_broken_install, container.getWineVersion()));
            }
            if (WineUtils.healGamepadServices(container)) containerDataChanged = true;
            if (WineUtils.clearHiddenJoystickOverrides(container)) containerDataChanged = true;
            // Backend-less custom builds get the main build's matched
            // winlator_bus driver stack (unix winebus.so + PE trio) so the
            // virtual gamepad enumerates there exactly like on default Wine.
            if (!WineInfo.isMainWineVersion(container.getWineVersion())
                    && WineUtils.ensureCustomWineGamepadStack(this, container)) containerDataChanged = true;
            // Stock Linux builds (e.g. Kron4ek) hardcode /tmp/.wine-UID as
            // the wineserver dir, which is unusable on Android and aborts
            // the boot instantly. Retarget it to rootfs/tmp instead.
            if (!WineInfo.isMainWineVersion(container.getWineVersion())
                    && WineServerDirPatcher.ensurePatched(this, container)) containerDataChanged = true;
            File winhandler = new File(container.getRootDir(), ".wine/drive_c/windows/winhandler.exe");
            if (!winhandler.isFile()) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "rootfs_patches.tzst", rootFS.getRootDir());
                containerDataChanged = true;
            }
        }
        catch (Exception e) {}
        // Stock upstream/TKG/Proton builds lack Winlator@Frost's winebus gamepad
        // backend entirely, so no HID gamepad can ever appear there (joy.cpl
        // stays empty). Tell the user once per Wine version instead of
        // failing silently.
        if (!WineInfo.isMainWineVersion(container.getWineVersion())
                && !WineUtils.hasWinlatorGamepadBus(this, container.getWineVersion())
                && !container.getExtra("warnedMissingGamepadBus", "").equals(container.getWineVersion())) {
            container.putExtra("warnedMissingGamepadBus", container.getWineVersion());
            containerDataChanged = true;
            AppUtils.showToast(this, getString(R.string.custom_wine_missing_gamepad_bus, container.getWineVersion()));
        }
        // Wine 11.x loaders crash the bundled box64 0.4.4 deterministically
        // (SIGSEGV in box64's own setbuf wrapper during loader startup, before
        // Wine prints anything), so the container can only bounce back to the
        // main menu. Tell the user once per Wine version instead of failing
        // silently. The boot is still attempted so a future box64 update can
        // revive these containers without recreating them.
        if (!WineInfo.isMainWineVersion(container.getWineVersion())
                && wineInfo != null && WineInstaller.wineMajorVersion(wineInfo.version) >= 11
                && !container.getExtra("warnedWine11Box64", "").equals(container.getWineVersion())) {
            container.putExtra("warnedWine11Box64", container.getWineVersion());
            containerDataChanged = true;
            AppUtils.showToast(this, getString(R.string.custom_wine11_boot_warning, container.getWineVersion()));
        }
        if (extractDXWrapperFiles()) containerDataChanged = true;

        if (!wincomponents.equals(container.getExtra("wincomponents"))) {
            extractWinComponentFiles();
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        String desktopTheme = container.getDesktopTheme();
        if (!(desktopTheme+","+xServer.screenInfo).equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), xServer.screenInfo);
            container.putExtra("desktopTheme", desktopTheme+","+xServer.screenInfo);
            containerDataChanged = true;
        }

        WineStartMenuCreator.create(this, container);
        WineUtils.createDosdevicesSymlinks(container, true);

        String startupSelection = String.valueOf(container.getStartupSelection());
        if (!startupSelection.equals(container.getExtra("startupSelection")) || wineprefixWasUpdated) {
            WineUtils.changeServicesStatus(container, container.getStartupSelection());
            container.putExtra("startupSelection", startupSelection);
            containerDataChanged = true;
        }

        boolean openAndroidBrowserFromWine = preferences.getBoolean("open_android_browser_from_wine", true);
        String openAndroidBrowserFromWineStr = openAndroidBrowserFromWine ? "t" : "f";
        if (!openAndroidBrowserFromWineStr.equals(container.getExtra("openAndroidBrowserFromWine")) || wineprefixWasUpdated) {
            WineUtils.changeBrowsersRegistryKey(container, openAndroidBrowserFromWine);
            container.putExtra("openAndroidBrowserFromWine", openAndroidBrowserFromWineStr);
            containerDataChanged = true;
        }

        if (containerDataChanged) container.saveData();
    }

    /**
     * Checks that a registry file is a usable Wine registry: it must exist,
     * start with the "WINE REGISTRY" header and, when required, declare a
     * 64-bit prefix via "#arch=win64" (wine refuses 64-bit apps otherwise).
     */
    private static boolean isRegistryFileValid(File file, boolean requireWin64Arch) {
        if (file == null || !file.isFile() || file.length() < 32) return false;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            if (line == null || !line.contains("WINE REGISTRY")) return false;
            if (!requireWin64Arch) return true;
            for (int i = 0; i < 16 && (line = reader.readLine()) != null; i++) {
                if (line.startsWith("#arch=")) return line.equals("#arch=win64");
            }
            return false;
        }
        catch (IOException e) {
            return false;
        }
    }

    /**
     * Self-heals a corrupt wineprefix (missing/empty/invalid system.reg or
     * user.reg, e.g. after an interrupted install or a damaged prefix) by
     * re-extracting the container pattern for the container's wine version.
     * Healthy prefixes are left untouched. Existing user data inside the
     * prefix is preserved (extraction overwrites, never deletes). The live
     * home/xuser symlink is also re-pointed at this container, so the prefix
     * wine actually boots is guaranteed to be the one just validated.
     *
     * @return true if a heal was performed and the registries are valid again.
     */
    private boolean ensureValidWineprefix() {
        // Validate through the same path wine boots from (home/xuser symlink),
        // not just the container dir, so a stale symlink cannot hide breakage.
        File livePrefixDir = new File(rootFS.getRootDir(), RootFS.WINEPREFIX);
        File containerPrefixDir = new File(container.getRootDir(), ".wine");
        boolean systemValid = isRegistryFileValid(new File(livePrefixDir, "system.reg"), true);
        boolean userValid = isRegistryFileValid(new File(livePrefixDir, "user.reg"), false);
        if (systemValid && userValid) return false;

        android.util.Log.e("XServerDisplayActivity", "Wine prefix registries invalid (system=" + systemValid
                + " user=" + userValid + "), re-extracting container pattern for container " + container.id);

        String wineVersion = container.getWineVersion();
        boolean extracted;
        if (WineInfo.isMainWineVersion(wineVersion)) {
            extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "container_pattern.tzst", container.getRootDir());
        }
        else {
            WineInfo wineInfo = WineInfo.fromIdentifier(this, wineVersion);
            File patternFile = new File(RootFS.find(this).getInstalledWineDir(), "container-pattern-" + wineInfo.fullVersion() + ".tzst");
            extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, patternFile, container.getRootDir());
        }

        if (!extracted) {
            android.util.Log.e("XServerDisplayActivity", "Failed to re-extract container pattern for wine " + wineVersion);
            return false;
        }

        // Re-point the live prefix symlink at this container, then re-validate
        // through the boot path.
        File liveLink = new File(rootFS.getRootDir(), RootFS.HOME_PATH);
        FileUtils.delete(liveLink);
        FileUtils.symlink(RootFS.USER + "-" + container.id, liveLink.getPath());

        boolean healed = isRegistryFileValid(new File(livePrefixDir, "system.reg"), true)
                && isRegistryFileValid(new File(livePrefixDir, "user.reg"), false);
        android.util.Log.e("XServerDisplayActivity", "Wine prefix heal " + (healed ? "succeeded" : "FAILED") + " for container " + container.id);
        return healed;
    }

    private void setupXEnvironment() {
        String rootPath = rootFS.getRootDir().getPath();
        // Diagnostic, llvmpipe-only: do NOT silence Mesa. `silent' only
        // controls log verbosity (behavior is identical), but while the
        // llvmpipe geometry issue (grey TestD3D, lavapipe black screen) is
        // open we need Mesa's own errors in the guest log: shader/LLVM
        // failures ("program N not linked"), u_blitter recursion floods,
        // and WSI negotiation warnings are all suppressed by `silent'.
        // Every other slot keeps `silent'. MESA_NO_ERROR stays everywhere
        // (behavioral parity with the reference). Re-silence llvmpipe once
        // its rendering is root-caused.
        if (!isLlvmpipeCombo()) envVars.put("MESA_DEBUG", "silent");
        envVars.put("MESA_NO_ERROR", "1");
        envVars.put("WINEPREFIX", rootPath+RootFS.WINEPREFIX);
        envVars.put("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", "1");

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels", SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS);
        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty() ? "+"+wineDebugChannels.replace(",", ",+") : "-all");

        FileUtils.clear(rootFS.getTmpDir());

        GuestProgramLauncherComponent guestProgramLauncherComponent = new GuestProgramLauncherComponent();

        if (container != null) {
            if (container.isShowHUD()) envVars.put("X11_WND_GPU_INFO", "1");

            String desktopName = shortcut != null || getIntent().hasExtra("exec_path") ? "nogui" : "shell";
            // Classic (non-WoW64) 64-bit builds must be driven via wine64:
            // their bin/wine is a 32-bit loader box64 cannot execute.
            String wineLoader = "wine";
            if (wineInfo != null && wineInfo != WineInfo.MAIN_WINE_INFO && wineInfo.path != null) {
                wineLoader = WineInstaller.getWineLoaderExecutable(new File(wineInfo.path), container.getWineVersion());
            }
            String guestExecutable = wineLoader+" explorer /desktop="+desktopName+","+xServer.screenInfo+" "+getWineStartCommand();
            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);

            envVars.putAll(container.getEnvVars());
            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));
            if (!envVars.has("WINEESYNC")) envVars.put("WINEESYNC", "1");
            // LC_ALL (Wine localization): container setting, overridable per-shortcut.
            // glibc override mapping to Windows locale/codepage inside Wine.
            // Must be exported before wine starts; empty falls back to container default.
            String lcAll = shortcut != null ? shortcut.getExtra("lcAll", container.getLCAll()) : container.getLCAll();
            if (lcAll != null && !lcAll.isEmpty()) {
                envVars.put("LC_ALL", lcAll);
            }
            // TZ (Wine timezone): IANA zone (e.g. Asia/Kuala_Lumpur) read by glibc/Wine
            // to compute local time, DST and Windows TIME_ZONE_INFORMATION.
            // Container setting, overridable per-shortcut via "timezone" extra.
            String timezone = shortcut != null ? shortcut.getExtra("timezone", container.getTimezone()) : container.getTimezone();
            if (timezone != null && !timezone.isEmpty()) {
                envVars.put("TZ", timezone);
            }
            // llvmpipe parity with the proven alexvorxx/winlator llvmpipe
            // branch: it sets NO Mesa/threading env at all (no mesa_glthread,
            // no LP_NUM_THREADS, no csmt rewrite, no GALLIUM_DRIVER). The
            // container default carries mesa_glthread=true (meant for Zink),
            // so drop that key for the llvmpipe GL slot to restore Mesa's own
            // default, exactly like the reference. An explicit user
            // mesa_glthread/LP_NUM_THREADS entry is left untouched.
            // (Single-threaded submission via mesa_glthread=false +
            // LP_NUM_THREADS=1 + csmt=0x0 was tried: it only slowed the
            // empty clear-color loop from ~800fps to ~40fps without ever
            // restoring the cube -- threading was never the cause.)
            clearLlvmpipeGLThreadingOverrides();

            guestProgramLauncherComponent.setBox64Preset(shortcut != null ? shortcut.getExtra("box64Preset", container.getBox64Preset()) : container.getBox64Preset());
            String box64Version = resolveSessionBox64Version(true);
            guestProgramLauncherComponent.setBox64Version(box64Version != null && !box64Version.isEmpty() ? box64Version : null);
            guestProgramLauncherComponent.setContainerRootDir(container.getRootDir());
            if (shortcut != null) {
                guestProgramLauncherComponent.setBox64RCFileId(RCManager.parseRCFileId(shortcut.getExtra("rcfileId", String.valueOf(container.getRCFileId())), container.getRCFileId()));
            }
            else guestProgramLauncherComponent.setBox64RCFileId(container.getRCFileId());
        }

        environment = new XEnvironment(this, rootFS);
        environment.addComponent(new SysVSharedMemoryComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        environment.addComponent(new XServerComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.XSERVER_PATH)));
        environment.addComponent(new NetworkInfoUpdateComponent());

        if (audioDriver.equals(AudioDrivers.ALSA)) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath+UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", ALSAClient.USE_SHARED_MEMORY ? "true" : "false");

            ALSAClient.Options options = ALSAClient.Options.fromKeyValueSet(audioDriverConfig);
            environment.addComponent(new ALSAServerComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.ALSA_SERVER_PATH), options));
        }
        else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
            PulseAudioComponent pulseAudioComponent = new PulseAudioComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.PULSE_SERVER_PATH));
            envVars.put("PULSE_SERVER", rootPath+UnixSocketConfig.PULSE_SERVER_PATH);

            if (!audioDriverConfig.isEmpty()) {
                envVars.put("PULSE_LATENCY_MSEC", audioDriverConfig.getInt("latencyMillis", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS));
                pulseAudioComponent.setVolume(audioDriverConfig.getFloat("volume", AudioDriverConfigDialog.DEFAULT_VOLUME));
                pulseAudioComponent.setPerformanceMode(audioDriverConfig.getInt("performanceMode", AudioDriverConfigDialog.DEFAULT_PERFORMANCE_MODE));
            }
            else envVars.put("PULSE_LATENCY_MSEC", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS);
            environment.addComponent(pulseAudioComponent);
        }

        if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) {
            VortekRendererComponent.Options options = VortekRendererComponent.Options.fromKeyValueSet(this, graphicsDriverConfig[0]);
            VortekRendererComponent vortekRendererComponent = new VortekRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VORTEK_SERVER_PATH), options);
            environment.addComponent(vortekRendererComponent);
        }
        if (graphicsDriver[1].equals(GraphicsDrivers.VIRGL)) {
            environment.addComponent(new VirGLRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VIRGL_SERVER_PATH)));
        }

        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> {
            android.util.Log.e("XServerDisplayActivity", "Guest program terminated with status " + status);
            exit();
        });
        environment.addComponent(guestProgramLauncherComponent);

        if (isGenerateWineprefix()) {
            wineInfo = getIntent().getParcelableExtra("wine_info");
            if (wineInfo != null) WineInstaller.generateWineprefix(wineInfo, environment);
        }
        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars = null;
        }
        environment.startEnvironmentComponents();

        winHandler.start();
        envVars.clear();
        graphicsDriver = null;
        dxwrapperConfig = null;
        graphicsDriverConfig = null;
        audioDriver = null;
        audioDriverConfig = null;
        wincomponents = null;
    }

    private void setupUI() {
        rootView = findViewById(R.id.FLXServerDisplay);
        xServerView = new XServerView(this, xServer);
        final GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(false);
        renderer.setCursorColor(preferences.getInt("cursor_color", 0xffffff));
        renderer.setCursorScale(preferences.getFloat("cursor_scale", 1.0f));
        renderer.setForceWindowsFullscreen(shortcut != null && shortcut.getExtra("forceFullscreen", "0").equals("1"));

        boolean fullscreenStretched = false;
        if (shortcut != null) {
            String extra = shortcut.getExtra("fullscreenStretched");
            if (!extra.isEmpty()) {
                fullscreenStretched = extra.equals("1");
            }
            else if (container != null) {
                fullscreenStretched = container.isFullscreenStretched();
            }
        }
        else if (container != null) {
            fullscreenStretched = container.isFullscreenStretched();
        }
        if (fullscreenStretched) renderer.setFullscreen(true);

        xServer.setRenderer(renderer);
        rootView.addView(xServerView);

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        capturePointerOnExternalMouse = preferences.getBoolean("capture_pointer_on_external_mouse", true);
        touchpadView = new TouchpadView(this, xServer, capturePointerOnExternalMouse);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setMoveCursorToTouchpoint(preferences.getBoolean("move_cursor_to_touchpoint", false));
        touchpadView.setFourFingersTapCallback(() -> {
            showXServerMenuDialog();
        });
        rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);

        if (container != null && container.isShowHUD()) {
            frameRating = new FrameRating(this);
            frameRating.setConfig(container.getHUDConfig());
            // Push the exact version this session will run (shortcut
            // override -> container -> global, plus the Wine 11 guard), so
            // the HUD never shows a stale guess or the global default for a
            // per-container choice.
            String hudBox64Version = resolveSessionBox64Version(false);
            frameRating.setBox64Version(hudBox64Version);
            frameRating.setVisibility(View.GONE);
            rootView.addView(frameRating, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (container != null && container.isShowQuickHUD()) {
            QuickHUDView quickHUDView = new QuickHUDView(this);
            rootView.addView(quickHUDView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null) showInputControls(profile);
            }
        }

        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));
        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);
    }

    public void showInputControlsDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.input_controls_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_input_controls);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);
        Runnable loadProfileSpinner = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- "+getString(R.string.disabled)+" --");
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (profile == inputControlsView.getProfile()) selectedPosition = i + 1;
                profileItems.add(profile.getName());
            }

            sProfile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, profileItems));
            sProfile.setSelection(selectedPosition);
        };
        loadProfileSpinner.run();

        final CheckBox cbRelativeMouseMovement = dialog.findViewById(R.id.CBRelativeMouseMovement);
        cbRelativeMouseMovement.setChecked(xServer.isRelativeMouseMovement());

        final CheckBox cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

        final CheckBox cbTouchControlsTimeout = dialog.findViewById(R.id.CBTouchControlsTimeout);
        cbTouchControlsTimeout.setChecked(preferences.getBoolean("touch_controls_timeout", false));

        final CheckBox cbMoveCursorToTouchpoint = dialog.findViewById(R.id.CBMoveCursorToTouchpoint);
        cbMoveCursorToTouchpoint.setChecked(touchpadView.isMoveCursorToTouchpoint());

        dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
            int position = sProfile.getSelectedItemPosition();
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("edit_input_controls", true);
            intent.putExtra("selected_profile_id", position > 0 ? inputControlsManager.getProfiles().get(position - 1).id : 0);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(true);
                loadProfileSpinner.run();
            };
            startActivityForResult(intent, MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE);
        });

        dialog.setOnConfirmCallback(() -> {
            xServer.setRelativeMouseMovement(cbRelativeMouseMovement.isChecked());
            inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
            preferences.edit().putBoolean("touch_controls_timeout", cbTouchControlsTimeout.isChecked()).apply();
            touchpadView.setMoveCursorToTouchpoint(cbMoveCursorToTouchpoint.isChecked());
            preferences.edit().putBoolean("move_cursor_to_touchpoint", cbMoveCursorToTouchpoint.isChecked()).apply();
            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            }
            else hideInputControls();
        });

        dialog.show();
    }

    private void showInputControls(ControlsProfile profile) {
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(profile);
        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonRightEnabled(false);

        GLRenderer renderer = xServerView.getRenderer();
        if (profile.isDisableMouseInput()) {
            renderer.setCursorVisible(false);
            touchpadView.setEnabled(false);
        }
        else {
            renderer.setCursorVisible(true);
            touchpadView.setEnabled(true);
        }

        inputControlsView.invalidate();
        scheduleTouchControlsTimeout();
    }

    private void hideInputControls() {
        cancelTouchControlsTimeout();
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        if (!touchpadView.isEnabled()) {
            touchpadView.setEnabled(true);
            xServerView.getRenderer().setCursorVisible(true);
        }

        inputControlsView.invalidate();
    }

    /**
     * Reference parity for the llvmpipe OpenGL slot (WineD3D path).
     *
     * <p>The proven alexvorxx/winlator llvmpipe branch sets NO Mesa or
     * threading env at all for this slot: no mesa_glthread, no
     * LP_NUM_THREADS, no GALLIUM_DRIVER, no csmt rewrite (csmt stays at the
     * WineD3D default 0x3), OffscreenRenderingMode stays fbo, and the GL
     * version stays at stock Mesa 26 (4.5 -- capping to 3.3COMPAT was tried
     * and broke shader compilation: "program 1 not linked" + black draws).
     *
     * <p>The container-wide default carries {@code mesa_glthread=true}
     * (meant for Zink), so that one key is dropped here to restore Mesa's
     * own default, exactly like the reference. An explicit user
     * mesa_glthread/LP_NUM_THREADS entry is never removed.
     *
     * <p>Single-threaded submission (mesa_glthread=false + LP_NUM_THREADS=1
     * + csmt=0x0) was tried on-device: it only slowed the empty clear-color
     * loop (~800fps to ~40fps) without ever restoring the cube, and the
     * u_blitter recursion flood persisted single-threaded (Mesa's guard
     * prints-and-continues, so it never explained the grey window either).
     * Threading was never the cause -- hence this revert to the reference.
     */
    /** True when either the Vulkan or the OpenGL slot uses llvmpipe. */
    private boolean isLlvmpipeCombo() {
        if (graphicsDriver == null) return false;
        if (graphicsDriver[0].equals(GraphicsDrivers.LLVMPIPE)) return true;
        return graphicsDriver.length > 1 && graphicsDriver[1].equals(GraphicsDrivers.LLVMPIPE);
    }

    private void clearLlvmpipeGLThreadingOverrides() {
        if (graphicsDriver == null || graphicsDriver.length < 2
                || !graphicsDriver[1].equals(GraphicsDrivers.LLVMPIPE)) return;
        // Container default (Zink-oriented); the reference llvmpipe branch
        // leaves this key unset. Remove only the default-carried value: an
        // explicit user entry must survive. No replacement value is set.
        if ("true".equals(envVars.get("mesa_glthread"))) envVars.remove("mesa_glthread");
    }

    private void extractGraphicsDriverFiles() {
        envVars.put("vblank_mode", "0");

        String cacheId = "";
        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            String version = graphicsDriverConfig[0].get("version");
            cacheId += graphicsDriver[0]+"-"+(!version.isEmpty() ? version : DefaultVersion.TURNIP);
        }
        else cacheId += graphicsDriver[0]+"-"+DefaultVersion.valueOf(graphicsDriver[0]);
        if (graphicsDriver[1].equals(GraphicsDrivers.VIRGL)) {
            String version = graphicsDriverConfig[1].get("version");
            cacheId += "-"+graphicsDriver[1]+"-"+(!version.isEmpty() ? version : DefaultVersion.VIRGL);
        }
        else cacheId += "-"+graphicsDriver[1]+"-"+DefaultVersion.valueOf(graphicsDriver[1]);
        // One-time refresh for llvmpipe combos: the GL-slot env handling
        // changed (reverted to the reference: no mesa_glthread/LP_NUM_THREADS
        // /csmt overrides), so a shader cache populated under a previous env
        // must not be reused. Bumping the id forces exactly one re-extract +
        // shader-cache drop via the `changed' path below; steady-state
        // launches afterwards keep a warm cache. Non-llvmpipe combos are
        // unaffected. Bump the suffix if llvmpipe handling changes again in
        // a way that invalidates compiled shaders.
        if (graphicsDriver[0].equals(GraphicsDrivers.LLVMPIPE)
                || (graphicsDriver.length > 1 && graphicsDriver[1].equals(GraphicsDrivers.LLVMPIPE))) {
            cacheId += "-stockenv3";
        }

        String currentGraphicsDriver = preferences.getString("current_graphics_driver", "");
        boolean changed = !cacheId.equals(currentGraphicsDriver);
        File rootDir = rootFS.getRootDir();
        File libDir = rootFS.getLibDir();

        if (changed) {
            FileUtils.delete(new File(libDir, "libvulkan_freedreno.so"));
            FileUtils.delete(new File(libDir, "libvulkan_vortek.so"));
            FileUtils.delete(new File(libDir, "libvulkan_lvp.so"));
            FileUtils.delete(new File(libDir, "libGL.so.1.7.0"));
            // Support libs for the llvmpipe 25.0.0 asset (Ubuntu jammy
            // user-space builds both drivers link against these; the base
            // rootfs ships neither). Deleted here so a stale copy can never
            // survive a driver switch; the llvmpipe extracts below restore
            // them. (Deliberately NOT deleting libzstd.so.1 here: the base
            // rootfs ships its own copy, so deleting would remove a rootfs
            // file when the new driver doesn't bundle one. The xcb libs
            // below are leftovers of the retired 26.0.0 asset, which is the
            // only one that ever bundled them.)
            FileUtils.delete(new File(libDir, "libtinfo.so.6"));
            FileUtils.delete(new File(libDir, "libsensors.so.5"));
            FileUtils.delete(new File(libDir, "libxcb-xfixes.so.0"));
            FileUtils.delete(new File(libDir, "libxshmfence.so.1"));
            FileUtils.delete(new File(libDir, "libxcb-keysyms.so.1"));

            File vulkanICDDir = new File(rootDir, "/usr/share/vulkan/icd.d");
            FileUtils.delete(vulkanICDDir);
            vulkanICDDir.mkdirs();

            // Mesa's on-disk shader cache (compiled LLVM machine code) lives
            // in the guest home and survives driver switches. Its keys do not
            // capture the driver build behind the same Mesa version, so a
            // cache populated by a previous (or differently built) llvmpipe
            // is silently reused after the switch -- stale codegen then
            // persists as black frames (lavapipe/DXVK) or missing geometry
            // (llvmpipe/wined3d: clears work, draws don't) even with fixed
            // binaries. Drop it on every driver combination change; it
            // rebuilds automatically (one-time compile hitch on next launch).
            // Scoped to the switch so steady-state launches keep a warm cache.
            String customCacheDir = null;
            if (container != null) {
                EnvVars containerEnv = new EnvVars(container.getEnvVars());
                if (containerEnv.has("MESA_SHADER_CACHE_DIR")) customCacheDir = containerEnv.get("MESA_SHADER_CACHE_DIR");
            }
            File mesaShaderCacheDir = (customCacheDir != null && !customCacheDir.isEmpty())
                ? new File(rootDir, customCacheDir)
                : new File(rootDir, RootFS.USER_CACHE_PATH + "/mesa_shader_cache");
            if (FileUtils.delete(mesaShaderCacheDir)) {
                Log.e("GraphicsDriver", "Dropped stale Mesa shader cache after driver switch: " + mesaShaderCacheDir);
            }

            preferences.edit().putString("current_graphics_driver", cacheId).apply();
        }

        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            TurnipConfigDialog.setEnvVars(this, graphicsDriverConfig[0], envVars);

            if (changed) {
                String version = graphicsDriverConfig[0].get("version");
                if (version == null || version.isEmpty()) version = DefaultVersion.TURNIP;
                GeneralComponents.extractFile(GeneralComponents.Type.TURNIP, this, version, DefaultVersion.TURNIP);
            }
        }
        else if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) {
            VortekConfigDialog.setEnvVars(this, graphicsDriverConfig[0], envVars);
            if (changed || MainActivity.DEBUG_MODE) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/vortek-" + DefaultVersion.VORTEK + ".tzst", rootDir);
            }
        }
        else if (graphicsDriver[0].equals(GraphicsDrivers.LLVMPIPE)) {
            // lavapipe (Mesa swrast Vulkan), exact parity with the proven
            // working alexvorxx/winlator llvmpipe branch: no Mesa env at all.
            // No GALLIUM_DRIVER override (the ICD manifest selects lavapipe),
            // no DXVK/VKD3D device env here (extractDXWrapperFiles() owns
            // that), and deliberately no MESA_VK_WSI_* overrides -- lavapipe
            // negotiates the WSI path itself against this X server's DRI3
            // 1.2 + GetSupportedModifiers support; forcing a present mode or
            // the sw debug path bypasses exactly that negotiation and was
            // observed to keep working enumeration paired with black frames.
            // Device selection needs no filter: the `changed' block above
            // wipes /usr/share/vulkan/icd.d, so with this slot lavapipe is
            // the SOLE Vulkan device and DXVK selects it when alone.
            Log.e("GraphicsDriver", "LLVMPipe Vulkan slot active"
                + " (stock Mesa WSI negotiation; lavapipe sole ICD)");
            if (changed || MainActivity.DEBUG_MODE) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/llvmpipe-vulkan-" + DefaultVersion.LLVMPIPE + ".tzst", rootDir);
                logExtractedDriverFiles("llvmpipe-vulkan", rootDir, "usr/lib/libvulkan_lvp.so",
                    "usr/lib/libtinfo.so.6", "usr/lib/libsensors.so.5", "usr/share/vulkan/icd.d");
            }
        }

        switch (graphicsDriver[1]) {
            case GraphicsDrivers.ZINK:
                envVars.put("GALLIUM_DRIVER", "zink");
                envVars.put("ZINK_CONTEXT_THREADED", "1");
                if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3");

                if (changed) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/zink-"+DefaultVersion.ZINK+".tzst", rootDir);
                break;
            case GraphicsDrivers.VIRGL:
                envVars.put("GALLIUM_DRIVER", "virpipe");
                envVars.put("VIRGL_NO_READBACK", "true");
                envVars.put("VIRGL_SERVER_PATH", rootDir+UnixSocketConfig.VIRGL_SERVER_PATH);
                // Stock Mesa >= 24 virpipe (vtest winsys) ignores
                // VIRGL_SERVER_PATH and reads VTEST_SOCKET_NAME instead
                // (default /tmp/.virgl_test, where nothing listens). Point it
                // at the same socket the host VirGLRendererComponent serves.
                // VIRGL_SERVER_PATH is kept for the legacy <= 23.1 custom
                // virgl assets, which read only that variable.
                envVars.put("VTEST_SOCKET_NAME", rootDir+UnixSocketConfig.VIRGL_SERVER_PATH);
                // Defensive no-op kept for future Mesa builds: it would force the
                // classic GLX swap path if any DRI present path existed. Both
                // shipped virgl assets are xlib builds containing no DRI3 code
                // at all (verified 0 DRI3 references in either libGL), so this
                // changes nothing today; presentation is owned by the driver's
                // flush_frontbuffer hook plus the host's id-9 present path.
                // Harmless for the legacy 23.1.9 asset.
                envVars.put("LIBGL_DRI3_DISABLE", "1");
                VirGLConfigDialog.setEnvVars(graphicsDriverConfig[1], envVars);

                if (changed) {
                    String version = graphicsDriverConfig[1].get("version");
                    if (version == null || version.isEmpty()) version = DefaultVersion.VIRGL;
                    Log.e("GraphicsDriver", "VirGL slot active version=" + version
                        + " (asset graphics_driver/virgl-" + version + ".tzst)");
                    GeneralComponents.extractFile(GeneralComponents.Type.VIRGL, this, version, DefaultVersion.VIRGL);
                    logExtractedDriverFiles("virgl-" + version, rootDir, "usr/lib/libGL.so.1.7.0");
                }
                break;
            case GraphicsDrivers.GLADIO:
                envVars.put("GLADIO_NO_ERROR", "1");
                // Gladio is the only slot served by the host-GPU GLX dialect:
                // its clients need native GLES contexts from gladiorenderer,
                // not the Mesa-dialect protocol. Every other OpenGL slot
                // (zink/virgl/llvmpipe) keeps the default mesaDriverMode=true.
                screenInfo.setMesaDriverMode(false);

                if (changed || MainActivity.DEBUG_MODE) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/gladio-"+DefaultVersion.GLADIO+".tzst", rootDir);
                break;
            case GraphicsDrivers.LLVMPIPE:
                // llvmpipe classic libGL (xlib GLX target, driver baked into
                // libGL itself -- no dri/*.so plugin, no DRI search-path
                // plumbing). Exact parity with the proven alexvorxx/winlator
                // llvmpipe branch: NO Mesa env at all here (no mesa_glthread,
                // no LP_NUM_THREADS, no GALLIUM_DRIVER override -- llvmpipe is
                // the only gallium driver compiled in, so the loader selects
                // it unaided) and no MESA_GL_VERSION_OVERRIDE (stock Mesa
                // default 4.5; capping to 3.3 broke shader compilation:
                // "program 1 not linked" + black draws). The container-wide
                // mesa_glthread=true default is dropped later in
                // clearLlvmpipeGLThreadingOverrides(); csmt stays at the
                // WineD3D default 0x3 and ORM stays fbo, like the reference.
                // The asset additionally bundles libtinfo.so.6 +
                // libsensors.so.5 (Ubuntu jammy user-space builds link both;
                // the base rootfs ships neither). Extraction order matters:
                // the Vulkan slot above runs first, then this overwrites
                // libGL, so mixed combos like Turnip(Vulkan)+LLVMPipe(OpenGL)
                // end with the right libGL.
                screenInfo.setMesaDriverMode(true);
                Log.e("GraphicsDriver", "LLVMPipe OpenGL slot active"
                    + " (stock Mesa env; stock 4.5 GL version)");
                if (changed || MainActivity.DEBUG_MODE) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/llvmpipe-opengl-"+DefaultVersion.LLVMPIPE+".tzst", rootDir);
                    repairLibGLSymlink(libDir);
                    logExtractedDriverFiles("llvmpipe-opengl", rootDir, "usr/lib/libGL.so.1.7.0",
                        "usr/lib/libtinfo.so.6", "usr/lib/libsensors.so.5");
                }
                break;
        }

        // Self-healing verification: TarCompressorUtils.extract() fails silently
        // (returns false, ignored above) when an asset is missing or misnamed, and
        // the `changed` block above already deleted the previous driver files and
        // saved the new cacheId -- without this check the container would stay
        // permanently driverless (blank GPU info, instant app close) with
        // changed=false on every later launch. Verify the files this combination
        // actually needs; if any are absent, log a diagnosable error and clear the
        // cacheId so the next launch retries extraction instead of staying poisoned.
        if (!verifyGraphicsDriverFiles(rootDir, libDir)) {
            preferences.edit().remove("current_graphics_driver").apply();
            // User-visible (not just logcat): a missing asset otherwise fails
            // silently every launch with blank GPU info and no hint why.
            // Fires only in genuinely broken states. showToast dispatches to
            // the UI thread internally, safe to call from this worker thread.
            AppUtils.showToast(this, "Graphics driver files missing for "
                + graphicsDriver[0] + "/" + graphicsDriver[1]
                + " -- asset not extracted, will retry next launch. Details in logcat [GraphicsDriver].");
        }
    }

    /**
     * Post-extraction diagnostics for software-rendered driver slots. A
     * llvmpipe-class failure signature is "enumerates but renders nothing"
     * (Vulkan ICD loads so vkEnumeratePhysicalDevices works, yet every
     * presented frame is black; OpenGL GPUInfo stays blank because no context
     * ever produces a GL_RENDERER string). When that happens the first
     * questions are always "did the right files actually land on-device, at
     * the right sizes, with the libGL.so.1 symlink resolving". This logs
     * exactly that to logcat under the GraphicsDriver tag so a bug report
     * can answer it without guessing. Never throws: diagnostics must not
     * break a boot that would otherwise work.
     */
    private void logExtractedDriverFiles(String slotName, File rootDir, String... relativePaths) {
        try {
            for (String relativePath : relativePaths) {
                File file = new File(rootDir, relativePath);
                if (file.isDirectory()) {
                    String[] entries = file.list();
                    android.util.Log.e("GraphicsDriver", slotName + ": dir " + relativePath
                        + " entries=" + (entries != null ? String.join(",", entries) : "<unreadable>"));
                }
                else if (file.isFile()) {
                    android.util.Log.e("GraphicsDriver", slotName + ": file " + relativePath
                        + " size=" + file.length());
                }
                else {
                    android.util.Log.e("GraphicsDriver", slotName + ": MISSING " + relativePath
                        + " (extraction of this slot's asset did not produce it)");
                }
            }
            File libGLLink = new File(rootDir, "usr/lib/libGL.so.1");
            File libGLTarget = new File(rootDir, "usr/lib/libGL.so.1.7.0");
            android.util.Log.e("GraphicsDriver", slotName + ": libGL.so.1 isFile=" + libGLLink.isFile()
                + " libGL.so.1.7.0 isFile=" + libGLTarget.isFile()
                + (libGLTarget.isFile() ? " size=" + libGLTarget.length() : ""));
        }
        catch (Exception e) {
            android.util.Log.e("GraphicsDriver", slotName + ": file diagnostics failed: " + e);
        }
    }

    /**
     * The llvmpipe-opengl asset ships only {@code libGL.so.1.7.0} and relies
     * on the base rootfs for the {@code libGL.so.1} symlink pointing at it
     * (the reference alexvorxx/winlator archive instead ships
     * {@code libGL.so.1} as a real file, so it can never dangle). After the
     * `changed' block deletes {@code libGL.so.1.7.0}, a rootfs whose symlink
     * is missing or points elsewhere would leave the guest loader with a
     * stale/dangling libGL. Re-point the link at our file when needed; never
     * throws.
     */
    private void repairLibGLSymlink(File libDir) {
        try {
            File target = new File(libDir, "libGL.so.1.7.0");
            File link = new File(libDir, "libGL.so.1");
            if (!target.isFile()) return;
            // Resolves (file or correct symlink): nothing to do. A real
            // regular file from the base rootfs is deliberately left alone;
            // only a missing/dangling/incorrect link is repaired.
            if (link.isFile()) {
                if (FileUtils.isSymlink(link)
                        && !FileUtils.readSymlink(link).endsWith("libGL.so.1.7.0")) {
                    FileUtils.delete(link);
                    FileUtils.symlink("libGL.so.1.7.0", link.getAbsolutePath());
                    android.util.Log.e("GraphicsDriver", "llvmpipe-opengl: re-pointed libGL.so.1 at libGL.so.1.7.0");
                }
                return;
            }
            if (FileUtils.isSymlink(link)) FileUtils.delete(link);
            FileUtils.symlink("libGL.so.1.7.0", link.getAbsolutePath());
            android.util.Log.e("GraphicsDriver", "llvmpipe-opengl: created libGL.so.1 -> libGL.so.1.7.0");
        }
        catch (Exception e) {
            android.util.Log.e("GraphicsDriver", "llvmpipe-opengl: libGL symlink repair failed: " + e);
        }
    }

    private boolean verifyGraphicsDriverFiles(File rootDir, File libDir) {
        boolean ok = true;
        String vulkanFile = null;
        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) vulkanFile = "libvulkan_freedreno.so";
        else if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) vulkanFile = "libvulkan_vortek.so";
        else if (graphicsDriver[0].equals(GraphicsDrivers.LLVMPIPE)) vulkanFile = "libvulkan_lvp.so";
        if (vulkanFile != null && !new File(libDir, vulkanFile).isFile()) {
            android.util.Log.e("GraphicsDriver", "Missing Vulkan driver file after extraction: " + vulkanFile
                + " (Vulkan slot=" + graphicsDriver[0] + "). The graphics_driver asset is absent or misnamed"
                + " -- expected asset for this build is listed in DefaultVersion.");
            ok = false;
        }
        if (vulkanFile != null) {
            File icdDir = new File(rootDir, "/usr/share/vulkan/icd.d");
            String[] icds = icdDir.isDirectory() ? icdDir.list() : null;
            if (icds == null || icds.length == 0) {
                android.util.Log.e("GraphicsDriver", "Vulkan ICD manifest missing under " + icdDir
                    + " after extraction (Vulkan slot=" + graphicsDriver[0] + "). The loader will enumerate no driver.");
                ok = false;
            }
        }
        // Every OpenGL slot in this project is served by a classic libGL build
        // (zink/virgl/gladio archives and llvmpipe-opengl all provide this file;
        // the base rootfs only ships the libGL.so.1 symlink pointing at it).
        if (!new File(libDir, "libGL.so.1.7.0").isFile()) {
            android.util.Log.e("GraphicsDriver", "Missing libGL.so.1.7.0 after extraction (OpenGL slot=" + graphicsDriver[1] + ").");
            ok = false;
        }
        // The llvmpipe 25.0.0 drivers link libtinfo.so.6 + libsensors.so.5
        // (Ubuntu user-space build; the base rootfs ships neither, so both
        // llvmpipe assets bundle them). A missing dep fails dlopen with
        // blank GPU info / no Vulkan device and no other trace, so verify
        // them whenever either llvmpipe slot is active (mixed combos extract
        // only one asset, but both assets carry the deps).
        if (isLlvmpipeCombo()) {
            for (String dep : new String[]{"libtinfo.so.6", "libsensors.so.5"}) {
                if (!new File(libDir, dep).isFile()) {
                    android.util.Log.e("GraphicsDriver", "Missing llvmpipe support lib after extraction: " + dep
                        + " (llvmpipe slot active). The loader will fail to open the driver.");
                    ok = false;
                }
            }
        }
        return ok;
    }

    private void showTouchpadHelpDialog() {
        ContentDialog dialog = new ContentDialog(this, R.layout.touchpad_help_dialog);
        dialog.setTitle(R.string.touchpad_help);
        dialog.setIcon(R.drawable.icon_help);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return !winHandler.onGenericMotionEvent(event) && !touchpadView.onExternalMouseEvent(event) && super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return (!inputControlsView.onKeyEvent(event) && !winHandler.onKeyEvent(event) && xServer.keyboard.onKeyEvent(event)) ||
               (!ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event));
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private boolean extractDXWrapperFiles() {
        String cacheId = "";
        if (dxwrapper.equals(DXWrappers.DXVK)) {
            DXVKConfigDialog.setEnvVars(this, dxwrapperConfig[0], envVars);
            cacheId += dxwrapper+"-"+dxwrapperConfig[0].get("version", DefaultVersion.DXVK(graphicsDriver[0]));
        }
        else if (dxwrapper.equals(DXWrappers.VEGAS)) {
            VegasConfigDialog.setEnvVars(this, dxwrapperConfig[0], envVars);
            cacheId += dxwrapper+"-"+dxwrapperConfig[0].get("version", DefaultVersion.VEGAS());
        }
        else if (dxwrapper.equals(DXWrappers.WINED3D)) {
            WineD3DConfigDialog.setEnvVars(dxwrapperConfig[0], envVars);
            cacheId += dxwrapper+"-"+dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
        }

        String ddrawWrapper = dxwrapperConfig[0].get("ddrawWrapper", DXWrappers.WINED3D);
        cacheId += "-"+DXWrappers.VKD3D+"-"+dxwrapperConfig[1].get("version", DefaultVersion.VKD3D)+"-"+ddrawWrapper;
        boolean changed = !cacheId.equals(container.getExtra("dxwrapper"));
        VKD3DConfigDialog.setEnvVars(dxwrapperConfig[1], envVars);

        if (ddrawWrapper.equals(DXWrappers.CNC_DDRAW)) envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\ProgramData\\cnc-ddraw\\ddraw.ini");

        if (!changed) return false;
        container.putExtra("dxwrapper", cacheId);

        File rootDir = rootFS.getRootDir();
        File windowsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");

        if (dxwrapper.equals(DXWrappers.WINED3D)) {
            String version = dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
            if (version.equals(WineInfo.MAIN_WINE_VERSION)) {
                final String[] dlls = {"d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "dxgi.dll", "ddraw.dll", "wined3d.dll"};
                restoreBuiltinDllFiles(dlls);
            }
            else GeneralComponents.extractFile(GeneralComponents.Type.WINED3D, this, version, DefaultVersion.WINED3D);
        }
        else if (dxwrapper.equals(DXWrappers.DXVK)) {
            final boolean[] hasD3D8DllFile = {false};
            final boolean[] hasD3D10DllFile = {false};

            GeneralComponents.extractFile(GeneralComponents.Type.DXVK, this, dxwrapperConfig[0].get("version"), DefaultVersion.DXVK(graphicsDriver[0]), (destination, size) -> {
                String name = destination.getName();
                if (name.equals("d3d10.dll")) {
                    hasD3D10DllFile[0] = true;
                }
                else if (name.equals("d3d8.dll")) {
                    hasD3D8DllFile[0] = true;
                }
                return destination;
            });

            if (!hasD3D8DllFile[0]) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d8vk-"+DefaultVersion.D8VK+".tzst", windowsDir);
            }
            if (!hasD3D10DllFile[0]) restoreBuiltinDllFiles("d3d10.dll", "d3d10_1.dll");
        }
        else if (dxwrapper.equals(DXWrappers.VEGAS)) {
            GeneralComponents.extractFile(GeneralComponents.Type.VEGAS, this, dxwrapperConfig[0].get("version"), DefaultVersion.VEGAS());
        }

        GeneralComponents.extractFile(GeneralComponents.Type.VKD3D, this, dxwrapperConfig[1].get("version"), DefaultVersion.VKD3D);

        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");
        FileUtils.delete(new File(containerSysWoW64Dir, "ddraw_.dll"));

        switch (ddrawWrapper) {
            case DXWrappers.CNC_DDRAW:
                final String assetDir = "dxwrapper/cnc-ddraw-"+DefaultVersion.CNC_DDRAW;
                File configFile = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/ddraw.ini");
                if (!configFile.isFile()) FileUtils.copy(this, assetDir+"/ddraw.ini", configFile);
                File shadersDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/Shaders");
                FileUtils.delete(shadersDir);
                FileUtils.copy(this, assetDir+"/Shaders", shadersDir);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, assetDir+"/ddraw.tzst", windowsDir);
                break;
            case DXWrappers.D7VK:
                restoreBuiltinDllFiles("ddraw.dll");
                (new File(containerSysWoW64Dir, "ddraw.dll")).renameTo(new File(containerSysWoW64Dir, "ddraw_.dll"));
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d7vk-"+DefaultVersion.D7VK+".tzst", windowsDir);
                break;
            case DXWrappers.DGVOODOO:
                restoreBuiltinDllFiles("ddraw.dll", "d3dimm.dll");
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/dgvoodoo-"+DefaultVersion.DGVOODOO+".tzst", windowsDir);
                break;
            default:
                restoreBuiltinDllFiles("ddraw.dll", "d3dimm.dll");
                break;
        }
        return true;
    }

    private void extractWinComponentFiles() {
        File rootDir = rootFS.getRootDir();
        File windowsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");
        File systemRegFile = new File(rootDir, RootFS.WINEPREFIX+"/system.reg");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(this, "wincomponents/wincomponents.json"));
            Iterator<String[]> oldWinComponentsIter = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();
            ArrayList<String> builtinDlls = new ArrayList<>();

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1])) continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                if (useNative) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir);
                }
                else {
                    JSONObject wincomponentJSONObject = wincomponentsJSONObject.getJSONObject(identifier);
                    if (wincomponentJSONObject.getBoolean("restoreBuiltinDlls")) {
                        JSONArray dlnames = wincomponentJSONObject.getJSONArray("dlnames");
                        for (int i = 0; i < dlnames.length(); i++) {
                            String dlname = dlnames.getString(i);
                            builtinDlls.add(!dlname.endsWith(".exe") ? dlname+".dll" : dlname);
                        }
                    }
                    else {
                        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir, (destination, size) -> {
                            String name = destination.getName();
                            if (name.endsWith(".dll") || name.endsWith(".manifest") || name.endsWith("_deadbeef")) FileUtils.delete(destination);
                            return null;
                        });
                    }
                }

                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative);
            }

            if (!builtinDlls.isEmpty()) restoreBuiltinDllFiles(builtinDlls.toArray(new String[0]));
            WineUtils.overrideWinComponentDlls(this, container, wincomponents);
        }
        catch (JSONException e) {}
    }

    private void restoreBuiltinDllFiles(final String... dlls) {
        File rootDir = rootFS.getRootDir();
        File wineDir = new File(rootDir, rootFS.getWinePath());
        File wineSystem32Dir = new File(wineDir, "/lib/wine/x86_64-windows");
        File wineSysWoW64Dir = new File(wineDir, "/lib/wine/i386-windows");
        File containerSystem32Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/system32");
        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");;

        for (String dll : dlls) {
            FileUtils.copy(new File(wineSysWoW64Dir, dll), new File(containerSysWoW64Dir, dll));
            FileUtils.copy(new File(wineSystem32Dir, dll), new File(containerSystem32Dir, dll));
        }
    }

    private boolean isGenerateWineprefix() {
        return getIntent().getBooleanExtra("generate_wineprefix", false);
    }

    private String getWineStartCommand() {
        String cmdArgs = "";
        String execPath = null;
        String execArgs = "";

        if (shortcut != null) {
            execArgs = shortcut.getExtra("execArgs");
            execArgs = !execArgs.isEmpty() ? " "+execArgs : "";

            if (shortcut.isLinkPath()) {
                cmdArgs = "/dir C:\\windows \"command\\start.exe\" \""+shortcut.path+"\""+execArgs;
            }
            else execPath = shortcut.path;
        }
        else {
            Intent intent = getIntent();
            if (intent.hasExtra("exec_path")) {
                execPath = WineUtils.unixToDOSPath(intent.getStringExtra("exec_path"), container);

                if (execPath.endsWith(".lnk")) {
                    cmdArgs = "/dir C:\\windows \"command\\start.exe\" \""+execPath+"\"";
                    execPath = null;
                }
            }
        }

        if (execPath != null) {
            String execDir = FileUtils.getDirname(execPath);
            String filename = FileUtils.getName(execPath);
            int dotIndex, spaceIndex;
            if ((dotIndex = filename.lastIndexOf(".")) != -1 && (spaceIndex = filename.indexOf(" ", dotIndex)) != -1) {
                execArgs = filename.substring(spaceIndex+1)+execArgs;
                filename = filename.substring(0, spaceIndex);
            }

            if (!execDir.isEmpty()) {
                cmdArgs = "/dir \"" + StringUtils.removeEndSlash(execDir) + "\" \"" + execPath + "\"" + execArgs;
            }
            else {
                cmdArgs = "\"" + filename + "\"" + execArgs;
            }
        }

        if (cmdArgs.isEmpty()) cmdArgs = "/dir \"C:\\windows\" \"C:\\windows\\wfm.exe\"";

        if (overrideEnvVars != null && overrideEnvVars.has("EXTRA_EXEC_ARGS")) {
            cmdArgs += " "+overrideEnvVars.get("EXTRA_EXEC_ARGS");
            overrideEnvVars.remove("EXTRA_EXEC_ARGS");
        }
        return "C:\\windows\\winhandler.exe "+cmdArgs;
    }

    public XServer getXServer() {
        return xServer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public XServerView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public RootFS getRootFs() {
        return rootFS;
    }

    public EnvVars getOverrideEnvVars() {
        if (overrideEnvVars == null) overrideEnvVars = new EnvVars();
        return overrideEnvVars;
    }

    public String getDXWrapper() {
        return dxwrapper;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public ScreenInfo getScreenInfo() {
        return screenInfo;
    }

    public void setScreenInfo(ScreenInfo screenInfo) {
        this.screenInfo = screenInfo;
    }

    public String getWinComponents() {
        return wincomponents;
    }

    public void setWinComponents(String wincomponents) {
        this.wincomponents = wincomponents;
    }

    public DebugDialog getDebugDialog() {
        return debugDialog;
    }

    public String getScreenEffectProfile() {
        return screenEffectProfile;
    }

    public void setScreenEffectProfile(String screenEffectProfile) {
        this.screenEffectProfile = screenEffectProfile;
    }

    public void setGameFpsLimit(int fps) {
        if (xServer != null) xServer.setPresentFpsLimit(fps);
    }

    public int getGameFpsLimit() {
        return xServer != null ? xServer.getPresentFpsLimit() : 0;
    }

    /**
     * Frame Pacing toggle for the Screen Effects dialog. Works together with
     * the FPS limiter: when ON, presents are spaced evenly on a stable,
     * drift-corrected timeline (smooth, no burst/jitter); when OFF, the
     * limiter only caps the rate with a coarse sleep (uneven spacing, slightly
     * lower latency). No-op while the FPS limit is Unlimited (no interval).
     */
    public void setFramePacingEnabled(boolean enabled) {
        if (xServer != null) xServer.setFramePacingEnabled(enabled);
    }

    public boolean isFramePacingEnabled() {
        return xServer == null || xServer.isFramePacingEnabled();
    }

    private void changeWineAudioDriver() {
        if (!audioDriver.equals(container.getExtra("audioDriver"))) {
            File rootDir = rootFS.getRootDir();
            File userRegFile = new File(rootDir, RootFS.WINEPREFIX+"/user.reg");
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                if (audioDriver.equals(AudioDrivers.ALSA)) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
                }
                else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
                }
            }
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = rootFS.getRootDir();
        FileUtils.delete(new File(rootDir, "/opt/apps"));
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "rootfs_patches.tzst", rootDir);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("dxwrapper", null);
        container.putExtra("desktopTheme", null);
        SettingsFragment.resetPreferenceVersions(this);
    }

    public void changeFrameRatingVisibility(Window window, boolean visible) {
        if (frameRating == null) return;
        if (visible) {
            if (window.id == frameRatingWindowId) return;
            Window child = window.getChildAt(0);
            boolean viewable = window.attributes.isMapped() && window.getWidth() >= ScreenInfo.MIN_WIDTH && window.getHeight() >= ScreenInfo.MIN_HEIGHT;
            Window frameRatingWindow = null;
            if (viewable && (window.isSurface() || (child != null && child.isSurface()))) {
                frameRatingWindow = window.isSurface() ? window : child;
            }
            else if (window.isSurface() && !window.isApplicationWindow()) {
                Window parent = window.getParent();
                if (parent != null && parent.isApplicationWindow() && !parent.isSurface()) frameRatingWindow = window;
            }

            if (frameRatingWindow != null) {
                Property gpuInfo = frameRatingWindow.getProperty(Atom._NET_WM_GPU_INFO);
                if (gpuInfo != null) frameRating.setGPUInfo(new String(gpuInfo.data.array()));
                frameRatingWindowId = frameRatingWindow.id;
                frameRating.reset();
                runOnUiThread(() -> frameRating.setVisibility(View.VISIBLE));
            }
        }
        else if (window.id == frameRatingWindowId) {
            frameRatingWindowId = -1;
            runOnUiThread(() -> frameRating.setVisibility(View.GONE));
        }
    }

    public void showHUDConfigDialog() {
        if (container == null) return;
        new HUDConfigDialog(this, container.getHUDConfig(), (newConfig) -> {
            container.setHUDConfig(newConfig);
            container.setShowHUD(true);
            container.saveData();
            if (frameRating == null) {
                frameRating = new FrameRating(this);
                rootView.addView(frameRating, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            // Keep the HUD pinned to this session's effective version (same
            // resolution as the launcher path above).
            String hudBox64Version = resolveSessionBox64Version(false);
            frameRating.setBox64Version(hudBox64Version);
            frameRating.setConfig(newConfig);
            frameRating.setVisibility(View.VISIBLE);
        }).show();
    }

    public boolean verifyUserRegistry() {
        File userRegFile = new File(rootFS.getRootDir(), RootFS.WINEPREFIX+"/user.reg");
        String lastModified = String.valueOf(userRegFile.lastModified());

        if (!lastModified.equals(container.getExtra("userRegLastModified"))) {
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                registryEditor.removeKey("Software\\Wow6432Node\\Wine", true);
            }

            container.putExtra("userRegLastModified", lastModified);
            return true;
        }
        else return false;
    }
}