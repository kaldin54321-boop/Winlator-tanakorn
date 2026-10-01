package com.winlator.socialhub;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches the news feed from the official website. Handles both a JSON API
 * (if the site exposes one) and plain HTML scraping as a fallback, so the
 * in-app reader keeps working regardless of how the site renders its list.
 */
public final class NewsApi {
    private NewsApi() {}

    public static List<NewsItem> fetchList() throws Exception {
        String body = null;
        try {
            body = NetUtils.httpGet(NetUtils.NEWS_LIST_URL);
        } catch (Exception primaryError) {
            // Primary host unreachable: fall back to the legacy mirror.
            body = NetUtils.httpGet(NetUtils.NEWS_LIST_URL_FALLBACK);
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            List<NewsItem> fromJson = parseJsonFeed(body);
            if (!fromJson.isEmpty()) return fromJson;
        }
        List<NewsItem> cards = parseNewsListCards(body);
        if (!cards.isEmpty()) return cards;
        return parseHtmlList(body);
    }

    // ---- JSON feed support (e.g. /news returning [{"title":..,"url":..,"image":..}]) ----
    private static List<NewsItem> parseJsonFeed(String body) {
        List<NewsItem> out = new ArrayList<>();
        try {
            JSONArray array;
            String t = body.trim();
            if (t.startsWith("{")) {
                JSONObject root = new JSONObject(t);
                String[] keys = {"news", "items", "articles", "data", "posts"};
                array = null;
                for (String k : keys) {
                    if (root.has(k) && root.optJSONArray(k) != null) { array = root.optJSONArray(k); break; }
                }
                if (array == null) return out;
            } else {
                array = new JSONArray(t);
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                NewsItem item = new NewsItem();
                item.title = o.optString("title", o.optString("name", "Untitled"));
                String url = o.optString("url", o.optString("link", o.optString("href", "")));
                String slug = o.optString("slug", "");
                if (url.isEmpty() && !slug.isEmpty()) url = NetUtils.NEWS_LIST_URL + "/" + slug;
                item.url = NetUtils.resolveUrl(NetUtils.NEWS_BASE_URL, url);
                String img = firstNonEmpty(o, "cover", "coverImage", "cover_image", "image", "thumbnail", "thumb", "img");
                if (!img.isEmpty()) item.coverImageUrl = NetUtils.resolveUrl(NetUtils.NEWS_BASE_URL, img);
                item.date = firstNonEmpty(o, "date", "publishedAt", "published_at", "createdAt", "created_at", "time");
                item.summary = firstNonEmpty(o, "summary", "excerpt", "description", "subtitle", "content");
                if (item.title != null && !item.title.isEmpty() && item.url != null && !item.url.isEmpty()) out.add(item);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    // ---- Official website markup (<article class="news-list-card"> ...) ----
    private static final Pattern NEWS_CARD = Pattern.compile(
            "<article[^>]*class\\s*=\\s*\"[^\"]*news-list-card[^\"]*\"[^>]*>(.*?)</article>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NEWS_META = Pattern.compile(
            "<div[^>]*class\\s*=\\s*\"[^\"]*news-meta[^\"]*\"[^>]*>(.*?)</div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SPAN = Pattern.compile(
            "<span[^>]*>(.*?)</span>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static List<NewsItem> parseNewsListCards(String html) {
        List<NewsItem> out = new ArrayList<>();
        Matcher cards = NEWS_CARD.matcher(html);
        while (cards.find()) {
            String card = cards.group(1);
            // Article link: the READ ARTICLE anchor (/en/news/<slug>).
            String href = null;
            Matcher links = ANCHOR.matcher(card);
            while (links.find()) {
                String h = links.group(1).trim();
                if (h.contains("/news/") && h.length() > "/en/news".length()) { href = h; break; }
            }
            if (href == null) continue;

            String title = "";
            Matcher h2 = Pattern.compile("<h2[^>]*>(.*?)</h2>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(card);
            if (h2.find()) title = NetUtils.htmlToText(h2.group(1));
            if (title.isEmpty()) continue;

            NewsItem item = new NewsItem();
            item.title = title;
            item.url = NetUtils.resolveUrl(NetUtils.NEWS_BASE_URL, href);
            Matcher img = IMG.matcher(card);
            if (img.find()) item.coverImageUrl = NetUtils.resolveUrl(NetUtils.NEWS_BASE_URL, img.group(1));

            String category = "";
            String date = "";
            Matcher meta = NEWS_META.matcher(card);
            if (meta.find()) {
                List<String> spans = new ArrayList<>();
                Matcher sm = SPAN.matcher(meta.group(1));
                while (sm.find()) spans.add(NetUtils.htmlToText(sm.group(1)));
                if (spans.size() >= 1) category = spans.get(0);
                if (spans.size() >= 2) date = spans.get(1);
            }
            item.date = category.isEmpty() ? date : (date.isEmpty() ? category : category + " • " + date);

            Matcher pm = PARA.matcher(card);
            if (pm.find()) {
                String s = NetUtils.htmlToText(pm.group(1));
                if (!s.equals(title) && s.length() > 1) item.summary = s;
            }
            out.add(item);
        }
        return out;
    }

    // ---- HTML scraping fallback ----
    private static final Pattern ANCHOR = Pattern.compile(
            "<a[^>]+href\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IMG = Pattern.compile(
            "<img[^>]+src\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING = Pattern.compile(
            "<h[1-4][^>]*>(.*?)</h[1-4]>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TIME = Pattern.compile(
            "(<time[^>]*>(.*?)</time>|<span[^>]*class\\s*=\\s*\"[^\"]*(date|time)[^\"]*\"[^>]*>(.*?)</span>)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PARA = Pattern.compile(
            "<p[^>]*>(.*?)</p>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static List<NewsItem> parseHtmlList(String html) {
        Map<String, NewsItem> dedup = new LinkedHashMap<>();
        Matcher anchors = ANCHOR.matcher(html);
        while (anchors.find()) {
            String href = anchors.group(1).trim();
            String inner = anchors.group(2);
            if (!looksLikeNewsLink(href)) continue;
            String absUrl = NetUtils.resolveUrl(NetUtils.NEWS_LIST_URL, href);
            if (dedup.containsKey(absUrl)) continue;

            String title = "";
            Matcher h = HEADING.matcher(inner);
            if (h.find()) title = NetUtils.htmlToText(h.group(1));
            if (title.isEmpty()) title = NetUtils.htmlToText(inner);
            if (title.length() > 160) title = title.substring(0, 157) + "...";
            if (title.isEmpty()) continue;

            NewsItem item = new NewsItem();
            item.title = title;
            item.url = absUrl;
            Matcher img = IMG.matcher(inner);
            if (img.find()) item.coverImageUrl = NetUtils.resolveUrl(NetUtils.NEWS_LIST_URL, img.group(1));

            // Look around the anchor for a nearby image / date / summary.
            int start = Math.max(0, anchors.start() - 4000);
            int end = Math.min(html.length(), anchors.end() + 4000);
            String context = html.substring(start, end);
            if (item.coverImageUrl == null) {
                Matcher ctxImg = IMG.matcher(context);
                if (ctxImg.find()) item.coverImageUrl = NetUtils.resolveUrl(NetUtils.NEWS_LIST_URL, ctxImg.group(1));
            }
            Matcher tm = TIME.matcher(context);
            if (tm.find()) item.date = NetUtils.htmlToText(tm.group(0));
            Matcher pm = PARA.matcher(context);
            if (pm.find()) {
                String s = NetUtils.htmlToText(pm.group(1));
                if (!s.equals(title) && s.length() > 10) item.summary = s.length() > 220 ? s.substring(0, 217) + "..." : s;
            }
            dedup.put(absUrl, item);
        }
        // Generic fallback: any heading + paragraph pair on the page counts as an item
        // pointing back at the news index (keeps the tab useful even for unusual markup).
        if (dedup.isEmpty()) {
            Matcher h = HEADING.matcher(html);
            int count = 0;
            while (h.find() && count < 30) {
                String title = NetUtils.htmlToText(h.group(1));
                if (title.length() < 4) continue;
                NewsItem item = new NewsItem();
                item.title = title;
                item.url = NetUtils.NEWS_LIST_URL + "#item-" + count;
                int ctxStart = Math.max(0, h.start() - 1500);
                int ctxEnd = Math.min(html.length(), h.end() + 2500);
                String context = html.substring(ctxStart, ctxEnd);
                Matcher ctxImg = IMG.matcher(context);
                if (ctxImg.find()) item.coverImageUrl = NetUtils.resolveUrl(NetUtils.NEWS_LIST_URL, ctxImg.group(1));
                Matcher pm = PARA.matcher(context);
                if (pm.find()) item.summary = NetUtils.htmlToText(pm.group(1));
                dedup.put(item.url, item);
                count++;
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private static boolean looksLikeNewsLink(String href) {
        if (href == null || href.isEmpty()) return false;
        String h = href.toLowerCase();
        if (h.startsWith("#") || h.startsWith("javascript:") || h.startsWith("mailto:")) return false;
        if (h.endsWith(".css") || h.endsWith(".js") || h.endsWith(".png") || h.endsWith(".jpg")
                || h.endsWith(".jpeg") || h.endsWith(".webp") || h.endsWith(".svg") || h.endsWith(".ico")) return false;
        return h.contains("/news/") || h.contains("news/") || h.matches(".*/[a-z0-9\\-_]{3,}/?$");
    }

    /** Loads the full article: paragraphs + every attached inline image. */
    public static void fetchDetail(NewsItem item) throws Exception {
        if (item.url.contains("#item-")) return; // generic fallback item: list data is all we have
        String html = NetUtils.httpGet(item.url);
        String scope = extractScope(html);
        if (item.date == null || item.date.isEmpty()) {
            // Official article header: <header class="article-header">...<span>Category</span><small>Date</small>...
            Matcher header = Pattern.compile("<header[^>]*class\\s*=\\s*\"[^\"]*article-header[^\"]*\"[^>]*>(.*?)</header>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
            if (header.find()) {
                String head = header.group(1);
                String category = "";
                String date = "";
                Matcher cs = Pattern.compile("<span[^>]*>(.*?)</span>",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(head);
                if (cs.find()) category = NetUtils.htmlToText(cs.group(1));
                Matcher ds = Pattern.compile("<small[^>]*>(.*?)</small>",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(head);
                if (ds.find()) date = NetUtils.htmlToText(ds.group(1));
                if (!date.isEmpty() || !category.isEmpty()) {
                    item.date = category.isEmpty() ? date : (date.isEmpty() ? category : category + " • " + date);
                }
            }
        }
        Matcher h1 = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(scope);
        if (h1.find()) {
            String t = NetUtils.htmlToText(h1.group(1));
            if (!t.isEmpty()) item.title = t;
        }
        // Cover: og:image first, then first content image.
        Matcher og = Pattern.compile("<meta[^>]+property\\s*=\\s*\"og:image\"[^>]+content\\s*=\\s*\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (og.find()) item.coverImageUrl = NetUtils.resolveUrl(item.url, og.group(1));

        Matcher imgs = IMG.matcher(scope);
        while (imgs.find()) {
            String src = NetUtils.resolveUrl(item.url, imgs.group(1).trim());
            if (src == null || src.isEmpty()) continue;
            String low = src.toLowerCase();
            if (low.contains("icon") || low.contains("logo") || low.contains("avatar") || low.endsWith(".svg")) continue;
            if (!item.bodyImageUrls.contains(src)) item.bodyImageUrls.add(src);
        }
        if (item.coverImageUrl == null && !item.bodyImageUrls.isEmpty()) {
            item.coverImageUrl = item.bodyImageUrls.get(0);
        }
        Matcher paras = PARA.matcher(scope);
        while (paras.find()) {
            String text = NetUtils.htmlToText(paras.group(1));
            if (text.length() >= 2) item.paragraphs.add(text);
        }
        if (item.paragraphs.isEmpty()) {
            String text = NetUtils.htmlToText(scope);
            if (!text.isEmpty()) {
                for (String chunk : text.split("\\n\\n")) {
                    String c = chunk.trim();
                    if (c.length() > 1) item.paragraphs.add(c);
                }
            }
        }
        item.detailLoaded = true;
    }

    private static String extractScope(String html) {
        String[] tags = {"article", "main"};
        for (String tag : tags) {
            Matcher m = Pattern.compile("<" + tag + "[^>]*>(.*?)</" + tag + ">",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
            if (m.find() && m.group(1).length() > 300) return m.group(1);
        }
        Matcher body = Pattern.compile("<body[^>]*>(.*?)</body>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (body.find()) return body.group(1);
        return html;
    }
}
