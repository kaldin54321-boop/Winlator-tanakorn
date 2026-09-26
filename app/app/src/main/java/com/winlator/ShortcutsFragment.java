package com.winlator;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.winlator.container.Container;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.CreateFolderDialog;
import com.winlator.contentdialog.ShortcutSettingsDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.ArrayUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.FileUtils;
import com.winlator.core.KeyValueSet;
import com.winlator.core.StringUtils;
import com.winlator.core.WineUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class ShortcutsFragment extends BaseFileManagerFragment<Shortcut> {
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewStyle = ViewStyle.valueOf(preferences.getString("shortcuts_view_style", "GRID"));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        FloatingActionButton addButton = view.findViewById(R.id.BTAdd);
        if (addButton != null) {
            addButton.setVisibility(View.VISIBLE);
            addButton.setOnClickListener(this::showAddShortcutMenu);
        }
    }

    @Override
    public void refreshContent() {
        super.refreshContent();

        Shortcut selectedFolder = !folderStack.isEmpty() ? folderStack.peek() : null;
        ArrayList<Shortcut> shortcuts = manager.loadShortcuts(selectedFolder);
        recyclerView.setAdapter(new ShortcutsAdapter(shortcuts));
        emptyTextView.setVisibility(shortcuts.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.shortcuts_menu, menu);
        refreshViewStyleMenuItem(menu.findItem(R.id.menu_item_view_style));
    }

    private void showAddShortcutMenu(View anchorView) {
        PopupMenu popupMenu = new PopupMenu(getContext(), anchorView);
        Menu menu = popupMenu.getMenu();
        menu.add(0, 1, 0, R.string.new_folder);
        menu.add(0, 2, 1, R.string.import_shortcut);

        popupMenu.setOnMenuItemClickListener((menuItem) -> {
            if (menuItem.getItemId() == 1) {
                createFolder();
                return true;
            }
            else if (menuItem.getItemId() == 2) {
                importShortcut();
                return true;
            }
            return false;
        });
        popupMenu.show();
    }

    private void importShortcut() {
        if (manager.getContainers().isEmpty()) {
            AppUtils.showToast(getContext(), R.string.no_container_found);
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.setOpenFileCallback((uri) -> {
                if (uri != null) handleImportedShortcutUri(uri);
            });
            activity.startActivityForResult(intent, MainActivity.OPEN_FILE_REQUEST_CODE);
        }
    }

    private void handleImportedShortcutUri(Uri uri) {
        Context context = getContext();
        if (context == null) return;

        String filename = FileUtils.getNameFromUri(context, uri);
        if (filename.isEmpty()) filename = "imported_file";

        ArrayList<Container> containers = manager.getContainers();
        if (containers.isEmpty()) return;

        final String finalFilename = filename;
        if (!folderStack.isEmpty()) {
            Container container = folderStack.peek().container;
            processImportedShortcutFile(uri, finalFilename, container);
        }
        else if (containers.size() == 1) {
            processImportedShortcutFile(uri, finalFilename, containers.get(0));
        }
        else {
            String[] items = new String[containers.size()];
            for (int i = 0; i < containers.size(); i++) items[i] = containers.get(i).getName();
            ContentDialog.showSelectionList(context, R.string.select_target_container, items, false, (selectedIndices) -> {
                if (!selectedIndices.isEmpty()) {
                    Container selectedContainer = containers.get(selectedIndices.get(0));
                    processImportedShortcutFile(uri, finalFilename, selectedContainer);
                }
            });
        }
    }

    private void processImportedShortcutFile(Uri uri, String filename, Container container) {
        Context context = getContext();
        if (context == null) return;

        File targetDir = !folderStack.isEmpty() ? folderStack.peek().file : new File(container.getUserDir(), "Desktop");
        if (!targetDir.exists()) {
            boolean created = targetDir.mkdirs();
            if (!created && !targetDir.exists()) return;
        }

        String lower = filename.toLowerCase();
        if (lower.endsWith(".desktop")) {
            File destFile = new File(targetDir, filename);
            int counter = 1;
            String baseName = FileUtils.getBasename(filename);
            while (destFile.exists()) {
                destFile = new File(targetDir, baseName + " (" + counter + ").desktop");
                counter++;
            }

            try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
                 FileOutputStream outputStream = new FileOutputStream(destFile)) {
                if (inputStream != null) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                    refreshContent();
                    AppUtils.showToast(context, R.string.shortcut_imported_successfully);
                }
            }
            catch (Exception e) {}
        }
        else if (lower.endsWith(".exe")) {
            String realPath = FileUtils.getFilePathFromUri(context, uri);
            if (realPath == null && "file".equalsIgnoreCase(uri.getScheme())) {
                realPath = uri.getPath();
            }

            if (realPath != null) {
                try {
                    File file = new File(realPath);
                    if (file.exists()) realPath = file.getCanonicalPath();
                }
                catch (Exception e) {}
            }

            String dosPath = (realPath != null && new File(realPath).exists()) ? WineUtils.unixToDOSPath(realPath, container) : "";

            if (!dosPath.contains(":")) {
                File exeFile = new File(targetDir, filename);
                try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
                     FileOutputStream outputStream = new FileOutputStream(exeFile)) {
                    if (inputStream != null) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                        }
                    }
                }
                catch (Exception e) {
                    return;
                }
                realPath = exeFile.getAbsolutePath();
                dosPath = WineUtils.unixToDOSPath(realPath, container);
            }
            String shortcutName = FileUtils.getBasename(filename);
            File desktopFile = new File(targetDir, shortcutName + ".desktop");
            int counter = 1;
            while (desktopFile.exists()) {
                desktopFile = new File(targetDir, shortcutName + " (" + counter + ").desktop");
                counter++;
            }

            String content = "[Desktop Entry]\n" +
                "Name=" + shortcutName + "\n" +
                "Exec=env WINEPREFIX=\"/home/winuser/.wine\" wine \"" + StringUtils.escapeDOSPath(dosPath) + "\"\n" +
                "Type=Application\n" +
                "StartupNotify=true\n" +
                "Icon=\n";

            FileUtils.writeString(desktopFile, content);
            refreshContent();
            AppUtils.showToast(context, R.string.shortcut_imported_successfully);
        }
        else {
            AppUtils.showToast(context, R.string.unsupported_file_format);
        }
    }

    private void createFolder() {
        clearClipboard();
        if (manager.getContainers().isEmpty()) return;
        CreateFolderDialog createFolderDialog = new CreateFolderDialog(manager);
        createFolderDialog.setOnCreateFolderListener((container, name) -> {
            File desktopDir = new File(container.getUserDir(), "Desktop");
            File parent = !folderStack.isEmpty() ? folderStack.peek().file : desktopDir;
            File file = new File(parent, name);
            if (file.isDirectory()) {
                AppUtils.showToast(getContext(), R.string.there_already_file_with_that_name);
            }
            else {
                file.mkdir();
                refreshContent();
            }
        });
        createFolderDialog.show();
    }

    @Override
    protected void pasteFiles() {
        if (folderStack.isEmpty()) {
            clearClipboard();
            AppUtils.showToast(getContext(), R.string.you_cannot_paste_files_here);
            return;
        }

        clipboard.targetDir = folderStack.peek().file;
        super.pasteFiles();
    }

    private void instantiateClipboard(Shortcut shortcut, boolean cutMode) {
        clearClipboard();
        File[] files = {new File(shortcut.file.getParentFile(), shortcut.file.getName())};
        if (shortcut.file.isFile() && !shortcut.isLinkPath()) {
            File linkFile = shortcut.getLinkFile();
            files = ArrayUtils.concat(files, new File[]{new File(linkFile.getParentFile(), linkFile.getName())});
        }

        clipboard = new Clipboard(files, cutMode);
        setPasteButtonPosition(true);
        pasteButton.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        if (itemId == R.id.menu_item_view_style) {
            setViewStyle(viewStyle == ViewStyle.GRID ? ViewStyle.LIST : ViewStyle.GRID);
            preferences.edit().putString("shortcuts_view_style", viewStyle.name()).apply();
            refreshViewStyleMenuItem(menuItem);
            return true;
        }
        else return super.onOptionsItemSelected(menuItem);
    }

    @Override
    protected String getHomeTitle() {
        return getString(R.string.shortcuts);
    }

    private class ShortcutsAdapter extends RecyclerView.Adapter<ShortcutsAdapter.ViewHolder> {
        private final List<Shortcut> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView runButton;
            private final ImageView menuButton;
            private final ImageView imageView;
            private final TextView title;
            private final TextView subtitle;

            private ViewHolder(View view) {
                super(view);
                this.imageView = view.findViewById(R.id.ImageView);
                this.title = view.findViewById(R.id.TVTitle);
                this.subtitle = view.findViewById(R.id.TVSubtitle);
                this.runButton = view.findViewById(R.id.BTRun);
                this.menuButton = view.findViewById(R.id.BTMenu);
            }
        }

        public ShortcutsAdapter(List<Shortcut> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int resource = viewStyle == ViewStyle.LIST ? R.layout.file_list_item : R.layout.file_grid_item;
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(resource, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final Shortcut item = data.get(position);

            if (item.icon == null) {
                int iconResId = item.file.isDirectory() ? R.drawable.container_folder : R.drawable.container_file_link;
                holder.imageView.setImageResource(iconResId);
            }
            else holder.imageView.setImageBitmap(item.icon);

            holder.title.setText(item.name);
            if (viewStyle == ViewStyle.LIST) {
                holder.subtitle.setMaxLines(5);
                holder.subtitle.setSingleLine(false);
                holder.subtitle.setText(buildShortcutDetailText(holder.itemView.getContext(), item));
            }
            else {
                holder.subtitle.setMaxLines(1);
                holder.subtitle.setSingleLine(true);
                holder.subtitle.setText(item.container.getName());
            }

            if (item.file.isDirectory()) {
                holder.runButton.setImageResource(R.drawable.icon_open);
            }
            else holder.runButton.setImageResource(R.drawable.icon_run);

            holder.imageView.setOnClickListener((v) -> runFromShortcut(item));
            holder.runButton.setOnClickListener((v) -> runFromShortcut(item));
            holder.menuButton.setOnClickListener((v) -> showListItemMenu(v, item));
        }

        @Override
        public final int getItemCount() {
            return data.size();
        }

        private String buildShortcutDetailText(Context context, Shortcut shortcut) {
            Container container = shortcut.container;
            if (shortcut.file.isDirectory()) return container.getName();
            String screenSize = shortcut.getExtra("screenSize", container.getScreenSize());
            String wineLabel = ContainersFragment.getWineLabel(context, container);

            String graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
            String[] graphicsIds = GraphicsDrivers.parseIdentifiers(graphicsDriver);
            String vkId = graphicsIds.length > 0 ? graphicsIds[0] : GraphicsDrivers.DEFAULT_VULKAN_DRIVER;
            String glId = graphicsIds.length > 1 ? graphicsIds[1] : GraphicsDrivers.DEFAULT_OPENGL_DRIVER;
            String graphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
            KeyValueSet[] graphicsConfigs = GraphicsDrivers.parseConfigs(graphicsDriver, graphicsDriverConfig);
            String vkVersion = graphicsConfigs.length > 0 ? graphicsConfigs[0].get("version", DefaultVersion.valueOf(vkId.toUpperCase(java.util.Locale.ENGLISH))) : DefaultVersion.valueOf(vkId.toUpperCase(java.util.Locale.ENGLISH));
            String glVersion = graphicsConfigs.length > 1 ? graphicsConfigs[1].get("version", DefaultVersion.valueOf(glId.toUpperCase(java.util.Locale.ENGLISH))) : DefaultVersion.valueOf(glId.toUpperCase(java.util.Locale.ENGLISH));

            String dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
            String dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
            KeyValueSet[] dxConfigs = DXWrappers.parseConfigs(dxwrapper, dxwrapperConfig);
            String dxDefault;
            if (DXWrappers.VEGAS.equals(dxwrapper)) dxDefault = DefaultVersion.VEGAS();
            else if (DXWrappers.WINED3D.equals(dxwrapper)) dxDefault = DefaultVersion.WINED3D;
            else dxDefault = DefaultVersion.DXVK(vkId);
            String dxVersion = dxConfigs.length > 0 ? dxConfigs[0].get("version", dxDefault) : dxDefault;
            String vkd3dVersion = dxConfigs.length > 1 ? dxConfigs[1].get("version", DefaultVersion.VKD3D) : DefaultVersion.VKD3D;

            StringBuilder sb = new StringBuilder();
            sb.append(container.getName()).append(" | ").append(screenSize).append("\n");
            sb.append(wineLabel).append("\n");
            sb.append("VK: ").append(GraphicsDrivers.getName(vkId)).append(" ").append(vkVersion);
            sb.append(" | GL: ").append(GraphicsDrivers.getName(glId)).append(" ").append(glVersion).append("\n");
            sb.append("DX: ").append(DXWrappers.getName(dxwrapper)).append(" ").append(dxVersion);
            sb.append(" | DX12: VKD3D ").append(vkd3dVersion);
            return sb.toString();
        }

        private void showListItemMenu(View anchorView, final Shortcut shortcut) {
            final Context context = getContext();
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            listItemMenu.inflate(R.menu.file_manager_popup_menu);

            Menu menu = listItemMenu.getMenu();
            menu.findItem(R.id.menu_item_rename).setVisible(false);
            menu.findItem(R.id.menu_item_add_favorite).setVisible(false);
            menu.findItem(R.id.menu_item_info).setVisible(false);
            boolean isFile = !shortcut.file.isDirectory();
            menu.findItem(R.id.menu_item_add_to_home_screen).setVisible(isFile);
            menu.findItem(R.id.menu_item_export_to_frontend).setVisible(isFile);

            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                int itemId = menuItem.getItemId();
                switch (itemId) {
                    case R.id.menu_item_settings:
                        clearClipboard();
                        (new ShortcutSettingsDialog(ShortcutsFragment.this, shortcut)).show();
                        break;
                    case R.id.menu_item_copy:
                    case R.id.menu_item_cut:
                        instantiateClipboard(shortcut, itemId == R.id.menu_item_cut);
                        break;
                    case R.id.menu_item_remove:
                        clearClipboard();
                        ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_file, () -> {
                            shortcut.remove();
                            refreshContent();
                        });
                        break;
                    case R.id.menu_item_add_to_home_screen:
                        clearClipboard();
                        addShortcutToHomeScreen(shortcut);
                        break;
                    case R.id.menu_item_export_to_frontend:
                        clearClipboard();
                        exportShortcutToFrontend(shortcut);
                        break;
                }
                return true;
            });
            listItemMenu.show();
        }

        private Bitmap getShortcutIconBitmap(Context context, Shortcut shortcut) {
            if (shortcut.icon != null) return shortcut.icon;
            try {
                java.io.File[] iconDirs = {shortcut.container.getIconsDir(64), shortcut.container.getIconsDir(48),
                    shortcut.container.getIconsDir(32), shortcut.container.getIconsDir(16)};
                for (java.io.File iconDir : iconDirs) {
                    java.io.File iconFile = new java.io.File(iconDir, shortcut.name + ".png");
                    if (iconFile.isFile()) {
                        Bitmap bitmap = BitmapFactory.decodeFile(iconFile.getPath());
                        if (bitmap != null) return bitmap;
                    }
                }
            }
            catch (Exception e) {}
            try {
                Drawable drawable = context.getPackageManager().getApplicationIcon(context.getApplicationInfo());
                if (drawable instanceof BitmapDrawable) return ((BitmapDrawable)drawable).getBitmap();
                int width = Math.max(1, drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 96);
                int height = Math.max(1, drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 96);
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                drawable.draw(canvas);
                return bitmap;
            }
            catch (Exception e) {
                return null;
            }
        }

        private void addShortcutToHomeScreen(Shortcut shortcut) {
            Context context = getContext();
            if (context == null) return;
            if (shortcut.file.isDirectory()) return;
            if (shortcut.getExtra("uuid").isEmpty()) shortcut.genUUID();
            Bitmap iconBitmap = getShortcutIconBitmap(context, shortcut);
            if (iconBitmap == null) {
                AppUtils.showToast(context, R.string.unable_to_export_shortcut);
                return;
            }
            try {
                ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
                if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported()) {
                    Intent intent = new Intent(context, XServerDisplayActivity.class);
                    intent.setAction(Intent.ACTION_VIEW);
                    intent.putExtra("container_id", shortcut.container.id);
                    intent.putExtra("shortcut_path", shortcut.file.getPath());
                    ShortcutInfo pinShortcut = new ShortcutInfo.Builder(context, shortcut.getExtra("uuid"))
                        .setShortLabel(shortcut.name)
                        .setLongLabel(shortcut.name)
                        .setIcon(Icon.createWithBitmap(iconBitmap))
                        .setIntent(intent)
                        .build();
                    shortcutManager.requestPinShortcut(pinShortcut, null);
                    AppUtils.showToast(context, R.string.shortcut_added_to_home_screen);
                }
                else {
                    Intent shortcutIntent = new Intent(context, XServerDisplayActivity.class);
                    shortcutIntent.setAction(Intent.ACTION_MAIN);
                    shortcutIntent.putExtra("container_id", shortcut.container.id);
                    shortcutIntent.putExtra("shortcut_path", shortcut.file.getAbsolutePath());
                    Intent addIntent = new Intent();
                    addIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
                    addIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, shortcut.name);
                    addIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON, iconBitmap);
                    addIntent.setAction("com.android.launcher.action.INSTALL_SHORTCUT");
                    context.sendBroadcast(addIntent);
                    AppUtils.showToast(context, R.string.shortcut_added_to_home_screen);
                }
            }
            catch (Exception e) {
                AppUtils.showToast(context, R.string.shortcut_pinned_not_supported);
            }
        }

        private void exportShortcutToFrontend(Shortcut shortcut) {
            Context context = getContext();
            if (context == null) return;
            if (shortcut.file.isDirectory()) return;
            try {
                java.io.File exportDir = new java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Winlator/Frontend");
                if (!exportDir.isDirectory() && !exportDir.mkdirs()) {
                    AppUtils.showToast(context, R.string.unable_to_export_shortcut);
                    return;
                }
                String packageName = context.getPackageName();
                java.io.File instructionsFile = new java.io.File(exportDir, "FRONTEND_INSTRUCTIONS.txt");
                if (!instructionsFile.isFile()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Instructions for adding Winlator@Frost shortcuts to front-ends:\n\n");
                    sb.append("Daijisho / ES-DE (Pegasus):\n");
                    sb.append("1. Open your front-end\n");
                    sb.append("2. Add/import the metadata.pegasus.txt from this folder\n");
                    sb.append("3. Set the sync/ROM path to this folder\n");
                    sb.append("4. Launch the exported .desktop file for your game\n\n");
                    sb.append("Beacon / custom launchers:\n");
                    sb.append("Use a custom launch command:\n");
                    sb.append("am start -n ").append(packageName).append("/com.winlator.XServerDisplayActivity -e shortcut_path {file_path}\n");
                    FileUtils.writeString(instructionsFile, sb.toString());
                }
                java.io.File metadataFile = new java.io.File(exportDir, "metadata.pegasus.txt");
                if (!metadataFile.isFile()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("collection: Windows (Winlator@Frost)\n");
                    sb.append("shortname: winlator\n");
                    sb.append("extensions: desktop\n");
                    sb.append("launch: am start\n");
                    sb.append("  -n ").append(packageName).append("/com.winlator.XServerDisplayActivity\n");
                    sb.append("  -e shortcut_path {file.path}\n");
                    sb.append("  --activity-clear-task\n");
                    sb.append("  --activity-clear-top\n");
                    sb.append("  --activity-no-history\n");
                    FileUtils.writeString(metadataFile, sb.toString());
                }
                java.io.File sourceFile = shortcut.file;
                java.io.File exportFile = new java.io.File(exportDir, sourceFile.getName());
                java.util.List<String> lines = new java.util.ArrayList<>();
                boolean hasContainerId = false;
                for (String line : FileUtils.readLines(sourceFile)) {
                    if (line.startsWith("container_id:") || line.startsWith("container_id=")) {
                        lines.add("container_id:" + shortcut.container.id);
                        hasContainerId = true;
                    }
                    else lines.add(line);
                }
                if (!hasContainerId) lines.add("container_id:" + shortcut.container.id);
                StringBuilder out = new StringBuilder();
                for (String line : lines) out.append(line).append("\n");
                FileUtils.writeString(exportFile, out.toString());
                AppUtils.showToast(context, context.getString(R.string.frontend_shortcut_exported) + " " + exportFile.getPath());
            }
            catch (Exception e) {
                AppUtils.showToast(context, R.string.unable_to_export_shortcut);
            }
        }

        private void runFromShortcut(Shortcut shortcut) {
            AppCompatActivity activity = (AppCompatActivity)getActivity();

            if (shortcut.file.isDirectory()) {
                folderStack.push(shortcut);
                refreshContent();

                ActionBar actionBar = activity.getSupportActionBar();
                actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_back);
                actionBar.setTitle(shortcut.name);
            }
            else {
                Intent intent = new Intent(activity, XServerDisplayActivity.class);
                intent.putExtra("container_id", shortcut.container.id);
                intent.putExtra("shortcut_path", shortcut.file.getPath());
                activity.startActivity(intent);
            }
        }
    }
}
