package com.winlator.socialhub;

/** A single YouTube video from the official channel. */
public class VideoItem {
    public String videoId = "";
    public String title = "";
    /** Display date, e.g. "Sep 3, 2026" (RSS) or "3w ago" (page scrape). */
    public String publishedAt = "";
    /** Raw view count, -1 when unknown. */
    public long viewCount = -1;
    /** Display views, e.g. "310 views". Empty when unknown. */
    public String viewsText = "";
    /** Explicit thumbnail URL (from RSS); falls back to the default pattern. */
    public String thumbnailOverride = "";

    public String thumbnailUrl() {
        if (thumbnailOverride != null && !thumbnailOverride.isEmpty()) return thumbnailOverride;
        return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }

    /** Combined "310 views • Sep 3, 2026" line for the list UI. */
    public String metaLine() {
        if (!viewsText.isEmpty() && !publishedAt.isEmpty()) return viewsText + " • " + publishedAt;
        if (!viewsText.isEmpty()) return viewsText;
        return publishedAt != null ? publishedAt : "";
    }

    public String watchUrl() {
        return "https://www.youtube.com/watch?v=" + videoId;
    }
}
