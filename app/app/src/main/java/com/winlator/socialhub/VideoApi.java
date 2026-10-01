package com.winlator.socialhub;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches the public uploads of the official YouTube channel without an API key.
 *
 * <p>Layered strategy (first non-empty result wins):
 * <ol>
 *   <li>Channel RSS feed — reliable, and already contains title, upload date,
 *       thumbnail and total view count per video.</li>
 *   <li>The channel {@code /videos} page — supplements uploads older than the
 *       RSS window (~15 latest) and is the full fallback when RSS fails. Parses
 *       both the current ({@code lockupMetadataViewModel}) and legacy markup.</li>
 * </ol>
 */
public final class VideoApi {
    private VideoApi() {}

    /** "Tech Tanakorn TV" — resolved from https://www.youtube.com/@TechTanakornOfficialTH */
    public static final String CHANNEL_ID = "UCIQa0dliPS9mcBWHjMINifQ";
    public static final String RSS_URL =
            "https://www.youtube.com/feeds/videos.xml?channel_id=" + CHANNEL_ID;

    public static List<VideoItem> fetchVideos() throws Exception {
        Exception lastError = null;
        // Layer 1: RSS feed.
        try {
            List<VideoItem> rss = parseRss(NetUtils.httpGet(RSS_URL));
            if (!rss.isEmpty()) {
                // Supplement with the /videos page (older uploads beyond the RSS window).
                try {
                    mergeSupplement(rss, parseVideosPage(NetUtils.httpGet(NetUtils.YT_VIDEOS_URL)));
                } catch (Exception ignored) {}
                return rss;
            }
        } catch (Exception e) {
            lastError = e;
        }
        // Layer 2: /videos page alone.
        try {
            List<VideoItem> page = parseVideosPage(NetUtils.httpGet(NetUtils.YT_VIDEOS_URL));
            if (!page.isEmpty()) return page;
        } catch (Exception e) {
            lastError = e;
        }
        // Layer 3: channel front page (sometimes embeds videoIds even when
        // the /videos tab is blocked). Also try RSS via a dynamically resolved
        // channel id before giving up (handles future handle/channel changes).
        try {
            String frontHtml = NetUtils.httpGet(NetUtils.YT_CHANNEL_URL);
            String dynamicId = resolveChannelId(frontHtml);
            if (dynamicId != null && !dynamicId.equals(CHANNEL_ID)) {
                try {
                    List<VideoItem> dynRss = parseRss(NetUtils.httpGet(
                            "https://www.youtube.com/feeds/videos.xml?channel_id=" + dynamicId));
                    if (!dynRss.isEmpty()) return dynRss;
                } catch (Exception ignored) {}
            }
            List<VideoItem> front = parseVideosPage(frontHtml);
            if (!front.isEmpty()) return front;
        } catch (Exception e) {
            lastError = e;
        }
        if (lastError != null) throw lastError;
        return new ArrayList<>();
    }

