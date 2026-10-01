package com.winlator.socialhub;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A forum thread plus its Reddit-style replies. */
public class ForumPost {
    public String id = UUID.randomUUID().toString();
    public String title = "";
    public String body = "";
    public String username = "";
    public String email = "";
    /** Absolute local file path of an attached image (optional). */
    public String imagePath;
    /** Absolute local file path of an attached video (optional). */
    public String videoPath;
    public long timestamp = System.currentTimeMillis();
    /** True when the author edited this post after publishing. */
    public boolean edited = false;
    /**
     * Local-only flag (never serialized): true when attachments could not be
     * uploaded yet, so the post is public as text while media stays on device.
     */
    public transient boolean mediaPending = false;
    public List<ForumReply> replies = new ArrayList<>();

    public static class ForumReply {
        public String id = UUID.randomUUID().toString();
        public String username = "";
        public String email = "";
        public String body = "";
        public long timestamp = System.currentTimeMillis();
        public boolean edited = false;
    }

    /** True for http(s) URLs that every device can fetch (vs local file paths). */
    public static boolean isRemoteUrl(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    /**
     * Full serialization used for the on-device cache. Must keep local file
     * paths, otherwise attachments vanish on the next load.
     */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("title", title);
            o.put("body", body);
            o.put("username", username);
            o.put("email", email);
            o.put("timestamp", timestamp);
            o.put("edited", edited);
            if (imagePath != null) o.put("imagePath", imagePath);
            if (videoPath != null) o.put("videoPath", videoPath);
            JSONArray arr = new JSONArray();
            for (ForumReply r : replies) {
                JSONObject ro = new JSONObject();
                ro.put("id", r.id);
                ro.put("username", r.username);
                ro.put("email", r.email);
                ro.put("body", r.body);
                ro.put("timestamp", r.timestamp);
                ro.put("edited", r.edited);
                arr.put(ro);
            }
            o.put("replies", arr);
        } catch (Exception ignored) {}
        return o;
    }

    /**
     * Upload payload. Absolute device paths (e.g. /data/data/...) are stripped
     * because other devices cannot read them; only remote http(s) URLs are
     * shared (as imageUrl/videoUrl). Local attachments should be uploaded via
     * the attachments endpoint first (see ForumStore).
     */
    public JSONObject toNetworkJson() {
        JSONObject o = toJson();
        o.remove("imagePath");
        o.remove("videoPath");
        try {
            if (isRemoteUrl(imagePath)) o.put("imageUrl", imagePath);
            if (isRemoteUrl(videoPath)) o.put("videoUrl", videoPath);
        } catch (Exception ignored) {}
        return o;
    }

    public static ForumPost fromJson(JSONObject o) {
        ForumPost p = new ForumPost();
        p.id = o.optString("id", p.id);
        p.title = o.optString("title", "");
        // Remote backends may use different field names; accept common aliases.
        p.body = firstPresent(o, "", "body", "content", "detail", "description", "text", "message");
        p.username = firstPresent(o, "", "username", "user", "author", "name");
        p.email = firstPresent(o, "", "email", "mail");
        p.timestamp = parseTimestamp(o);
        p.edited = o.optBoolean("edited", false);
        if (o.has("imagePath")) p.imagePath = o.optString("imagePath", null);
        if (o.has("imageUrl")) p.imagePath = o.optString("imageUrl", p.imagePath);
        if (o.has("videoPath")) p.videoPath = o.optString("videoPath", null);
        if (o.has("videoUrl")) p.videoPath = o.optString("videoUrl", p.videoPath);
        JSONArray arr = o.optJSONArray("replies");
        if (arr == null) arr = o.optJSONArray("comments");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject ro = arr.optJSONObject(i);
                if (ro == null) continue;
                ForumReply r = new ForumReply();
                r.id = ro.optString("id", r.id);
                r.username = firstPresent(ro, "", "username", "user", "author", "name");
                r.email = firstPresent(ro, "", "email", "mail");
                r.body = firstPresent(ro, "", "body", "content", "text", "message", "comment");
                r.timestamp = parseTimestamp(ro);
                r.edited = ro.optBoolean("edited", false);
                p.replies.add(r);
            }
        }
        return p;
    }

    private static String firstPresent(JSONObject o, String fallback, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, null);
            if (v != null && !v.isEmpty()) return v;
        }
        return fallback;
    }

    /**
     * Accepts epoch millis (number or numeric string) as well as ISO-8601
     * date strings (e.g. 2026-09-30T12:00:00Z), which some backends emit.
     * Falls back to now so a bad value never yields a 1970 date.
     */
    static long parseTimestamp(JSONObject o) {
        long fallback = System.currentTimeMillis();
        for (String k : new String[]{"timestamp", "createdAt", "created_at", "date"}) {
            if (!o.has(k) || o.isNull(k)) continue;
            long asLong = o.optLong(k, Long.MIN_VALUE);
            if (asLong != Long.MIN_VALUE) {
                // Seconds-precision epochs are plausible from small backends.
                if (asLong > 0 && asLong < 10000000000L) asLong *= 1000;
                if (asLong > 0) return asLong;
                continue;
            }
            String s = o.optString(k, "");
            if (s.isEmpty()) continue;
            try {
                // Try ISO-8601 via the platform date parser.
                java.text.SimpleDateFormat[] formats = {
                        new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", java.util.Locale.US),
                        new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", java.util.Locale.US),
                        new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US),
                };
                for (java.text.SimpleDateFormat f : formats) {
                    try {
                        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                        java.util.Date d = f.parse(s);
                        if (d != null) return d.getTime();
                    } catch (Exception ignored) {}
                }
                long numeric = Long.parseLong(s.trim());
                if (numeric > 0 && numeric < 10000000000L) numeric *= 1000;
                if (numeric > 0) return numeric;
            } catch (Exception ignored) {}
        }
        return fallback;
    }
}
