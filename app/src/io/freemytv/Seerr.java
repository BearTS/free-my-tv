package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Your own Seerr (Jellyseerr or Overseerr; Home Settings > Seerr: its address and API key): Browse's Request button
 * for a title that isn't on Jellyfin, and where each request is (approved, downloading with Radarr's or Sonarr's
 * progress, on Jellyfin). Every call is made off the main thread.
 */
final class Seerr {
    /** Seerr's media states (MediaStatus) and request states (MediaRequestStatus). */
    static final int PENDING = 2, PROCESSING = 3, PARTLY = 4, AVAILABLE = 5;
    static final int WAITING = 1, APPROVED = 2, DECLINED = 3, FAILED = 4;

    /** Where a title is in Seerr: nothing (media 0), requested, downloading, on Jellyfin. */
    static final class State {
        int media;            // PENDING, PROCESSING, PARTLY, AVAILABLE, or 0 when Seerr has never seen it
        int request;          // the newest request's status, or 0
        float progress = -1;  // what's downloaded (0 to 1) while Radarr or Sonarr downloads it, else -1
        String left = "";     // "18 min left", from the download client
        String episode = "";  // the episode downloading, "S1 E4"
        String jellyfinId;    // the title's item on Jellyfin, once it's there

        boolean requested() {
            return media == PENDING || media == PROCESSING || request == WAITING || request == APPROVED;
        }
    }

    /** One of your requests, newest first: Browse's Requested row. */
    static final class Request {
        String kind;   // "movie" or "tv"
        long tmdbId;
        State state = new State();
    }

    private Seerr() {}

    static boolean configured() {
        return !Prefs.seerrUrl().isEmpty() && !Prefs.seerrKey().isEmpty();
    }

    /** The title's state in Seerr, or null when it couldn't say. */
    static State state(String kind, long tmdbId) {
        try {
            JSONObject o = new JSONObject(request("GET", "/api/v1/" + ("tv".equals(kind) ? "tv/" : "movie/") + tmdbId, null));
            return state(o.optJSONObject("mediaInfo"));
        } catch (Exception e) {
            AppLog.w("seerr: " + e.getMessage());
            return null;
        }
    }

    /** Asks for the title (a show: all its seasons). Null when Seerr took it, else why not. */
    static String ask(String kind, long tmdbId) {
        try {
            JSONObject body = new JSONObject().put("mediaType", "tv".equals(kind) ? "tv" : "movie").put("mediaId", tmdbId);
            if ("tv".equals(kind)) body.put("seasons", "all");
            request("POST", "/api/v1/request", body.toString());
            return null;
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("HTTP 401") || m.contains("HTTP 403")) return Ui.tr("Seerr refused the request.");
            if (m.contains("HTTP 409")) return Ui.tr("It's already requested.");
            return Ui.trf("Couldn't reach Seerr: {error}", "error", m);
        } catch (Exception e) {
            return Ui.trf("Couldn't reach Seerr: {error}", "error", e.getClass().getSimpleName());
        }
    }

    /** Your newest requests (at most `max`), with each one's state; empty when Seerr can't be reached. */
    static List<Request> requests(int max) {
        List<Request> out = new ArrayList<>();
        try {
            JSONArray results = new JSONObject(request("GET", "/api/v1/request?take=" + max + "&skip=0&sort=added&filter=all", null))
                    .optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                JSONObject media = r.optJSONObject("media");
                if (media == null || media.optLong("tmdbId") <= 0) continue;
                Request q = new Request();
                q.kind = "tv".equals(media.optString("mediaType")) ? "tv" : "movie";
                q.tmdbId = media.optLong("tmdbId");
                q.state = state(media);
                q.state.request = r.optInt("status");
                out.add(q);
            }
        } catch (Exception e) {
            AppLog.w("seerr requests: " + e.getMessage());
        }
        return out;
    }

    private static State state(JSONObject media) {
        State s = new State();
        if (media == null) return s;
        s.media = media.optInt("status");
        String jf = media.optString("jellyfinMediaId", "");
        s.jellyfinId = jf.isEmpty() || "null".equals(jf) ? null : jf;
        JSONArray requests = media.optJSONArray("requests");
        if (requests != null && requests.length() > 0) s.request = requests.optJSONObject(requests.length() - 1).optInt("status");
        JSONArray downloads = media.optJSONArray("downloadStatus");
        double size = 0, left = 0;
        long secondsLeft = 0;
        for (int i = 0; downloads != null && i < downloads.length(); i++) {
            JSONObject d = downloads.optJSONObject(i);
            if (d == null) continue;
            size += d.optDouble("size", 0);
            left += d.optDouble("sizeLeft", 0);
            secondsLeft = Math.max(secondsLeft, seconds(d.optString("timeLeft", "")));
            JSONObject ep = d.optJSONObject("episode");
            if (ep != null && s.episode.isEmpty()) {
                s.episode = String.format(Locale.ROOT, "S%d E%d", ep.optInt("seasonNumber"), ep.optInt("episodeNumber"));
            }
        }
        if (size > 0) s.progress = (float) Math.max(0, Math.min(1, 1 - left / size));
        if (secondsLeft > 0) {
            long min = (secondsLeft + 59) / 60;
            s.left = min < 60 ? Ui.trf("{n} min left", "n", min) : Ui.trf("{h} h {m} min left", "h", min / 60, "m", min % 60);
        }
        return s;
    }

    /** Radarr's and Sonarr's "1.02:03:04" or "00:18:21" in seconds. */
    private static long seconds(String timeLeft) {
        if (timeLeft == null || timeLeft.isEmpty()) return 0;
        try {
            long days = 0;
            String t = timeLeft;
            int dot = t.indexOf('.');
            if (dot > 0 && dot < t.indexOf(':')) {
                days = Long.parseLong(t.substring(0, dot));
                t = t.substring(dot + 1);
            }
            String[] p = t.split(":");
            long s = 0;
            for (String part : p) s = s * 60 + (long) Double.parseDouble(part);
            return days * 86400 + s;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // MARK: - Checking the settings

    /** The pasted key without spaces, or "" if it can't be one: Seerr's is base64 (about 68 characters, "=" at the end). */
    static String cleanKey(String pasted) {
        String key = pasted == null ? "" : pasted.replaceAll("\\s+", "");
        return key.matches("[A-Za-z0-9+/=_-]{16,200}") ? key : "";
    }

    /** Whether the address and key work (off the main thread): null if they do, else why not. */
    static String check(String url, String key) {
        try {
            request(Jellyfin.trim(url), "GET", "/api/v1/auth/me", null, key);
            return null;
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("HTTP 401") || m.contains("HTTP 403")) return Ui.tr("Seerr refused this key.");
            return Ui.trf("Couldn't reach Seerr: {error}", "error", m);
        } catch (Exception e) {
            return Ui.trf("Couldn't reach Seerr: {error}", "error", e.getClass().getSimpleName());
        }
    }

    // MARK: - HTTP

    private static String request(String method, String path, String body) throws IOException {
        return request(Jellyfin.trim(Prefs.seerrUrl()), method, path, body, Prefs.seerrKey());
    }

    /** Seerr's answer. The key goes in a header, never in the address, and never follows a redirect. */
    private static String request(String base, String method, String path, String body, String key) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Api-Key", key);
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code < 200 || code > 299) throw new IOException("Seerr returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
