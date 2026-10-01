package com.winlator.socialhub;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Official Videos tab: lists every public upload of the official YouTube
 * channel. Tapping a video opens it in the YouTube app automatically.
 */
public class OfficialVideosFragment extends Fragment {
    private Context ctx;
    private TextView offlineBg;
    private LinearLayout listContainer;
    private ProgressBar progress;
    private TextView statusText;
    private SwipeRefreshLayout swipeLayout;
    private final List<VideoItem> items = new ArrayList<>();
    private volatile boolean loading = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ctx = requireContext();
        FrameLayout root = new FrameLayout(ctx);

        offlineBg = new TextView(ctx);
        offlineBg.setText(NetUtils.OFFLINE_TEXT);
        offlineBg.setGravity(Gravity.CENTER);
        offlineBg.setTextSize(16f);
        offlineBg.setTextColor(0xFF888888);
        offlineBg.setPadding(dp(32), dp(32), dp(32), dp(32));
        root.addView(offlineBg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Pull-to-refresh (standard circle spinner) replaces the Refresh button.
        // Scroll-aware: only fires at the top of the list, never while scrolling back up.
        swipeLayout = new ChildScrollSwipeRefreshLayout(ctx);
        root.addView(swipeLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        swipeLayout.setOnRefreshListener(() -> loadVideos());

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        swipeLayout.addView(content, new SwipeRefreshLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout statusRow = new LinearLayout(ctx);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(16), dp(8), dp(16), dp(8));
        progress = new ProgressBar(ctx);
        progress.setVisibility(View.GONE);
        statusRow.addView(progress, new LinearLayout.LayoutParams(dp(28), dp(28)));
        statusText = new TextView(ctx);
        statusText.setTextSize(13f);
        statusText.setTextColor(0xFFAAAAAA);
        statusText.setPadding(dp(8), 0, 0, 0);
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(statusRow);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(12), dp(4), dp(12), dp(12));
        scroll.addView(listContainer);
        content.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        updateOfflineBg();
        loadVideos();
        return root;
    }

    private void updateOfflineBg() {
        if (offlineBg == null || ctx == null) return;
        offlineBg.setVisibility(NetUtils.isOnline(ctx) ? View.GONE : View.VISIBLE);
    }

    private void loadVideos() {
        if (loading) return;
        loading = true;
        updateOfflineBg();
        progress.setVisibility(View.VISIBLE);
        statusText.setText(ctx.getString(com.winlator.R.string.socialhub_loading_videos));
        new Thread(() -> {
            List<VideoItem> fetched = new ArrayList<>();
            String error = null;
            try {
                fetched = VideoApi.fetchVideos();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final List<VideoItem> result = fetched;
            final String err = error;
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                loading = false;
                progress.setVisibility(View.GONE);
                if (swipeLayout != null) swipeLayout.setRefreshing(false);
                updateOfflineBg();
                items.clear();
                items.addAll(result);
                renderList();
                if (result.isEmpty()) {
                    if (!NetUtils.isOnline(ctx)) {
                        statusText.setText(NetUtils.OFFLINE_TEXT);
                    } else if (err != null) {
                        statusText.setText(ctx.getString(com.winlator.R.string.socialhub_load_failed, err));
                    } else {
                        statusText.setText(ctx.getString(com.winlator.R.string.socialhub_no_videos));
                    }
                } else {
                    statusText.setText(ctx.getString(com.winlator.R.string.socialhub_videos_count, result.size()));
                }
            });
        }, "socialhub-videos").start();
    }

    private void renderList() {
        listContainer.removeAllViews();
        for (VideoItem item : items) {
            listContainer.addView(makeCard(item));
        }
    }

    private View makeCard(VideoItem item) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundColor(0xFF1E1E1E);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        FrameLayout thumbWrap = new FrameLayout(ctx);
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(128), dp(72));
        thumbLp.rightMargin = dp(10);
        card.addView(thumbWrap, thumbLp);
        ImageView thumb = new ImageView(ctx);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbWrap.addView(thumb, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageLoader.load(item.thumbnailUrl(), thumb, 320, 180);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        card.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(ctx);
        title.setText(item.title != null && !item.title.isEmpty() ? item.title : item.videoId);
        title.setTextSize(14f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFFFFFFFF);
        title.setMaxLines(3);
        textCol.addView(title);
        String metaLine = item.metaLine();
        if (metaLine != null && !metaLine.isEmpty()) {
            TextView meta = new TextView(ctx);
            meta.setText(metaLine);
            meta.setTextSize(12f);
            meta.setTextColor(0xFF888888);
            meta.setPadding(0, dp(4), 0, 0);
            textCol.addView(meta);
        }
        TextView play = new TextView(ctx);
        play.setText(ctx.getString(com.winlator.R.string.socialhub_watch_on_youtube));
        play.setTextSize(13f);
        play.setTextColor(0xFFFF8A80);
        play.setPadding(0, dp(4), 0, 0);
        textCol.addView(play);

        card.setOnClickListener(v -> openInYouTube(item));
        return card;
    }

    private void openInYouTube(VideoItem item) {
        // 1) Explicit YouTube app deep link. 2) Generic view (lets the
        // system/youtube app handle it). 3) Browser fallback.
        try {
            Intent appIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + item.videoId));
            appIntent.setPackage("com.google.android.youtube");
            startActivity(appIntent);
            return;
        } catch (ActivityNotFoundException ignored) {}
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.watchUrl())));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_no_app_to_open), Toast.LENGTH_SHORT).show();
        }
    }

    public void refreshOfflineState() {
        updateOfflineBg();
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
