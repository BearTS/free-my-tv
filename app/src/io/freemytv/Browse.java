package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Browse's data (BrowseActivity): TMDB's lists, a title's page (cast, the franchise, more like this, where it streams)
 * and a person's, and where each title is for this TV: on your Jellyfin, on one of the TV's apps, or requested in
 * Seerr. Every method that reads the network runs off the main thread.
 */
final class Browse {
    static final String IMAGES = "https://image.tmdb.org/t/p/";

    /** A poster in a row: a movie, a show or a person. */
    static final class Item {
        String kind;          // "movie", "tv" or "person"
        long id;
        String title = "", year = "", poster, backdrop, overview = "", role = "", date = "";
        double rating;
        int votes;
        boolean here;         // the title whose page this is (its franchise's row)
    }

    /** Where a title streams: one of the TV's services (or one it hasn't the app for). */
    static final class Where {
        String key, pkg, name, logo;
        boolean installed, free;
    }

    /** A title's page. */
    static final class Title {
        String kind, title = "", year = "", tagline = "", overview = "", poster, backdrop, imdbId, trailer;
        String cert = "", runtime = "", genres = "", status = "", director = "", writer = "", released = "";
        long directorId;
        double rating;
        int votes;
        String collection;    // the franchise's name, or null
        final List<Item> cast = new ArrayList<>(), franchise = new ArrayList<>(), related = new ArrayList<>();
        final List<Where> where = new ArrayList<>();
        long id;
        String imdbRating, rtRating;
    }

    /** A person's page. */
    static final class Person {
        long id;
        String name = "", photo, bio = "", dept = "", birthday = "", deathday = "", place = "", knownFor = "";
        int credits;
        final List<Item> works = new ArrayList<>();
    }

    /** What a poster's tag says: on Jellyfin, requested, on one of the TV's apps, or that it can be requested. */
    static final class Badge {
        static final int JELLYFIN = 1, REQUESTED = 2, DOWNLOADING = 3, SERVICE = 4, REQUEST = 5;
        int type;
        String key = "", label = "";
        float progress = -1;
    }

    private static final Map<String, List<Where>> providers = new ConcurrentHashMap<>();
    private static final Map<String, Badge> badges = new ConcurrentHashMap<>();
    private static volatile Map<String, Seerr.State> requested = new HashMap<>();

    private Browse() {}

    static String id(String kind, long id) {
        return kind + ":" + id;
    }

    // MARK: - Lists

    /** A TMDB list's titles (results[]), without people and titles with no poster. `kind` for lists of one kind. */
    static List<Item> list(String path, String kind) throws Exception {
        JSONObject o = Recs.tmdb(path + (path.contains("?") ? "&" : "?") + "language=" + Recs.lang());
        return items(o.optJSONArray("results"), kind, false);
    }

