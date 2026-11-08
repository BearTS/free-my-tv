package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Your own Jellyfin server (Home Settings > Jellyfin: its address, your user and an API key): the Jellyfin tab's
 * titles, as picks. First what you're in the middle of (Resume) and each show's next episode (Next Up), then what was
 * added lately to the libraries switched on. OK plays the title in Wholphin, from where you stopped: a show plays its
 * next unwatched episode, asked of the server at that moment so it's never stale.
 */
final class Jellyfin {
    static final String KIND = "jf";
    static final String WHOLPHIN = "com.github.damontecres.wholphin";
    private static final String PLAYBACK = WHOLPHIN + ".PLAYBACK";
    private static final long FRESH_MS = 5 * 60_000L;
    private static final int UP_NEXT = 20, PER_LIBRARY = 16;

    private static Jellyfin instance;

    static synchronized Jellyfin get(Context c) {
        if (instance == null) instance = new Jellyfin(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile List<Recs.Pick> picks = new ArrayList<>();
    private volatile List<String[]> libraries = new ArrayList<>();   // {id, name} of the video libraries
    private volatile long loadedAt;
    private volatile boolean loading;
    private volatile String error = "";
    private volatile String userId, userFor = "";
    private Runnable listener;

    private Jellyfin(Context c) {
        context = c;
    }

    static boolean configured() {
        return !Prefs.jellyfinUrl().isEmpty() && !Prefs.jellyfinUser().isEmpty() && !Prefs.jellyfinKey().isEmpty();
    }

    void setListener(Runnable r) { listener = r; }

    List<Recs.Pick> picks() { return configured() ? picks : new ArrayList<>(); }

    List<String[]> libraries() { return libraries; }

    String error() { return error; }

    /** Reads the lists again when they're older than a few minutes (or `now`: coming back from playing something). */
    void refresh(boolean now) {
        if (!configured() || loading) return;
        if (!now && System.currentTimeMillis() - loadedAt < FRESH_MS) return;
        loading = true;
        io.execute(() -> {
            try {
                load();
                error = "";
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                AppLog.w("jellyfin: " + error);
            } finally {
                loadedAt = System.currentTimeMillis();
                loading = false;
                main.post(() -> { if (listener != null) listener.run(); });
            }
        });
    }

    /** The libraries, read now (off the main thread): for Home Settings' list. */
    List<String[]> readLibraries() throws Exception {
        List<String[]> out = new ArrayList<>();
        JSONArray items = get("/Users/" + user() + "/Views").optJSONArray("Items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            String type = o.optString("CollectionType", "");
            // Movies, shows and mixed folders; not music, books, photos, live TV, playlists or collections.
            if (type.isEmpty() || type.equals("movies") || type.equals("tvshows") || type.equals("mixed") || type.equals("homevideos")) {
                out.add(new String[] {o.getString("Id"), o.optString("Name")});
            }
        }
        libraries = out;
        return out;
    }

    private void load() throws Exception {
        String uid = user();
        List<String[]> libs = readLibraries();
        // What you're in the middle of, newest first, then each show's next episode.
        List<JSONObject> progress = new ArrayList<>(), recent = new ArrayList<>();
        JSONArray resume = get("/Users/" + uid + "/Items/Resume?Limit=" + UP_NEXT + "&MediaTypes=Video&Fields=" + FIELDS + "&EnableUserData=true")
                .optJSONArray("Items");
        for (int i = 0; resume != null && i < resume.length(); i++) progress.add(resume.getJSONObject(i));
        JSONArray next = get("/Shows/NextUp?UserId=" + uid + "&Limit=" + UP_NEXT + "&Fields=" + FIELDS + "&EnableUserData=true")
                .optJSONArray("Items");
        for (int i = 0; next != null && i < next.length(); i++) progress.add(next.getJSONObject(i));
        // Lately added, one library after another in turn, from the libraries switched on.
        Set<String> off = Prefs.jellyfinOff();
        List<JSONArray> latest = new ArrayList<>();
        for (String[] lib : libs) {
            if (off.contains(lib[0])) continue;
            JSONArray items = getArray("/Users/" + uid + "/Items/Latest?ParentId=" + lib[0] + "&Limit=" + PER_LIBRARY
                    + "&Fields=" + FIELDS + "&GroupItems=true&EnableUserData=true");
            latest.add(items);
        }
        for (int i = 0; i < PER_LIBRARY; i++) {
            for (JSONArray items : latest) if (i < items.length()) recent.add(items.getJSONObject(i));
        }
        // An episode is rated as its show (TMDB, IMDb, Rotten Tomatoes): the shows, in one request.
        Map<String, JSONObject> shows = new HashMap<>();
        StringBuilder ids = new StringBuilder();
        for (List<JSONObject> list : java.util.Arrays.asList(progress, recent)) {
            for (JSONObject o : list) {
                String sid = o.optString("SeriesId", "");
                if ("Episode".equals(o.optString("Type")) && !sid.isEmpty() && ids.indexOf(sid) < 0) ids.append(ids.length() == 0 ? "" : ",").append(sid);
            }
        }
        if (ids.length() > 0) {
            JSONArray found = get("/Users/" + uid + "/Items?Ids=" + ids + "&Fields=" + FIELDS).optJSONArray("Items");
            for (int i = 0; found != null && i < found.length(); i++) shows.put(found.getJSONObject(i).optString("Id"), found.getJSONObject(i));
        }
        Set<String> seen = new HashSet<>(), series = new HashSet<>();
        List<Recs.Pick> out = new ArrayList<>();
        for (JSONObject o : progress) add(out, seen, series, o, shows.get(o.optString("SeriesId", "")), true);
        for (JSONObject o : recent) add(out, seen, series, o, shows.get(o.optString("SeriesId", "")), false);
        picks = out;
    }

    private static final String FIELDS = "Overview,Genres,ProviderIds,OfficialRating";

    /** One title as a pick (once per item, and once per show among the ones in progress). */
    private void add(List<Recs.Pick> out, Set<String> seen, Set<String> series, JSONObject o, JSONObject show, boolean progress) {
        String id = o.optString("Id"), type = o.optString("Type");
        if (id.isEmpty() || !seen.add(id)) return;
        String seriesId = o.optString("SeriesId", "");
        if (type.equals("Episode") && !seriesId.isEmpty() && !series.add(seriesId)) return;
        if (type.equals("Series") && series.contains(id)) return;   // its next episode is already there
        if (!type.equals("Movie") && !type.equals("Series") && !type.equals("Episode") && !type.equals("Video")) return;
        Recs.Pick p = new Recs.Pick();
        p.kind = KIND;
        p.id = -Math.abs((id.hashCode() & 0x3fffffff) + 1);   // never TMDB's: no details to fetch
        p.link = id;
        p.type = type;
        p.app = "jellyfin";
        p.pkg = WHOLPHIN;
        p.detailed = true;
        p.overview = o.optString("Overview", "");
        boolean episode = type.equals("Episode");
        // Ratings as for the other picks: TMDB's (Jellyfin's community rating comes from it), and IMDb's and Rotten
        // Tomatoes' with an OMDb key, by the title's IMDb id (an episode's show's).
        JSONObject rated = episode && show != null ? show : o;
        JSONObject providers = rated.optJSONObject("ProviderIds");
        String imdb = providers == null ? "" : providers.optString("Imdb", "");
        p.imdbId = imdb.startsWith("tt") ? imdb : null;
        boolean tmdb = providers != null && !providers.optString("Tmdb", "").isEmpty();
        p.rating = tmdb ? rated.optDouble("CommunityRating", 0) : 0;
        p.votes = p.rating > 0 ? 100 : 0;
        p.title = episode ? o.optString("SeriesName", o.optString("Name")) : o.optString("Name");
        List<String> parts = new ArrayList<>();
        JSONObject data = o.optJSONObject("UserData");
        long position = data == null ? 0 : data.optLong("PlaybackPositionTicks") / 10_000;
        long length = o.optLong("RunTimeTicks") / 10_000;
        if (episode) {
            int s = o.optInt("ParentIndexNumber", -1), e = o.optInt("IndexNumber", -1);
            if (s >= 0 && e >= 0) parts.add(Ui.trf("S{season} E{episode}", "season", s, "episode", e));
            String name = o.optString("Name", "");
            if (!name.isEmpty()) parts.add(name);
        } else {
            if (type.equals("Series")) parts.add(Ui.tr("Series"));
            JSONArray genres = o.optJSONArray("Genres");
            if (genres != null && genres.length() > 0) parts.add(genres.optString(0));
            int year = o.optInt("ProductionYear");
            if (year > 0) parts.add(String.valueOf(year));
            String rated = o.optString("OfficialRating", "");
            if (!rated.isEmpty()) parts.add(rated);
        }
        if (!type.equals("Series") && length > 0) parts.add(minutes(length / 60_000));
        p.meta = String.join(" · ", parts);
        if (progress && position > 0 && length > position) {
            p.tag = Ui.trf("Continue · {time} left", "time", minutes(Math.max(1, (length - position) / 60_000)));
        } else if (progress && episode) {
            p.tag = Ui.tr("Up next");
        } else {
            p.tag = Ui.tr("Recently added");
        }
        // The poster: the show's for an episode. The landscape picture: the backdrop (the show's for an episode), else
        // an episode's own still.
        String posterOf = episode && !seriesId.isEmpty() ? seriesId : id;
        p.poster = image(posterOf, "Primary", "maxHeight=750");
        JSONArray backdrops = o.optJSONArray("BackdropImageTags"), parentBackdrops = o.optJSONArray("ParentBackdropImageTags");
        String backdropOf = backdrops != null && backdrops.length() > 0 ? id
                : parentBackdrops != null && parentBackdrops.length() > 0 ? o.optString("ParentBackdropItemId", seriesId) : null;
        p.backdrop = backdropOf != null ? image(backdropOf, "Backdrop", "maxWidth=1920") : image(id, "Primary", "maxWidth=1920");
        out.add(p);
    }

    private static String minutes(long min) {
        return min >= 60 ? Ui.trf("{h}h {m}m", "h", min / 60, "m", min % 60) : Ui.trf("{m}m", "m", min);
    }

    private static String image(String id, String kind, String size) {
        return base() + "/Items/" + id + "/Images/" + kind + "?" + size + "&quality=90";
    }

    // MARK: - Playing in Wholphin

    /**
     * Plays the pick in Wholphin (or opens its page, Home Settings > Jellyfin's Watch now): a show plays its next
     * unwatched episode (the first one if none was watched), and anything half-watched from where you stopped, asked
     * of the server now. If the server can't be reached, Wholphin opens the title as it was listed.
     */
    void open(Activity a, Recs.Pick p, Runnable failed) {
        boolean play = Prefs.jellyfinPlay();
        io.execute(() -> {
            String id = p.link;
            long position = 0;
            try {
                String uid = user();
                if ("Series".equals(p.type)) {
                    JSONArray next = get("/Shows/NextUp?UserId=" + uid + "&SeriesId=" + id + "&Limit=1").optJSONArray("Items");
                    if (next == null || next.length() == 0) {
                        next = get("/Shows/" + id + "/Episodes?UserId=" + uid + "&Limit=1&IsMissing=false").optJSONArray("Items");
                    }
                    if (next != null && next.length() > 0) id = next.getJSONObject(0).getString("Id");
                }
                JSONObject item = get("/Users/" + uid + "/Items/" + id);
                JSONObject data = item.optJSONObject("UserData");
                if (data != null && !data.optBoolean("Played")) position = data.optLong("PlaybackPositionTicks") / 10_000;
            } catch (Exception e) {
                AppLog.w("jellyfin open: " + e.getMessage());
            }
            final String itemId = id;
            final long at = position;
            main.post(() -> {
                Intent intent = new Intent(play ? PLAYBACK : Intent.ACTION_VIEW)
                        .setComponent(new ComponentName(WHOLPHIN, WHOLPHIN + ".MainActivity"))
                        .putExtra("itemId", itemId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (play && at > 0) intent.putExtra("position", at);
                try {
                    a.startActivity(intent);
                    loadedAt = 0;   // coming back, the lists are read again (the episode just watched moves on)
                } catch (ActivityNotFoundException | SecurityException e) {
                    AppLog.w("wholphin: " + e.getMessage());
                    if (failed != null) failed.run();
                }
            });
        });
    }

    // MARK: - Checking the settings

    /** Whether the address, user and key work together (off the main thread): null if they do, else why not. */
    static String check(String url, String user, String key) {
        try {
            JSONArray users = new JSONArray(request(trim(url), "/Users", key));
            for (int i = 0; i < users.length(); i++) {
                if (users.getJSONObject(i).optString("Name").equalsIgnoreCase(user.trim())) return null;
            }
            return Ui.trf("The server has no user named {user}.", "user", user.trim());
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("HTTP 401") || m.contains("HTTP 403")) return Ui.tr("Jellyfin refused this key.");
            return Ui.trf("Couldn't reach Jellyfin: {error}", "error", m);
        } catch (Exception e) {
            return Ui.trf("Couldn't reach Jellyfin: {error}", "error", e.getClass().getSimpleName());
        }
    }

    /** "http://192.168.1.20:8096/" → "http://192.168.1.20:8096". */
    static String trim(String url) {
        return url == null ? "" : url.trim().replaceAll("/+$", "");
    }

    // MARK: - HTTP

    private static String base() {
        return trim(Prefs.jellyfinUrl());
    }

    private String user() throws Exception {
        String want = Prefs.jellyfinUser().trim(), forNow = base() + "|" + want;
        if (userId != null && forNow.equals(userFor)) return userId;
        JSONArray users = new JSONArray(request(base(), "/Users", Prefs.jellyfinKey()));
        for (int i = 0; i < users.length(); i++) {
            JSONObject u = users.getJSONObject(i);
            if (u.optString("Name").equalsIgnoreCase(want)) {
                userId = u.getString("Id");
                userFor = forNow;
                return userId;
            }
        }
        throw new IOException("no user named " + want);
    }

    private static JSONObject get(String path) throws Exception {
        return new JSONObject(request(base(), path, Prefs.jellyfinKey()));
    }

    private static JSONArray getArray(String path) throws Exception {
        return new JSONArray(request(base(), path, Prefs.jellyfinKey()));
    }

    /** The server's answer. The key goes in a header, never in the address, and never follows a redirect. */
    private static String request(String base, String path, String key) throws IOException {
        HttpURLConnection c = open(base + path, key);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Jellyfin returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** A connection with the key, for the lists and the pictures (Recs downloads those through it). */
    static HttpURLConnection open(String url) throws IOException {
        return open(url, Prefs.jellyfinKey());
    }

    private static HttpURLConnection open(String url, String key) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Authorization", "MediaBrowser Client=\"Free My TV\", Device=\"TV\", DeviceId=\"freemytv\", Version=\"1\", Token=\""
                + key.replace("\"", "") + "\"");
        c.setRequestProperty("X-Emby-Token", key);
        return c;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
