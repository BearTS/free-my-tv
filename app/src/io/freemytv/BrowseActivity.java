package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Browse (the compass at the start of the home screen's tab row): search, TMDB's lists by category, and every movie
 * and show with where it is for this TV. A title's page plays it on Jellyfin (Wholphin) when it's there, opens it in
 * the TV's app that streams it, or requests it in Seerr, with Radarr's or Sonarr's progress while it downloads; then
 * its cast (each person's page lists their work), its franchise in release order and more like it. Back goes back a
 * screen, and from Browse itself to the home screen.
 */
public class BrowseActivity extends Activity {
    private static final int BG = 0xFF0B0C10, INK = 0xFFF3F4F6, SOFT = 0xFFC9CDD3, DIM = 0xFFA3A8B2, FAINT = 0xFF8E95A1,
            DARK = 0xFF15171A, CARD = 0xFF262A33, BLUE = 0xFF7CC4FF, YELLOW = 0xFFFFD60A, GREEN = 0xFF5DD39E, RED = 0xFFFF6B6B;
    private static final int JF_A = 0xFFAA5CC3, JF_B = 0xFF00A4DC;
    private static final int MARGIN = 80;
    private static final String[] TYPES = {null, "movie", "tv"};
    /** The grids' posters across (Search, See all). */
    private static final int COLS = 7;
    /** {name, movies' list, shows' list (null: none)}: Browse's categories; REGION stands for the TV's country. */
    private static final String[][] CATEGORIES = {
        {"Trending this week", "/trending/movie/week", "/trending/tv/week"},
        {"New releases", "/movie/now_playing?region=REGION", "/tv/on_the_air"},
        {"Popular", "/movie/popular?region=REGION", "/tv/popular"},
        {"Top rated", "/movie/top_rated", "/tv/top_rated"},
        {"Coming soon", "/movie/upcoming?region=REGION", null},
        {"Action", "/discover/movie?with_genres=28&sort_by=popularity.desc", "/discover/tv?with_genres=10759&sort_by=popularity.desc"},
        {"Comedy", "/discover/movie?with_genres=35&sort_by=popularity.desc", "/discover/tv?with_genres=35&sort_by=popularity.desc"},
        {"Thriller", "/discover/movie?with_genres=53&sort_by=popularity.desc", "/discover/tv?with_genres=9648&sort_by=popularity.desc"},
        {"Sci-Fi", "/discover/movie?with_genres=878&sort_by=popularity.desc", "/discover/tv?with_genres=10765&sort_by=popularity.desc"},
        {"Horror", "/discover/movie?with_genres=27&sort_by=popularity.desc", null},
        {"Crime", "/discover/movie?with_genres=80&sort_by=popularity.desc", "/discover/tv?with_genres=80&sort_by=popularity.desc"},
        {"Drama", "/discover/movie?with_genres=18&sort_by=popularity.desc", "/discover/tv?with_genres=18&sort_by=popularity.desc"},
        {"Romance", "/discover/movie?with_genres=10749&sort_by=popularity.desc", null},
        {"Anime", "/discover/movie?with_genres=16&with_original_language=ja&sort_by=popularity.desc",
                "/discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc"},
        {"Animation", "/discover/movie?with_genres=16&sort_by=popularity.desc", "/discover/tv?with_genres=16&sort_by=popularity.desc"},
        {"Documentary", "/discover/movie?with_genres=99&sort_by=popularity.desc", "/discover/tv?with_genres=99&sort_by=popularity.desc"},
        {"Family", "/discover/movie?with_genres=10751&sort_by=popularity.desc", "/discover/tv?with_genres=10751&sort_by=popularity.desc"},
    };
    /** The home's rows: the first categories. */
    private static final int HOME_ROWS = 5;

    private float k;
    private FrameLayout root;
    private Recs recs;
    private final List<Screen> stack = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    /** Newest first: what was asked for last (the screen just opened) comes before what scrolled away. */
    private final ExecutorService lookups = lifo(3), pictures = lifo(3);
    private LruCache<String, Bitmap> cache;
    private volatile boolean destroyed;
    private boolean resumedOnce;

    /** One screen of the stack: Browse, Search, a list, a title, a person. */
    private static final class Screen {
        final FrameLayout view;
        View focus;
        Runnable resumed;
        final List<Poster> posters = new ArrayList<>();

        Screen(FrameLayout v) {
            view = v;
        }
    }

    private static ExecutorService lifo(int threads) {
        return new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingDeque<Runnable>() {
            @Override public boolean offer(Runnable r) {
                return offerFirst(r);
            }
        });
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        DisplayMetrics m = getResources().getDisplayMetrics();
        k = Math.max(m.widthPixels, m.heightPixels) / 1920f;
        int max = (int) Math.min(Runtime.getRuntime().maxMemory() / 6, 40L << 20);
        cache = new LruCache<String, Bitmap>(max) {
            @Override protected int sizeOf(String key, Bitmap b) {
                return b.getAllocationByteCount();
            }
        };
        recs = Recs.get(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        if (!Recs.hasKey(this)) {
            push(message(Ui.tr("Browse needs a TMDB key."), Ui.tr("Add yours in Free My TV's Home Settings › TMDB key.")), null);
            return;
        }
        Screen home = home();
        push(home, null);
        io.execute(() -> {
            Browse.refreshPlaces(this);
            main.post(this::placesChanged);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (!resumedOnce) {
            resumedOnce = true;
            return;
        }
        // Back from Wholphin or an app, or a request made: what's on Jellyfin and in Seerr may have changed.
        io.execute(() -> {
            Browse.refreshPlaces(this);
            main.post(() -> {
                placesChanged();
                Screen top = top();
                if (top != null && top.resumed != null) top.resumed.run();
            });
        });
    }

    @Override protected void onDestroy() {
        destroyed = true;
        io.shutdownNow();
        lookups.shutdownNow();
        pictures.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (stack.size() > 1) {
            Screen gone = stack.remove(stack.size() - 1);
            root.removeView(gone.view);
            Screen top = top();
            top.view.setVisibility(View.VISIBLE);
            if (top.focus != null) top.focus.requestFocus();
            return;
        }
        super.onBackPressed();
    }

    private Screen top() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    private void push(Screen s, View focus) {
        Screen was = top();
        if (was != null) {
            was.focus = getCurrentFocus();
            was.view.setVisibility(View.GONE);
        }
        stack.add(s);
        root.addView(s.view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (focus != null) focus.post(focus::requestFocus);
    }

    /** Whether the screen is still the one on top (an answer that arrives late doesn't move the focus elsewhere). */
    private boolean showing(Screen s) {
        return !destroyed && top() == s;
    }

    private void placesChanged() {
        for (Screen s : stack) for (Poster p : s.posters) bindBadge(p);
    }

    // MARK: - Browse: search, the type, the categories, then the rows

    private int type;   // 0 all, 1 movies, 2 shows
    private LinearLayout homeRows;
    private View homeSearch;
    private boolean homeMoved;

    private Screen home() {
        ScrollView scroll = scroller();
        LinearLayout col = column();
        col.setPadding(0, px(52), 0, px(60));
        scroll.addView(col);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(scroll);
        Screen s = new Screen(frame);

        LinearLayout header = hbox();
        header.setPadding(px(MARGIN), 0, px(MARGIN), 0);
        header.addView(label(Ui.tr("Browse"), 56, INK, Ui.displaySemibold));
        header.addView(spacer());
        header.addView(label(Ui.tr("Back returns home"), 20, DIM, Ui.regular));
        col.addView(header);

        LinearLayout controls = hbox();
        controls.setPadding(px(MARGIN), px(28), px(MARGIN), 0);
        View search = searchBox();
        homeSearch = search;
        search.setOnClickListener(v -> openSearch());
        controls.addView(search, new LinearLayout.LayoutParams(px(760), px(68)));
        controls.addView(gap(24));
        controls.addView(segmented(new String[] {Ui.tr("filter|All"), Ui.tr("Movies"), Ui.tr("Shows")}, () -> type, i -> {
            type = i;
            fillHome(s);
        }));
        col.addView(controls);

        HorizontalScrollView chipScroll = hscroll();
        LinearLayout chips = hbox();
        chips.setPadding(px(MARGIN), px(22), px(MARGIN), px(10));
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int c = i;
            String name = i == 0 ? Ui.tr("Trending") : Ui.tr(CATEGORIES[i][0]);
            TextView chip = chip(name);
            chip.setOnClickListener(v -> openCategory(c));
            chips.addView(chip);
            if (i < CATEGORIES.length - 1) chips.addView(gap(12));
        }
        chipScroll.addView(chips);
        col.addView(chipScroll);

        homeRows = column();
        col.addView(homeRows);
        fillHome(s);
        s.resumed = () -> fillRequested(s);
        search.setOnFocusChangeListener(null);
        boxFocus(search);
        search.post(search::requestFocus);
        return s;
    }

    /** The rows for the type picked: Requested (Seerr), then the first categories. */
    private void fillHome(Screen s) {
        homeRows.removeAllViews();
        s.posters.clear();
        if (Seerr.configured()) {
            Row requested = new Row(Ui.tr("Requested"), Ui.tr("From Seerr, with Radarr's and Sonarr's progress"), null);
            requested.box.setTag("requested");
            requested.box.setVisibility(View.GONE);
            homeRows.addView(requested.box);
            fillRequested(s);
        }
        for (int i = 0; i < HOME_ROWS; i++) {
            String path = path(i, type);
            if (path == null) continue;
            final int c = i;
            Row row = new Row(Ui.tr(CATEGORIES[i][0]), i == 0 ? Ui.tr("On your Jellyfin and apps first, then the rest") : null,
                    () -> openCategory(c));
            homeRows.addView(row.box);
            row.loading();
            final int want = type;
            io.execute(() -> {
                try {
                    List<Browse.Item> items = list(c, want, 1);
                    main.post(() -> {
                        if (type != want || destroyed) return;
                        row.fill(s, items, 196, 294, Poster.PLAIN);
                        // The first time: the focus starts on the first trending poster, if nothing was pressed yet.
                        if (c == 0 && !homeMoved && homeSearch.hasFocus() && row.strip.getChildCount() > 0) {
                            homeMoved = true;
                            row.strip.getChildAt(0).requestFocus();
                        }
                    });
                } catch (Exception e) {
                    main.post(() -> row.failed(e));
                }
            });
        }
    }

    /** Requested: your Seerr requests with where each one is, newest first. */
    private void fillRequested(Screen s) {
        if (!Seerr.configured() || homeRows == null) return;
        View box = homeRows.findViewWithTag("requested");
        if (box == null) return;
        io.execute(() -> {
            List<Seerr.Request> list = Seerr.requests(20);
            List<Browse.Item> items = new ArrayList<>();
            List<Seerr.State> states = new ArrayList<>();
            for (Seerr.Request r : list) {
                try {
                    Browse.Item it = Browse.items(new org.json.JSONArray().put(Recs.tmdb("/" + r.kind + "/" + r.tmdbId + "?language=" + Recs.lang())), r.kind, false)
                            .get(0);
                    items.add(it);
                    states.add(r.state);
                } catch (Exception ignored) {   // gone from TMDB, or no poster
                }
            }
            main.post(() -> {
                if (destroyed) return;
                LinearLayout strip = box.findViewWithTag("strip");
                View hadFocus = strip.findFocus();
                int at = hadFocus == null ? -1 : strip.indexOfChild(hadFocus);
                strip.removeAllViews();
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) strip.addView(gap(24));
                    strip.addView(requestCard(items.get(i), states.get(i)));
                }
                box.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
                if (at >= 0 && at < strip.getChildCount()) strip.getChildAt(at).requestFocus();
            });
        });
    }

    private View requestCard(Browse.Item it, Seerr.State st) {
        LinearLayout card = hbox();
        card.setBackground(round(0x12FFFFFF, 18));
        card.setPadding(0, 0, px(20), 0);
        ImageView img = image();
        clip(img, 18);
        card.addView(img, new LinearLayout.LayoutParams(px(100), px(150)));
        load(img, it.poster, "w185", px(100));
        LinearLayout text = column();
        text.setPadding(px(18), 0, 0, 0);
        text.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label(it.title, 22, INK, Ui.semibold);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(name);
        int ink;
        String state;
        float bar;
        if (st.media == Seerr.AVAILABLE) {
            state = Ui.tr("On Jellyfin");
            ink = GREEN;
            bar = 1;
        } else if (st.progress >= 0) {
            state = Ui.trf("Downloading · {n}%", "n", Math.round(st.progress * 100)) + (st.left.isEmpty() ? "" : " · " + st.left);
            ink = BLUE;
            bar = st.progress;
        } else if (st.media == Seerr.PARTLY) {
            state = Ui.tr("Partly on Jellyfin");
            ink = SOFT;
            bar = 0.5f;
        } else if (st.request == Seerr.DECLINED) {
            state = Ui.tr("Declined");
            ink = RED;
            bar = 0;
        } else if (st.request == Seerr.WAITING) {
            state = Ui.tr("Waiting for approval");
            ink = YELLOW;
            bar = 0.06f;
        } else {
            state = Ui.tr("Approved · waiting for the download");
            ink = BLUE;
            bar = 0.06f;
        }
        TextView line = label(state, 18, ink, Ui.medium);
        line.setPadding(0, px(8), 0, px(12));
        line.setMaxLines(1);
        line.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(line);
        text.addView(progress(bar, ink), new LinearLayout.LayoutParams(px(280), px(6)));
        card.addView(text, new LinearLayout.LayoutParams(px(300), ViewGroup.LayoutParams.MATCH_PARENT));
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnClickListener(v -> openTitle(it));
        cardFocus(card, 18, 1.05f);
        return card;
    }

    /** A category's list (a page of it) for a type: All takes movies and shows in turn. */
    private List<Browse.Item> list(int category, int type, int page) throws Exception {
        if (category == 0 && type == 0) return Browse.list("/trending/all/week?page=" + page, null);
        String movies = type == 2 ? null : path(category, 1), shows = type == 1 ? null : path(category, 2);
        List<Browse.Item> a = movies == null ? new ArrayList<>() : Browse.list(paged(movies, page), "movie");
        List<Browse.Item> b = shows == null ? new ArrayList<>() : Browse.list(paged(shows, page), "tv");
        return Browse.interleave(a, b);
    }

    private static String path(int category, int type) {
        String[] c = CATEGORIES[category];
        if (type == 0) return c[1] != null ? c[1] : c[2];
        String p = type == 1 ? c[1] : c[2];
        return p == null ? null : p.replace("REGION", Recs.REGION);
    }

    private static String paged(String path, int page) {
        return path.replace("REGION", Recs.REGION) + (path.contains("?") ? "&" : "?") + "page=" + page;
    }

    // MARK: - A whole list (See all, a category), loading more as you scroll down

    private void openCategory(int category) {
        String name = category == 0 ? Ui.tr("Trending") : Ui.tr(CATEGORIES[category][0]);
        final int want = type;
        openGrid(name, page -> list(category, want, page));
    }

    private interface Pages {
        List<Browse.Item> page(int n) throws Exception;
    }

    private void openGrid(String title, Pages pages) {
        ScrollView scroll = scroller();
        LinearLayout col = column();
        col.setPadding(0, px(52), 0, px(60));
        scroll.addView(col);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(scroll);
        Screen s = new Screen(frame);
        TextView head = label(title, 56, INK, Ui.displaySemibold);
        head.setPadding(px(MARGIN), 0, px(MARGIN), px(24));
        col.addView(head);
        Grid grid = new Grid(s, col);
        TextView wait = label(Ui.tr("Loading…"), 22, DIM, Ui.regular);
        wait.setPadding(px(MARGIN), 0, 0, 0);
        col.addView(wait);
        push(s, null);
        int[] next = {1};
        boolean[] busy = {false}, done = {false};
        Runnable more = new Runnable() {
            @Override public void run() {
                if (busy[0] || done[0] || next[0] > 20) return;
                busy[0] = true;
                int n = next[0]++;
                io.execute(() -> {
                    List<Browse.Item> items;
                    try {
                        items = pages.page(n);
                    } catch (Exception e) {
                        items = null;
                    }
                    List<Browse.Item> got = items;
                    main.post(() -> {
                        busy[0] = false;
                        if (destroyed) return;
                        if (got == null || got.isEmpty()) {
                            done[0] = true;
                            if (grid.count == 0) wait.setText(got == null ? Ui.tr("Couldn't load this list.") : Ui.tr("Nothing here yet."));
                            return;
                        }
                        wait.setVisibility(View.GONE);
                        boolean first = grid.count == 0;
                        grid.add(got, this);
                        if (first && showing(s)) grid.first().requestFocus();
                    });
                });
            }
        };
        more.run();
    }

    /** Posters in rows of COLS with their titles; reaching the last row asks for more. */
    private final class Grid {
        final Screen screen;
        final LinearLayout into;
        LinearLayout line;
        int count;
        final int w = 220, h = 330;

        Grid(Screen s, LinearLayout into) {
            screen = s;
            this.into = into;
        }

        View first() {
            LinearLayout l = (LinearLayout) into.findViewWithTag("grid0");
            return l == null ? into : l.getChildAt(0);
        }

        void clear() {
            for (int i = into.getChildCount() - 1; i >= 0; i--) {
                Object tag = into.getChildAt(i).getTag();
                if (tag instanceof String && ((String) tag).startsWith("grid")) into.removeViewAt(i);
            }
            screen.posters.clear();
            line = null;
            count = 0;
        }

        void add(List<Browse.Item> items, Runnable more) {
            for (Browse.Item it : items) {
                if (count % COLS == 0) {
                    line = hbox();
                    line.setTag("grid" + count / COLS);
                    line.setClipChildren(false);
                    line.setClipToPadding(false);
                    line.setPadding(px(MARGIN), px(14), px(MARGIN), px(14));
                    into.addView(line, into.getChildCount() - (into.getChildAt(into.getChildCount() - 1) instanceof TextView ? 1 : 0));
                }
                if (count % COLS > 0) line.addView(gap(26));
                Poster p = new Poster(it, w, h, "person".equals(it.kind) ? Poster.PERSON : Poster.TITLED);
                screen.posters.add(p);
                bindBadge(p);
                final int index = count;
                p.onFocus = () -> {
                    if (more != null && index >= count - COLS) more.run();
                };
                line.addView(p);
                count++;
            }
        }
    }

    // MARK: - Search

    private void openSearch() {
        ScrollView scroll = scroller();
        LinearLayout col = column();
        col.setPadding(0, px(52), 0, px(60));
        scroll.addView(col);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(scroll);
        Screen s = new Screen(frame);

        LinearLayout header = hbox();
        header.setPadding(px(MARGIN), 0, px(MARGIN), 0);
        header.addView(label(Ui.tr("Search"), 56, INK, Ui.displaySemibold));
        header.addView(gap(24));
        header.addView(label(Ui.tr("Movies, shows and people"), 20, DIM, Ui.regular));
        col.addView(header);

        LinearLayout controls = hbox();
        controls.setPadding(px(MARGIN), px(28), px(MARGIN), px(18));
        LinearLayout box = hbox();
        box.setPadding(px(26), 0, px(26), 0);
        ImageView glass = new ImageView(this);
        glass.setImageDrawable(new Glyph(Glyph.SEARCH, SOFT));
        box.addView(glass, new LinearLayout.LayoutParams(px(30), px(30)));
        box.addView(gap(16));
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(Ui.tr("Search movies, shows and people"));
        field.setHintTextColor(FAINT);
        field.setTextColor(INK);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28 * k);
        field.setTypeface(Ui.medium);
        field.setBackground(null);
        field.setPadding(0, 0, 0, 0);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        TextView count = label("", 20, DIM, Ui.regular);
        box.addView(count);
        box.setBackground(round(0x14FFFFFF, 999, 0x33FFFFFF));
        field.setOnFocusChangeListener((v, has) -> box.setBackground(has ? round(0x24FFFFFF, 999, INK) : round(0x14FFFFFF, 999, 0x33FFFFFF)));
        controls.addView(box, new LinearLayout.LayoutParams(px(900), px(72)));
        controls.addView(gap(24));
        int[] kind = {0};
        LinearLayout results = column();
        Grid grid = new Grid(s, results);
        Runnable[] run = new Runnable[1];
        controls.addView(segmented(new String[] {Ui.tr("filter|All"), Ui.tr("Movies"), Ui.tr("Shows")}, () -> kind[0], i -> {
            kind[0] = i;
            run[0].run();
        }));
        col.addView(controls);
        col.addView(results);
        TextView empty = label(Ui.tr("Type a title or a name."), 22, DIM, Ui.regular);
        empty.setPadding(px(MARGIN), px(10), 0, 0);
        results.addView(empty);

        int[] asked = {0};
        run[0] = () -> {
            String q = field.getText().toString().trim();
            int ask = ++asked[0];
            if (q.length() < 2) {
                grid.clear();
                count.setText("");
                empty.setText(Ui.tr("Type a title or a name."));
                empty.setVisibility(View.VISIBLE);
                return;
            }
            String want = TYPES[kind[0]];
            io.execute(() -> {
                List<Browse.Item> found;
                try {
                    found = Browse.search(q, want);
                } catch (Exception e) {
                    found = null;
                }
                List<Browse.Item> got = found;
                main.post(() -> {
                    if (ask != asked[0] || destroyed) return;   // more was typed meanwhile
                    grid.clear();
                    if (got == null || got.isEmpty()) {
                        count.setText("");
                        empty.setText(got == null ? Ui.tr("Couldn't search right now.") : Ui.tr("Nothing found."));
                        empty.setVisibility(View.VISIBLE);
                        return;
                    }
                    empty.setVisibility(View.GONE);
                    count.setText(Ui.plural("plural|one:{n} result|other:{n} results", got.size()));
                    grid.add(got, null);
                });
            });
        };
        Runnable debounced = () -> run[0].run();
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable e) {
                main.removeCallbacks(debounced);
                main.postDelayed(debounced, 400);
            }
        });
        field.setOnEditorActionListener((v, action, event) -> {
            hideKeyboard(field);
            main.removeCallbacks(debounced);
            run[0].run();
            if (grid.count > 0) grid.first().requestFocus();
            return true;
        });
        push(s, field);
        field.postDelayed(() -> {
            InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null && field.hasFocus()) im.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }, 250);
    }

    private void hideKeyboard(View v) {
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    // MARK: - A title's page

    private void openTitle(Browse.Item it) {
        if ("person".equals(it.kind)) {
            openPerson(it.id, it.title, it.poster);
            return;
        }
        TitlePage page = new TitlePage(it);
        push(page.screen, null);
        page.fetch();
    }

    private final class TitlePage {
        final Browse.Item item;
        final Screen screen;
        final LinearLayout col, middle, aside, actions, status, ratings, below;
        final TextView name, by, meta, tagline, overview, posterTag;
        final ImageView poster, backdrop;
        Browse.Title t;
        String[] jellyfin;     // its item on Jellyfin (Jellyfin.find), or null
        Seerr.State seerr;
        boolean asking;

        TitlePage(Browse.Item it) {
            item = it;
            ScrollView scroll = scroller();
            col = column();
            scroll.addView(col);
            FrameLayout frame = new FrameLayout(this0());
            frame.addView(scroll);
            frame.setTag(Browse.id(it.kind, it.id));   // a person's page opened from here starts on this title
            screen = new Screen(frame);

            FrameLayout hero = new FrameLayout(this0());
            hero.setClipChildren(false);
            backdrop = image();
            backdrop.setAlpha(0.55f);
            hero.addView(backdrop, at(0, 0, ViewGroup.LayoutParams.MATCH_PARENT, 740));
            View shade = new View(this0());
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xEB0B0C10, 0xB80B0C10, 0x8C0B0C10}));
            hero.addView(shade, at(0, 0, ViewGroup.LayoutParams.MATCH_PARENT, 740));
            View fade = new View(this0());
            fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {BG, 0x000B0C10}));
            hero.addView(fade, at(0, 400, ViewGroup.LayoutParams.MATCH_PARENT, 340));
            hero.addView(label("‹ " + Ui.tr("Browse"), 20, DIM, Ui.regular), at(MARGIN, 52, -2, -2));

            poster = image();
            clip(poster, 22);
            poster.setElevation(px(20));
            hero.addView(poster, at(MARGIN, 112, 340, 510));
            load(poster, it.poster, "w500", px(340));
            posterTag = label("", 15, INK, Ui.semibold);
            posterTag.setLetterSpacing(0.06f);
            posterTag.setGravity(Gravity.CENTER);
            posterTag.setPadding(px(16), 0, px(16), 0);
            posterTag.setElevation(px(24));
            posterTag.setVisibility(View.GONE);
            FrameLayout tagBox = new FrameLayout(this0());
            tagBox.addView(posterTag, new FrameLayout.LayoutParams(-2, px(36), Gravity.CENTER_HORIZONTAL));
            hero.addView(tagBox, at(MARGIN, 604, 340, 40));

            middle = column();
            hero.addView(middle, at(480, 104, 820, -2));
            name = label(it.title, it.title.length() > 22 ? 64 : 88, INK, Ui.displaySemibold);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setLineSpacing(0, 0.95f);
            middle.addView(name);
            by = label("", 22, 0xFFDDE0E4, Ui.regular);
            by.setPadding(px(10), px(4), px(10), px(4));
            LinearLayout.LayoutParams byAt = new LinearLayout.LayoutParams(-2, -2);
            byAt.setMargins(-px(10), px(12), 0, 0);
            middle.addView(by, byAt);
            meta = label(it.year, 22, DIM, Ui.regular);
            meta.setPadding(0, px(6), 0, 0);
            middle.addView(meta);
            ratings = hbox();
            ratings.setPadding(0, px(20), 0, 0);
            middle.addView(ratings);
            tagline = label("", 22, SOFT, Typeface.create(Ui.regular, Typeface.ITALIC));
            tagline.setPadding(0, px(24), 0, 0);
            tagline.setVisibility(View.GONE);
            middle.addView(tagline);
            overview = label(it.overview, 24, 0xFFE4E6EA, Ui.regular);
            overview.setLineSpacing(0, 1.4f);
            overview.setMaxLines(4);
            overview.setEllipsize(TextUtils.TruncateAt.END);
            overview.setPadding(0, px(12), 0, 0);
            middle.addView(overview);
            actions = hbox();
            actions.setClipChildren(false);
            actions.setClipToPadding(false);
            actions.setPadding(0, px(32), 0, 0);
            middle.addView(actions);
            status = column();
            status.setVisibility(View.GONE);
            LinearLayout.LayoutParams statusAt = new LinearLayout.LayoutParams(px(660), -2);
            statusAt.topMargin = px(22);
            middle.addView(status, statusAt);

            aside = column();
            hero.addView(aside, at(1380, 112, 460, -2));
            col.addView(hero, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2));
            below = column();
            below.setPadding(0, 0, 0, px(60));
            col.addView(below);
            load(backdrop, it.backdrop, "w1280", px(1920));
            TextView wait = label(Ui.tr("Loading…"), 22, DIM, Ui.regular);
            actions.addView(wait);
            screen.resumed = this::refreshPlace;
        }

        void fetch() {
            io.execute(() -> {
                try {
                    Browse.Title got = Browse.title(this0(), item.kind, item.id);
                    Jellyfin.get(this0()).index();
                    String[] jf = Jellyfin.get(this0()).find(got.kind, got.id, got.imdbId);
                    Seerr.State st = Seerr.configured() ? Seerr.state(got.kind, got.id) : null;
                    main.post(() -> {
                        if (destroyed) return;
                        t = got;
                        jellyfin = jf;
                        seerr = st;
                        show();
                    });
                    Recs.Pick rated = new Recs.Pick();
                    rated.imdbId = got.imdbId;
                    recs.ratings(rated);
                    main.post(() -> {
                        got.imdbRating = rated.imdbRating;
                        got.rtRating = rated.rtRating;
                        if (!destroyed && t == got) showRatings();
                    });
                } catch (Exception e) {
                    main.post(() -> {
                        actions.removeAllViews();
                        actions.addView(label(Ui.trf("Couldn't load this title: {error}", "error", String.valueOf(e.getMessage())), 22, RED, Ui.regular));
                    });
                }
            });
        }

        void refreshPlace() {
            if (t == null) return;
            io.execute(() -> {
                String[] jf = Jellyfin.get(this0()).find(t.kind, t.id, t.imdbId);
                Seerr.State st = Seerr.configured() ? Seerr.state(t.kind, t.id) : null;
                main.post(() -> {
                    if (destroyed) return;
                    jellyfin = jf;
                    if (st != null) seerr = st;
                    showPlace(false);
                });
            });
        }

        void show() {
            name.setText(t.title);
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, (t.title.length() > 22 ? 64 : 88) * k);
            if (!t.director.isEmpty()) {
                by.setText(("tv".equals(t.kind) ? Ui.tr("Created by") : Ui.tr("Directed by")) + " " + t.director);
                if (t.directorId > 0) {
                    by.setFocusable(true);
                    by.setClickable(true);
                    by.setOnClickListener(v -> openPerson(t.directorId, t.director, null));
                    by.setOnFocusChangeListener((v, has) -> {
                        by.setBackground(has ? round(INK, 10) : null);
                        by.setTextColor(has ? DARK : 0xFFDDE0E4);
                    });
                }
            } else {
                by.setVisibility(View.GONE);
            }
            List<String> m = new ArrayList<>();
            m.add(t.year);
            m.add(t.runtime);
            m.add(t.cert);
            m.add(t.genres);
            meta.setText(Browse.join(m, " · "));
            if (!t.tagline.isEmpty()) {
                tagline.setText(t.tagline);
                tagline.setVisibility(View.VISIBLE);
            }
            overview.setText(t.overview);
            if (item.poster == null && t.poster != null) load(poster, t.poster, "w500", px(340));
            if (item.backdrop == null && t.backdrop != null) load(backdrop, t.backdrop, "w1280", px(1920));
            showRatings();
            showPlace(true);
            showDetails();
            showRows();
        }

        void showRatings() {
            ratings.removeAllViews();
            if (t.rating > 0) ratings.addView(rating("TMDB", Math.round(t.rating * 10) + "%"));
            if (t.imdbRating != null) ratings.addView(rating("IMDb", t.imdbRating));
            if (t.rtRating != null) ratings.addView(rating("RT", t.rtRating));
            ratings.setVisibility(ratings.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        }

        /** The buttons, the poster's tag, the request's progress and Where to watch: they follow where the title is. */
        void showPlace(boolean focus) {
            View had = getCurrentFocus();
            boolean focusHere = focus || (had != null && (isChild(actions, had) || isChild(aside, had)));
            actions.removeAllViews();
            status.removeAllViews();
            status.setVisibility(View.GONE);
            String[] jf = jellyfin;
            if (jf == null && seerr != null && seerr.jellyfinId != null && seerr.media == Seerr.AVAILABLE) {
                jf = new String[] {seerr.jellyfinId, "tv".equals(t.kind) ? "Series" : "Movie", "0", "", "0"};
            }
            final String[] onJf = jf;
            Browse.Where app = null;
            for (Browse.Where w : t.where) if (w.installed) {
                app = w;
                break;
            }
            View primary = null;
            if (onJf != null) {
                long at = parse(onJf[2]), length = parse(onJf.length > 4 ? onJf[4] : "0");
                boolean started = at > 60_000 && onJf[3].isEmpty() && "Movie".equals(onJf[1]);
                String sub = started && length > at ? Ui.trf("{time} left", "time", duration(length - at)) : "tv".equals(t.kind)
                        ? Ui.tr("The next episode, in Wholphin") : Ui.tr("Plays in Wholphin");
                primary = bigButton(Glyph.PLAY, started ? Ui.tr("Continue on Jellyfin") : Ui.tr("Play on Jellyfin"), sub,
                        started && length > 0 ? at / (float) length : -1, true);
                primary.setOnClickListener(v -> playJellyfin(onJf, Jellyfin.RESUME));
                actions.addView(primary);
                if (started) {
                    View again = roundButton(Glyph.RESTART, Ui.tr("Play from start"));
                    again.setOnClickListener(v -> playJellyfin(onJf, Jellyfin.FROM_START));
                    actions.addView(gap(16));
                    actions.addView(again);
                }
                tag(Ui.tr("ON YOUR JELLYFIN"), true);
            } else {
                boolean requested = seerr != null && (seerr.requested() || seerr.media == Seerr.PARTLY);
                if (app != null) {
                    final Browse.Where w = app;
                    primary = bigButton(Glyph.PLAY, Ui.trf("Watch on {app}", "app", w.name),
                            w.free ? Ui.tr("Free") : Ui.tr("With your subscription"), -1, true);
                    primary.setOnClickListener(v -> openApp(w));
                    actions.addView(primary);
                }
                if (Seerr.configured() && !requested) {
                    View ask = app == null
                            ? bigButton(Glyph.PLUS, Ui.tr("Request on Jellyfin"), Ui.tr("Seerr downloads it; it shows up on Jellyfin"), -1, true)
                            : pillButton(Glyph.PLUS, Ui.tr("Request"));
                    ask.setOnClickListener(v -> request());
                    if (app != null) actions.addView(gap(16));
                    actions.addView(ask);
                    if (primary == null) primary = ask;
                }
                if (requested) showProgress();
                if (requested) tag(Ui.tr("REQUESTED"), false);
                else if (app != null) tag(Ui.trf("ON {app}", "app", app.name.toUpperCase(Locale.ROOT)), false);
                else posterTag.setVisibility(View.GONE);
                if (primary == null && !requested) {
                    String country = new Locale("", Recs.REGION).getDisplayCountry(Ui.locale());
                    TextView none = label(t.where.isEmpty() ? Ui.trf("Not streaming in {country}", "country", country)
                            : Ui.tr("Not on this TV's apps"), 22, DIM, Ui.regular);
                    actions.addView(none);
                }
            }
            if (t.trailer != null) {
                View trailer = roundButton(Glyph.TRAILER, Ui.tr("Trailer"));
                trailer.setOnClickListener(v -> openTrailer(t.trailer));
                if (actions.getChildCount() > 0) actions.addView(gap(16));
                actions.addView(trailer);
                if (primary == null) primary = trailer;
            }
            showWhere(onJf);
            if (focusHere && primary != null && showing(screen)) primary.requestFocus();
        }

        void tag(String text, boolean jellyfinTag) {
            posterTag.setText(text);
            posterTag.setBackground(jellyfinTag ? gradient(JF_A, JF_B, 999) : round(0xE6202430, 999));
            posterTag.setVisibility(View.VISIBLE);
        }

        /** The request's progress: Seerr's state, then Radarr's or Sonarr's download. */
        void showProgress() {
            Seerr.State st = seerr;
            LinearLayout card = column();
            card.setBackground(round(0x147CC4FF, 20, 0x667CC4FF));
            card.setPadding(px(22), px(18), px(22), px(18));
            String head, sub;
            if (st.progress >= 0) {
                head = Ui.trf("Downloading for Jellyfin · {n}%", "n", Math.round(st.progress * 100));
                sub = Browse.join(java.util.Arrays.asList(st.episode, st.left, "tv".equals(t.kind) ? "Sonarr" : "Radarr"), " · ");
            } else if (st.request == Seerr.WAITING) {
                head = Ui.tr("Requested · waiting for approval");
                sub = Ui.tr("Seerr asks before it downloads");
            } else if (st.media == Seerr.PARTLY) {
                head = Ui.tr("Partly on Jellyfin");
                sub = Ui.tr("The rest is on its way");
            } else {
                head = Ui.tr("Requested · approved");
                sub = Ui.tr("Waiting for the download to start");
            }
            card.addView(label(head, 22, INK, Ui.semibold));
            TextView s2 = label(sub, 18, DIM, Ui.regular);
            s2.setPadding(0, px(4), 0, px(12));
            card.addView(s2);
            card.addView(progress(st.progress >= 0 ? st.progress : 0.04f, BLUE), new LinearLayout.LayoutParams(-1, px(8)));
            LinearLayout steps = hbox();
            steps.setPadding(0, px(12), 0, 0);
            boolean approved = st.request == Seerr.APPROVED || st.media == Seerr.PROCESSING || st.progress >= 0;
            steps.addView(step("✓ " + Ui.tr("Requested"), SOFT));
            steps.addView(step((approved ? "✓ " : "○ ") + Ui.tr("Approved"), approved ? SOFT : FAINT));
            steps.addView(step((st.progress >= 0 ? "● " : "○ ") + Ui.tr("Downloading"), st.progress >= 0 ? BLUE : FAINT));
            steps.addView(step("○ " + Ui.tr("On Jellyfin"), FAINT));
            card.addView(steps);
            status.addView(card);
            status.setVisibility(View.VISIBLE);
        }

        private TextView step(String text, int ink) {
            TextView v = label(text, 18, ink, Ui.medium);
            v.setPadding(0, 0, px(26), 0);
            return v;
        }

        void request() {
            if (asking) return;
            asking = true;
            Toast.makeText(this0(), Ui.tr("Asking Seerr…"), Toast.LENGTH_SHORT).show();
            io.execute(() -> {
                String why = Seerr.ask(t.kind, t.id);
                Seerr.State st = why == null ? Seerr.state(t.kind, t.id) : null;
                main.post(() -> {
                    asking = false;
                    if (destroyed) return;
                    if (why != null) {
                        Toast.makeText(this0(), why, Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(this0(), Ui.tr("Requested. It shows up on Jellyfin once it's downloaded."), Toast.LENGTH_LONG).show();
                    seerr = st != null ? st : requestedState();
                    Browse.forget(t.kind, t.id);
                    showPlace(true);
                });
            });
        }

        private Seerr.State requestedState() {
            Seerr.State s = new Seerr.State();
            s.media = Seerr.PENDING;
            s.request = Seerr.WAITING;
            return s;
        }

        /** Where to watch: Jellyfin first, then the TV's apps, then the services it has no app for (dimmed). */
        void showWhere(String[] onJf) {
            aside.removeAllViews();
            aside.addView(label(Ui.tr("Where to watch"), 28, INK, Ui.semibold));
            List<View> tiles = new ArrayList<>();
            if (onJf != null) {
                View tile = whereTile(null, "Jellyfin", true, true);
                tile.setOnClickListener(v -> playJellyfin(onJf, Jellyfin.RESUME));
                tiles.add(tile);
            }
            for (Browse.Where w : t.where) {
                if (tiles.size() >= 6) break;
                View tile = whereTile(w.logo, w.installed ? (w.free ? Ui.tr("Free") : Ui.tr("Stream")) : Ui.tr("No app"), w.installed, false);
                if (w.installed) tile.setOnClickListener(v -> openApp(w));
                tiles.add(tile);
            }
            if (tiles.isEmpty()) {
                String country = new Locale("", Recs.REGION).getDisplayCountry(Ui.locale());
                TextView none = label(Ui.trf("Not streaming in {country}", "country", country), 20, DIM, Ui.regular);
                none.setPadding(0, px(14), 0, 0);
                aside.addView(none);
            }
            LinearLayout line = null;
            for (int i = 0; i < tiles.size(); i++) {
                if (i % 3 == 0) {
                    line = hbox();
                    line.setClipChildren(false);
                    line.setPadding(0, px(14), 0, 0);
                    aside.addView(line);
                }
                if (i % 3 > 0) line.addView(gap(14));
                line.addView(tiles.get(i), new LinearLayout.LayoutParams(px(144), px(132)));
            }
            details = column();
            aside.addView(details);
            showDetails();
        }

        LinearLayout details;

        void showDetails() {
            if (details == null || t == null) return;
            details.removeAllViews();
            TextView head = label(Ui.tr("Details"), 28, INK, Ui.semibold);
            head.setPadding(0, px(30), 0, px(14));
            details.addView(head);
            LinearLayout card = column();
            card.setBackground(round(0x12FFFFFF, 20));
            card.setPadding(px(26), px(14), px(26), px(14));
            boolean tv = "tv".equals(t.kind);
            fact(card, tv ? Ui.tr("Created by") : Ui.tr("Director"), t.director);
            fact(card, Ui.tr("Writer"), t.writer);
            fact(card, Ui.tr("Genres"), t.genres);
            fact(card, Ui.tr("Rated"), t.cert);
            fact(card, tv ? Ui.tr("Seasons") : Ui.tr("Runtime"), t.runtime);
            fact(card, tv ? Ui.tr("First aired") : Ui.tr("Released"), Browse.date(t.released));
            if (tv) fact(card, Ui.tr("Status"), t.status);
            if (card.getChildCount() > 0) details.addView(card);
        }

        private void fact(LinearLayout card, String name, String value) {
            if (value == null || value.isEmpty()) return;
            LinearLayout line = hbox();
            line.setPadding(0, px(8), 0, px(8));
            TextView n = label(name, 20, FAINT, Ui.regular);
            line.addView(n, new LinearLayout.LayoutParams(px(140), -2));
            TextView v = label(value, 20, INK, Ui.medium);
            v.setMaxLines(2);
            v.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(line);
        }

        /** Cast, the franchise in release order, more like this. */
        void showRows() {
            below.removeAllViews();
            screen.posters.clear();
            if (!t.cast.isEmpty()) {
                Row cast = new Row(Ui.tr("Cast"), null, null);
                below.addView(cast.box);
                cast.fill(screen, t.cast, 168, 220, Poster.CAST);
            }
            if (!t.franchise.isEmpty()) {
                Row f = new Row(t.collection, Ui.tr("The whole franchise, in release order"), null);
                below.addView(f.box);
                f.fill(screen, t.franchise, 180, 270, Poster.TITLED);
            }
            if (!t.related.isEmpty()) {
                Row r = new Row(Ui.tr("More like this"), Ui.tr("What people who liked it watched too"), () -> openGrid(Ui.tr("More like this"),
                        n -> n == 1 ? t.related : new ArrayList<Browse.Item>()));
                below.addView(r.box);
                r.fill(screen, t.related.subList(0, Math.min(20, t.related.size())), 180, 270, Poster.PLAIN);
            }
        }

        void playJellyfin(String[] onJf, int how) {
            Jellyfin.get(this0()).play(BrowseActivity.this, onJf, how,
                    () -> Toast.makeText(this0(), Ui.tr("Install Wholphin to play your Jellyfin titles."), Toast.LENGTH_LONG).show());
        }

        void openApp(Browse.Where w) {
            Recs.Pick p = new Recs.Pick();
            p.kind = t.kind;
            p.id = (int) t.id;
            p.app = w.key;
            p.pkg = w.pkg;
            p.title = t.title;
            io.execute(() -> {
                String link = recs.link(p);
                main.post(() -> {
                    boolean opened = HomeApps.open(this0(), HomeApps.app(w.pkg, w.name, link), (Bundle) null);
                    if (!opened) Toast.makeText(this0(), Ui.trf("Couldn't open {app}.", "app", w.name), Toast.LENGTH_SHORT).show();
                });
            });
        }
    }

    private BrowseActivity this0() {
        return this;
    }

    private static boolean isChild(View parent, View v) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) if (p == parent) return true;
        return false;
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String duration(long ms) {
        long min = Math.max(1, ms / 60_000);
        return min < 60 ? min + "m" : (min / 60) + "h " + (min % 60) + "m";
    }

    private void openTrailer(String key) {
        Intent tv = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key)).setPackage("com.google.android.youtube.tv");
        try {
            startActivity(tv);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key)));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, Ui.tr("Install YouTube to watch the trailer."), Toast.LENGTH_SHORT).show();
            }
        }
    }

    // MARK: - A person's page

    private void openPerson(long id, String name, String photo) {
        FrameLayout frame = new FrameLayout(this);
        Screen s = new Screen(frame);
        ImageView glow = image();
        glow.setAlpha(0.35f);
        if (Build.VERSION.SDK_INT >= 31) {
            glow.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(90 * k, 90 * k, android.graphics.Shader.TileMode.CLAMP));
        } else {
            glow.setAlpha(0.12f);
        }
        frame.addView(glow, at(-200, -300, 1400, 1200));
        View veil = new View(this);
        veil.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x4D0B0C10, 0xD90B0C10, BG}));
        frame.addView(veil, new FrameLayout.LayoutParams(-1, -1));
        ScrollView scroll = scroller();
        frame.addView(scroll);
        FrameLayout page = new FrameLayout(this);
        page.setClipChildren(false);
        scroll.addView(page);
        page.addView(label("‹ " + Ui.tr("Back"), 20, DIM, Ui.regular), at(MARGIN, 52, -2, -2));
        ImageView pic = image();
        clip(pic, 22);
        page.addView(pic, at(MARGIN, 112, 340, 510));
        load(pic, photo, "h632", px(340));
        load(glow, photo, "w185", px(185));

        LinearLayout info = column();
        page.addView(info, at(480, 104, 1360, -2));
        TextView title = label(name, name.length() > 22 ? 64 : 88, INK, Ui.displaySemibold);
        title.setMaxLines(2);
        info.addView(title);
        TextView dept = label("", 22, DIM, Ui.regular);
        dept.setPadding(0, px(12), 0, 0);
        info.addView(dept);
        TextView bio = label("", 23, 0xFFE4E6EA, Ui.regular);
        bio.setLineSpacing(0, 1.45f);
        bio.setMaxLines(5);
        bio.setEllipsize(TextUtils.TruncateAt.END);
        bio.setPadding(0, px(24), 0, 0);
        info.addView(bio);
        LinearLayout more = hbox();
        more.setPadding(0, px(14), 0, 0);
        info.addView(more);
        LinearLayout facts = hbox();
        facts.setPadding(0, px(34), 0, 0);
        info.addView(facts);

        LinearLayout works = column();
        page.addView(works, at(0, 690, ViewGroup.LayoutParams.MATCH_PARENT, -2));
        TextView wait = label(Ui.tr("Loading…"), 22, DIM, Ui.regular);
        wait.setPadding(px(MARGIN), 0, 0, 0);
        works.addView(wait);
        View spacerBottom = new View(this);
        page.addView(spacerBottom, at(0, 1100, 10, 10));
        push(s, null);
        scroll.setFocusable(true);
        scroll.requestFocus();

        io.execute(() -> {
            Browse.Person p;
            try {
                p = Browse.person(id);
            } catch (Exception e) {
                main.post(() -> wait.setText(Ui.trf("Couldn't load this page: {error}", "error", String.valueOf(e.getMessage()))));
                return;
            }
            Browse.Person got = p;
            main.post(() -> {
                if (destroyed) return;
                title.setText(got.name);
                if (photo == null && got.photo != null) {
                    load(pic, got.photo, "h632", px(340));
                    load(glow, got.photo, "w185", px(185));
                }
                String role = got.dept.isEmpty() ? "" : Ui.tr(got.dept.equals("Acting") ? "Acting" : got.dept.equals("Directing") ? "Directing"
                        : got.dept.equals("Writing") ? "Writing" : got.dept);
                dept.setText(Browse.join(java.util.Arrays.asList(role, Ui.plural("plural|one:{n} known credit|other:{n} known credits", got.credits)), " · "));
                bio.setText(got.bio.isEmpty() ? Ui.tr("No biography on TMDB yet.") : got.bio);
                if (got.bio.length() > 420) {
                    View read = pillButton(-1, Ui.tr("Read more"));
                    read.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle(got.name).setMessage(got.bio).setPositiveButton(Ui.tr("Close"), null).show());
                    more.addView(read);
                }
                fact(facts, Ui.tr("Birthday"), Browse.date(got.birthday));
                if (!got.deathday.isEmpty()) fact(facts, Ui.tr("Died"), Browse.date(got.deathday));
                fact(facts, Ui.tr("Age"), Browse.age(got.birthday, got.deathday));
                fact(facts, Ui.tr("Born in"), shortPlace(got.place));
                fact(facts, Ui.tr("Known for"), got.knownFor);
                showWorks(s, works, got);
            });
        });
    }

    /** "Barnes, London, England, UK" → "Barnes, London, England": enough for one line. */
    private static String shortPlace(String place) {
        String[] parts = place.split(",\\s*");
        if (parts.length <= 3) return place;
        return parts[0] + ", " + parts[1] + ", " + parts[2];
    }

    private void fact(LinearLayout facts, String name, String value) {
        if (value == null || value.isEmpty()) return;
        if (facts.getChildCount() > 0) {
            View line = new View(this);
            line.setBackgroundColor(0x24FFFFFF);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(1), ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMargins(px(30), 0, px(30), 0);
            facts.addView(line, lp);
        }
        LinearLayout box = column();
        box.addView(label(name, 18, FAINT, Ui.regular));
        TextView v = label(value, 22, INK, Ui.medium);
        v.setMaxLines(1);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setPadding(0, px(6), 0, 0);
        box.addView(v);
        facts.addView(box, new LinearLayout.LayoutParams(-2, -2));
    }

    private int workType;

    private void showWorks(Screen s, LinearLayout works, Browse.Person p) {
        works.removeAllViews();
        workType = 0;
        Row row = new Row(Ui.tr("Movies and shows"), null, () -> openGrid(p.name, n -> n == 1 ? filtered(p, workType) : new ArrayList<Browse.Item>()));
        LinearLayout header = (LinearLayout) row.box.getChildAt(0);
        header.addView(segmented(new String[] {Ui.tr("filter|All"), Ui.tr("Movies"), Ui.tr("Shows")}, () -> workType, i -> {
            workType = i;
            row.fill(s, filtered(p, i), 196, 294, Poster.ROLE);
        }), 1);
        header.addView(gap(20), 1);
        TextView order = label(Ui.tr("Newest first"), 19, FAINT, Ui.regular);
        order.setPadding(px(18), 0, 0, 0);
        header.addView(order, 3);
        works.addView(row.box);
        List<Browse.Item> all = filtered(p, 0);
        if (all.isEmpty()) {
            row.empty(Ui.tr("Nothing on TMDB yet."));
            return;
        }
        row.fill(s, all, 196, 294, Poster.ROLE);
        // The focus starts on the title you came from, else the newest.
        Screen under = stack.size() > 1 ? stack.get(stack.size() - 2) : null;
        View target = row.strip.getChildAt(0);
        for (int i = 0; i < row.strip.getChildCount(); i++) {
            View c = row.strip.getChildAt(i);
            if (c instanceof Poster && under != null && under.view.getTag() instanceof String && ((Poster) c).item != null
                    && under.view.getTag().equals(Browse.id(((Poster) c).item.kind, ((Poster) c).item.id))) {
                target = c;
            }
        }
        if (showing(s) && target != null) target.requestFocus();
    }

    private static List<Browse.Item> filtered(Browse.Person p, int type) {
        List<Browse.Item> out = new ArrayList<>();
        for (Browse.Item it : p.works) if (type == 0 || it.kind.equals(TYPES[type])) out.add(it);
        return out;
    }

    // MARK: - A row: its heading (and See all), a strip of posters and the focused one's details under it

    private final class Row {
        final LinearLayout box, strip;
        final TextView caption;

        Row(String title, String sub, Runnable seeAll) {
            box = column();
            box.setPadding(0, px(30), 0, 0);
            LinearLayout header = hbox();
            header.setPadding(px(MARGIN), 0, px(MARGIN), 0);
            header.addView(label(title, 28, INK, Ui.semibold));
            if (sub != null) {
                TextView s = label(sub, 20, DIM, Ui.regular);
                s.setPadding(px(18), 0, 0, 0);
                header.addView(s);
            }
            header.addView(spacer());
            if (seeAll != null) {
                TextView all = chip(Ui.tr("See all ›"));
                all.setOnClickListener(v -> seeAll.run());
                header.addView(all);
            }
            box.addView(header, new LinearLayout.LayoutParams(-1, px(48)));
            HorizontalScrollView scroll = hscroll();
            strip = hbox();
            strip.setTag("strip");
            strip.setClipChildren(false);
            strip.setClipToPadding(false);
            strip.setPadding(px(MARGIN), px(18), px(MARGIN), px(18));
            strip.setGravity(Gravity.TOP);
            scroll.addView(strip);
            box.addView(scroll);
            caption = label("", 21, SOFT, Ui.regular);
            caption.setPadding(px(MARGIN), 0, px(MARGIN), 0);
            caption.setMaxLines(1);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setVisibility(View.GONE);
            box.addView(caption);
        }

        void loading() {
            strip.removeAllViews();
            for (int i = 0; i < 8; i++) {
                View ghost = new View(BrowseActivity.this);
                ghost.setBackground(round(0x0FFFFFFF, 18));
                if (i > 0) strip.addView(gap(22));
                strip.addView(ghost, new LinearLayout.LayoutParams(px(196), px(294)));
            }
        }

        void failed(Exception e) {
            empty(Ui.trf("Couldn't load: {error}", "error", String.valueOf(e.getMessage())));
        }

        void empty(String text) {
            strip.removeAllViews();
            strip.addView(label(text, 20, DIM, Ui.regular));
        }

        void fill(Screen s, List<Browse.Item> items, int w, int h, int mode) {
            View had = strip.findFocus();
            for (int i = 0; i < strip.getChildCount(); i++) s.posters.remove(strip.getChildAt(i));
            strip.removeAllViews();
            boolean poster = mode == Poster.PLAIN;
            for (int i = 0; i < items.size(); i++) {
                Browse.Item it = items.get(i);
                Poster p = new Poster(it, w, h, mode);
                if (poster) p.onFocus = () -> caption(p);
                s.posters.add(p);
                bindBadge(p);
                if (i > 0) strip.addView(gap(mode == Poster.CAST ? 22 : 22));
                strip.addView(p);
            }
            if (had != null && strip.getChildCount() > 0) strip.getChildAt(0).requestFocus();
            caption.setVisibility(poster && !items.isEmpty() ? View.INVISIBLE : View.GONE);
        }

        void caption(Poster p) {
            Browse.Item it = p.item;
            List<String> parts = new ArrayList<>();
            parts.add(it.title);
            parts.add("tv".equals(it.kind) ? Ui.tr("Show") : Ui.tr("Movie"));
            parts.add(it.year);
            if (it.rating > 0) parts.add("TMDB " + String.format(Locale.ROOT, "%.1f", it.rating));
            Browse.Badge b = p.badgeInfo;
            if (b != null) {
                if (b.type == Browse.Badge.JELLYFIN) parts.add(Ui.tr("On your Jellyfin"));
                else if (b.type == Browse.Badge.SERVICE) parts.add(Ui.trf("On {app}", "app", b.label));
                else if (b.type == Browse.Badge.REQUESTED || b.type == Browse.Badge.DOWNLOADING) parts.add(Ui.tr("Requested"));
                else if (b.type == Browse.Badge.REQUEST) parts.add(Ui.tr("Not on your apps: you can request it"));
            }
            caption.setText(Browse.join(parts, "  ·  "));
            caption.setVisibility(View.VISIBLE);
        }
    }

    // MARK: - A poster

    private final class Poster extends FrameLayout {
        static final int PLAIN = 0, TITLED = 1, ROLE = 2, CAST = 3, PERSON = 4;
        final Browse.Item item;
        final int mode;
        final TextView badge;
        Browse.Badge badgeInfo;
        Runnable onFocus;

        Poster(Browse.Item it, int w, int h, int mode) {
            super(BrowseActivity.this);
            item = it;
            this.mode = mode;
            boolean cast = mode == CAST;
            int ph = mode == PERSON ? w : h;   // a person in a grid: a round photo, the name under it
            setLayoutParams(new LinearLayout.LayoutParams(px(w), cast || mode == PERSON ? -2 : px(h)));
            setFocusable(true);
            setClickable(true);
            setClipChildren(false);
            FrameLayout pic = new FrameLayout(BrowseActivity.this);
            pic.setBackground(round(CARD, mode == PERSON ? 999 : 16));
            clip(pic, mode == PERSON ? w / 2f : 16);
            ImageView img = image();
            pic.addView(img, new FrameLayout.LayoutParams(-1, -1));
            load(img, it.poster, cast || mode == PERSON ? "w185" : "w342", px(w));
            if (it.poster == null) {
                TextView initials = label(initials(it.title), cast ? 40 : 30, SOFT, Ui.semibold);
                initials.setGravity(Gravity.CENTER);
                pic.addView(initials, new FrameLayout.LayoutParams(-1, -1));
            }
            boolean overlay = mode == TITLED || mode == ROLE;
            if (overlay || mode == PLAIN) {
                View shade = new View(BrowseActivity.this);
                shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xEB000000, 0x00000000}));
                pic.addView(shade, new FrameLayout.LayoutParams(-1, px(overlay ? 150 : 80), Gravity.BOTTOM));
            }
            LinearLayout bottom = column();
            bottom.setPadding(px(12), 0, px(12), px(12));
            badge = label("", 13, INK, Ui.semibold);
            badge.setPadding(px(9), 0, px(9), 0);
            badge.setGravity(Gravity.CENTER_VERTICAL);
            badge.setVisibility(View.GONE);
            bottom.addView(badge, new LinearLayout.LayoutParams(-2, px(24)));
            if (overlay) {
                TextView t = label(it.title, 19, INK, Ui.semibold);
                t.setMaxLines(2);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(0, px(6), 0, 0);
                bottom.addView(t);
                String sub = mode == ROLE ? Browse.join(java.util.Arrays.asList(it.year, it.role.isEmpty() ? "" : Ui.trf("as {role}", "role", it.role)), " · ")
                        : Browse.join(java.util.Arrays.asList("tv".equals(it.kind) ? Ui.tr("Show") : Ui.tr("Movie"), it.year), " · ");
                if (it.here) sub = Ui.tr("You're here") + " · " + it.year;
                TextView s = label(sub, 14, 0xB3FFFFFF, Ui.regular);
                s.setMaxLines(1);
                s.setEllipsize(TextUtils.TruncateAt.END);
                s.setPadding(0, px(2), 0, 0);
                bottom.addView(s);
            }
            pic.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            if (it.here) {
                TextView here = label(Ui.tr("YOU'RE HERE"), 13, DARK, Ui.semibold);
                here.setGravity(Gravity.CENTER);
                here.setBackgroundColor(INK);
                here.setLetterSpacing(0.06f);
                pic.addView(here, new FrameLayout.LayoutParams(-1, px(28), Gravity.TOP));
            }
            if (cast || mode == PERSON) {
                LinearLayout col = column();
                col.addView(pic, new LinearLayout.LayoutParams(px(w), px(ph)));
                TextView n = label(it.title, 19, INK, Ui.semibold);
                n.setMaxLines(1);
                n.setEllipsize(TextUtils.TruncateAt.END);
                n.setPadding(0, px(10), 0, 0);
                if (mode == PERSON) n.setGravity(Gravity.CENTER_HORIZONTAL);
                col.addView(n, new LinearLayout.LayoutParams(-1, -2));
                TextView r = label(it.role, 16, DIM, Ui.regular);
                r.setMaxLines(1);
                r.setEllipsize(TextUtils.TruncateAt.END);
                r.setPadding(0, px(2), 0, 0);
                if (mode == PERSON) r.setGravity(Gravity.CENTER_HORIZONTAL);
                col.addView(r, new LinearLayout.LayoutParams(-1, -2));
                addView(col);
                ring(pic, mode == PERSON ? w / 2f : 16, it.here);
                setOnFocusChangeListener((v, has) -> {
                    pic.animate().scaleX(has ? 1.07f : 1).scaleY(has ? 1.07f : 1).setDuration(140).start();
                    pic.setForeground(has ? ringDrawable(mode == PERSON ? w / 2f : 16) : null);
                    if (has && onFocus != null) onFocus.run();
                });
            } else {
                addView(pic, new FrameLayout.LayoutParams(-1, -1));
                ring(pic, 16, it.here);
                setOnFocusChangeListener((v, has) -> {
                    animate().scaleX(has ? 1.08f : 1).scaleY(has ? 1.08f : 1).setDuration(140).start();
                    setElevation(has ? px(12) : 0);
                    pic.setForeground(has ? ringDrawable(16) : it.here ? hereDrawable(16) : null);
                    if (has && onFocus != null) onFocus.run();
                });
            }
            setOnClickListener(v -> {
                if (it.here) return;
                openTitle(it);
            });
        }

        private void ring(View pic, float radius, boolean here) {
            if (here) pic.setForeground(hereDrawable(radius));
        }
    }

    private static String initials(String name) {
        StringBuilder b = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isEmpty() && b.length() < 2) b.append(Character.toUpperCase(part.charAt(0)));
        }
        return b.toString();
    }

    private Drawable ringDrawable(float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius * k);
        d.setColor(0);
        d.setStroke(px(4), INK);
        return d;
    }

    private Drawable hereDrawable(float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius * k);
        d.setColor(0);
        d.setStroke(px(3), 0x99F3F4F6);
        return d;
    }

    /** The badge: from what's known at once, else asked for in the background. */
    private void bindBadge(Poster p) {
        if ("person".equals(p.item.kind)) return;
        Browse.Badge quick = Browse.quickBadge(this, p.item);
        if (quick != null) {
            styleBadge(p, quick);
            return;
        }
        lookups.execute(() -> {
            if (destroyed) return;
            Browse.Badge b = Browse.badge(this, p.item);
            main.post(() -> styleBadge(p, b));
        });
    }

    private void styleBadge(Poster p, Browse.Badge b) {
        p.badgeInfo = b;
        TextView v = p.badge;
        if (b == null || b.type == 0) {
            v.setVisibility(View.GONE);
            return;
        }
        int ink = INK;
        Drawable bg;
        String text = b.label;
        switch (b.type) {
            case Browse.Badge.JELLYFIN:
                bg = gradient(JF_A, JF_B, 7);
                break;
            case Browse.Badge.REQUESTED:
                bg = round(0xD9000000, 7);
                ink = YELLOW;
                break;
            case Browse.Badge.DOWNLOADING:
                bg = round(0xD9000000, 7);
                ink = BLUE;
                text = "↓ " + b.label;
                break;
            case Browse.Badge.REQUEST:
                bg = round(0x8C000000, 7, 0x99FFFFFF);
                break;
            default:
                int[] c = serviceColors(b.key);
                bg = round(c[0], 7);
                ink = c[1];
                text = serviceShort(b.key, b.label);
        }
        v.setText(text);
        v.setTextColor(ink);
        v.setBackground(bg);
        v.setVisibility(View.VISIBLE);
    }

    private static int[] serviceColors(String key) {
        switch (key == null ? "" : key) {
            case "netflix": return new int[] {0xFF000000, 0xFFE50914};
            case "prime": return new int[] {0xFF1A98FF, INK};
            case "hotstar": return new int[] {0xFF0E1C46, INK};
            case "disney": return new int[] {0xFF113CCF, INK};
            case "appletv": return new int[] {0xFF000000, INK};
            case "crunchyroll": return new int[] {0xFFF47521, INK};
            case "max": return new int[] {0xFF002BE7, INK};
            default: return new int[] {0xE62A2C33, INK};
        }
    }

    private static String serviceShort(String key, String label) {
        if ("netflix".equals(key)) return "NETFLIX";
        if ("prime".equals(key)) return "prime";
        return label;
    }

    // MARK: - Buttons and controls

    /** The page's main button: white, an icon, its words and a line under them (with a progress bar). */
    private View bigButton(int glyph, String title, String sub, float progress, boolean white) {
        LinearLayout b = hbox();
        b.setPadding(px(22), 0, px(30), 0);
        b.setBackground(round(white ? INK : 0x1FFFFFFF, 999));
        Glyph g = new Glyph(glyph, DARK);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(g);
        b.addView(icon, new LinearLayout.LayoutParams(px(26), px(26)));
        b.addView(gap(16));
        LinearLayout text = column();
        text.addView(label(title, 23, DARK, Ui.semibold));
        LinearLayout line = hbox();
        line.setPadding(0, px(4), 0, 0);
        if (progress >= 0) {
            line.addView(progress(progress, DARK, 0x24000000), new LinearLayout.LayoutParams(px(110), px(5)));
            line.addView(gap(10));
        }
        line.addView(label(sub, 15, 0xFF55595F, Ui.regular));
        text.addView(line);
        b.addView(text);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.04f : 1).scaleY(has ? 1.04f : 1).setDuration(140).start();
            b.setBackground(has ? round(INK, 999, 0x66FFFFFF, 4) : round(INK, 999));
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(-2, px(72)));
        return b;
    }

    /** An outlined button with an icon: Request, Read more. */
    private View pillButton(int glyph, String title) {
        LinearLayout b = hbox();
        b.setPadding(px(22), 0, px(24), 0);
        b.setBackground(round(0x0AFFFFFF, 999, 0x4DFFFFFF));
        Glyph g = glyph >= 0 ? new Glyph(glyph, INK) : null;
        if (g != null) {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(g);
            b.addView(icon, new LinearLayout.LayoutParams(px(22), px(22)));
            b.addView(gap(12));
        }
        TextView t = label(title, 21, INK, Ui.semibold);
        b.addView(t);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(has ? round(INK, 999) : round(0x0AFFFFFF, 999, 0x4DFFFFFF));
            t.setTextColor(has ? DARK : INK);
            if (g != null) g.color(has ? DARK : INK);
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(-2, px(glyph >= 0 ? 64 : 46)));
        return b;
    }

    private View roundButton(int glyph, String description) {
        FrameLayout b = new FrameLayout(this);
        b.setBackground(round(0x1FFFFFFF, 999));
        b.setContentDescription(description);
        Glyph g = new Glyph(glyph, INK);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(g);
        b.addView(icon, new FrameLayout.LayoutParams(px(26), px(26), Gravity.CENTER));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(round(has ? INK : 0x1FFFFFFF, 999));
            g.color(has ? DARK : INK);
            v.animate().scaleX(has ? 1.08f : 1).scaleY(has ? 1.08f : 1).setDuration(140).start();
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(px(64), px(64)));
        return b;
    }

    /** A Where to watch tile: the service's logo (Jellyfin's mark) and what OK does; dimmed without the app. */
    private View whereTile(String logo, String text, boolean on, boolean jellyfin) {
        LinearLayout t = column();
        t.setGravity(Gravity.CENTER);
        t.setBackground(on ? round(0x1AFFFFFF, 18) : round(0, 18, 0x29FFFFFF));
        View mark;
        if (jellyfin) {
            FrameLayout j = new FrameLayout(this);
            j.setBackground(gradient(JF_A, JF_B, 14));
            ImageView play = new ImageView(this);
            play.setImageDrawable(new Glyph(Glyph.PLAY, INK));
            j.addView(play, new FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER));
            mark = j;
        } else {
            ImageView img = image();
            clip(img, 14);
            img.setBackground(round(CARD, 14));
            load(img, logo, "w92", px(56));
            if (!on) img.setAlpha(0.45f);
            mark = img;
        }
        t.addView(mark, new LinearLayout.LayoutParams(px(56), px(56)));
        TextView l = label(jellyfin ? "Jellyfin" : text, on ? 19 : 17, on ? INK : FAINT, on ? Ui.semibold : Ui.medium);
        l.setPadding(0, px(12), 0, 0);
        t.addView(l);
        if (on) {
            t.setFocusable(true);
            t.setClickable(true);
            t.setOnFocusChangeListener((v, has) -> {
                t.setBackground(round(has ? INK : 0x1AFFFFFF, 18));
                l.setTextColor(has ? DARK : INK);
                v.animate().scaleX(has ? 1.06f : 1).scaleY(has ? 1.06f : 1).setDuration(140).start();
            });
        }
        return t;
    }

    private View rating(String source, String value) {
        LinearLayout r = hbox();
        r.setPadding(0, 0, px(26), 0);
        TextView tag = label(source, 15, 0, Ui.semibold);
        tag.setPadding(px(9), px(3), px(9), px(3));
        if (source.equals("TMDB")) {
            tag.setBackground(round(0xFF0D253F, 6));
            tag.setTextColor(0xFF01B4E4);
        } else if (source.equals("IMDb")) {
            tag.setBackground(round(0xFFF5C518, 6));
            tag.setTextColor(0xFF000000);
        } else {
            tag.setBackground(round(0xFF3A1B17, 6));
            tag.setTextColor(0xFFFA6A50);
        }
        r.addView(tag);
        r.addView(gap(9));
        r.addView(label(value, 24, INK, Ui.semibold));
        return r;
    }

    private interface Getter {
        int get();
    }

    private interface Picked {
        void picked(int i);
    }

    /** All / Movies / Shows: a segmented control; the arrows move the focus, OK picks. */
    private View segmented(String[] names, Getter now, Picked picked) {
        LinearLayout box = hbox();
        box.setPadding(px(4), px(4), px(4), px(4));
        box.setBackground(round(0x14FFFFFF, 999, 0x1FFFFFFF));
        TextView[] items = new TextView[names.length];
        Runnable paint = () -> {
            for (int i = 0; i < items.length; i++) {
                boolean on = now.get() == i, focus = items[i].hasFocus();
                items[i].setBackground(focus ? round(INK, 999) : on ? round(0x3DFFFFFF, 999) : null);
                items[i].setTextColor(focus ? DARK : on ? INK : DIM);
                items[i].setTypeface(on || focus ? Ui.semibold : Ui.medium);
            }
        };
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView t = label(names[i], 20, DIM, Ui.medium);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(20), 0, px(20), 0);
            t.setFocusable(true);
            t.setClickable(true);
            t.setOnFocusChangeListener((v, has) -> paint.run());
            t.setOnClickListener(v -> {
                if (now.get() == index) return;
                picked.picked(index);
                paint.run();
            });
            items[i] = t;
            box.addView(t, new LinearLayout.LayoutParams(-2, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        paint.run();
        box.setLayoutParams(new LinearLayout.LayoutParams(-2, px(52)));
        return box;
    }

    private TextView chip(String text) {
        TextView t = label(text, 20, SOFT, Ui.medium);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(20), 0, px(20), 0);
        t.setBackground(round(0, 999, 0x2EFFFFFF));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnFocusChangeListener((v, has) -> {
            t.setBackground(has ? round(INK, 999) : round(0, 999, 0x2EFFFFFF));
            t.setTextColor(has ? DARK : SOFT);
        });
        t.setLayoutParams(new LinearLayout.LayoutParams(-2, px(46)));
        return t;
    }

    private View searchBox() {
        LinearLayout box = hbox();
        box.setPadding(px(26), 0, px(26), 0);
        box.setBackground(round(0x14FFFFFF, 999, 0x33FFFFFF));
        ImageView glass = new ImageView(this);
        Glyph g = new Glyph(Glyph.SEARCH, SOFT);
        glass.setImageDrawable(g);
        box.addView(glass, new LinearLayout.LayoutParams(px(28), px(28)));
        box.addView(gap(16));
        TextView hint = label(Ui.tr("Search movies, shows and people"), 24, FAINT, Ui.regular);
        hint.setTag("hint");
        box.addView(hint);
        box.setFocusable(true);
        box.setClickable(true);
        return box;
    }

    private void boxFocus(View box) {
        box.setOnFocusChangeListener((v, has) -> {
            box.setBackground(has ? round(INK, 999) : round(0x14FFFFFF, 999, 0x33FFFFFF));
            TextView hint = box.findViewWithTag("hint");
            if (hint != null) hint.setTextColor(has ? 0xFF55595F : FAINT);
            ImageView glass = (ImageView) ((LinearLayout) box).getChildAt(0);
            ((Glyph) glass.getDrawable()).color(has ? DARK : SOFT);
        });
    }

    private void cardFocus(View v, float radius, float grow) {
        v.setOnFocusChangeListener((x, has) -> {
            x.animate().scaleX(has ? grow : 1).scaleY(has ? grow : 1).setDuration(140).start();
            x.setForeground(has ? ringDrawable(radius) : null);
        });
    }

    private View progress(float value, int ink) {
        return progress(value, ink, 0x24FFFFFF);
    }

    private View progress(float value, int ink, int track) {
        FrameLayout bar = new FrameLayout(this);
        bar.setBackground(round(track, 999));
        View fill = new View(this);
        fill.setBackground(round(ink, 999));
        bar.addView(fill, new FrameLayout.LayoutParams(0, -1));
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = Math.round((r - l) * Math.max(0, Math.min(1, value)));
            ViewGroup.LayoutParams lp = fill.getLayoutParams();
            if (lp.width != w) {
                lp.width = w;
                fill.post(() -> fill.setLayoutParams(lp));
            }
        });
        return bar;
    }

    private Screen message(String title, String body) {
        FrameLayout frame = new FrameLayout(this);
        LinearLayout col = column();
        col.setPadding(px(MARGIN), px(200), px(MARGIN), 0);
        col.addView(label(title, 56, INK, Ui.displaySemibold));
        TextView b = label(body, 26, DIM, Ui.regular);
        b.setPadding(0, px(20), 0, 0);
        col.addView(b);
        frame.addView(col);
        return new Screen(frame);
    }

    // MARK: - Pictures

    private void load(ImageView v, String path, String size, int width) {
        if (path == null) {
            v.setTag(null);
            return;
        }
        String url = Browse.IMAGES + size + path;
        if (url.equals(v.getTag())) return;
        v.setTag(url);
        Bitmap b = cache.get(url);
        if (b != null) {
            v.setImageBitmap(b);
            return;
        }
        v.setImageDrawable(null);
        pictures.execute(() -> {
            if (destroyed || !url.equals(v.getTag())) return;
            Bitmap got = cache.get(url);
            if (got == null) got = download(url, width);
            if (got == null) return;
            cache.put(url, got);
            Bitmap shown = got;
            main.post(() -> {
                if (url.equals(v.getTag())) v.setImageBitmap(shown);
            });
        });
    }

    /** The picture at `width` at most (posters are drawn smaller than TMDB's sizes), or null. */
    private static Bitmap download(String url, int width) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b;
            try (InputStream in = c.getInputStream()) {
                b = BitmapFactory.decodeStream(in, null, o);
            }
            if (b == null) return null;
            if (width > 0 && b.getWidth() > width * 1.15f) {
                int h = Math.round(b.getHeight() * (width / (float) b.getWidth()));
                Bitmap s = Bitmap.createScaledBitmap(b, width, Math.max(1, h), true);
                if (s != b) b.recycle();
                b = s;
            }
            return b;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // MARK: - Small helpers

    private int px(float v) {
        return Math.round(v * k);
    }

    private TextView label(String text, float size, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        return l;
    }

    private LinearLayout hbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        return l;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        return v;
    }

    private View gap(int w) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(px(w), 1));
        return v;
    }

    private ImageView image() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private ScrollView scroller() {
        ScrollView s = new ScrollView(this);
        s.setVerticalScrollBarEnabled(false);
        s.setClipChildren(false);
        s.setFillViewport(true);
        s.setSmoothScrollingEnabled(true);
        return s;
    }

    private HorizontalScrollView hscroll() {
        HorizontalScrollView s = new HorizontalScrollView(this);
        s.setHorizontalScrollBarEnabled(false);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        s.setFillViewport(false);
        return s;
    }

    private FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? w : px(w), h < 0 ? h : px(h));
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        return lp;
    }

    private GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius * k);
        return d;
    }

    private GradientDrawable round(int color, float radius, int stroke) {
        return round(color, radius, stroke, 1.5f);
    }

    private GradientDrawable round(int color, float radius, int stroke, float width) {
        GradientDrawable d = round(color, radius);
        d.setStroke(Math.max(1, px(width)), stroke);
        return d;
    }

    private GradientDrawable gradient(int from, int to, float radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {from, to});
        d.setCornerRadius(radius * k);
        return d;
    }

    private void clip(View v, float radius) {
        float r = radius * k;
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    /** Small icons drawn with paths: play, play from start, trailer, search, plus. */
    private static final class Glyph extends Drawable {
        static final int PLAY = 0, RESTART = 1, TRAILER = 2, SEARCH = 3, PLUS = 4;
        private final int type;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF oval = new RectF();

        Glyph(int type, int color) {
            this.type = type;
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void color(int c) {
            paint.setColor(c);
            invalidateSelf();
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()), cx = b.exactCenterX(), cy = b.exactCenterY();
            paint.setStrokeWidth(s * 0.1f);
            path.reset();
            switch (type) {
                case PLAY:
                    paint.setStyle(Paint.Style.FILL);
                    path.moveTo(cx - s * 0.3f, cy - s * 0.4f);
                    path.lineTo(cx + s * 0.4f, cy);
                    path.lineTo(cx - s * 0.3f, cy + s * 0.4f);
                    path.close();
                    c.drawPath(path, paint);
                    break;
                case RESTART: {
                    paint.setStyle(Paint.Style.STROKE);
                    float r = s * 0.36f;
                    oval.set(cx - r, cy - r, cx + r, cy + r);
                    c.drawArc(oval, -60, 300, false, paint);
                    paint.setStyle(Paint.Style.FILL);
                    float ax = cx, ay = cy - r;
                    path.moveTo(ax + s * 0.16f, ay);
                    path.lineTo(ax - s * 0.05f, ay - s * 0.15f);
                    path.lineTo(ax - s * 0.05f, ay + s * 0.15f);
                    path.close();
                    c.drawPath(path, paint);
                    break;
                }
                case TRAILER:
                    paint.setStyle(Paint.Style.STROKE);
                    oval.set(cx - s * 0.42f, cy - s * 0.32f, cx + s * 0.42f, cy + s * 0.32f);
                    c.drawRoundRect(oval, s * 0.12f, s * 0.12f, paint);
                    paint.setStyle(Paint.Style.FILL);
                    path.moveTo(cx - s * 0.1f, cy - s * 0.14f);
                    path.lineTo(cx + s * 0.16f, cy);
                    path.lineTo(cx - s * 0.1f, cy + s * 0.14f);
                    path.close();
                    c.drawPath(path, paint);
                    break;
                case SEARCH:
                    paint.setStyle(Paint.Style.STROKE);
                    c.drawCircle(cx - s * 0.08f, cy - s * 0.08f, s * 0.3f, paint);
                    c.drawLine(cx + s * 0.15f, cy + s * 0.15f, cx + s * 0.42f, cy + s * 0.42f, paint);
                    break;
                default:
                    paint.setStyle(Paint.Style.STROKE);
                    c.drawLine(cx - s * 0.38f, cy, cx + s * 0.38f, cy, paint);
                    c.drawLine(cx, cy - s * 0.38f, cx, cy + s * 0.38f, paint);
            }
        }

        @Override public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override public void setColorFilter(ColorFilter f) {
            paint.setColorFilter(f);
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