    /** Two lists one after the other in turn (a movie, a show…): Browse's All. */
    static List<Item> interleave(List<Item> a, List<Item> b) {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i < a.size()) out.add(a.get(i));
            if (i < b.size()) out.add(b.get(i));
        }
        return out;
    }

    static List<Item> items(JSONArray a, String kind, boolean people) {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String k = o.optString("media_type", kind == null ? "movie" : kind);
            if ("person".equals(k) && !people) continue;
            Item it = item(o, k);
            if (it == null || !seen.add(id(it.kind, it.id))) continue;
            out.add(it);
        }
        return out;
    }

    private static Item item(JSONObject o, String kind) {
        Item it = new Item();
        it.kind = kind;
        it.id = o.optLong("id");
        if ("person".equals(kind)) {
            it.title = o.optString("name");
            it.poster = path(o, "profile_path");
            it.role = o.optString("known_for_department");
            return it.id > 0 ? it : null;
        }
        it.title = o.optString("movie".equals(kind) ? "title" : "name", o.optString("title", o.optString("name")));
        it.date = o.optString("movie".equals(kind) ? "release_date" : "first_air_date", "");
        it.year = it.date.length() >= 4 ? it.date.substring(0, 4) : "";
        it.poster = path(o, "poster_path");
        it.backdrop = path(o, "backdrop_path");
        it.overview = o.optString("overview");
        it.rating = o.optDouble("vote_average", 0);
        it.votes = o.optInt("vote_count");
        return it.id > 0 && it.poster != null ? it : null;
    }

    private static String path(JSONObject o, String name) {
        String p = o.optString(name, "");
        return p.startsWith("/") ? p : null;
    }

    // MARK: - Search

    /** Titles and people for what was typed, best first; `kind` null for all, else "movie" or "tv". */
    static List<Item> search(String query, String kind) throws Exception {
        String q = java.net.URLEncoder.encode(query.trim(), "UTF-8");
        String path = kind == null ? "/search/multi" : "/search/" + kind;
        JSONObject o = Recs.tmdb(path + "?query=" + q + "&include_adult=false&language=" + Recs.lang());
        List<Item> out = items(o.optJSONArray("results"), kind, kind == null);
        return out;
    }

    // MARK: - A title

    static Title title(Context c, String kind, long id) throws Exception {
        boolean tv = "tv".equals(kind);
        JSONObject o = Recs.tmdb("/" + kind + "/" + id + "?language=" + Recs.lang() + "&append_to_response=credits,external_ids,recommendations,similar,videos,"
                + "watch/providers," + (tv ? "content_ratings" : "release_dates") + "&include_video_language=en,null");
        Title t = new Title();
        t.kind = kind;
        t.id = id;
        t.title = o.optString(tv ? "name" : "title");
        String date = o.optString(tv ? "first_air_date" : "release_date", "");
        t.year = date.length() >= 4 ? date.substring(0, 4) : "";
        t.released = date;
        t.tagline = o.optString("tagline");
        t.overview = o.optString("overview");
        t.poster = path(o, "poster_path");
        t.backdrop = path(o, "backdrop_path");
        t.rating = o.optDouble("vote_average", 0);
        t.votes = o.optInt("vote_count");
        JSONObject ext = o.optJSONObject("external_ids");
        t.imdbId = o.optString("imdb_id", ext == null ? "" : ext.optString("imdb_id", ""));
        if (t.imdbId.isEmpty() && ext != null) t.imdbId = ext.optString("imdb_id", "");
        JSONArray genres = o.optJSONArray("genres");
        List<String> names = new ArrayList<>();
        for (int i = 0; genres != null && i < genres.length(); i++) names.add(genres.getJSONObject(i).optString("name"));
        t.genres = join(names, ", ");
        if (tv) {
            int seasons = o.optInt("number_of_seasons"), episodes = o.optInt("number_of_episodes");
            t.runtime = Ui.plural("plural|one:{n} season|other:{n} seasons", seasons)
                    + (episodes > 0 ? " · " + Ui.plural("plural|one:{n} episode|other:{n} episodes", episodes) : "");
            t.status = o.optString("status");
            JSONArray by = o.optJSONArray("created_by");
            if (by != null && by.length() > 0) {
                t.director = by.getJSONObject(0).optString("name");
                t.directorId = by.getJSONObject(0).optLong("id");
            }
        } else {
            int min = o.optInt("runtime");
            t.runtime = min <= 0 ? "" : min < 60 ? min + "m" : (min / 60) + "h " + (min % 60) + "m";
        }
        t.cert = certification(o, tv);
        JSONObject credits = o.optJSONObject("credits");
        if (credits != null) {
            JSONArray cast = credits.optJSONArray("cast");
            for (int i = 0; cast != null && i < cast.length() && t.cast.size() < 20; i++) {
                JSONObject p = cast.getJSONObject(i);
                Item it = new Item();
                it.kind = "person";
                it.id = p.optLong("id");
                it.title = p.optString("name");
                it.role = p.optString("character");
                it.poster = path(p, "profile_path");
                t.cast.add(it);
            }
            JSONArray crew = credits.optJSONArray("crew");
            List<String> writers = new ArrayList<>();
            for (int i = 0; crew != null && i < crew.length(); i++) {
                JSONObject p = crew.getJSONObject(i);
                String job = p.optString("job");
                if (!tv && "Director".equals(job) && t.director.isEmpty()) {
                    t.director = p.optString("name");
                    t.directorId = p.optLong("id");
                }
                if (("Screenplay".equals(job) || "Writer".equals(job)) && !writers.contains(p.optString("name")) && writers.size() < 2) {
                    writers.add(p.optString("name"));
                }
            }
            t.writer = join(writers, ", ");
        }
        JSONObject videos = o.optJSONObject("videos");
        JSONArray v = videos == null ? null : videos.optJSONArray("results");
        for (int pass = 0; pass < 2 && t.trailer == null; pass++) {
            for (int i = 0; v != null && i < v.length() && t.trailer == null; i++) {
                JSONObject x = v.getJSONObject(i);
                if (!"YouTube".equals(x.optString("site")) || !"Trailer".equals(x.optString("type"))) continue;
                if (pass == 0 && !x.optBoolean("official")) continue;
                t.trailer = x.optString("key");
            }
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        t.where.addAll(where(c, wp));
        providers.put(id(kind, id), t.where);
        // More like this: TMDB's recommendations (what people who liked it watched), else its similar titles.
        JSONObject rec = o.optJSONObject("recommendations");
        t.related.addAll(items(rec == null ? null : rec.optJSONArray("results"), kind, false));
        if (t.related.size() < 6) {
            JSONObject sim = o.optJSONObject("similar");
            Set<Long> have = new HashSet<>();
            for (Item it : t.related) have.add(it.id);
            for (Item it : items(sim == null ? null : sim.optJSONArray("results"), kind, false)) if (have.add(it.id)) t.related.add(it);
        }
        // The franchise: the collection it belongs to, in release order, this one marked.
        JSONObject coll = o.optJSONObject("belongs_to_collection");
        if (coll != null && coll.optLong("id") > 0) {
            try {
                JSONObject col = Recs.tmdb("/collection/" + coll.optLong("id") + "?language=" + Recs.lang());
                t.collection = col.optString("name", coll.optString("name"));
                List<Item> parts = items(col.optJSONArray("parts"), "movie", false);
                List<Item> dated = new ArrayList<>(), undated = new ArrayList<>();
                for (Item it : parts) (it.date.isEmpty() ? undated : dated).add(it);
                Collections.sort(dated, (a, b) -> a.date.compareTo(b.date));
                dated.addAll(undated);
                for (Item it : dated) it.here = it.id == id;
                if (dated.size() > 1) t.franchise.addAll(dated);
            } catch (Exception e) {
                AppLog.w("browse collection: " + e.getMessage());
            }
        }
        return t;
    }

    /** The title's age rating in the TV's country (else the US's): "A", "R", "TV-MA"… */
    private static String certification(JSONObject o, boolean tv) {
        String[] want = {Recs.REGION, "US"};
        for (String region : want) {
            if (tv) {
                JSONObject cr = o.optJSONObject("content_ratings");
                JSONArray r = cr == null ? null : cr.optJSONArray("results");
                for (int i = 0; r != null && i < r.length(); i++) {
                    JSONObject x = r.optJSONObject(i);
                    if (region.equals(x.optString("iso_3166_1")) && !x.optString("rating").isEmpty()) return x.optString("rating");
                }
            } else {
                JSONObject rd = o.optJSONObject("release_dates");
                JSONArray r = rd == null ? null : rd.optJSONArray("results");
                for (int i = 0; r != null && i < r.length(); i++) {
                    JSONObject x = r.optJSONObject(i);
                    if (!region.equals(x.optString("iso_3166_1"))) continue;
                    JSONArray d = x.optJSONArray("release_dates");
                    for (int j = 0; d != null && j < d.length(); j++) {
                        String cert = d.optJSONObject(j).optString("certification");
                        if (!cert.isEmpty()) return cert;
                    }
                }
            }
        }
        return "";
    }

    /** The services a title streams on in the TV's country (subscription, free or with ads): the TV's apps first. */
    private static List<Where> where(Context c, JSONObject wp) {
        List<Where> installed = new ArrayList<>(), others = new ArrayList<>();
        JSONObject results = wp == null ? null : wp.optJSONObject("results");
        JSONObject here = results == null ? null : results.optJSONObject(Recs.REGION);
        if (here == null) return installed;
        Recs recs = Recs.get(c);
        Set<String> seen = new HashSet<>();
        for (String type : new String[] {"flatrate", "free", "ads"}) {
            JSONArray a = here.optJSONArray(type);
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                String name = p.optString("provider_name");
                String[] s = recs.provider(name, p.optInt("provider_id"));
                Where w = new Where();
                w.name = s != null ? Recs.serviceLabel(s[1]) : name;
                w.key = s != null ? s[1] : null;
                w.pkg = s != null ? s[2] : null;
                w.installed = s != null && !s[3].isEmpty();
                w.free = !"flatrate".equals(type);
                w.logo = path(p, "logo_path");
                if (!seen.add(w.key != null ? w.key : name.toLowerCase(Locale.ROOT))) continue;
                (w.installed ? installed : others).add(w);
            }
        }
        installed.addAll(others);
        return installed;
    }

    // MARK: - A person

    static Person person(long id) throws Exception {
        JSONObject o = Recs.tmdb("/person/" + id + "?language=" + Recs.lang() + "&append_to_response=combined_credits");
        Person p = new Person();
        p.id = id;
        p.name = o.optString("name");
        p.photo = path(o, "profile_path");
        p.bio = o.optString("biography").trim();
        if (p.bio.isEmpty() && !Recs.lang().startsWith("en")) {   // not translated: in English
            try {
                p.bio = Recs.tmdb("/person/" + id + "?language=en-US").optString("biography").trim();
            } catch (Exception ignored) {
            }
        }
        p.dept = o.optString("known_for_department");
        p.birthday = o.optString("birthday", "");
        p.deathday = o.optString("deathday", "");
        if ("null".equals(p.birthday)) p.birthday = "";
        if ("null".equals(p.deathday)) p.deathday = "";
        p.place = o.optString("place_of_birth", "");
        if ("null".equals(p.place)) p.place = "";
        JSONObject credits = o.optJSONObject("combined_credits");
        boolean acting = p.dept.isEmpty() || "Acting".equals(p.dept);
        JSONArray a = credits == null ? null : credits.optJSONArray(acting ? "cast" : "crew");
        Map<String, Item> byId = new HashMap<>();
        List<Item> all = new ArrayList<>();
        int known = 0;
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            known++;
            if (!acting && !p.dept.equals(x.optString("department"))) continue;
            String role = acting ? x.optString("character", "") : x.optString("job", "");
            if (acting && self(role, x.optJSONArray("genre_ids"))) continue;
            String kind = x.optString("media_type", "movie");
            Item it = item(x, kind);
            if (it == null) continue;
            Item had = byId.get(id(kind, it.id));
            if (had != null) {   // a show's several roles, or a director who also wrote it
                if (!role.isEmpty() && !had.role.contains(role)) had.role = had.role.isEmpty() ? role : had.role + ", " + role;
                continue;
            }
            it.role = role;
            byId.put(id(kind, it.id), it);
            all.add(it);
        }
        p.credits = known;
        // Known for: what most people rated.
        List<Item> popular = new ArrayList<>(all);
        Collections.sort(popular, (x, y) -> Integer.compare(y.votes, x.votes));
        List<String> names = new ArrayList<>();
        for (int i = 0; i < Math.min(3, popular.size()); i++) names.add(popular.get(i).title);
        p.knownFor = join(names, ", ");
        // Newest first; announced titles (no date yet) after the dated ones.
        List<Item> dated = new ArrayList<>(), undated = new ArrayList<>();
        for (Item it : all) (it.date.isEmpty() ? undated : dated).add(it);
        Collections.sort(dated, (x, y) -> y.date.compareTo(x.date));
        p.works.addAll(dated);
        p.works.addAll(undated);
        return p;
    }

    /** Talk shows, award shows and making-ofs, where they play themselves: left out of the person's row. */
    private static boolean self(String role, JSONArray genres) {
        String r = role.toLowerCase(Locale.ROOT);
        if (r.matches(".*\\b(self|himself|herself|themselves|host|guest)\\b.*") || r.contains("archive footage")) return true;
        for (int i = 0; genres != null && i < genres.length(); i++) {
            int g = genres.optInt(i);
            if (g == 10767 || g == 10763 || g == 10764) return true;   // talk, news, reality
        }
        return false;
    }

    /** Age today, or at their death: "40". */
    static String age(String birthday, String deathday) {
        if (birthday.length() < 10) return "";
        try {
            int by = Integer.parseInt(birthday.substring(0, 4)), bm = Integer.parseInt(birthday.substring(5, 7)), bd = Integer.parseInt(birthday.substring(8, 10));
            Calendar now = Calendar.getInstance();
            int y = now.get(Calendar.YEAR), m = now.get(Calendar.MONTH) + 1, d = now.get(Calendar.DAY_OF_MONTH);
            if (deathday.length() >= 10) {
                y = Integer.parseInt(deathday.substring(0, 4));
                m = Integer.parseInt(deathday.substring(5, 7));
                d = Integer.parseInt(deathday.substring(8, 10));
            }
            int age = y - by - (m < bm || (m == bm && d < bd) ? 1 : 0);
            return age > 0 ? String.valueOf(age) : "";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /** "1986-05-13" → "May 13, 1986" in the TV's language. */
    static String date(String iso) {
        if (iso == null || iso.length() < 10) return iso == null ? "" : iso;
        try {
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(Integer.parseInt(iso.substring(0, 4)), Integer.parseInt(iso.substring(5, 7)) - 1, Integer.parseInt(iso.substring(8, 10)));
            return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Ui.locale()).format(c.getTime());
        } catch (NumberFormatException e) {
            return iso;
        }
    }

    // MARK: - Where each title is: Jellyfin, Seerr, the TV's apps

    /** Reads what's on Jellyfin and what's requested in Seerr (at most every few minutes; off the main thread). */
    static void refreshPlaces(Context c) {
        Jellyfin.get(c).index();
        if (Seerr.configured()) {
            Map<String, Seerr.State> map = new HashMap<>();
            for (Seerr.Request r : Seerr.requests(50)) if (!map.containsKey(id(r.kind, r.tmdbId))) map.put(id(r.kind, r.tmdbId), r.state);
            requested = map;
            badges.clear();
        }
    }

    static Seerr.State requested(String kind, long id) {
        return requested.get(id(kind, id));
    }

    static void forget(String kind, long id) {
        badges.remove(id(kind, id));
    }

    /** The badge from what's known now (Jellyfin, Seerr), or null until badge() has asked TMDB where it streams. */
    static Badge quickBadge(Context c, Item it) {
        if ("person".equals(it.kind)) return null;
        Badge known = badges.get(id(it.kind, it.id));
        if (known != null) return known;
        if (Jellyfin.get(c).find(it.kind, it.id, null) != null) return badge(Badge.JELLYFIN, "jellyfin", "Jellyfin", -1);
        Seerr.State s = requested(it.kind, it.id);
        if (s != null && s.media == Seerr.AVAILABLE) return badge(Badge.JELLYFIN, "jellyfin", "Jellyfin", -1);
        return null;
    }

    /** The poster's badge, asking TMDB where it streams when needed (off the main thread). */
    static Badge badge(Context c, Item it) {
        Badge quick = quickBadge(c, it);
        if (quick != null || "person".equals(it.kind)) return quick;
        Seerr.State s = requested(it.kind, it.id);
        List<Where> where = providers.get(id(it.kind, it.id));
        if (where == null) {
            try {
                where = where(c, Recs.tmdb("/" + it.kind + "/" + it.id + "/watch/providers"));
                providers.put(id(it.kind, it.id), where);
            } catch (Exception e) {
                where = new ArrayList<>();
            }
        }
        Badge b = null;
        for (Where w : where) {
            if (w.installed) {
                b = badge(Badge.SERVICE, w.key, w.name, -1);
                break;
            }
        }
        if (b == null && s != null && s.requested()) {
            b = s.progress >= 0 ? badge(Badge.DOWNLOADING, "seerr", Ui.trf("{n}%", "n", Math.round(s.progress * 100)), s.progress)
                    : badge(Badge.REQUESTED, "seerr", Ui.tr("Requested"), -1);
        }
        if (b == null && Seerr.configured()) b = badge(Badge.REQUEST, "request", Ui.tr("+ Request"), -1);
        if (b == null) b = badge(0, "", "", -1);
        badges.put(id(it.kind, it.id), b);
        return b;
    }

    private static Badge badge(int type, String key, String label, float progress) {
        Badge b = new Badge();
        b.type = type;
        b.key = key;
        b.label = label;
        b.progress = progress;
        return b;
    }

    static String join(List<String> parts, String sep) {
        StringBuilder b = new StringBuilder();
        for (String s : parts) {
            if (s == null || s.isEmpty()) continue;
            if (b.length() > 0) b.append(sep);
            b.append(s);
        }
        return b.toString();
    }
}
