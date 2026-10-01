package com.winlator.socialhub;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Patterns;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Forums tab: Reddit-style public conversations. Users create threads with
 * title, details, username, email and optional image/video attachments;
 * anyone can open a thread and post replies (username + email + reply).
 * Threads are synced through the shared website backend so every user sees
 * the same forums when online (see {@link ForumStore}).
 */
public class ForumsFragment extends Fragment {
    private static final int REQ_ATTACH_IMAGE = 1101;
    private static final int REQ_ATTACH_VIDEO = 1102;

    private Context ctx;
    private TextView offlineBg;
    private LinearLayout listContainer;
    private ScrollView listScroll;
    private ScrollView detailScroll;
    private LinearLayout detailContainer;
    private ProgressBar progress;
    private TextView statusText;
    private SwipeRefreshLayout swipeLayout;

    private final List<ForumPost> posts = new ArrayList<>();
    private ForumPost openPost;
    private volatile boolean loading = false;

    // Reply polling: re-sync once a minute while the tab is visible so authors
    // get notified of new replies without leaving the forums open. Online only.
    private static final long POLL_INTERVAL_MS = 60 * 1000L;
    private android.os.Handler pollHandler;
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (isResumed() && ctx != null && NetUtils.isOnline(ctx)) loadForums();
            } catch (Exception ignored) {}
            schedulePoll();
        }
    };

    // Pending attachment state for the "new forum" dialog.
    private String pendingImagePath;
    private String pendingVideoPath;
    private TextView pendingAttachLabel;

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

        // Pull-to-refresh (standard circle spinner): replaces the old Refresh
        // button. The scroll-aware subclass only fires when the visible list
        // is scrolled to the top, so scrolling back up never refreshes.
        swipeLayout = new ChildScrollSwipeRefreshLayout(ctx);
        root.addView(swipeLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        swipeLayout.setOnRefreshListener(() -> loadForums());

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

        Button fab = new Button(ctx);
        fab.setText(ctx.getString(com.winlator.R.string.socialhub_new_forum));
        fab.setOnClickListener(v -> showNewPostDialog());
        LinearLayout.LayoutParams fabLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fabLp.setMargins(dp(12), dp(4), dp(12), dp(12));
        content.addView(fab, fabLp);

        updateOfflineBg();
        loadForums();
        return root;
    }

    private void updateOfflineBg() {
        if (offlineBg == null || ctx == null) return;
        offlineBg.setVisibility(NetUtils.isOnline(ctx) ? View.GONE : View.VISIBLE);
    }

    private void loadForums() {
        if (loading) return;
        loading = true;
        updateOfflineBg();
        progress.setVisibility(View.VISIBLE);
        statusText.setText(ctx.getString(com.winlator.R.string.socialhub_loading_forums));
        new Thread(() -> {
            List<ForumPost> fetched = ForumStore.load(ctx.getApplicationContext());
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                loading = false;
                progress.setVisibility(View.GONE);
                if (swipeLayout != null) swipeLayout.setRefreshing(false);
                updateOfflineBg();
                posts.clear();
                posts.addAll(fetched);
                renderList();
                // Author notifications: runs only when online (load fetches
                // remote only on connection) and never spams baselines.
                try {
                    ForumNotifier.checkForNewReplies(ctx.getApplicationContext(), fetched);
                    ForumNotifier.pruneKnownThreads(ctx.getApplicationContext(), fetched);
                } catch (Exception ignored) {}
                if (fetched.isEmpty()) {
                    statusText.setText(NetUtils.isOnline(ctx)
                            ? ctx.getString(com.winlator.R.string.socialhub_no_forums)
                            : NetUtils.OFFLINE_TEXT);
                } else {
                    statusText.setText(ctx.getString(com.winlator.R.string.socialhub_forums_count, fetched.size()));
                }
            });
        }, "socialhub-forums").start();
    }

    // ---------- list ----------
    private void renderList() {
        showDetail(false);
        listContainer.removeAllViews();
        for (ForumPost post : posts) {
            listContainer.addView(makePostCard(post));
        }
    }

    private View makePostCard(ForumPost post) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF1E1E1E);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        TextView title = new TextView(ctx);
        title.setText(titledWithEdited(post.title, post.edited));
        title.setTextSize(16f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFFFFFFFF);
        card.addView(title);

        TextView meta = new TextView(ctx);
        meta.setText(ctx.getString(com.winlator.R.string.socialhub_forum_meta,
                nonEmpty(post.username, "?"), formatDate(post.timestamp))
                + editedSuffix(post.edited));
        meta.setTextSize(12f);
        meta.setTextColor(0xFF888888);
        meta.setPadding(0, dp(4), 0, 0);
        card.addView(meta);

        if (post.body != null && !post.body.isEmpty()) {
            TextView snippet = new TextView(ctx);
            String s = post.body.length() > 140 ? post.body.substring(0, 137) + "..." : post.body;
            snippet.setText(s);
            snippet.setTextSize(13f);
            snippet.setTextColor(0xFFBBBBBB);
            snippet.setMaxLines(3);
            snippet.setPadding(0, dp(6), 0, 0);
            card.addView(snippet);
        }
        String listImageUrl = attachmentImageUrl(post);
        if (listImageUrl != null) {
            ImageView thumb = new ImageView(ctx);
            thumb.setAdjustViewBounds(true);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
            imgLp.topMargin = dp(8);
            card.addView(thumb, imgLp);
            ImageLoader.load(listImageUrl, thumb, 640, 360);
            // Tap thumbnail -> fullscreen gallery-style viewer with pinch zoom.
            thumb.setOnClickListener(v -> showImageViewer(listImageUrl));
        }
        // Inline video badge: tapping the card opens detail where video plays in-app.
        if (hasVideo(post)) {
            TextView videoBadge = new TextView(ctx);
            videoBadge.setText("\u25B6 " + ctx.getString(com.winlator.R.string.socialhub_play_video));
            videoBadge.setTextSize(13f);
            videoBadge.setTextColor(0xFF7BC47F);
            videoBadge.setPadding(0, dp(6), 0, 0);
            card.addView(videoBadge);
        }
        TextView replies = new TextView(ctx);
        replies.setText(ctx.getResources().getQuantityString(
                com.winlator.R.plurals.socialhub_replies_count, post.replies.size(), post.replies.size()));
        replies.setTextSize(13f);
        replies.setTextColor(0xFF7BC47F);
        replies.setPadding(0, dp(6), 0, 0);
        card.addView(replies);

        // Edit / Delete are shown ONLY to the real author of the forum
        // (ownership persisted on this device). Others just open the thread.
        if (ForumStore.canManagePost(ctx.getApplicationContext(), post)) {
            LinearLayout cardManageRow = new LinearLayout(ctx);
            cardManageRow.setOrientation(LinearLayout.HORIZONTAL);
            cardManageRow.setPadding(0, dp(8), 0, 0);
            Button cardEdit = new Button(ctx);
            cardEdit.setText(ctx.getString(com.winlator.R.string.socialhub_edit));
            cardEdit.setTextSize(12f);
            cardEdit.setOnClickListener(v -> showEditPostDialog(post));
            Button cardDelete = new Button(ctx);
            cardDelete.setText(ctx.getString(com.winlator.R.string.socialhub_delete));
            cardDelete.setTextSize(12f);
            cardDelete.setOnClickListener(v -> confirmDeletePost(post));
            cardManageRow.addView(cardEdit, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            cardManageRow.addView(cardDelete, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(cardManageRow);
        }

        card.setOnClickListener(v -> openPost(post));
        // Long-press: authors get Open / Edit / Delete, others just open.
        card.setOnLongClickListener(v -> {
            if (ForumStore.canManagePost(ctx.getApplicationContext(), post)) {
                showManagePostDialog(post);
            } else {
                openPost(post);
            }
            return true;
        });
        return card;
    }

    // ---------- detail ----------
    private void openPost(ForumPost post) {
        openPost = post;
        showDetail(true);
        renderDetail();
    }

    private void showDetail(boolean show) {
        if (listScroll == null) return;
        listScroll.setVisibility(show ? View.GONE : View.VISIBLE);
        detailScroll.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void renderDetail() {
        detailContainer.removeAllViews();
        detailScroll.scrollTo(0, 0);
        final ForumPost post = openPost;
        if (post == null) return;

        Button back = new Button(ctx);
        back.setText(ctx.getString(com.winlator.R.string.socialhub_back));
        back.setOnClickListener(v -> { openPost = null; renderList(); });
        detailContainer.addView(back);

        TextView title = new TextView(ctx);
        title.setText(titledWithEdited(post.title, post.edited));
        title.setTextSize(20f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(0, dp(8), 0, dp(4));
        detailContainer.addView(title);

        TextView meta = new TextView(ctx);
        meta.setText(ctx.getString(com.winlator.R.string.socialhub_forum_meta,
                nonEmpty(post.username, "?"), formatDate(post.timestamp))
                + editedSuffix(post.edited));
        meta.setTextSize(12f);
        meta.setTextColor(0xFF888888);
        detailContainer.addView(meta);

        // Edit / Delete for the forum, visible only to its real author.
        // Ownership is persisted on this device; the dialogs additionally
        // verify the author email for safety.
        if (ForumStore.canManagePost(ctx.getApplicationContext(), post)) {
            LinearLayout manageRow = new LinearLayout(ctx);
            manageRow.setOrientation(LinearLayout.HORIZONTAL);
            manageRow.setPadding(0, dp(4), 0, 0);
            Button editBtn = new Button(ctx);
            editBtn.setText(ctx.getString(com.winlator.R.string.socialhub_edit));
            editBtn.setOnClickListener(v -> showEditPostDialog(post));
            Button deleteBtn = new Button(ctx);
            deleteBtn.setText(ctx.getString(com.winlator.R.string.socialhub_delete));
            deleteBtn.setOnClickListener(v -> confirmDeletePost(post));
            manageRow.addView(editBtn, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            manageRow.addView(deleteBtn, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            detailContainer.addView(manageRow);
        }

        TextView body = new TextView(ctx);
        body.setText(post.body);
        body.setTextSize(15f);
        body.setTextColor(0xFFE0E0E0);
        body.setPadding(0, dp(8), 0, dp(8));
        detailContainer.addView(body);

        String detailImageUrl = attachmentImageUrl(post);
        if (detailImageUrl != null) {
            ImageView image = new ImageView(ctx);
            image.setAdjustViewBounds(true);
            detailContainer.addView(image);
            ImageLoader.load(detailImageUrl, image);
            // Tap -> fullscreen gallery-style viewer with pinch-to-zoom.
            image.setOnClickListener(v -> showImageViewer(detailImageUrl));
            TextView zoomHint = new TextView(ctx);
            zoomHint.setText(ctx.getString(com.winlator.R.string.socialhub_tap_to_zoom));
            zoomHint.setTextSize(12f);
            zoomHint.setTextColor(0xFF888888);
            zoomHint.setPadding(0, dp(4), 0, 0);
            detailContainer.addView(zoomHint);
        }
        // Video plays in a separate fullscreen player (no external browser,
        // no inline playback inside the thread). Tap to open it.
        if (hasVideo(post)) {
            Button watchBtn = new Button(ctx);
            watchBtn.setText("\u25B6 " + ctx.getString(com.winlator.R.string.socialhub_play_fullscreen));
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.topMargin = dp(8);
            detailContainer.addView(watchBtn, wlp);
            watchBtn.setOnClickListener(v -> showFullscreenVideoPlayer(post.videoPath));
        }

        TextView repliesHeader = new TextView(ctx);
        repliesHeader.setText(ctx.getResources().getQuantityString(
                com.winlator.R.plurals.socialhub_replies_count, post.replies.size(), post.replies.size()));
        repliesHeader.setTextSize(16f);
        repliesHeader.setTypeface(null, Typeface.BOLD);
        repliesHeader.setTextColor(0xFFFFFFFF);
        repliesHeader.setPadding(0, dp(12), 0, dp(8));
        detailContainer.addView(repliesHeader);

        if (post.replies.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText(ctx.getString(com.winlator.R.string.socialhub_no_replies));
            empty.setTextColor(0xFF888888);
            detailContainer.addView(empty);
        } else {
            for (ForumPost.ForumReply reply : post.replies) {
                detailContainer.addView(makeReplyView(post, reply));
            }
        }

        Button replyBtn = new Button(ctx);
        replyBtn.setText(ctx.getString(com.winlator.R.string.socialhub_reply));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(12);
        detailContainer.addView(replyBtn, rlp);
        replyBtn.setOnClickListener(v -> showReplyDialog(post));
    }

    private View makeReplyView(ForumPost post, ForumPost.ForumReply reply) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF262626);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        box.setLayoutParams(lp);

        TextView meta = new TextView(ctx);
        meta.setText(ctx.getString(com.winlator.R.string.socialhub_forum_meta,
                nonEmpty(reply.username, "?"), formatDate(reply.timestamp))
                + editedSuffix(reply.edited));
        meta.setTextSize(12f);
        meta.setTypeface(null, Typeface.BOLD);
        meta.setTextColor(0xFF7BC47F);
        box.addView(meta);

        TextView body = new TextView(ctx);
        body.setText(reply.body);
        body.setTextSize(14f);
        body.setTextColor(0xFFE0E0E0);
        body.setPadding(0, dp(4), 0, 0);
        box.addView(body);

        // Edit / Delete are visible ONLY to the real author of the reply
        // (ownership persisted on this device). Long-press opens the same
        // popup for the author; others have no management affordance.
        final boolean mine = ForumStore.canManageReply(ctx.getApplicationContext(), post, reply);
        box.setOnLongClickListener(v -> {
            if (mine) showManageReplyDialog(post, reply);
            return true;
        });
        if (mine) {
            LinearLayout manageRow = new LinearLayout(ctx);
            manageRow.setOrientation(LinearLayout.HORIZONTAL);
            manageRow.setPadding(0, dp(6), 0, 0);
            Button editReply = new Button(ctx);
            editReply.setText(ctx.getString(com.winlator.R.string.socialhub_edit));
            editReply.setTextSize(12f);
            editReply.setOnClickListener(v -> showEditReplyDialog(post, reply));
            Button deleteReply = new Button(ctx);
            deleteReply.setText(ctx.getString(com.winlator.R.string.socialhub_delete));
            deleteReply.setTextSize(12f);
            deleteReply.setOnClickListener(v -> confirmDeleteReply(post, reply));
            manageRow.addView(editReply, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            manageRow.addView(deleteReply, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            box.addView(manageRow);
        }
        return box;
    }

    /** Popup shown on long-press of the author's own reply: Edit / Delete. */
    private void showManageReplyDialog(ForumPost post, ForumPost.ForumReply reply) {
        if (!ForumStore.canManageReply(ctx.getApplicationContext(), post, reply)) {
            Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_not_author),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = {
                ctx.getString(com.winlator.R.string.socialhub_edit),
                ctx.getString(com.winlator.R.string.socialhub_delete),
        };
        new AlertDialog.Builder(ctx)
                .setTitle(shorten(reply.body, 40))
                .setItems(items, (d, which) -> {
                    if (which == 0) showEditReplyDialog(post, reply);
                    else confirmDeleteReply(post, reply);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------- in-app video + gallery image viewer ----------

    private static boolean hasVideo(ForumPost post) {
        if (post == null || post.videoPath == null || post.videoPath.isEmpty()) return false;
        if (ForumPost.isRemoteUrl(post.videoPath)) return true;
        try {
            return new File(post.videoPath).isFile();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Separate fullscreen video player (no external browser, nothing inline
     * in the thread). Remote http(s) URLs are streamed; local files play
     * from disk. A MediaController provides play/pause/seek; playback stops
     * when the player is closed.
     */
    private void showFullscreenVideoPlayer(String videoPath) {
        if (ctx == null || videoPath == null) return;
        try {
            Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            FrameLayout root = new FrameLayout(ctx);
            root.setBackgroundColor(0xFF000000);

            VideoView video = new VideoView(ctx);
            FrameLayout.LayoutParams videoLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            videoLp.gravity = Gravity.CENTER;
            root.addView(video, videoLp);

            MediaController controller = new MediaController(ctx);
            controller.setAnchorView(video);
            video.setMediaController(controller);

            if (ForumPost.isRemoteUrl(videoPath)) {
                video.setVideoURI(Uri.parse(videoPath));
            } else {
                video.setVideoPath(videoPath);
            }
            video.requestFocus();
            video.setOnPreparedListener(mp -> {
                try { mp.setLooping(false); } catch (Exception ignored) {}
                video.start();
            });
            video.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_video_failed),
                        Toast.LENGTH_SHORT).show();
                return false;
            });

            Button close = new Button(ctx);
            close.setText(ctx.getString(com.winlator.R.string.socialhub_back));
            FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            closeLp.gravity = Gravity.TOP | Gravity.END;
            closeLp.setMargins(dp(12), dp(12), dp(12), dp(12));
            root.addView(close, closeLp);
            close.setOnClickListener(v -> {
                try { video.stopPlayback(); } catch (Exception ignored) {}
                dialog.dismiss();
            });
            dialog.setOnDismissListener(d -> {
                try { video.stopPlayback(); } catch (Exception ignored) {}
            });

            dialog.setContentView(root);
            dialog.show();
        } catch (Exception e) {
            Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_video_failed),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Fullscreen gallery-style viewer with pinch-to-zoom and pan. */
    private void showImageViewer(String imageUrl) {
        if (ctx == null || imageUrl == null) return;
        try {
            Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            FrameLayout root = new FrameLayout(ctx);
            root.setBackgroundColor(0xFF000000);

            // Centered on screen: FIT_CENTER keeps the photo in the middle
            // until the user pinches (which switches to MATRIX zoom mode).
            ZoomableImageView image = new ZoomableImageView(ctx);
            image.setBackgroundColor(0xFF000000);
            FrameLayout.LayoutParams imageLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            imageLp.gravity = Gravity.CENTER;
            root.addView(image, imageLp);

            TextView hint = new TextView(ctx);
            hint.setText(ctx.getString(com.winlator.R.string.socialhub_zoom_hint));
            hint.setTextSize(13f);
            hint.setTextColor(0xFFFFFFFF);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(dp(16), dp(16), dp(16), dp(16));
            FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hintLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            root.addView(hint, hintLp);

            Button close = new Button(ctx);
            close.setText(ctx.getString(com.winlator.R.string.socialhub_back));
            FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            closeLp.gravity = Gravity.TOP | Gravity.END;
            closeLp.setMargins(dp(12), dp(12), dp(12), dp(12));
            root.addView(close, closeLp);
            close.setOnClickListener(v -> dialog.dismiss());
            // Single tap on the image also closes (gallery-style), pinch/drag zooms.
            image.setOnSingleTapListener(dialog::dismiss);

            dialog.setContentView(root);
            dialog.show();
            ImageLoader.load(imageUrl, image);
        } catch (Exception ignored) {}
    }

    /**
     * Minimal gallery-style ImageView: pinch-to-zoom + drag-to-pan +
     * double-tap to toggle 1x/2.5x, single-tap callback for dismiss.
     */
    public static class ZoomableImageView extends androidx.appcompat.widget.AppCompatImageView {
        private final Matrix matrix = new Matrix();
        private final float[] values = new float[9];
        private float saveScale = 1f;
        private final float minScale = 1f;
        private final float maxScale = 5f;
        private final ScaleGestureDetector scaleDetector;
        private final android.view.GestureDetector gestureDetector;
        private final PointF last = new PointF();
        private Runnable singleTapListener;

        public ZoomableImageView(Context context) {
            super(context);
            // FIT_CENTER keeps the photo exactly in the middle of the screen;
            // the first pinch/double-tap switches to MATRIX for zoom + pan.
            setScaleType(ScaleType.FIT_CENTER);
            setAdjustViewBounds(false);
            scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
            gestureDetector = new android.view.GestureDetector(context,
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onSingleTapConfirmed(MotionEvent e) {
                            if (singleTapListener != null) singleTapListener.run();
                            return true;
                        }
                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            ensureMatrixMode();
                            float target = (saveScale < 2f) ? 2.5f : 1f;
                            float factor = target / saveScale;
                            matrix.postScale(factor, factor, e.getX(), e.getY());
                            saveScale = target;
                            if (saveScale <= 1f) center();
                            else {
                                setImageMatrix(matrix);
                                fixBounds();
                            }
                            return true;
                        }
                    });
            setOnTouchListener((v, event) -> {
                scaleDetector.onTouchEvent(event);
                gestureDetector.onTouchEvent(event);
                PointF curr = new PointF(event.getX(), event.getY());
                switch (event.getAction() & MotionEvent.ACTION_MASK) {
                    case MotionEvent.ACTION_DOWN:
                        last.set(curr);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!scaleDetector.isInProgress() && saveScale > 1f) {
                            float dx = curr.x - last.x;
                            float dy = curr.y - last.y;
                            matrix.postTranslate(dx, dy);
                            fixBounds();
                            setImageMatrix(matrix);
                        }
                        last.set(curr);
                        break;
                    default:
                        break;
                }
                return true;
            });
        }

        public ZoomableImageView(Context context, AttributeSet attrs) {
            this(context);
        }

        public void setOnSingleTapListener(Runnable r) {
            singleTapListener = r;
        }

        @Override
        public void setImageBitmap(android.graphics.Bitmap bm) {
            super.setImageBitmap(bm);
            if (saveScale <= 1f) post(this::center);
        }

        @Override
        public void setImageDrawable(@Nullable android.graphics.drawable.Drawable drawable) {
            super.setImageDrawable(drawable);
            if (saveScale <= 1f) post(this::center);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (changed && saveScale <= 1f) center();
        }

        /** Switches to MATRIX zoom mode, keeping the current fit as base. */
        private void ensureMatrixMode() {
            if (getScaleType() != ScaleType.MATRIX) {
                setScaleType(ScaleType.MATRIX);
                center();
            }
        }

        /** Fits the drawable inside the view and centers it exactly. */
        private void center() {
            try {
                android.graphics.drawable.Drawable d = getDrawable();
                int width = getWidth();
                int height = getHeight();
                if (d == null || width == 0 || height == 0) return;
                int imgW = d.getIntrinsicWidth();
                int imgH = d.getIntrinsicHeight();
                if (imgW <= 0 || imgH <= 0) return;
                matrix.reset();
                float scale = Math.min((float) width / imgW, (float) height / imgH);
                matrix.postScale(scale, scale);
                float dx = (width - imgW * scale) / 2f;
                float dy = (height - imgH * scale) / 2f;
                matrix.postTranslate(dx, dy);
                setImageMatrix(matrix);
                saveScale = 1f;
            } catch (Exception ignored) {}
        }

        private void fixBounds() {
            // Clamp translation so the image cannot be panned fully off-screen.
            try {
                matrix.getValues(values);
                float transX = values[Matrix.MTRANS_X];
                float transY = values[Matrix.MTRANS_Y];
                float width = getWidth();
                float height = getHeight();
                if (width == 0 || height == 0) return;
                android.graphics.drawable.Drawable d = getDrawable();
                if (d == null) return;
                float imgW = d.getIntrinsicWidth() * values[Matrix.MSCALE_X];
                float imgH = d.getIntrinsicHeight() * values[Matrix.MSCALE_Y];
                float maxX = Math.max(0, (width - imgW) / 2);
                float minX = Math.min(0, width - imgW - maxX);
                float maxY = Math.max(0, (height - imgH) / 2);
                float minY = Math.min(0, height - imgH - maxY);
                float dx = 0, dy = 0;
                if (transX > maxX) dx = maxX - transX;
                else if (transX < minX) dx = minX - transX;
                if (transY > maxY) dy = maxY - transY;
                else if (transY < minY) dy = minY - transY;
                if (dx != 0 || dy != 0) {
                    matrix.postTranslate(dx, dy);
                    setImageMatrix(matrix);
                }
            } catch (Exception ignored) {}
        }

        private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                ensureMatrixMode();
                float factor = detector.getScaleFactor();
                float orig = saveScale;
                saveScale *= factor;
                if (saveScale > maxScale) {
                    saveScale = maxScale;
                    factor = maxScale / orig;
                } else if (saveScale < minScale) {
                    saveScale = minScale;
                    factor = minScale / orig;
                }
                if (saveScale <= 1f) {
                    center();
                } else {
                    matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                    setImageMatrix(matrix);
                    fixBounds();
                }
                return true;
            }
        }
    }

    // ---------- new post dialog ----------
    private void showNewPostDialog() {
        pendingImagePath = null;
        pendingVideoPath = null;
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), dp(4));

        EditText etTitle = makeInput(ctx.getString(com.winlator.R.string.socialhub_hint_title), InputType.TYPE_CLASS_TEXT);
        EditText etBody = makeInput(ctx.getString(com.winlator.R.string.socialhub_hint_details),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etBody.setMinLines(4);
        EditText etUser = makeInput(ctx.getString(com.winlator.R.string.socialhub_hint_username), InputType.TYPE_CLASS_TEXT);
        EditText etEmail = makeInput(ctx.getString(com.winlator.R.string.socialhub_hint_email),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        // Remembered owner identity: pre-fill so the author keeps the same
        // username/email (which keeps their Edit/Delete rights working).
        try {
            etUser.setText(ForumStore.getLastUsername(ctx.getApplicationContext()));
            etEmail.setText(ForumStore.getLastEmail(ctx.getApplicationContext()));
        } catch (Exception ignored) {}
        form.addView(etTitle);
        form.addView(etBody);
        form.addView(etUser);
        form.addView(etEmail);

        pendingAttachLabel = new TextView(ctx);
        pendingAttachLabel.setText(ctx.getString(com.winlator.R.string.socialhub_no_attachment));
        pendingAttachLabel.setTextSize(12f);
        pendingAttachLabel.setTextColor(0xFF888888);
        pendingAttachLabel.setPadding(0, dp(8), 0, dp(4));
        form.addView(pendingAttachLabel);

        LinearLayout attachRow = new LinearLayout(ctx);
        attachRow.setOrientation(LinearLayout.HORIZONTAL);
        Button attachImage = new Button(ctx);
        attachImage.setText(ctx.getString(com.winlator.R.string.socialhub_attach_image));
        attachImage.setOnClickListener(v -> pickFile("image/*", REQ_ATTACH_IMAGE));
        Button attachVideo = new Button(ctx);
        attachVideo.setText(ctx.getString(com.winlator.R.string.socialhub_attach_video));
        attachVideo.setOnClickListener(v -> pickFile("video/*", REQ_ATTACH_VIDEO));
        attachRow.addView(attachImage, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        attachRow.addView(attachVideo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        form.addView(attachRow);

        new AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(com.winlator.R.string.socialhub_new_forum))
                .setView(form)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String title = etTitle.getText().toString().trim();
                    String body = etBody.getText().toString().trim();
                    String user = etUser.getText().toString().trim();
                    String email = etEmail.getText().toString().trim();
                    if (title.isEmpty() || body.isEmpty() || user.isEmpty() || email.isEmpty()) {
                        Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_fill_all_fields),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                        Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_invalid_email),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ForumPost post = new ForumPost();
                    post.title = title;
                    post.body = body;
                    post.username = user;
                    post.email = email;
                    post.imagePath = pendingImagePath;
                    post.videoPath = pendingVideoPath;
                    // Publish off the UI thread: ForumStore does network I/O.
                    // On success the post is public; otherwise it stays in the
                    // local cache and is retried on the next load/refresh.
                    progress.setVisibility(View.VISIBLE);
                    statusText.setText(ctx.getString(com.winlator.R.string.socialhub_loading_forums));
                    new Thread(() -> {
                        boolean published = ForumStore.publishPostBlocking(
                                ctx.getApplicationContext(), post);
                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(() -> {
                            int msg;
                            if (!published) {
                                msg = com.winlator.R.string.socialhub_forum_saved_offline;
                            } else if (post.mediaPending) {
                                // Text is public, but the attachments failed to
                                // upload, so other devices cannot see them yet.
                                msg = com.winlator.R.string.socialhub_forum_published_media_local;
                            } else {
                                msg = com.winlator.R.string.socialhub_forum_published;
                            }
                            Toast.makeText(ctx, ctx.getString(msg), Toast.LENGTH_LONG).show();
                            loadForums();
                        });
                    }, "socialhub-forum-publish").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------- reply dialog ----------
    private void showReplyDialog(ForumPost post) {
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), dp(4));
        EditText etUser = makeInput(ctx.getString(com.winlator.R.string.socialhub_hint_username), InputType.TYPE_CLASS_TEXT);
        EditText etEmail = makeInput(ctx.getString(com.winlator.R.string.socialhub_hint_email),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText etBody = makeInput(ctx.getString(com.winlator.R.string.socialhub_hint_reply),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etBody.setMinLines(3);
        // Remembered owner identity, pre-filled like the new-forum form.
        try {
            etUser.setText(ForumStore.getLastUsername(ctx.getApplicationContext()));
            etEmail.setText(ForumStore.getLastEmail(ctx.getApplicationContext()));
        } catch (Exception ignored) {}
        form.addView(etUser);
        form.addView(etEmail);
        form.addView(etBody);

        new AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(com.winlator.R.string.socialhub_reply_to, shorten(post.title, 30)))
                .setView(form)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String user = etUser.getText().toString().trim();
                    String email = etEmail.getText().toString().trim();
                    String body = etBody.getText().toString().trim();
                    if (user.isEmpty() || email.isEmpty() || body.isEmpty()) {
                        Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_fill_all_fields),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                        Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_invalid_email),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ForumPost.ForumReply reply = new ForumPost.ForumReply();
                    reply.username = user;
                    reply.email = email;
                    reply.body = body;
                    // Publish off the UI thread, then re-render from the
                    // merged local copy so the reply is never lost locally.
                    new Thread(() -> {
                        boolean published = ForumStore.publishReplyBlocking(
                                ctx.getApplicationContext(), post.id, reply);
                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(() -> {
                            boolean known = false;
                            for (ForumPost.ForumReply r : post.replies) {
                                if (r.id.equals(reply.id)) { known = true; break; }
                            }
                            if (!known) post.replies.add(reply);
                            renderDetail();
                            Toast.makeText(ctx, ctx.getString(published
                                            ? com.winlator.R.string.socialhub_reply_published
                                            : com.winlator.R.string.socialhub_reply_saved_offline),
                                    Toast.LENGTH_LONG).show();
                        });
                    }, "socialhub-reply-publish").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------- manage (edit/delete, author only) ----------
    private void showManagePostDialog(ForumPost post) {
        // Safety: only the real author may manage; others just open.
        if (!ForumStore.canManagePost(ctx.getApplicationContext(), post)) {
            openPost(post);
            return;
        }
        String[] items = {
                ctx.getString(com.winlator.R.string.socialhub_open),
                ctx.getString(com.winlator.R.string.socialhub_edit),
                ctx.getString(com.winlator.R.string.socialhub_delete),
        };
        new AlertDialog.Builder(ctx)
                .setTitle(shorten(post.title, 40))
                .setItems(items, (d, which) -> {
                    if (which == 1) showEditPostDialog(post);
                    else if (which == 2) confirmDeletePost(post);
                    else openPost(post);
                })
                .show();
    }

    private void showEditPostDialog(ForumPost post) {
        // Safety gate: hidden buttons + this check = author-only editing.
        if (!ForumStore.canManagePost(ctx.getApplicationContext(), post)) {
            Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_not_author),
                    Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), dp(4));
        EditText etTitle = makeInput(ctx.getString(com.winlator.R.string.socialhub_hint_title),
                InputType.TYPE_CLASS_TEXT);
        etTitle.setText(post.title);
        EditText etBody = makeInput(ctx.getString(com.winlator.R.string.socialhub_hint_details),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etBody.setMinLines(4);
        etBody.setText(post.body);
        EditText etEmail = makeInput(ctx.getString(com.winlator.R.string.socialhub_hint_email_confirm),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        // Pre-fill the author's remembered email (still must match to save).
        try {
            String known = ForumStore.getLastEmail(ctx.getApplicationContext());
            if (known != null && !known.isEmpty()) etEmail.setText(known);
        } catch (Exception ignored) {}
        form.addView(etTitle);
        form.addView(etBody);
        form.addView(etEmail);

        new AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(com.winlator.R.string.socialhub_edit_forum))
                .setView(form)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String title = etTitle.getText().toString().trim();
                    String body = etBody.getText().toString().trim();
                    String email = etEmail.getText().toString().trim();
                    if (title.isEmpty() || body.isEmpty() || email.isEmpty()) {
                        Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_fill_all_fields),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!email.equalsIgnoreCase(post.email)) {
                        Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_not_author),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    progress.setVisibility(View.VISIBLE);
                    new Thread(() -> {
                        boolean ok = ForumStore.updatePostBlocking(
                                ctx.getApplicationContext(), post.id, title, body, email);
                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(() -> {
                            Toast.makeText(ctx, ctx.getString(ok
                                            ? com.winlator.R.string.socialhub_updated
                                            : com.winlator.R.string.socialhub_action_failed),
                                    Toast.LENGTH_SHORT).show();
                            if (openPost != null && openPost.id.equals(post.id)) {
                                post.title = title;
                                post.body = body;
                                post.edited = true;
                                renderDetail();
                            } else {
                                loadForums();
                            }
                        });
                    }, "socialhub-forum-update").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDeletePost(ForumPost post) {
        // Safety gate: hidden buttons + this check = author-only deletion.
        if (!ForumStore.canManagePost(ctx.getApplicationContext(), post)) {
            Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_not_author),
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(com.winlator.R.string.socialhub_delete))
                .setMessage(ctx.getString(com.winlator.R.string.socialhub_delete_forum_confirm))
                .setPositiveButton(android.R.string.ok, (d, w) -> askEmailAndDeletePost(post))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void askEmailAndDeletePost(ForumPost post) {
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), dp(4));
        EditText etEmail = makeInput(ctx.getString(com.winlator.R.string.socialhub_hint_email_confirm),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        try {
            String known = ForumStore.getLastEmail(ctx.getApplicationContext());
            if (known != null && !known.isEmpty()) etEmail.setText(known);
        } catch (Exception ignored) {}
        form.addView(etEmail);
        new AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(com.winlator.R.string.socialhub_delete))
                .setView(form)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String email = etEmail.getText().toString().trim();
                    if (!email.equalsIgnoreCase(post.email)) {
                        Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_not_author),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    progress.setVisibility(View.VISIBLE);
                    new Thread(() -> {
                        boolean ok = ForumStore.deletePostBlocking(
                                ctx.getApplicationContext(), post.id, email);
                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(() -> {
                            Toast.makeText(ctx, ctx.getString(ok
                                            ? com.winlator.R.string.socialhub_deleted
                                            : com.winlator.R.string.socialhub_action_failed),
                                    Toast.LENGTH_SHORT).show();
                            if (openPost != null && openPost.id.equals(post.id)) openPost = null;
                            loadForums();
                        });
                    }, "socialhub-forum-delete").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showEditReplyDialog(ForumPost post, ForumPost.ForumReply reply) {
        // Safety gate: author-only editing.
        if (!ForumStore.canManageReply(ctx.getApplicationContext(), post, reply)) {
            Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_not_author),
                    Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), dp(4));
        EditText etBody = makeInput(ctx.getString(com.winlator.R.string.socialhub_hint_reply),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etBody.setMinLines(3);
        etBody.setText(reply.body);
        EditText etEmail = makeInput(ctx.getString(com.winlator.R.string.socialhub_hint_email_confirm),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        try {
            String known = ForumStore.getLastEmail(ctx.getApplicationContext());
            if (known != null && !known.isEmpty()) etEmail.setText(known);
        } catch (Exception ignored) {}
        form.addView(etBody);
        form.addView(etEmail);

        new AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(com.winlator.R.string.socialhub_edit_reply))
                .setView(form)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String body = etBody.getText().toString().trim();
                    String email = etEmail.getText().toString().trim();
                    if (body.isEmpty() || email.isEmpty()) {
                        Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_fill_all_fields),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!email.equalsIgnoreCase(reply.email)) {
                        Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_not_author),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    new Thread(() -> {
                        boolean ok = ForumStore.updateReplyBlocking(
                                ctx.getApplicationContext(), post.id, reply.id, body, email);
                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(() -> {
                            if (ok) {
                                reply.body = body;
                                reply.edited = true;
                                renderDetail();
                            }
                            Toast.makeText(ctx, ctx.getString(ok
                                            ? com.winlator.R.string.socialhub_updated
                                            : com.winlator.R.string.socialhub_action_failed),
                                    Toast.LENGTH_SHORT).show();
                        });
                    }, "socialhub-reply-update").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDeleteReply(ForumPost post, ForumPost.ForumReply reply) {
        // Safety gate: author-only deletion.
        if (!ForumStore.canManageReply(ctx.getApplicationContext(), post, reply)) {
            Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_not_author),
                    Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), dp(4));
        EditText etEmail = makeInput(ctx.getString(com.winlator.R.string.socialhub_hint_email_confirm),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        try {
            String known = ForumStore.getLastEmail(ctx.getApplicationContext());
            if (known != null && !known.isEmpty()) etEmail.setText(known);
        } catch (Exception ignored) {}
        form.addView(etEmail);
        new AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(com.winlator.R.string.socialhub_delete))
                .setMessage(ctx.getString(com.winlator.R.string.socialhub_delete_reply_confirm))
                .setView(form)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String email = etEmail.getText().toString().trim();
                    if (!email.equalsIgnoreCase(reply.email)) {
                        Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_not_author),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    new Thread(() -> {
                        boolean ok = ForumStore.deleteReplyBlocking(
                                ctx.getApplicationContext(), post.id, reply.id, email);
                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(() -> {
                            if (ok) {
                                for (int i = 0; i < post.replies.size(); i++) {
                                    if (post.replies.get(i).id.equals(reply.id)) {
                                        post.replies.remove(i);
                                        break;
                                    }
                                }
                                renderDetail();
                            }
                            Toast.makeText(ctx, ctx.getString(ok
                                            ? com.winlator.R.string.socialhub_deleted
                                            : com.winlator.R.string.socialhub_action_failed),
                                    Toast.LENGTH_SHORT).show();
                        });
                    }, "socialhub-reply-delete").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private EditText makeInput(String hint, int inputType) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setInputType(inputType);
        et.setTextSize(14f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        et.setLayoutParams(lp);
        return et;
    }

    // ---------- attachments ----------
    private void pickFile(String mime, int requestCode) {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType(mime);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            getActivity().startActivityFromFragment(this, Intent.createChooser(intent,
                    getString(com.winlator.R.string.socialhub_choose_file)), requestCode);
        } catch (Exception e) {
            Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_no_app_to_open),
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode != REQ_ATTACH_IMAGE && requestCode != REQ_ATTACH_VIDEO) return;
        Uri uri = data.getData();
        new Thread(() -> {
            String path = copyToPrivateStorage(uri, requestCode == REQ_ATTACH_IMAGE ? "img" : "vid");
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (path == null) {
                    Toast.makeText(ctx, ctx.getString(com.winlator.R.string.socialhub_attach_failed),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (requestCode == REQ_ATTACH_IMAGE) pendingImagePath = path;
                else pendingVideoPath = path;
                if (pendingAttachLabel != null) {
                    pendingAttachLabel.setText(ctx.getString(com.winlator.R.string.socialhub_attachment_added,
                            new File(path).getName()));
                }
            });
        }, "socialhub-attach").start();
    }

    private String copyToPrivateStorage(Uri uri, String prefix) {
        try {
            File dir = new File(ctx.getFilesDir(), "social_attachments");
            if (!dir.isDirectory()) dir.mkdirs();
            String name = prefix + "_" + System.currentTimeMillis();
            String ext = guessExtension(uri);
            File dest = new File(dir, name + ext);
            try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(dest)) {
                if (in == null) return null;
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            return dest.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private String guessExtension(Uri uri) {
        try {
            String type = ctx.getContentResolver().getType(uri);
            if (type != null) {
                if (type.contains("png")) return ".png";
                if (type.contains("gif")) return ".gif";
                if (type.contains("webp")) return ".webp";
                if (type.contains("jpeg") || type.contains("jpg")) return ".jpg";
                if (type.contains("mp4")) return ".mp4";
                if (type.contains("3gp")) return ".3gp";
                if (type.contains("mkv")) return ".mkv";
            }
            Cursor c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (c.moveToFirst() && idx != -1) {
                        String display = c.getString(idx);
                        int dot = display.lastIndexOf('.');
                        if (dot != -1) return display.substring(dot);
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    // ---------- misc ----------
    /**
     * Resolves an attachment to something {@link ImageLoader} can fetch:
     * remote http(s) URLs are shared between devices, local paths only work
     * on the device that created them. Returns null when there is nothing
     * viewable (so callers simply hide the image view).
     */
    private static String attachmentImageUrl(ForumPost post) {
        if (post == null || post.imagePath == null || post.imagePath.isEmpty()) return null;
        if (ForumPost.isRemoteUrl(post.imagePath)) return post.imagePath;
        if (new File(post.imagePath).isFile()) return "file://" + post.imagePath;
        return null;
    }

    public boolean onBackPressed() {
        if (detailScroll != null && detailScroll.getVisibility() == View.VISIBLE) {
            openPost = null;
            renderList();
            return true;
        }
        return false;
    }

    public void refreshOfflineState() {
        updateOfflineBg();
    }

    @Override
    public void onResume() {
        super.onResume();
        schedulePoll();
    }

    @Override
    public void onPause() {
        stopPoll();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        stopPoll();
        super.onDestroyView();
    }

    private void schedulePoll() {
        try {
            stopPoll();
            if (pollHandler == null) pollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        } catch (Exception ignored) {}
    }

    private void stopPoll() {
        try {
            if (pollHandler != null) pollHandler.removeCallbacks(pollRunnable);
        } catch (Exception ignored) {}
    }

    private String formatDate(long timestamp) {
        try {
            return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(timestamp));
        } catch (Exception e) {
            return "";
        }
    }

    private static String nonEmpty(String s, String fallback) {
        return (s == null || s.isEmpty()) ? fallback : s;
    }

    /** Appends the "(edited)" badge beside titles when the item was edited. */
    private String titledWithEdited(String title, boolean edited) {
        String t = title != null ? title : "";
        if (edited) t += " " + ctx.getString(com.winlator.R.string.socialhub_edited_badge);
        return t;
    }

    /** Appends " • (edited)" to meta lines when the item was edited. */
    private String editedSuffix(boolean edited) {
        if (!edited || ctx == null) return "";
        return " • " + ctx.getString(com.winlator.R.string.socialhub_edited_badge);
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
