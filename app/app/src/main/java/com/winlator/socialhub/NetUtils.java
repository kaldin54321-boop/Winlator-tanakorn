package com.winlator.socialhub;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small networking helpers shared by all Social Hub tabs. No external dependencies. */
public final class NetUtils {
    private NetUtils() {}

    public static final String NEWS_BASE_URL = "https://winlator-frost.tanakorn-website.workers.dev";
    public static final String NEWS_LIST_URL = NEWS_BASE_URL + "/news";
    // Legacy mirror, kept as a fallback if the primary host is unreachable.
    public static final String NEWS_BASE_URL_FALLBACK = "https://tanakorn-website.onrender.com";
    public static final String NEWS_LIST_URL_FALLBACK = NEWS_BASE_URL_FALLBACK + "/news";
    public static final String FORUM_API_BASE = NEWS_BASE_URL + "/api";
    public static final String FORUM_API_BASE_FALLBACK = NEWS_BASE_URL_FALLBACK + "/api";
    public static final String YT_CHANNEL_URL = "https://www.youtube.com/@TechTanakornOfficialTH";
    public static final String YT_VIDEOS_URL = YT_CHANNEL_URL + "/videos";

    public static final String OFFLINE_TEXT = "No Internet connection. Please connect to the Internet to view the contents";

    public static boolean isOnline(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    public static String httpGet(String urlString, int timeoutMs) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(true);
            // A desktop browser UA + consent cookie is required: YouTube rejects
            // default Java UAs and may serve a consent wall to mobile/shell clients.
            // The /videos page must contain ytInitialData with lockupViewModel items.
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
            connection.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            connection.setRequestProperty("Accept-Charset", "utf-8");
            connection.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI");
            if (urlString.contains("youtube.com")) {
                connection.setRequestProperty("Referer", "https://www.youtube.com/");
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " for " + urlString);
            try (InputStream in = connection.getInputStream()) {
                return readAll(in);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static String httpGet(String urlString) throws Exception {
        return httpGet(urlString, 15000);
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * POST a JSON payload and return the response body. Throws on transport
     * errors and non-2xx status codes so callers can fall back to the mirror
     * host or queue the payload for a later retry.
     */
    public static String httpPostJson(String urlString, String jsonBody, int timeoutMs) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(true);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json, */*");
            connection.setRequestProperty("User-Agent", "WinlatorFrost-Android");
            byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
            connection.getOutputStream().write(body);
            int code = connection.getResponseCode();
            InputStream in = (code >= 200 && code < 300)
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = "";
            if (in != null) {
                try (InputStream autoClose = in) {
                    response = readAll(autoClose);
                }
            }
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + " for " + urlString
                        + (response.isEmpty() ? "" : ": " + response.substring(0, Math.min(200, response.length()))));
            }
            return response;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * POSTs a single file as multipart/form-data and returns the response
     * body. Throws on transport errors and non-2xx status codes.
     */
    public static String httpPostMultipart(String urlString, String filePath, String fieldName,
                                           String fileName, String mimeType, int timeoutMs) throws Exception {
        java.io.File file = new java.io.File(filePath);
        if (!file.isFile()) throw new Exception("Attachment not found: " + filePath);
        String boundary = "----WinlatorFrost" + System.currentTimeMillis();
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(true);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("Accept", "application/json, */*");
            connection.setRequestProperty("User-Agent", "WinlatorFrost-Android");
            try (java.io.OutputStream out = connection.getOutputStream();
                 java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                String header = "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"" + fieldName
                        + "\"; filename=\"" + fileName + "\"\r\n"
                        + "Content-Type: " + mimeType + "\r\n\r\n";
                out.write(header.getBytes(StandardCharsets.UTF_8));
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            int code = connection.getResponseCode();
            InputStream in = (code >= 200 && code < 300)
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = "";
            if (in != null) {
                try (InputStream autoClose = in) {
                    response = readAll(autoClose);
                }
            }
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + " for " + urlString
                        + (response.isEmpty() ? "" : ": " + response.substring(0, Math.min(200, response.length()))));
            }
            return response;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * PUT a JSON payload and return the response body. Throws on transport
     * errors and non-2xx status codes.
     */
    public static String httpPutJson(String urlString, String jsonBody, int timeoutMs) throws Exception {
        return httpWriteJson("PUT", urlString, jsonBody, timeoutMs);
    }

    /**
     * DELETE with an optional JSON body (used to prove authorship) and return
     * the response body. Throws on transport errors and non-2xx codes.
     */
    public static String httpDeleteJson(String urlString, String jsonBody, int timeoutMs) throws Exception {
        return httpWriteJson("DELETE", urlString, jsonBody, timeoutMs);
    }

    private static String httpWriteJson(String method, String urlString, String jsonBody,
                                        int timeoutMs) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(true);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json, */*");
            connection.setRequestProperty("User-Agent", "WinlatorFrost-Android");
            if (jsonBody != null) {
                byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
                connection.getOutputStream().write(body);
            }
            int code = connection.getResponseCode();
            InputStream in = (code >= 200 && code < 300)
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = "";
            if (in != null) {
                try (InputStream autoClose = in) {
                    response = readAll(autoClose);
                }
            }
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + " for " + urlString
                        + (response.isEmpty() ? "" : ": " + response.substring(0, Math.min(200, response.length()))));
            }
            return response;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Best-effort POST of a JSON payload; failures are swallowed (offline-first UX). */
    public static void httpPostJsonQuiet(String urlString, String jsonBody) {
        try {
            httpPostJson(urlString, jsonBody, 10000);
        } catch (Exception ignored) {
        }
    }

    public static String resolveUrl(String base, String href) {
        if (href == null) return null;
        href = href.trim();
        if (href.startsWith("http://") || href.startsWith("https://")) return href;
        if (href.startsWith("//")) return "https:" + href;
        if (href.startsWith("/")) {
            String origin = base;
            int idx = origin.indexOf("/", origin.indexOf("://") + 3);
            if (idx != -1) origin = origin.substring(0, idx);
            return origin + href;
        }
        if (!base.endsWith("/")) base = base + "/";
        return base + href;
    }

    /** Strips tags and decodes the most common HTML entities. */
    public static String htmlToText(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?s)<script.*?</script>", " ")
                .replaceAll("(?s)<style.*?</style>", " ")
                .replaceAll("<br\\s*/?>", "\n")
                .replaceAll("</p\\s*>", "\n\n")
                .replaceAll("<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&rsquo;", "'").replace("&ldquo;", "\"").replace("&rdquo;", "\"");
        s = decodeNumericEntities(s);
        return s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n").trim();
    }

    /** Decodes numeric character references such as {@code &#x27;} / {@code &#39;}. */
    private static String decodeNumericEntities(String s) {
        try {
            java.util.regex.Matcher hex = java.util.regex.Pattern.compile("&#x([0-9a-fA-F]+);").matcher(s);
            StringBuffer out = new StringBuffer();
            while (hex.find()) {
                int code = Integer.parseInt(hex.group(1), 16);
                hex.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) code)));
            }
            hex.appendTail(out);
            s = out.toString();
            java.util.regex.Matcher dec = java.util.regex.Pattern.compile("&#(\\d+);").matcher(s);
            out = new StringBuffer();
            while (dec.find()) {
                int code = Integer.parseInt(dec.group(1));
                dec.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) code)));
            }
            dec.appendTail(out);
            s = out.toString();
        } catch (Exception ignored) {}
        return s;
    }
}