    private static void mergeSupplement(List<VideoItem> base, List<VideoItem> extra) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (VideoItem v : base) seen.put(v.videoId, true);
        for (VideoItem v : extra) {
            if (!seen.containsKey(v.videoId)) {
                base.add(v);
                seen.put(v.videoId, true);
            }
            if (base.size() >= 60) break;
        }
    }

    // ---------------- RSS feed ----------------
    private static final Pattern ENTRY = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL);
    private static final Pattern TAG_CONTENT = Pattern.compile("<%s>(.*?)</%s>", Pattern.DOTALL);

    private static List<VideoItem> parseRss(String rss) {
        List<VideoItem> out = new ArrayList<>();
        Matcher em = ENTRY.matcher(rss);
        while (em.find()) {
            String e = em.group(1);
            String id = tag(e, "yt:videoId");
            String title = unescapeXml(tag(e, "title"));
            if (id.isEmpty() || title.isEmpty()) continue;
            VideoItem item = new VideoItem();
            item.videoId = id;
            item.title = title;
            String published = tag(e, "published");
            if (!published.isEmpty()) item.publishedAt = formatIsoDate(published);
            Matcher thumb = Pattern.compile("<media:thumbnail[^>]+url\\s*=\\s*\"([^\"]+)\"").matcher(e);
            if (thumb.find()) item.thumbnailOverride = thumb.group(1);
            Matcher views = Pattern.compile("<media:statistics[^>]+views\\s*=\\s*\"(\\d+)\"").matcher(e);
            if (views.find()) {
                try {
                    item.viewCount = Long.parseLong(views.group(1));
                    item.viewsText = formatViews(item.viewCount);
                } catch (NumberFormatException ignored) {}
            }
            out.add(item);
        }
        return out;
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String unescapeXml(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'");
    }

    private static String formatIsoDate(String iso) {
        try {
            String normalized = iso.length() >= 19 ? iso.substring(0, 19) : iso;
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = in.parse(normalized);
            if (date == null) return iso.substring(0, Math.min(10, iso.length()));
            SimpleDateFormat out = new SimpleDateFormat("MMM d, yyyy", Locale.US);
            return out.format(date);
        } catch (Exception e) {
            return iso.length() >= 10 ? iso.substring(0, 10) : iso;
        }
    }

    /** 310 -> "310 views", 1 -> "1 view", 1200 -> "1.2K views", 2500000 -> "2.5M views". */
    public static String formatViews(long count) {
        if (count < 0) return "";
        if (count == 1) return "1 view";
        if (count < 1000) return count + " views";
        if (count < 1_000_000) return trim1(count / 1000.0) + "K views";
        if (count < 1_000_000_000) return trim1(count / 1_000_000.0) + "M views";
        return trim1(count / 1_000_000_000.0) + "B views";
    }

    private static String trim1(double v) {
        String s = String.format(Locale.US, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    // ---------------- /videos page scraping ----------------
    private static final Pattern VIDEO_ID = Pattern.compile("\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{11})\"");
    private static final Pattern CHANNEL_ID_PAT = Pattern.compile("\"(?:externalId|channelId)\"\\s*:\\s*\"(UC[A-Za-z0-9_-]{20,})\"");
    // Current markup: "title":{"content":"..."}
    private static final Pattern TITLE_CONTENT =
            Pattern.compile("\"title\"\\s*:\\s*\\{\\s*\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    // Legacy markup fallbacks.
    private static final Pattern TITLE_RUNS = Pattern.compile("\"title\"\\s*:\\s*\\{\\s*\"runs\"\\s*:\\s*\\[\\s*\\{\\s*\"text\"\\s*:\\s*\"(.*?)\"",
            Pattern.DOTALL);
    private static final Pattern TITLE_SIMPLE = Pattern.compile("\"title\"\\s*:\\s*\\{\\s*\"simpleText\"\\s*:\\s*\"(.*?)\"",
            Pattern.DOTALL);
    private static final Pattern PUBLISHED = Pattern.compile("\"publishedTimeText\"\\s*:\\s*\\{\\s*\"simpleText\"\\s*:\\s*\"(.*?)\"",
            Pattern.DOTALL);
    // Current metadata rows: "text":{"content":"310"},"accessibilityLabel":"310 views"
    private static final Pattern META_PART = Pattern.compile(
            "\"text\"\\s*:\\s*\\{\\s*\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\}"
                    + "(?:\\s*,\\s*\"accessibilityLabel\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\")?");

    private static List<VideoItem> parseVideosPage(String html) {
        Map<String, VideoItem> dedup = new LinkedHashMap<>();
        Matcher m = VIDEO_ID.matcher(html);
        List<String> ids = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        while (m.find()) {
            if (!ids.contains(m.group(1))) {
                ids.add(m.group(1));
                positions.add(m.start());
            }
            if (ids.size() >= 60) break;
        }
        for (int i = 0; i < ids.size(); i++) {
            int start = positions.get(i);
            int end = Math.min(html.length(), start + 12000);
            String window = html.substring(start, end);
            VideoItem item = new VideoItem();
            item.videoId = ids.get(i);

            Matcher t = TITLE_CONTENT.matcher(window);
            if (t.find()) item.title = unescapeJs(t.group(1));
            if (item.title.isEmpty()) {
                Matcher tr = TITLE_RUNS.matcher(window);
                if (tr.find()) item.title = unescapeJs(tr.group(1));
            }
            if (item.title.isEmpty()) {
                Matcher ts = TITLE_SIMPLE.matcher(window);
                if (ts.find()) item.title = unescapeJs(ts.group(1));
            }
            if (item.title.isEmpty()) item.title = "Video " + item.videoId;

            // Views + upload date from the metadata rows.
            Matcher mp = META_PART.matcher(window);
            String dateCandidate = "";
            while (mp.find()) {
                String content = unescapeJs(mp.group(1));
                String label = mp.groupCount() >= 2 && mp.group(2) != null ? unescapeJs(mp.group(2)) : "";
                String lowLabel = label.toLowerCase(Locale.US);
                if (lowLabel.contains("view")) {
                    long n = parseCount(label);
                    if (n < 0) n = parseCount(content);
                    if (n >= 0) {
                        item.viewCount = n;
                        item.viewsText = formatViews(n);
                    } else if (!label.isEmpty()) {
                        item.viewsText = label;
                    } else if (!content.isEmpty()) {
                        item.viewsText = content;
                    }
                } else if (dateCandidate.isEmpty() && isDateText(content, label)) {
                    dateCandidate = !label.isEmpty() ? label : content;
                }
            }
            if (!dateCandidate.isEmpty()) item.publishedAt = dateCandidate;
            if (item.publishedAt.isEmpty()) {
                Matcher p = PUBLISHED.matcher(window);
                if (p.find()) item.publishedAt = unescapeJs(p.group(1));
            }
            dedup.put(item.videoId, item);
        }
        return new ArrayList<>(dedup.values());
    }

    private static boolean isDateText(String content, String label) {
        String s = (!label.isEmpty() ? label : content).toLowerCase(Locale.US);
        if (s.isEmpty() || s.contains("view")) return false;
        return s.contains("ago") || s.contains("hour") || s.contains("day") || s.contains("week")
                || s.contains("month") || s.contains("year") || s.contains("minute")
                || s.contains("second") || s.matches(".*\\d{4}.*") || s.contains("streamed")
                || s.contains("premiered");
    }

    /** Parses "1,234", "12K", "3.4M" style counts; -1 when unparseable. */
    private static long parseCount(String s) {
        try {
            String t = s.replace(",", "").trim();
            Matcher num = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*([KMBkmb]?)").matcher(t);
            if (!num.find()) return -1;
            double v = Double.parseDouble(num.group(1));
            String suffix = num.group(2).toUpperCase(Locale.US);
            if (suffix.equals("K")) v *= 1000;
            else if (suffix.equals("M")) v *= 1_000_000;
            else if (suffix.equals("B")) v *= 1_000_000_000;
            return (long) v;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String resolveChannelId(String html) {
        if (html == null || html.isEmpty()) return null;
        Matcher cm = CHANNEL_ID_PAT.matcher(html);
        if (cm.find()) return cm.group(1);
        // <link rel="canonical" href="https://www.youtube.com/channel/UC...">
        Matcher canon = Pattern.compile("/channel/(UC[A-Za-z0-9_-]{20,})").matcher(html);
        if (canon.find()) return canon.group(1);
        // <link ... videos.xml?channel_id=UC...>
        Matcher rss = Pattern.compile("videos\\.xml\\?channel_id=(UC[A-Za-z0-9_-]{20,})").matcher(html);
        if (rss.find()) return rss.group(1);
        // browseId (used by every tab endpoint): "browseId":"UC..."
        Matcher browse = Pattern.compile("\"browseId\"\\s*:\\s*\"(UC[A-Za-z0-9_-]{20,})\"").matcher(html);
        if (browse.find()) return browse.group(1);
        return null;
    }

    private static String unescapeJs(String s) {
        String out = s.replace("\\\"", "\"").replace("\\/", "/").replace("\\n", " ");
        // Decode U+XXXX escapes (e.g. U+0026 -> &, U+00E9 -> e-acute).
        // NOTE: the backslash-u pattern is built via concatenation so the
        // Java source itself never contains a literal backslash-u sequence
        // (javac translates those even inside comments/strings).
        try {
            Matcher um = Pattern.compile("\\\\" + "u([0-9a-fA-F]{4})").matcher(out);
            StringBuffer sb = new StringBuffer();
            while (um.find()) {
                int code = Integer.parseInt(um.group(1), 16);
                um.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) code)));
            }
            um.appendTail(sb);
            out = sb.toString();
        } catch (Exception ignored) {}
        out = out.replace("\\\\", "\\").replace("&amp;", "&").replace("&#39;", "'")
                .replace("&#x27;", "'").replace("&quot;", "\"").trim();
        return out;
    }
}
