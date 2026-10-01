package com.winlator.socialhub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.List;

/**
 * Notifies the author of a forum when someone replies to it.
 *
 * <p>How it works: after every successful online sync, reply counts of threads
 * created on this device (matched by remembered author email, see
 * {@link ForumStore#isMine}) are compared with the counts seen last time.
 * A notification is posted only when the count grew because of somebody
 * else's reply. The first sync only records a baseline so installing the
 * update does not spam old threads. Polling happens while the app is open
 * (see ForumsFragment); nothing runs when offline.
 */
public final class ForumNotifier {
    private static final String PREFS = "socialhub_notif";
    private static final String CHANNEL_ID = "forum_replies";

    private ForumNotifier() {}

    public static void checkForNewReplies(Context appContext, List<ForumPost> fetched) {
        if (appContext == null || fetched == null || fetched.isEmpty()) return;
        if (!NetUtils.isOnline(appContext)) return;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (ForumPost post : fetched) {
            if (!ForumStore.isMine(appContext, post.email)) continue;
            if (post.replies == null || post.replies.isEmpty()) continue;
            String key = "count_" + post.id;
            int seen = prefs.getInt(key, -1);
            int now = post.replies.size();
            if (seen == -1) {
                // First sight of this thread: baseline only, never notify.
                prefs.edit().putInt(key, now).apply();
                continue;
            }
            if (now > seen) {
                ForumPost.ForumReply newestOther = null;
                for (int i = seen; i < now && i < post.replies.size(); i++) {
                    ForumPost.ForumReply r = post.replies.get(i);
                    if (r != null && !ForumStore.isMine(appContext, r.email)) newestOther = r;
                }
                prefs.edit().putInt(key, now).apply();
                if (newestOther != null) notifyReply(appContext, post, newestOther);
            } else if (now != seen) {
                // Count shrank (deleted reply) or list rebuilt: re-baseline quietly.
                prefs.edit().putInt(key, now).apply();
            }
        }
    }

    /** Drops stored baselines for threads that no longer exist. */
    public static void pruneKnownThreads(Context appContext, List<ForumPost> fetched) {
        if (appContext == null || fetched == null) return;
        java.util.Set<String> alive = new HashSet<>();
        for (ForumPost p : fetched) alive.add("count_" + p.id);
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = null;
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("count_") && !alive.contains(key)) {
                if (editor == null) editor = prefs.edit();
                editor.remove(key);
            }
        }
        if (editor != null) editor.apply();
    }

    private static void notifyReply(Context appContext, ForumPost post, ForumPost.ForumReply reply) {
        try {
            NotificationManager manager =
                    (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    appContext.getString(com.winlator.R.string.socialhub_notif_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);

            String snippet = reply.body != null ? reply.body : "";
            if (snippet.length() > 120) snippet = snippet.substring(0, 117) + "...";
            String title = appContext.getString(com.winlator.R.string.socialhub_notif_new_reply)
                    + ": " + post.title;
            String text = appContext.getString(com.winlator.R.string.socialhub_notif_new_reply_text,
                    reply.username != null && !reply.username.isEmpty() ? reply.username : "?",
                    snippet);

            PendingIntent tap = null;
            try {
                android.content.Intent launch =
                        appContext.getPackageManager().getLaunchIntentForPackage(appContext.getPackageName());
                if (launch != null) {
                    tap = PendingIntent.getActivity(appContext, post.id.hashCode(), launch,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                }
            } catch (Exception ignored) {}

            Notification.Builder builder = new Notification.Builder(appContext, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setSmallIcon(com.winlator.R.drawable.icon_social_hub)
                    .setAutoCancel(true);
            if (tap != null) builder.setContentIntent(tap);
            manager.notify(post.id.hashCode(), builder.build());
        } catch (Exception ignored) {
            // Notifications must never break the forums tab.
        }
    }
}
