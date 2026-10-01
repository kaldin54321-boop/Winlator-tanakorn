package com.winlator.socialhub;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * News Feed tab: fetches news from the official website and renders the
 * selected article fully inside the app (cover image + inline images),
 * without ever opening an external browser.
 */
public class NewsFeedFragment extends Fragment {
    private Context ctx;
    private FrameLayout root;
    private TextView offlineBg;
    private LinearLayout listContainer;
    private ScrollView listScroll;
    private LinearLayout detailContainer;
    private ScrollView detailScroll;
    private ProgressBar progress;
    private TextView statusText;
    private SwipeRefreshLayout swipeLayout;
    private final List<NewsItem> items = new ArrayList<>();
    private volatile boolean loading = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ctx = requireContext();
        root = new FrameLayout(ctx);

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
        swipeLayout.setOnRefreshListener(() -> loadNews());

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

        listScroll = new ScrollView(ctx);
        listScroll.setFillViewport(true);
        listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(12), dp(4), dp(12), dp(12));
        listScroll.addView(listContainer);
        content.addView(listScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        detailScroll = new ScrollView(ctx);
        detailScroll.setFillViewport(true);
        detailScroll.setVisibility(View.GONE);
        detailContainer = new LinearLayout(ctx);
        detailContainer.setOrientation(LinearLayout.VERTICAL);
        detailContainer.setPadding(dp(16), dp(12), dp(16), dp(24));
        detailScroll.addView(detailContainer);
        content.addView(detailScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        updateOfflineBg();
        loadNews();
        return root;
    }

    private void updateOfflineBg() {
        if (offlineBg == null || ctx == null) return;
        offlineBg.setVisibility(NetUtils.isOnline(ctx) ? View.GONE : View.VISIBLE);
    }

    private void loadNews() {
        if (loading) return;
        loading = true;
        updateOfflineBg();
        showLoading(true, ctx.getString(com.winlator.R.string.socialhub_loading_news));
        new Thread(() -> {
            List<NewsItem> fetched = new ArrayList<>();
            String error = null;
            try {
                fetched = NewsApi.fetchList();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final List<NewsItem> result = fetched;
            final String err = error;
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                loading = false;
                showLoading(false, null);
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
                        statusText.setText(ctx.getString(com.winlator.R.string.socialhub_no_news));
                    }
                } else {
                    statusText.setText("");
                }
            });
        }, "socialhub-news").start();
    }

    private void showLoading(boolean show, String msg) {
        progress.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && msg != null) statusText.setText(msg);
    }

    private void renderList() {
        listContainer.removeAllViews();
        showDetail(false);
        for (NewsItem item : items) {
            listContainer.addView(makeCard(item));
        }
    }

    private View makeCard(NewsItem item) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF1E1E1E);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        if (item.coverImageUrl != null && !item.coverImageUrl.isEmpty()) {
            ImageView cover = new ImageView(ctx);
            cover.setAdjustViewBounds(true);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setMinimumHeight(dp(140));
            cover.setMaxHeight(dp(220));
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(180));
            imgLp.bottomMargin = dp(8);
            card.addView(cover, imgLp);
            ImageLoader.load(item.coverImageUrl, cover);
        }
        TextView title = new TextView(ctx);
        title.setText(item.title);
        title.setTextSize(16f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFFFFFFFF);
        card.addView(title);
        if (item.date != null && !item.date.isEmpty()) {
            TextView date = new TextView(ctx);
            date.setText(item.date);
            date.setTextSize(12f);
            date.setTextColor(0xFF888888);
            date.setPadding(0, dp(4), 0, 0);
            card.addView(date);
        }
        if (item.summary != null && !item.summary.isEmpty()) {
            TextView summary = new TextView(ctx);
            summary.setText(item.summary);
            summary.setTextSize(13f);
            summary.setTextColor(0xFFBBBBBB);
            summary.setMaxLines(3);
            summary.setPadding(0, dp(4), 0, 0);
            card.addView(summary);
        }
        Button read = new Button(ctx);
        read.setText(ctx.getString(com.winlator.R.string.socialhub_read_more));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(8);
        card.addView(read, btnLp);
        View.OnClickListener open = v -> openDetail(item);
        card.setOnClickListener(open);
        read.setOnClickListener(open);
        return card;
    }

    private void openDetail(NewsItem item) {
        showDetail(true);
        renderDetailLoading(item);
        new Thread(() -> {
            String error = null;
            if (!item.detailLoaded) {
                try {
                    NewsApi.fetchDetail(item);
                } catch (Exception e) {
                    error = e.getMessage();
                }
            }
            final String err = error;
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (err != null && !item.detailLoaded) {
                    renderDetailError(item, err);
                } else {
                    renderDetail(item);
                }
            });
        }, "socialhub-news-detail").start();
    }

    private void showDetail(boolean show) {
        listScroll.setVisibility(show ? View.GONE : View.VISIBLE);
        detailScroll.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void renderDetailLoading(NewsItem item) {
        detailContainer.removeAllViews();
        detailScroll.scrollTo(0, 0);
        Button back = makeBackButton();
        detailContainer.addView(back);
        TextView title = new TextView(ctx);
        title.setText(item.title);
        title.setTextSize(20f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(0, dp(8), 0, dp(8));
        detailContainer.addView(title);
        TextView loading = new TextView(ctx);
        loading.setText(ctx.getString(com.winlator.R.string.socialhub_loading_article));
        loading.setTextColor(0xFFAAAAAA);
        detailContainer.addView(loading);
    }

    private void renderDetailError(NewsItem item, String error) {
        detailContainer.removeAllViews();
        detailContainer.addView(makeBackButton());
        TextView title = new TextView(ctx);
        title.setText(item.title);
        title.setTextSize(20f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFFFFFFFF);
        detailContainer.addView(title);
        TextView err = new TextView(ctx);
        err.setText(ctx.getString(com.winlator.R.string.socialhub_load_failed, error != null ? error : ""));
        err.setTextColor(0xFFFF8A80);
        err.setPadding(0, dp(12), 0, 0);
        detailContainer.addView(err);
        if (item.summary != null && !item.summary.isEmpty()) {
            TextView s = new TextView(ctx);
            s.setText(item.summary);
            s.setTextColor(0xFFBBBBBB);
            s.setPadding(0, dp(8), 0, 0);
            detailContainer.addView(s);
        }
    }

    private void renderDetail(NewsItem item) {
        detailContainer.removeAllViews();
        detailScroll.scrollTo(0, 0);
        detailContainer.addView(makeBackButton());
        TextView title = new TextView(ctx);
        title.setText(item.title);
        title.setTextSize(20f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(0, dp(8), 0, dp(4));
        detailContainer.addView(title);
        if (item.date != null && !item.date.isEmpty()) {
            TextView date = new TextView(ctx);
            date.setText(item.date);
            date.setTextSize(12f);
            date.setTextColor(0xFF888888);
            date.setPadding(0, 0, 0, dp(8));
            detailContainer.addView(date);
        }
        if (item.coverImageUrl != null && !item.coverImageUrl.isEmpty()) {
            ImageView cover = new ImageView(ctx);
            cover.setAdjustViewBounds(true);
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            imgLp.bottomMargin = dp(12);
            detailContainer.addView(cover, imgLp);
            ImageLoader.load(item.coverImageUrl, cover);
        }
        int imageIdx = 0;
        if (item.paragraphs.isEmpty() && item.summary != null && !item.summary.isEmpty()) {
            item.paragraphs.add(item.summary);
        }
        for (int i = 0; i < item.paragraphs.size(); i++) {
            TextView p = new TextView(ctx);
            p.setText(item.paragraphs.get(i));
            p.setTextSize(15f);
            p.setTextColor(0xFFE0E0E0);
            p.setLineSpacing(dp(4), 1.0f);
            p.setPadding(0, 0, 0, dp(10));
            detailContainer.addView(p);
            // Interleave inline article images between paragraphs.
            if (imageIdx < item.bodyImageUrls.size() && (i % 2 == 1 || i == item.paragraphs.size() - 1)) {
                String imgUrl = item.bodyImageUrls.get(imageIdx++);
                if (item.coverImageUrl == null || !imgUrl.equals(item.coverImageUrl)) {
                    ImageView inline = new ImageView(ctx);
                    inline.setAdjustViewBounds(true);
                    LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    imgLp.bottomMargin = dp(10);
                    detailContainer.addView(inline, imgLp);
                    ImageLoader.load(imgUrl, inline);
                }
            }
        }
        while (imageIdx < item.bodyImageUrls.size()) {
            String imgUrl = item.bodyImageUrls.get(imageIdx++);
            if (item.coverImageUrl != null && imgUrl.equals(item.coverImageUrl)) continue;
            ImageView inline = new ImageView(ctx);
            inline.setAdjustViewBounds(true);
            detailContainer.addView(inline);
            ImageLoader.load(imgUrl, inline);
        }
    }

    private Button makeBackButton() {
        Button back = new Button(ctx);
        back.setText(ctx.getString(com.winlator.R.string.socialhub_back));
        back.setOnClickListener(v -> showDetail(false));
        return back;
    }

    /** Returns true if the back press was consumed (detail -> list). */
    public boolean onBackPressed() {
        if (detailScroll != null && detailScroll.getVisibility() == View.VISIBLE) {
            showDetail(false);
            return true;
        }
        return false;
    }

    public void refreshOfflineState() {
        updateOfflineBg();
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
