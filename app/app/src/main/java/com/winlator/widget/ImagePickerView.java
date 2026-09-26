package com.winlator.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.ImageUtils;
import com.winlator.core.WineThemeManager;

import java.io.File;
import java.util.ArrayList;

public class ImagePickerView extends FrameLayout {
    public interface OnChangeSourceListener {
        void onChangeSource(String source);
    }
    private String selectedSource = WineThemeManager.DEFAULT_WALLPAPER_ID;
    private OnChangeSourceListener onChangeSourceListener;
    private final ImageView imageView;

    public ImagePickerView(@NonNull Context context) {
        this(context, null);
    }

    public ImagePickerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ImagePickerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(imageView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setBackgroundResource(R.drawable.combo_box);
        setClickable(true);
        setFocusable(true);
        updatePreview();
    }

    public String getSelectedSource() {
        return selectedSource;
    }

    public void setSelectedSource(String selectedSource) {
        this.selectedSource = selectedSource;
        updatePreview();
    }

    public OnChangeSourceListener getOnChangeSourceListener() {
        return onChangeSourceListener;
    }

    public void setOnChangeSourceListener(OnChangeSourceListener onChangeSourceListener) {
        this.onChangeSourceListener = onChangeSourceListener;
    }

    private void updatePreview() {
        Context context = getContext();
        if (selectedSource == null || selectedSource.equals("solid_color")) {
            imageView.setImageResource(android.R.color.transparent);
            return;
        }

        Bitmap bitmap = null;
        if (selectedSource.startsWith("wallpaper-")) {
            bitmap = ImageUtils.getBitmapFromAsset(context, "wallpapers/" + selectedSource + "/image.png");
        }
        else {
            File wallpaperFile = WineThemeManager.getUserWallpaperFile(context, selectedSource);
            if (wallpaperFile.isFile()) {
                bitmap = BitmapFactory.decodeFile(wallpaperFile.getPath());
            }
        }

        if (bitmap == null) {
            bitmap = ImageUtils.getBitmapFromAsset(context, "wallpapers/" + WineThemeManager.DEFAULT_WALLPAPER_ID + "/image.png");
        }

        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
        }
        else {
            imageView.setImageResource(android.R.color.transparent);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        Context context = getContext();
        showPopupWindow(this, context);
        return true;
    }

    private void showPopupWindow(View anchor, Context context) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.image_picker_view, null, false);
        final PopupWindow[] popupWindow = {null};

        ArrayList<String> sources = new ArrayList<>();
        sources.add("wallpaper-1");
        sources.add("wallpaper-2");
        sources.add("wallpaper-3");

        File userWallpapersDir = WineThemeManager.getUserWallpapersDir(context);
        File[] userFiles = userWallpapersDir.listFiles();
        if (userFiles != null) {
            for (File file : userFiles) {
                if (file.isFile() && (file.getName().endsWith(".png") || file.getName().endsWith(".jpg") || file.getName().endsWith(".jpeg"))) {
                    sources.add(file.getName());
                }
            }
        }

        File legacyUserWallpaper = new File(context.getFilesDir(), "user_wallpaper.png");
        if (legacyUserWallpaper.exists() && !sources.contains("user_wallpaper.png")) {
            sources.add("user_wallpaper.png");
        }

        LinearLayout llImageList = view.findViewById(R.id.LLImageList);

        for (String source : sources) {
            final View itemView = inflater.inflate(R.layout.image_picker_list_item, llImageList, false);
            ImageView itemImageView = itemView.findViewById(R.id.ImageView);

            if (source.startsWith("wallpaper-")) {
                Bitmap bitmap = ImageUtils.getBitmapFromAsset(context, "wallpapers/" + source + "/image.png");
                itemImageView.setImageBitmap(bitmap);
            }
            else {
                File wallpaperFile = WineThemeManager.getUserWallpaperFile(context, source);
                if (wallpaperFile.isFile()) {
                    Bitmap bitmap = BitmapFactory.decodeFile(wallpaperFile.getPath());
                    itemImageView.setImageBitmap(bitmap);
                }
            }

            View removeButton = itemView.findViewById(R.id.BTRemove);
            boolean isUserWallpaper = !source.startsWith("wallpaper-") && !source.equals("solid_color");
            if (isUserWallpaper) {
                removeButton.setVisibility(View.VISIBLE);
                removeButton.setOnClickListener((v) -> {
                    File wallpaperFile = WineThemeManager.getUserWallpaperFile(context, source);
                    FileUtils.delete(wallpaperFile);
                    if (source.equals(selectedSource)) {
                        selectedSource = WineThemeManager.DEFAULT_WALLPAPER_ID;
                        updatePreview();
                        if (onChangeSourceListener != null) onChangeSourceListener.onChangeSource(selectedSource);
                    }
                    popupWindow[0].dismiss();
                });
            }

            if (source.equals(selectedSource)) itemView.setBackgroundResource(R.drawable.bordered_panel);

            itemView.setOnClickListener((v) -> {
                selectedSource = source;
                updatePreview();
                if (onChangeSourceListener != null) onChangeSourceListener.onChangeSource(selectedSource);
                popupWindow[0].dismiss();
            });
            llImageList.addView(itemView);
        }

        View browseButton = view.findViewById(R.id.BTBrowse);
        browseButton.setOnClickListener((v) -> {
            MainActivity activity = (MainActivity)AppUtils.getActivity(context);
            if (activity == null) return;

            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

            activity.setOpenIntentCallback((data) -> {
                if (data == null) return;
                ArrayList<Uri> uris = new ArrayList<>();
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    for (int i = 0; i < count; i++) {
                        uris.add(data.getClipData().getItemAt(i).getUri());
                    }
                }
                else if (data.getData() != null) {
                    uris.add(data.getData());
                }

                if (uris.isEmpty()) return;

                File wallpapersDir = WineThemeManager.getUserWallpapersDir(context);
                String lastSavedName = null;
                long timestamp = System.currentTimeMillis();

                for (int i = 0; i < uris.size(); i++) {
                    Uri uri = uris.get(i);
                    Bitmap bitmap = ImageUtils.getBitmapFromUri(context, uri, 1280);
                    if (bitmap != null) {
                        String filename = "user_wallpaper_" + timestamp + "_" + i + ".png";
                        File targetFile = new File(wallpapersDir, filename);
                        ImageUtils.save(bitmap, targetFile, Bitmap.CompressFormat.PNG, 100);
                        lastSavedName = filename;
                    }
                }

                if (lastSavedName != null) {
                    selectedSource = lastSavedName;
                    updatePreview();
                    if (onChangeSourceListener != null) onChangeSourceListener.onChangeSource(selectedSource);
                }
                popupWindow[0].dismiss();
            });
            activity.startActivityForResult(intent, MainActivity.OPEN_FILE_REQUEST_CODE);
        });

        popupWindow[0] = AppUtils.showPopupWindow(anchor, view, 0, 200);
    }
}