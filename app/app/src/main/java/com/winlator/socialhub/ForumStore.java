package com.winlator.socialhub;

import android.content.Context;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Forum persistence + shared-backend sync.
 *
 * <p>Source of truth for "every user sees the same forums" is the website
 * backend ({@code https://winlator-frost.tanakorn-website.workers.dev/api/forums},
 * falling back to {@code https://tanakorn-website.onrender.com/api/forums}):
 * <ul>
 *   <li>{@code GET /api/forums} returns a JSON array of forum objects
 *       ({@code id,title,body/content,username/user,email,timestamp,
 *       replies/comments[]}).</li>
 *   <li>{@code POST /api/forums} accepts one forum object to publish it.</li>
 *   <li>{@code POST /api/forums/{id}/replies} accepts one reply object.</li>
 * </ul>
 * Local storage is a cache/fallback so the tab stays usable offline and
 * while the backend endpoints are still being rolled out; whenever the
 * device is online the remote list is merged in (remote wins on id clash)
 * and local posts are pushed up best-effort.
 */
public final class ForumStore {
    private static final String FILE_NAME = "social_forums.json";
    private static final String PREFS_IDENTITY = "socialhub_identity";
    private static final int GET_TIMEOUT_MS = 12000;
    private static final int POST_TIMEOUT_MS = 8000;

    private ForumStore() {}

    /**
     * Remembers the author's identity (username + email) used on this device.
     * Stored in SharedPreferences, so it survives leaving the Social Hub and
     * full app restarts. Used to decide which threads/replies this device may
     * manage (edit/delete), to pre-fill posting forms, and for reply
     * notifications. Device-local only.
     */
    public static void rememberIdentity(Context context, String username, String email) {
        if (email == null || email.trim().isEmpty()) return;
        String key = email.trim().toLowerCase(java.util.Locale.US);
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS_IDENTITY, android.content.Context.MODE_PRIVATE);
        java.util.Set<String> copy =
                new java.util.HashSet<>(prefs.getStringSet("emails", new java.util.HashSet<>()));
        copy.add(key);
        android.content.SharedPreferences.Editor ed = prefs.edit().putStringSet("emails", copy);
        if (username != null && !username.trim().isEmpty()) {
            ed.putString("name_" + key, username.trim());
        }
        // Last-used identity pre-fills the username/email fields next time.
        ed.putString("last_email", email.trim());
        if (username != null && !username.trim().isEmpty()) {
            ed.putString("last_username", username.trim());
        }
        ed.apply();
    }

    /** Legacy entry point: remembers the email only (no username known). */
    public static void rememberEmail(Context context, String email) {
        rememberIdentity(context, null, email);
    }

    /** True when this device has posted with the given author email. */
    public static boolean isMine(Context context, String email) {
        if (email == null || email.trim().isEmpty()) return false;
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS_IDENTITY, android.content.Context.MODE_PRIVATE);
        return prefs.getStringSet("emails", new java.util.HashSet<>())
                .contains(email.trim().toLowerCase(java.util.Locale.US));
    }

    /** Last username typed on this device (for pre-filling forms). */
    public static String getLastUsername(Context context) {
        try {
            return context.getSharedPreferences(PREFS_IDENTITY, android.content.Context.MODE_PRIVATE)
                    .getString("last_username", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** Last email typed on this device (for pre-filling forms). */
    public static String getLastEmail(Context context) {
        try {
            return context.getSharedPreferences(PREFS_IDENTITY, android.content.Context.MODE_PRIVATE)
                    .getString("last_email", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** Username previously used together with the given email, if any. */
    public static String getUsernameForEmail(Context context, String email) {
        try {
            if (email == null || email.trim().isEmpty()) return "";
            String key = email.trim().toLowerCase(java.util.Locale.US);
            String name = context.getSharedPreferences(PREFS_IDENTITY, android.content.Context.MODE_PRIVATE)
                    .getString("name_" + key, "");
            return name != null ? name : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- per-item ownership (survives email reuse across devices) ----------

    /**
     * Records that a forum thread was created on this device. Ownership ids
     * are persisted alongside the identity, so the Edit/Delete buttons stay
     * available for the real author even after leaving the hub or restarting
     * the app.
     */
    public static void rememberPostOwnership(Context context, String postId, String email) {
        if (postId == null || postId.isEmpty()) return;
        try {
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences(PREFS_IDENTITY, android.content.Context.MODE_PRIVATE);
            java.util.Set<String> copy =
                    new java.util.HashSet<>(prefs.getStringSet("owned_posts", new java.util.HashSet<>()));
            if (copy.add(postId)) prefs.edit().putStringSet("owned_posts", copy).apply();
        } catch (Exception ignored) {}
    }

    /** Records that a reply was created on this device (see above). */
    public static void rememberReplyOwnership(Context context, String replyId, String email) {
        if (replyId == null || replyId.isEmpty()) return;
        try {
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences(PREFS_IDENTITY, android.content.Context.MODE_PRIVATE);
            java.util.Set<String> copy =
                    new java.util.HashSet<>(prefs.getStringSet("owned_replies", new java.util.HashSet<>()));
            if (copy.add(replyId)) prefs.edit().putStringSet("owned_replies", copy).apply();
        } catch (Exception ignored) {}
    }

    /** Drops ownership when the author's own thread is deleted. */
    public static void forgetPostOwnership(Context context, String postId) {
        if (postId == null || postId.isEmpty()) return;
        try {
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences(PREFS_IDENTITY, android.content.Context.MODE_PRIVATE);
            java.util.Set<String> copy =
                    new java.util.HashSet<>(prefs.getStringSet("owned_posts", new java.util.HashSet<>()));
            if (copy.remove(postId)) prefs.edit().putStringSet("owned_posts", copy).apply();
        } catch (Exception ignored) {}
    }

    /** Drops ownership when the author's own reply is deleted. */
    public static void forgetReplyOwnership(Context context, String replyId) {
        if (replyId == null || replyId.isEmpty()) return;
        try {
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences(PREFS_IDENTITY, android.content.Context.MODE_PRIVATE);
            java.util.Set<String> copy =
                    new java.util.HashSet<>(prefs.getStringSet("owned_replies", new java.util.HashSet<>()));
            if (copy.remove(replyId)) prefs.edit().putStringSet("owned_replies", copy).apply();
        } catch (Exception ignored) {}
    }

    /**
     * True only for the real author of the thread: either this device created
     * it (ownership id) or it posted under an email remembered on this device.
     * Edit/Delete buttons are shown exclusively on this basis.
     */
    public static boolean canManagePost(Context context, ForumPost post) {
        if (post == null) return false;
        try {
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences(PREFS_IDENTITY, android.content.Context.MODE_PRIVATE);
            if (post.id != null && prefs.getStringSet("owned_posts", new java.util.HashSet<>()).contains(post.id)) {
                return true;
            }
        } catch (Exception ignored) {}
        return isMine(context, post.email);
    }

    /** True only for the real author of the reply (see {@link #canManagePost}). */
    public static boolean canManageReply(Context context, ForumPost post, ForumPost.ForumReply reply) {
        if (reply == null) return false;
        try {
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences(PREFS_IDENTITY, android.content.Context.MODE_PRIVATE);
            if (reply.id != null && prefs.getStringSet("owned_replies", new java.util.HashSet<>()).contains(reply.id)) {
                return true;
            }
        } catch (Exception ignored) {}
        return isMine(context, reply.email);
    }

    // ---------- sticky offline-first mutations (edits/deletes) ----------
    //
    // Why this exists: load() merges the on-device cache with the server
    // list using "remote wins", then overwrites the cache. If the server has
    // not (yet) honoured an edit or a delete — endpoint missing on one mirror
    // host, transient failure, offline change — the next refresh silently
    // reverts the edit or resurrects the deleted item, and the revert gets
    // persisted. So every edit/delete is recorded durably here first:
    //   * deletes leave tombstones that filter server results until the
    //     server confirms the item is gone (with throttled re-DELETE retries);
    //   * edits leave pending overlays that are re-applied over server data
    //     on every load (with throttled re-PUT retries) until the server
    //     echoes the same values, at which point they are dropped.
    // Together this makes refresh-after-edit/delete always stick, online or
    // offline, without ever needing an app reinstall.

    private static final String PREFS_MUTATIONS = "socialhub_mutations";
    private static final String KEY_TOMB_POSTS = "tomb_posts";
    private static final String KEY_TOMB_REPLIES = "tomb_replies";
    private static final long RETRY_MIN_INTERVAL_MS = 60 * 1000L;

    private static android.content.SharedPreferences mutations(Context context) {
        return context.getSharedPreferences(PREFS_MUTATIONS, android.content.Context.MODE_PRIVATE);
    }

    /** Tombstone value for a deleted post. Emails never contain '|'. */
    private static String postTombstone(String postId, String email) {
        return postId + "|" + (email != null ? email : "");
    }

    /** Tombstone value for a deleted reply. */
    private static String replyTombstone(String postId, String replyId, String email) {
        return postId + "|" + replyId + "|" + (email != null ? email : "");
    }

    private static void addTombstone(Context context, String setKey, String value) {
        try {
            android.content.SharedPreferences prefs = mutations(context);
            java.util.Set<String> copy =
                    new java.util.HashSet<>(prefs.getStringSet(setKey, new java.util.HashSet<>()));
            if (copy.add(value)) prefs.edit().putStringSet(setKey, copy).apply();
        } catch (Exception ignored) {}
    }

    private static void removeTombstone(Context context, String setKey, String value) {
        try {
            android.content.SharedPreferences prefs = mutations(context);
            java.util.Set<String> copy =
                    new java.util.HashSet<>(prefs.getStringSet(setKey, new java.util.HashSet<>()));
            if (copy.remove(value)) prefs.edit().putStringSet(setKey, copy).apply();
        } catch (Exception ignored) {}
    }

    private static java.util.Set<String> tombstones(Context context, String setKey) {
        try {
            return new java.util.HashSet<>(mutations(context).getStringSet(setKey, new java.util.HashSet<>()));
        } catch (Exception e) {
            return new java.util.HashSet<>();
        }
    }

    private static void savePendingPostEdit(Context context, String postId,
                                            String title, String body, String email) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("title", title);
            o.put("body", body);
            o.put("email", email != null ? email : "");
            mutations(context).edit().putString("pend_post_" + postId, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static org.json.JSONObject loadPendingPostEdit(Context context, String postId) {
        try {
            String raw = mutations(context).getString("pend_post_" + postId, null);
            if (raw == null || raw.isEmpty()) return null;
            return new org.json.JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static void clearPendingPostEdit(Context context, String postId) {
        try {
            mutations(context).edit().remove("pend_post_" + postId).apply();
        } catch (Exception ignored) {}
    }

    private static void savePendingReplyEdit(Context context, String postId, String replyId,
                                             String body, String email) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("body", body);
            o.put("email", email != null ? email : "");
            mutations(context).edit().putString("pend_reply_" + postId + "|" + replyId, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static org.json.JSONObject loadPendingReplyEdit(Context context, String postId, String replyId) {
        try {
            String raw = mutations(context).getString("pend_reply_" + postId + "|" + replyId, null);
            if (raw == null || raw.isEmpty()) return null;
            return new org.json.JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static void clearPendingReplyEdit(Context context, String postId, String replyId) {
        try {
            mutations(context).edit().remove("pend_reply_" + postId + "|" + replyId).apply();
        } catch (Exception ignored) {}
    }

    /** Throttles server retries so refresh/poll storms never spam the backend. */
    private static boolean retryDue(Context context, String throttleKey) {
        try {
            long now = System.currentTimeMillis();
            android.content.SharedPreferences prefs = mutations(context);
            long last = prefs.getLong(throttleKey + "_at", 0);
            if (now - last < RETRY_MIN_INTERVAL_MS) return false;
            prefs.edit().putLong(throttleKey + "_at", now).apply();
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private static String tombEmail(String tombstone, int parts) {
        try {
            String[] split = tombstone.split("\\|", -1);
            if (split.length == parts) return split[parts - 1];
        } catch (Exception ignored) {}
        return "";
    }

    public static synchronized List<ForumPost> load(Context context) {
        List<ForumPost> local = readLocal(context);
        if (!NetUtils.isOnline(context)) return local;
        String body = null;
        try {
            body = NetUtils.httpGet(NetUtils.FORUM_API_BASE + "/forums", GET_TIMEOUT_MS);
        } catch (Exception primaryError) {
            try {
                body = NetUtils.httpGet(NetUtils.FORUM_API_BASE_FALLBACK + "/forums", GET_TIMEOUT_MS);
            } catch (Exception fallbackError) {
                return local;
            }
        }
        List<ForumPost> remote;
        try {
            remote = parseArray(body);
        } catch (Exception e) {
            return local;
        }
        if (remote.isEmpty() && !local.isEmpty()) {
            // Ambiguous: empty backend vs backend failure. Push local-only posts
            // up so a fresh backend gets seeded, then keep showing local data.
            pushMissingPosts(local, remote);
            return local;
        }
        // Self-healing sync: anything created on this device that the server
        // does not know about yet is pushed now (this is what used to leave
        // posts stranded in local-only mode when the first POST failed).
        pushMissingPosts(local, remote);
        pushMissingReplies(local, remote);
        // Sticky edits/deletes: filter tombstones, re-apply pending overlays
        // and retry unhonoured mutations, so a refresh can never silently
        // revert an edit or resurrect a deleted item.
        reconcileMutations(context, remote);
        Map<String, ForumPost> merged = new LinkedHashMap<>();
        for (ForumPost p : local) merged.put(p.id, p);
        for (ForumPost p : remote) merged.put(p.id, p); // remote wins
        // Belt and braces: a tombstoned id must never survive the merge even
        // if it arrived via the local cache.
        java.util.Set<String> tombPosts = tombstones(context, KEY_TOMB_POSTS);
        java.util.Set<String> tombReplies = tombstones(context, KEY_TOMB_REPLIES);
        if (!tombPosts.isEmpty() || !tombReplies.isEmpty()) {
            java.util.Iterator<Map.Entry<String, ForumPost>> it = merged.entrySet().iterator();
            while (it.hasNext()) {
                ForumPost p = it.next().getValue();
                boolean postGone = false;
                for (String t : tombPosts) {
                    if (p.id.equals(tombPostId(t))) { postGone = true; break; }
                }
                if (postGone) { it.remove(); continue; }
                for (int i = p.replies.size() - 1; i >= 0; i--) {
                    ForumPost.ForumReply r = p.replies.get(i);
                    if (isReplyTombstoned(tombReplies, p.id, r.id)) p.replies.remove(i);
                }
            }
        }
        List<ForumPost> result = new ArrayList<>(merged.values());
        result.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        saveLocal(context, result);
        return result;
    }

    /** Extracts the post id from a post tombstone value. */
    private static String tombPostId(String tombstone) {
        try {
            int cut = tombstone.indexOf('|');
            return cut == -1 ? tombstone : tombstone.substring(0, cut);
        } catch (Exception e) {
            return tombstone;
        }
    }

    /** True when the given reply of the given post has a delete tombstone. */
    private static boolean isReplyTombstoned(java.util.Set<String> tombReplies,
                                             String postId, String replyId) {
        try {
            String prefix = postId + "|" + replyId + "|";
            for (String t : tombReplies) {
                if (t.startsWith(prefix)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Reconciles sticky mutations against a freshly fetched server list.
     * Mutates {@code remote} in place: tombstoned items are removed, pending
     * edits are overlaid (and dropped once the server echoes them), and
     * unhonoured mutations are retried (throttled) so the server converges.
     */
    private static void reconcileMutations(Context context, List<ForumPost> remote) {
        if (remote == null) return;
        // ---- deleted posts ----
        java.util.Set<String> tombPosts = tombstones(context, KEY_TOMB_POSTS);
        if (!tombPosts.isEmpty()) {
            java.util.Set<String> remoteIds = new java.util.HashSet<>();
            for (ForumPost p : remote) remoteIds.add(p.id);
            for (String tomb : new java.util.ArrayList<>(tombPosts)) {
                String id = tombPostId(tomb);
                if (!remoteIds.contains(id)) {
                    // Server confirms the post is gone: tombstone has done its job.
                    removeTombstone(context, KEY_TOMB_POSTS, tomb);
                } else {
                    for (int i = remote.size() - 1; i >= 0; i--) {
                        if (remote.get(i).id.equals(id)) remote.remove(i);
                    }
                    if (retryDue(context, "retry_del_post_" + id)) {
                        try {
                            org.json.JSONObject o = new org.json.JSONObject();
                            o.put("email", tombEmail(tomb, 2));
                            deleteJsonWithFallback("/forums/" + id, o.toString());
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        // ---- deleted replies + pending edits, per thread ----
        java.util.Set<String> tombReplies = tombstones(context, KEY_TOMB_REPLIES);
        for (ForumPost p : remote) {
            if (p == null) continue;
            // Pending post edit: converge or overlay + retry.
            try {
                org.json.JSONObject pend = loadPendingPostEdit(context, p.id);
                if (pend != null) {
                    String title = pend.optString("title", p.title);
                    String body = pend.optString("body", p.body);
                    String email = pend.optString("email", "");
                    if (title.equals(p.title) && body.equals(p.body)) {
                        clearPendingPostEdit(context, p.id);
                    } else {
                        p.title = title;
                        p.body = body;
                        p.edited = true;
                        if (retryDue(context, "retry_put_post_" + p.id)) {
                            try {
                                org.json.JSONObject o = new org.json.JSONObject();
                                o.put("title", title);
                                o.put("body", body);
                                o.put("email", email);
                                putJsonWithFallback("/forums/" + p.id, o.toString());
                            } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception ignored) {}
            if (p.replies == null) continue;
            // Collect the reply ids still present on the server for tombstone cleanup.
            java.util.Set<String> remoteReplyIds = new java.util.HashSet<>();
            for (ForumPost.ForumReply r : p.replies) remoteReplyIds.add(r.id);
            if (!tombReplies.isEmpty()) {
                for (String tomb : new java.util.ArrayList<>(tombReplies)) {
                    try {
                        String[] parts = tomb.split("\\|", -1);
                        if (parts.length != 3 || !parts[0].equals(p.id)) continue;
                        String replyId = parts[1];
                        String email = parts[2];
                        if (!remoteReplyIds.contains(replyId)) {
                            removeTombstone(context, KEY_TOMB_REPLIES, tomb);
                        } else {
                            for (int i = p.replies.size() - 1; i >= 0; i--) {
                                if (p.replies.get(i).id.equals(replyId)) p.replies.remove(i);
                            }
                            if (retryDue(context, "retry_del_reply_" + p.id + "_" + replyId)) {
                                try {
                                    org.json.JSONObject o = new org.json.JSONObject();
                                    o.put("email", email);
                                    deleteJsonWithFallback("/forums/" + p.id + "/replies/" + replyId, o.toString());
                                } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
            // Pending reply edits: converge or overlay + retry.
            for (ForumPost.ForumReply r : p.replies) {
                try {
                    org.json.JSONObject pend = loadPendingReplyEdit(context, p.id, r.id);
                    if (pend == null) continue;
                    String body = pend.optString("body", r.body);
                    String email = pend.optString("email", "");
                    if (body.equals(r.body)) {
                        clearPendingReplyEdit(context, p.id, r.id);
                    } else {
                        r.body = body;
                        r.edited = true;
                        if (retryDue(context, "retry_put_reply_" + p.id + "_" + r.id)) {
                            try {
                                org.json.JSONObject o = new org.json.JSONObject();
                                o.put("body", body);
                                o.put("email", email);
                                putJsonWithFallback("/forums/" + p.id + "/replies/" + r.id, o.toString());
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Saves the post locally and publishes it to the shared backend,
     * blocking the caller. Returns true when at least one backend host
     * accepted it (i.e. it is now public to other devices).
     */
    public static boolean publishPostBlocking(Context context, ForumPost post) {
        rememberIdentity(context, post.username, post.email);
        rememberPostOwnership(context, post.id, post.email);
        boolean online = NetUtils.isOnline(context);
        // Uploads can take a while: do them before taking the store lock so
        // list refreshes never block on a large attachment.
        if (online) uploadPostAttachments(post);
        post.mediaPending = hasLocalAttachments(post);
        String payload;
        synchronized (ForumStore.class) {
            List<ForumPost> all = readLocal(context);
            boolean known = false;
            for (ForumPost p : all) {
                if (p.id.equals(post.id)) { known = true; break; }
            }
            if (!known) {
                all.add(0, post);
            } else {
                // Refresh the cached copy (e.g. local paths replaced by URLs).
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).id.equals(post.id)) { all.set(i, post); break; }
                }
            }
            saveLocal(context, all);
            payload = post.toNetworkJson().toString();
        }
        if (!online) return false;
        return postJsonWithFallback("/forums", payload);
    }

    /**
     * Saves the reply locally and publishes it, blocking the caller.
     * Returns true when a backend host accepted it.
     */
    public static boolean publishReplyBlocking(Context context, String postId,
                                                        ForumPost.ForumReply reply) {
        rememberIdentity(context, reply.username, reply.email);
        rememberReplyOwnership(context, reply.id, reply.email);
        String payload;
        try {
            org.json.JSONObject ro = new org.json.JSONObject();
            ro.put("id", reply.id);
            ro.put("username", reply.username);
            ro.put("email", reply.email);
            ro.put("body", reply.body);
            ro.put("timestamp", reply.timestamp);
            payload = ro.toString();
        } catch (Exception ignored) {
            return false;
        }
        synchronized (ForumStore.class) {
            List<ForumPost> all = readLocal(context);
            for (ForumPost p : all) {
                if (p.id.equals(postId)) {
                    boolean known = false;
                    for (ForumPost.ForumReply r : p.replies) {
                        if (r.id.equals(reply.id)) { known = true; break; }
                    }
                    if (!known) p.replies.add(reply);
                    break;
                }
            }
            saveLocal(context, all);
        }
        if (!NetUtils.isOnline(context)) return false;
        return postJsonWithFallback("/forums/" + postId + "/replies", payload);
    }

    /**
     * Updates a thread's title/body. The author email must match what was
     * used at creation (enforced client-side and again by the server).
     * Returns true when a backend host accepted the change.
     */
    public static boolean updatePostBlocking(Context context, String postId,
                                              String title, String body, String email) {
        rememberIdentity(context, getUsernameForEmail(context, email), email);
        // Record first: the overlay keeps the edit visible across refreshes
        // even while offline or when the server has not honoured the PUT yet.
        savePendingPostEdit(context, postId, title, body, email);
        String payload;
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("title", title);
            o.put("body", body);
            o.put("email", email);
            payload = o.toString();
        } catch (Exception ignored) {
            return false;
        }
        synchronized (ForumStore.class) {
            List<ForumPost> all = readLocal(context);
            for (ForumPost p : all) {
                if (p.id.equals(postId)) {
                    p.title = title;
                    p.body = body;
                    p.edited = true;
                    break;
                }
            }
            saveLocal(context, all);
        }
        if (!NetUtils.isOnline(context)) return false;
        return putJsonWithFallback("/forums/" + postId, payload);
    }

    /**
     * Deletes a thread (and its replies) after author-email verification.
     * Returns true when a backend host accepted the deletion.
     */
    public static boolean deletePostBlocking(Context context, String postId, String email) {
        forgetPostOwnership(context, postId);
        // Record first: the tombstone filters the post out of every refresh
        // until the server confirms it is gone (offline deletes stick too).
        addTombstone(context, KEY_TOMB_POSTS, postTombstone(postId, email));
        clearPendingPostEdit(context, postId);
        String payload;
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("email", email);
            payload = o.toString();
        } catch (Exception ignored) {
            return false;
        }
        synchronized (ForumStore.class) {
            List<ForumPost> all = readLocal(context);
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id.equals(postId)) { all.remove(i); break; }
            }
            saveLocal(context, all);
        }
        if (!NetUtils.isOnline(context)) return false;
        return deleteJsonWithFallback("/forums/" + postId, payload);
    }

    /** Updates a reply's body after author-email verification. */
    public static boolean updateReplyBlocking(Context context, String postId, String replyId,
                                               String body, String email) {
        rememberIdentity(context, getUsernameForEmail(context, email), email);
        // Record first: the overlay keeps the edit visible across refreshes
        // even while offline or when the server has not honoured the PUT yet.
        savePendingReplyEdit(context, postId, replyId, body, email);
        String payload;
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("body", body);
            o.put("email", email);
            payload = o.toString();
        } catch (Exception ignored) {
            return false;
        }
        synchronized (ForumStore.class) {
            List<ForumPost> all = readLocal(context);
            for (ForumPost p : all) {
                if (!p.id.equals(postId)) continue;
                for (ForumPost.ForumReply r : p.replies) {
                    if (r.id.equals(replyId)) { r.body = body; r.edited = true; break; }
                }
                break;
            }
            saveLocal(context, all);
        }
        if (!NetUtils.isOnline(context)) return false;
        return putJsonWithFallback("/forums/" + postId + "/replies/" + replyId, payload);
    }

    /** Deletes a reply after author-email verification. */
    public static boolean deleteReplyBlocking(Context context, String postId, String replyId, String email) {
        forgetReplyOwnership(context, replyId);
        // Record first: the tombstone filters the reply out of every refresh
        // until the server confirms it is gone (offline deletes stick too).
        addTombstone(context, KEY_TOMB_REPLIES, replyTombstone(postId, replyId, email));
        clearPendingReplyEdit(context, postId, replyId);
        String payload;
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("email", email);
            payload = o.toString();
        } catch (Exception ignored) {
            return false;
        }
        synchronized (ForumStore.class) {
            List<ForumPost> all = readLocal(context);
            for (ForumPost p : all) {
                if (!p.id.equals(postId)) continue;
                for (int i = 0; i < p.replies.size(); i++) {
                    if (p.replies.get(i).id.equals(replyId)) { p.replies.remove(i); break; }
                }
                break;
            }
            saveLocal(context, all);
        }
        if (!NetUtils.isOnline(context)) return false;
        return deleteJsonWithFallback("/forums/" + postId + "/replies/" + replyId, payload);
    }

    private static boolean putJsonWithFallback(String path, String payload) {
        try {
            NetUtils.httpPutJson(NetUtils.FORUM_API_BASE + path, payload, POST_TIMEOUT_MS);
            return true;
        } catch (Exception primaryError) {
            try {
                NetUtils.httpPutJson(NetUtils.FORUM_API_BASE_FALLBACK + path, payload, POST_TIMEOUT_MS);
                return true;
            } catch (Exception fallbackError) {
                return false;
            }
        }
    }

    private static boolean deleteJsonWithFallback(String path, String payload) {
        try {
            NetUtils.httpDeleteJson(NetUtils.FORUM_API_BASE + path, payload, POST_TIMEOUT_MS);
            return true;
        } catch (Exception primaryError) {
            try {
                NetUtils.httpDeleteJson(NetUtils.FORUM_API_BASE_FALLBACK + path, payload, POST_TIMEOUT_MS);
                return true;
            } catch (Exception fallbackError) {
                return false;
            }
        }
    }

    /**
     * Legacy fire-and-forget entry points (never block the caller).
     * Prefer {@link #publishPostBlocking} / {@link #publishReplyBlocking}
     * from a background thread when the caller needs the sync result.
     */
    public static void addPost(Context context, ForumPost post) {
        final Context app = context.getApplicationContext();
        new Thread(() -> publishPostBlocking(app, post), "socialhub-forum-push").start();
    }

    public static void addReply(Context context, String postId, ForumPost.ForumReply reply) {
        final Context app = context.getApplicationContext();
        new Thread(() -> publishReplyBlocking(app, postId, reply), "socialhub-reply-push").start();
    }

    /** POSTs to the primary host, then the mirror. True if either accepted it. */
    private static boolean postJsonWithFallback(String path, String payload) {
        try {
            NetUtils.httpPostJson(NetUtils.FORUM_API_BASE + path, payload, POST_TIMEOUT_MS);
            return true;
        } catch (Exception primaryError) {
            try {
                NetUtils.httpPostJson(NetUtils.FORUM_API_BASE_FALLBACK + path, payload, POST_TIMEOUT_MS);
                return true;
            } catch (Exception fallbackError) {
                return false;
            }
        }
    }

    /** Pushes local posts the server list does not contain yet. Best-effort. */
    private static void pushMissingPosts(List<ForumPost> local, List<ForumPost> remote) {
        if (local.isEmpty()) return;
        Map<String, Boolean> remoteIds = new LinkedHashMap<>();
        for (ForumPost p : remote) remoteIds.put(p.id, true);
        for (ForumPost p : local) {
            if (!remoteIds.containsKey(p.id)) {
                // Stranded posts may still hold local attachments: upload them
                // so the retried post goes public complete with its media.
                // (Local objects are mutated, so the merge below persists URLs.)
                uploadPostAttachments(p);
                postJsonWithFallback("/forums", p.toNetworkJson().toString());
            }
        }
    }

    /**
     * Replaces local attachment paths with public URLs, uploading the files
     * first. On any failure the local path is kept, so the media still shows
     * on this device and is retried on the next sync.
     */
    /** True when attachments exist only as on-device files (not yet public). */
    public static boolean hasLocalAttachments(ForumPost post) {
        if (post == null) return false;
        return (!ForumPost.isRemoteUrl(post.imagePath) && post.imagePath != null && !post.imagePath.isEmpty())
                || (!ForumPost.isRemoteUrl(post.videoPath) && post.videoPath != null && !post.videoPath.isEmpty());
    }

    private static void uploadPostAttachments(ForumPost post) {
        if (post == null) return;
        if (!ForumPost.isRemoteUrl(post.imagePath) && post.imagePath != null && !post.imagePath.isEmpty()) {
            String url = uploadAttachmentWithFallback(post.imagePath, false);
            if (url != null) post.imagePath = url;
        }
        if (!ForumPost.isRemoteUrl(post.videoPath) && post.videoPath != null && !post.videoPath.isEmpty()) {
            String url = uploadAttachmentWithFallback(post.videoPath, true);
            if (url != null) post.videoPath = url;
        }
    }

    private static String uploadAttachmentWithFallback(String localPath, boolean isVideo) {
        java.io.File file = new java.io.File(localPath);
        if (!file.isFile() || file.length() == 0) return null;
        long maxBytes = isVideo ? 32L * 1024L * 1024L : 10L * 1024L * 1024L;
        if (file.length() > maxBytes) return null;
        String mime = guessAttachmentMime(localPath, isVideo);
        String[] endpoints = {
                NetUtils.FORUM_API_BASE + "/forums/attachments",
                NetUtils.FORUM_API_BASE_FALLBACK + "/forums/attachments",
        };
        for (String endpoint : endpoints) {
            try {
                String response = NetUtils.httpPostMultipart(
                        endpoint, localPath, "file", file.getName(), mime, 90000);
                String url = new org.json.JSONObject(response).optString("url", "");
                if (ForumPost.isRemoteUrl(url)) return url;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String guessAttachmentMime(String path, boolean isVideo) {
        String lower = path.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".avif")) return "image/avif";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".3gp")) return "video/3gpp";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        return isVideo ? "video/mp4" : "image/jpeg";
    }

    /** Pushes local replies the server copy of each thread is missing. Best-effort. */
    private static void pushMissingReplies(List<ForumPost> local, List<ForumPost> remote) {
        Map<String, ForumPost> remoteById = new LinkedHashMap<>();
        for (ForumPost p : remote) remoteById.put(p.id, p);
        for (ForumPost p : local) {
            ForumPost server = remoteById.get(p.id);
            if (server == null) continue; // its post push above covers it
            Map<String, Boolean> serverReplyIds = new LinkedHashMap<>();
            for (ForumPost.ForumReply r : server.replies) serverReplyIds.put(r.id, true);
            for (ForumPost.ForumReply r : p.replies) {
                if (!serverReplyIds.containsKey(r.id)) {
                    try {
                        org.json.JSONObject ro = new org.json.JSONObject();
                        ro.put("id", r.id);
                        ro.put("username", r.username);
                        ro.put("email", r.email);
                        ro.put("body", r.body);
                        ro.put("timestamp", r.timestamp);
                        postJsonWithFallback("/forums/" + p.id + "/replies", ro.toString());
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private static List<ForumPost> parseArray(String body) {
        List<ForumPost> out = new ArrayList<>();
        try {
            String t = body.trim();
            JSONArray array;
            if (t.startsWith("{")) {
                org.json.JSONObject root = new org.json.JSONObject(t);
                array = null;
                for (String k : new String[]{"forums", "items", "data", "posts"}) {
                    if (root.optJSONArray(k) != null) { array = root.optJSONArray(k); break; }
                }
                if (array == null) return out;
            } else {
                array = new JSONArray(t);
            }
            for (int i = 0; i < array.length(); i++) {
                if (array.optJSONObject(i) != null) out.add(ForumPost.fromJson(array.optJSONObject(i)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static List<ForumPost> readLocal(Context context) {
        List<ForumPost> out = new ArrayList<>();
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.isFile()) return out;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int r = in.read(bytes, read, bytes.length - read);
                if (r == -1) break;
                read += r;
            }
            JSONArray array = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                if (array.optJSONObject(i) != null) out.add(ForumPost.fromJson(array.optJSONObject(i)));
            }
        } catch (Exception ignored) {}
        out.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        return out;
    }

    private static void saveLocal(Context context, List<ForumPost> posts) {
        try {
            JSONArray array = new JSONArray();
            for (ForumPost p : posts) array.put(p.toJson());
            File file = new File(context.getFilesDir(), FILE_NAME);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(array.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }
}
