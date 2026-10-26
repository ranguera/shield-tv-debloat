package com.zen.home;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HomeActivity extends Activity implements WallpaperView.Listener {

    private static final String[] DEFAULT_DOCK = {
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.disney.disneyplus",
            "com.wbd.stream",
            "com.amazon.amazonvideo.livingroom",
            "com.hulu.livingroomplus",
            "com.peacocktv.peacockandroid",
            "com.plexapp.android",
    };
    private static final int COLUMNS = 6;
    /** Favorites per dock row. A seventh app wraps; past twelve the two rows scroll. */
    private static final int DOCK_PER_ROW = 6;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final DecelerateInterpolator ease = new DecelerateInterpolator(2f);

    private float density;
    private int screenW, screenH, tileW, tileH, gap, side, headerH;
    private int dockW, dockH, dockSlotW, dockSlotH, dockGap, dockPadX, dockPadY, dockViewportW;
    private int dockRows = 1;
    private SharedPreferences prefs;

    private WallpaperView wall;
    private View dim;
    private TallLayout content;
    private static final int GLASS_RADIUS = 30;
    private static final int TOP_SCRIM = 0x8C000000, TOP_SCRIM_DP = 210;
    // Tall enough to sit behind the two-row dock and the app name above it.
    private static final int BOTTOM_SCRIM = 0x80000000, BOTTOM_SCRIM_DP = 330;
    private GlassView glass;
    /** Every glass element on screen (dock + pills); all re-bake when the wallpaper changes. */
    private final List<GlassView> glasses = new ArrayList<>();
    private View dockPanel;
    private WideLayout dockRow;
    private LinearLayout grid;
    private TextView captionTitle, captionCredit, dockEmpty, dockName, greeting, moveHint;
    private Weather weather;
    private FrameLayout weatherHost;
    private LinearLayout weatherBody;
    private TextView weatherFallback, wxNowTemp, wxNowCond, wxPlace;
    private ImageView wxNowIcon;
    private final ImageView[] wxDayIcon = new ImageView[3];
    private final TextView[] wxDayLabel = new TextView[3];
    private final TextView[] wxDayRange = new TextView[3];

    private final Map<String, AppRepo.App> apps = new LinkedHashMap<>();
    private final List<String> dock = new ArrayList<>();
    private final List<String> gridOrder = new ArrayList<>();
    private final Set<String> hidden = new HashSet<>();
    private boolean showHidden;
    private AppTile moving;
    private Dialog menu;
    private boolean okHeld, glassReady, menuArmed, dockNameShown = true;
    private Sounds sounds;
    private float dockTargetX;

    private boolean started, tilesStale;

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            tilesStale = true;
            main.removeCallbacks(refresh);
            if (started) main.postDelayed(refresh, 600);
        }
    };
    private final Runnable refresh = this::refreshTiles;

    private final BroadcastReceiver tilesReady = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            readCachedTiles();
        }
    };

    private final BroadcastReceiver wallpapersReady = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            wall.reloadIfChanged();
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("zen", MODE_PRIVATE);
        weather = new Weather(prefs, main);
        weather.setListener(this::bindWeather);
        sounds = new Sounds(this);
        DisplayMetrics m = getResources().getDisplayMetrics();
        density = m.density;
        screenW = m.widthPixels;
        screenH = m.heightPixels;
        tileW = dp(130);
        tileH = Math.round(tileW * 9f / 16f);
        gap = dp(18);
        side = Math.max(dp(32), (screenW - (COLUMNS * tileW + (COLUMNS - 1) * gap)) / 2);
        dockGap = dp(14);
        // Room inside the glass so an enlarged end tile never reaches the panel's rounded corners.
        dockPadX = dp(22);
        dockPadY = dp(14);
        dockViewportW = screenW - 2 * side - 2 * dockPadX;
        headerH = dp(150);
        sizeDock(dockTileColumns(DEFAULT_DOCK.length), dockRowCount(DEFAULT_DOCK.length));
        hidden.addAll(split(prefs.getString("hidden", "")));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        wall = new WallpaperView(this, prefs);
        Display.Mode mode = getWindowManager().getDefaultDisplay().getMode();
        int pw = Math.min(3840, mode.getPhysicalWidth()), ph = Math.min(2160, mode.getPhysicalHeight());
        wall.setBufferSize(pw, ph);
        wall.setListener(this);
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));

        root.addView(gradient(TOP_SCRIM, 0x00000000, GradientDrawable.Orientation.TOP_BOTTOM),
                new FrameLayout.LayoutParams(-1, dp(TOP_SCRIM_DP), Gravity.TOP));
        root.addView(gradient(0x00000000, BOTTOM_SCRIM, GradientDrawable.Orientation.TOP_BOTTOM),
                new FrameLayout.LayoutParams(-1, dp(BOTTOM_SCRIM_DP), Gravity.BOTTOM));
        dim = new View(this);
        dim.setBackgroundColor(0xFF000000);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        content = new TallLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, -2));
        buildChrome();

        moveHint = text("◀ ▶  move    ·    OK  done", 12, 0xFFFFFFFF, "sans-serif-medium");
        moveHint.setPadding(dp(18), dp(8), dp(18), dp(8));
        moveHint.setBackground(round(0xCC1C1C1E, dp(18)));
        moveHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hl.bottomMargin = dp(28);
        root.addView(moveHint, hl);

        setContentView(root);
        // Zen Home plays its own Apple TV-style sounds; mute Android's default focus clicks.
        getWindow().getDecorView().setSoundEffectsEnabled(false);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> onFocusMoved(newF));

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);

        registerReceiver(tilesReady, new IntentFilter(TileLoaderService.ACTION_READY));
        registerReceiver(wallpapersReady, new IntentFilter(WallpaperFetcher.ACTION_READY));

        loadApps();
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        wall.reloadIfChanged();
        wall.start();
        WallpaperFetcher.schedule(this);
        sounds.wake();
        greetingTick.run();
        weatherTick.run();
        if (tilesStale) refreshTiles();
    }

    @Override protected void onStop() {
        started = false;
        main.removeCallbacks(greetingTick);
        main.removeCallbacks(weatherTick);
        sounds.sleep();
        wall.stop();
        super.onStop();
    }

    private final Runnable greetingTick = new Runnable() {
        @Override public void run() {
            int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            greeting.setText(h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening");
            main.postDelayed(this, 60_000);
        }
    };

    private final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            if (!started || weather == null) return;
            weather.refresh(false);
            main.postDelayed(this, 30 * 60_000L);
        }
    };

    @Override protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        unregisterReceiver(tilesReady);
        unregisterReceiver(wallpapersReady);
        if (weather != null) weather.close();
        io.shutdownNow();
        sounds.release();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) wall.releaseIfHidden();
    }

    /** Home pressed while already home: back to the dock, like Apple TV. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        resetToDock();
    }

    @Override public void onBackPressed() {
        resetToDock();
    }

    // ------------------------------------------------------------------ layout

    private void buildChrome() {
        // Header: greeting, 24-hour clock and date on the left, weather beside the time,
        // Search / Settings pills on the right.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.TOP);
        header.setBaselineAligned(false);
        header.setClipChildren(false);
        // The pills sit on the padding edge; let a focused (enlarged) pill draw past it.
        header.setClipToPadding(false);
        header.setPadding(side, dp(30), side, 0);

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setClipChildren(false);
        greeting = text("", 9.5f, 0xD9FFFFFF, "sans-serif-medium");
        caps(greeting, 0.2f);
        TextClock time = new TextClock(this);
        // Both patterns are 24-hour, so the clock stays on military time even if the
        // SHIELD itself is set to 12-hour.
        time.setFormat12Hour("HH:mm");
        time.setFormat24Hour("HH:mm");
        style(time, 64, 0xFFFFFFFF, "sans-serif-thin");
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE d MMMM");
        date.setFormat24Hour("EEEE d MMMM");
        style(date, 9.5f, 0xD9FFFFFF, "sans-serif-medium");
        caps(date, 0.15f);
        for (TextView t : new TextView[] { greeting, time, date }) t.setShadowLayer(dp(6), 0, dp(1), 0x66000000);

        LinearLayout timeCol = new LinearLayout(this);
        timeCol.setOrientation(LinearLayout.VERTICAL);
        timeCol.addView(time);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.topMargin = dp(4);
        timeCol.addView(date, dl);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        timeRow.setBaselineAligned(false);
        timeRow.setClipChildren(false);
        timeRow.addView(timeCol);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, -2);
        wl.leftMargin = dp(28);
        timeRow.addView(buildWeather(), wl);

        clock.addView(greeting);
        LinearLayout.LayoutParams tr = new LinearLayout.LayoutParams(-2, -2);
        tr.topMargin = dp(2);
        clock.addView(timeRow, tr);
        header.addView(clock, new LinearLayout.LayoutParams(-2, -2));
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));

        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setClipChildren(false);
        pills.addView(pill(R.drawable.ic_search, "Search", v -> openSearch()));
        View s = pill(R.drawable.ic_settings, "Settings", v -> safeStart(new Intent(Settings.ACTION_SETTINGS)));
        ((LinearLayout.LayoutParams) s.getLayoutParams()).leftMargin = dp(10);
        pills.addView(s);
        header.addView(pills, new LinearLayout.LayoutParams(-2, -2));
        content.addView(header, new LinearLayout.LayoutParams(-1, headerH));

        // Stage: the rest of the first screen. The dock is anchored to its bottom edge with the
        // focused app's name above it, the wallpaper caption opposite, Apple TV style.
        FrameLayout stage = new FrameLayout(this);
        stage.setClipChildren(false);
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);

        FrameLayout nameRow = new FrameLayout(this);
        dockName = text("", 22, 0xFFFFFFFF, "sans-serif-medium");
        dockName.setShadowLayer(dp(8), 0, dp(1), 0x80000000);
        nameRow.addView(dockName, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.BOTTOM));
        LinearLayout caption = new LinearLayout(this);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setGravity(Gravity.END);
        captionTitle = text("", 10.5f, 0xE6FFFFFF, "sans-serif-medium");
        captionCredit = text("", 8.5f, 0x99FFFFFF, "sans-serif");
        captionTitle.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
        captionCredit.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
        captionTitle.setSingleLine(true);
        captionCredit.setSingleLine(true);
        caption.addView(captionTitle, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams cc = new LinearLayout.LayoutParams(-2, -2);
        cc.topMargin = dp(2);
        caption.addView(captionCredit, cc);
        nameRow.addView(caption, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.leftMargin = side + dp(4);
        nl.rightMargin = side + dp(4);
        nl.bottomMargin = dp(12);
        bottom.addView(nameRow, nl);

        FrameLayout panel = new FrameLayout(this);
        glass = newGlass(dp(GLASS_RADIUS), 0);
        panel.addView(glass, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout viewport = new FrameLayout(this);
        viewport.setClipChildren(false);
        // Vertical, as wide as its rows: favorites wrap onto a second line and both scroll together.
        dockRow = new WideLayout(this);
        dockRow.setOrientation(LinearLayout.VERTICAL);
        dockRow.setClipChildren(false);
        viewport.addView(dockRow, new FrameLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(-1, -2);
        vp.setMargins(dockPadX, dockPadY, dockPadX, dockPadY);
        panel.addView(viewport, vp);
        dockEmpty = text("Your Dock is empty. Press ▼ and hold OK on any app to add it.",
                12, 0xCCFFFFFF, "sans-serif-medium");
        dockEmpty.setGravity(Gravity.CENTER);
        dockEmpty.setVisibility(View.GONE);
        panel.addView(dockEmpty, new FrameLayout.LayoutParams(-1, -1));
        // Clip the row to the glass (rounded) so a long dock scrolls inside it.
        panel.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(GLASS_RADIUS));
            }
        });
        panel.setClipToOutline(true);
        // The outline above frames the dock; don't also trim the row to its rectangle, or an
        // enlarged end tile loses its rounded outer corners.
        panel.setClipChildren(false);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dockPanelHeight());
        pl.leftMargin = side;
        pl.rightMargin = side;
        bottom.addView(panel, pl);
        dockPanel = panel;

        TextView more = text("▾  All apps", 9, 0x8CFFFFFF, "sans-serif-medium");
        caps(more, 0.12f);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.gravity = Gravity.CENTER_HORIZONTAL;
        ml.topMargin = dp(10);
        ml.bottomMargin = dp(12);
        bottom.addView(more, ml);

        stage.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        content.addView(stage, new LinearLayout.LayoutParams(-1, screenH - headerH));

        TextView appsLabel = text("Apps", 12.5f, 0xB3FFFFFF, "sans-serif-medium");
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
        al.leftMargin = side;
        al.topMargin = dp(24);
        content.addView(appsLabel, al);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
        gl.leftMargin = side;
        gl.topMargin = dp(12);
        content.addView(grid, gl);

        TextView help = text("Hold OK (or press ☰) on any app to move it, add it to the Dock, or hide it.",
                10.5f, 0x80FFFFFF, "sans-serif");
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.leftMargin = side;
        hp.topMargin = dp(10);
        hp.bottomMargin = dp(48);
        content.addView(help, hp);
    }

    /**
     * Current conditions beside the clock, then today and the next two days.
     * OK on the widget sets the city; until then the place is guessed from the network.
     */
    private View buildWeather() {
        weatherHost = new FrameLayout(this);
        weatherHost.setClipChildren(false);
        weatherHost.setPadding(dp(12), dp(6), dp(12), dp(6));
        weatherHost.setFocusable(true);
        weatherHost.setClickable(true);
        weatherHost.setSoundEffectsEnabled(false);
        weatherHost.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        weatherHost.setBackground(round(0x00000000, dp(16)));
        weatherHost.setOnFocusChangeListener((v, has) -> {
            weatherHost.setBackground(round(has ? 0x2EFFFFFF : 0x00000000, dp(16)));
            weatherHost.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(180).start();
        });
        weatherHost.setOnClickListener(v -> {
            sounds.select();
            editWeatherCity();
        });

        weatherBody = new LinearLayout(this);
        weatherBody.setOrientation(LinearLayout.HORIZONTAL);
        weatherBody.setGravity(Gravity.CENTER_VERTICAL);
        weatherBody.setBaselineAligned(false);

        wxNowIcon = weatherIcon();
        weatherBody.addView(wxNowIcon, new LinearLayout.LayoutParams(dp(32), dp(32)));

        LinearLayout now = new LinearLayout(this);
        now.setOrientation(LinearLayout.VERTICAL);
        wxNowTemp = text("", 28, 0xFFFFFFFF, "sans-serif-thin");
        wxNowCond = text("", 11, 0xD9FFFFFF, "sans-serif-medium");
        wxNowCond.setSingleLine(true);
        wxNowCond.setEllipsize(TextUtils.TruncateAt.END);
        wxNowCond.setMaxWidth(dp(128));
        wxPlace = text("", 9.5f, 0xA6FFFFFF, "sans-serif");
        wxPlace.setSingleLine(true);
        wxPlace.setMaxWidth(dp(128));
        now.addView(wxNowTemp);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.topMargin = dp(1);
        now.addView(wxNowCond, cl);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
        pl.topMargin = dp(1);
        now.addView(wxPlace, pl);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.leftMargin = dp(8);
        weatherBody.addView(now, nl);

        View rule = new View(this);
        rule.setBackgroundColor(0x40FFFFFF);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(48));
        rlp.leftMargin = dp(16);
        rlp.rightMargin = dp(6);
        rlp.gravity = Gravity.CENTER_VERTICAL;
        weatherBody.addView(rule, rlp);

        for (int i = 0; i < 3; i++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            wxDayLabel[i] = text("", 9, 0xD9FFFFFF, "sans-serif-medium");
            caps(wxDayLabel[i], 0.12f);
            wxDayIcon[i] = weatherIcon();
            wxDayRange[i] = text("", 12, 0xFFFFFFFF, "sans-serif-medium");
            wxDayRange[i].setSingleLine(true);
            col.addView(wxDayLabel[i], new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(22), dp(22));
            il.topMargin = dp(3);
            il.gravity = Gravity.CENTER_HORIZONTAL;
            col.addView(wxDayIcon[i], il);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
            rl.topMargin = dp(2);
            col.addView(wxDayRange[i], rl);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(12);
            weatherBody.addView(col, lp);
        }
        for (TextView t : new TextView[] { wxNowTemp, wxNowCond, wxPlace, wxDayLabel[0], wxDayLabel[1],
                wxDayLabel[2], wxDayRange[0], wxDayRange[1], wxDayRange[2] }) {
            t.setShadowLayer(dp(5), 0, dp(1), 0x80000000);
        }

        weatherFallback = text("Add weather", 13, 0xFFFFFFFF, "sans-serif-medium");
        weatherFallback.setShadowLayer(dp(5), 0, dp(1), 0x80000000);
        weatherFallback.setVisibility(View.GONE);

        weatherHost.addView(weatherBody, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL));
        weatherHost.addView(weatherFallback, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        weatherHost.setVisibility(View.GONE);
        return weatherHost;
    }

    private ImageView weatherIcon() {
        ImageView icon = new ImageView(this);
        icon.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private void bindWeather(Weather.Snapshot s) {
        if (weatherHost == null) return;
        if (s == null || s.days == null || s.days.length < 3) {
            weatherBody.setVisibility(View.GONE);
            weatherFallback.setVisibility(View.VISIBLE);
            weatherHost.setVisibility(View.VISIBLE);
            weatherHost.setContentDescription("Add weather");
            return;
        }
        weatherFallback.setVisibility(View.GONE);
        weatherBody.setVisibility(View.VISIBLE);
        weatherHost.setVisibility(View.VISIBLE);
        wxNowIcon.setImageDrawable(Weather.Icon.forCode(s.code, s.isDay));
        wxNowTemp.setText(s.temp + "°");
        wxNowCond.setText(s.condition);
        wxPlace.setText(s.place == null ? "" : s.place);
        wxPlace.setVisibility(s.place == null || s.place.isEmpty() ? View.GONE : View.VISIBLE);
        String where = s.place == null || s.place.isEmpty() ? "" : ", " + s.place;
        weatherHost.setContentDescription("Weather, " + s.temp + " degrees, " + s.condition + where);
        for (int i = 0; i < 3; i++) {
            Weather.Day d = s.days[i];
            wxDayLabel[i].setText(d.label);
            wxDayIcon[i].setImageDrawable(Weather.Icon.forCode(d.code, true));
            wxDayRange[i].setText(d.min + "° / " + d.max + "°");
        }
    }

    private void editWeatherCity() {
        if (menu != null && menu.isShowing()) return;
        Dialog dialog = new Dialog(this, R.style.ZenDialog);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(14));
        box.setBackground(round(0xF21C1C1E, dp(18)));

        TextView title = text("Weather city", 15, 0xFFFFFFFF, "sans-serif-medium");
        title.setPadding(dp(6), 0, dp(6), dp(6));
        box.addView(title);
        String saved = weather.savedLabel();
        if (saved != null && !saved.isEmpty()) {
            TextView current = text(saved, 12, 0xB3FFFFFF, "sans-serif");
            current.setPadding(dp(6), 0, dp(6), dp(10));
            box.addView(current);
        }

        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setHint("City name");
        et.setHintTextColor(0x66FFFFFF);
        et.setTextColor(0xFFFFFFFF);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        et.setPadding(dp(14), dp(10), dp(14), dp(10));
        et.setBackground(round(0x33FFFFFF, dp(12)));
        String city = prefs.getString("wx_city", "");
        et.setText(city);
        et.setSelection(et.getText().length());
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));

        TextView credit = text("Forecast data: Open-Meteo.com", 9, 0x80FFFFFF, "sans-serif");
        credit.setPadding(dp(6), dp(8), dp(6), dp(4));
        box.addView(credit);

        TextView status = text("", 11, 0xCCFFFFFF, "sans-serif");
        status.setPadding(dp(6), dp(4), dp(6), 0);
        status.setMinHeight(dp(18));

        final boolean[] busy = { false };
        Runnable submit = () -> {
            if (busy[0]) return;
            String q = et.getText().toString().trim();
            if (q.isEmpty()) {
                status.setText("Enter a city name");
                return;
            }
            busy[0] = true;
            status.setText("Looking up…");
            sounds.select();
            weather.findCity(q, new Weather.CityCallback() {
                @Override public void found(String foundCity) {
                    if (weatherBody.getVisibility() == View.VISIBLE) {
                        wxPlace.setText(foundCity);
                        wxPlace.setVisibility(View.VISIBLE);
                        wxNowCond.setText("Updating");
                    }
                    if (dialog.isShowing()) dialog.dismiss();
                    weather.refresh(true);
                }
                @Override public void failed() {
                    busy[0] = false;
                    if (dialog.isShowing()) status.setText("City not found, or no connection");
                }
            });
        };
        et.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                submit.run();
                return true;
            }
            return false;
        });

        TextView save = dialogButton("Save", submit);
        TextView cancel = dialogButton("Cancel", () -> {
            sounds.select();
            dialog.dismiss();
        });
        box.addView(save);
        box.addView(cancel);
        box.addView(status);

        dialog.setContentView(box, new ViewGroup.LayoutParams(dp(340), -2));
        dialogArmed = false;
        dialog.setOnShowListener(d -> et.requestFocus());
        dialog.show();
        main.post(() -> dialogArmed = true);
        if (dialog.getWindow() != null) dialog.getWindow().getDecorView().setSoundEffectsEnabled(false);
    }

    private boolean dialogArmed;

    private TextView dialogButton(String label, Runnable action) {
        TextView v = text(label, 13, 0xFFFFFFFF, "sans-serif-medium");
        v.setPadding(dp(14), 0, dp(14), 0);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setFocusable(true);
        v.setBackground(round(0x00000000, dp(10)));
        v.setOnFocusChangeListener((x, f) -> {
            v.setBackground(round(f ? 0xFFFFFFFF : 0x00000000, dp(10)));
            v.setTextColor(f ? 0xFF000000 : 0xFFFFFFFF);
            if (f && dialogArmed) sounds.tick();
        });
        v.setSoundEffectsEnabled(false);
        v.setOnClickListener(x -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(38));
        lp.topMargin = dp(4);
        v.setLayoutParams(lp);
        return v;
    }

    /**
     * How many apps sit on the first dock row before the next one starts.
     * Each row holds up to six. Two through six favorites stay split across both rows;
     * seven through twelve fill the top row first; more than twelve lengthens both
     * rows and the glass scrolls.
     */
    private static int dockColumns(int count) {
        if (count <= 1) return 1;
        if (count <= DOCK_PER_ROW) return (count + 1) / 2;
        if (count <= DOCK_PER_ROW * 2) return DOCK_PER_ROW;
        return (count + 1) / 2;
    }

    /** Columns used to size tiles: six across, even when a longer row scrolls. */
    private static int dockTileColumns(int count) {
        return Math.min(DOCK_PER_ROW, dockColumns(count));
    }

    private static int dockRowCount(int count) {
        return count > 1 ? 2 : 1;
    }

    private int dockPanelHeight() {
        return dockRows * dockSlotH + Math.max(0, dockRows - 1) * dockGap + 2 * dockPadY;
    }

    /**
     * Tiles grow to fill the glass, up to Apple-ish size, but never so tall that the two
     * rows, the app name and the "all apps" hint fall off the first screen.
     */
    private void sizeDock(int columns, int rows) {
        dockRows = Math.max(1, rows);
        int n = Math.max(1, columns);
        int ring = 2 * AppTile.slotGap(density);
        int w = (dockViewportW - (n - 1) * dockGap) / n - ring;
        int chrome = dp(78);
        int availH = screenH - headerH - chrome - 2 * dockPadY - Math.max(0, dockRows - 1) * dockGap;
        int maxH = Math.max(dp(48), availH / dockRows - ring);
        int cap = Math.min(dp(150), Math.round(maxH * 16f / 9f));
        dockW = Math.max(dp(72), Math.min(cap, w));
        dockH = Math.round(dockW * 9f / 16f);
        dockSlotW = dockW + ring;
        dockSlotH = dockH + ring;
    }

    private void rebuild(String focusPkg, Boolean focusInDock) {
        List<AppRepo.App> docked = new ArrayList<>();
        for (String pkg : dock) if (apps.containsKey(pkg)) docked.add(apps.get(pkg));
        int cols = dockColumns(docked.size());
        sizeDock(dockTileColumns(docked.size()), dockRowCount(docked.size()));
        dockRow.removeAllViews();
        LinearLayout line = null;
        for (int i = 0; i < docked.size(); i++) {
            if (i % cols == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setClipChildren(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
                if (dockRow.getChildCount() > 0) rl.topMargin = dockGap;
                dockRow.addView(line, rl);
            }
            if (line == null) continue;
            AppTile t = tile(docked.get(i), true);
            t.setId(View.generateViewId());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i % cols != 0) lp.leftMargin = dockGap;
            line.addView(t, lp);
        }
        // Like Apple TV, each row's ends are a stop: ◀ / ▶ never wrap, and ▼ from the
        // top row lands on the app directly below it.
        for (int r = 0; r < dockRow.getChildCount(); r++) {
            ViewGroup row = (ViewGroup) dockRow.getChildAt(r);
            if (row.getChildCount() == 0) continue;
            View first = row.getChildAt(0), last = row.getChildAt(row.getChildCount() - 1);
            first.setNextFocusLeftId(first.getId());
            last.setNextFocusRightId(last.getId());
        }
        if (dockRow.getChildCount() == 2) {
            ViewGroup top = (ViewGroup) dockRow.getChildAt(0);
            ViewGroup bot = (ViewGroup) dockRow.getChildAt(1);
            int n = Math.min(top.getChildCount(), bot.getChildCount());
            for (int c = 0; c < n; c++) {
                View above = top.getChildAt(c), below = bot.getChildAt(c);
                above.setNextFocusDownId(below.getId());
                below.setNextFocusUpId(above.getId());
            }
            if (n > 0 && top.getChildCount() > n) {
                int fallback = bot.getChildAt(n - 1).getId();
                for (int c = n; c < top.getChildCount(); c++) top.getChildAt(c).setNextFocusDownId(fallback);
            }
        }
        ViewGroup.LayoutParams pl = dockPanel.getLayoutParams();
        pl.height = dockPanelHeight();
        dockPanel.setLayoutParams(pl);
        dockEmpty.setVisibility(docked.isEmpty() ? View.VISIBLE : View.GONE);
        if (docked.isEmpty()) dockName.setText("");

        grid.removeAllViews();
        List<View> cells = new ArrayList<>();
        for (String pkg : visibleGrid()) {
            AppTile t = tile(apps.get(pkg), false);
            if (hidden.contains(pkg)) t.setAlpha(0.45f);
            cells.add(t);
        }
        if (!hidden.isEmpty()) cells.add(hiddenToggle());
        LinearLayout row = null;
        for (int i = 0; i < cells.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
                if (grid.getChildCount() > 0) rl.topMargin = dp(10);
                grid.addView(row, rl);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i % COLUMNS != 0) lp.leftMargin = gap;
            row.addView(cells.get(i), lp);
        }

        View target = focusPkg != null ? findTile(focusPkg, focusInDock) : null;
        if (target == null && getCurrentFocus() == null) target = firstDockTileOrGrid();
        if (target != null) target.requestFocus();
    }

    private AppTile tile(AppRepo.App a, boolean inDock) {
        AppTile t = new AppTile(this, a, inDock, inDock ? dockW : tileW, inDock ? dockH : tileH, density);
        t.setSoundEffectsEnabled(false);
        t.setOnClickListener(v -> { if (moving == null) { sounds.select(); launch(a, t); } });
        return t;
    }

    private View hiddenToggle() {
        TextView v = text(showHidden ? "Done" : "Hidden (" + hidden.size() + ")", 12, 0xFFFFFFFF, "sans-serif-medium");
        v.setGravity(Gravity.CENTER);
        v.setFocusable(true);
        v.setBackground(round(0x33FFFFFF, dp(9)));
        v.setLayoutParams(new LinearLayout.LayoutParams(tileW, tileH));
        v.setOnFocusChangeListener((x, f) -> {
            v.setBackground(round(f ? 0xFFFFFFFF : 0x33FFFFFF, dp(9)));
            v.setTextColor(f ? 0xFF000000 : 0xFFFFFFFF);
            v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(150).start();
        });
        v.setSoundEffectsEnabled(false);
        v.setOnClickListener(x -> {
            sounds.select();
            showHidden = !showHidden;
            rebuild(null, null);
            View t = grid.getChildCount() > 0 ? lastCell() : null;
            if (t != null) t.requestFocus();
        });
        return v;
    }

    // ------------------------------------------------------------------ data

    /** Show cached tiles immediately, then have the :tiles process re-render them. */
    private void loadApps() {
        readCachedTiles();
        refreshTiles();
    }

    private void readCachedTiles() {
        io.execute(() -> {
            List<AppRepo.App> list = TileCache.read(getApplicationContext());
            if (!list.isEmpty()) main.post(() -> applyApps(list));
        });
    }

    private void refreshTiles() {
        try {
            startService(new Intent(this, TileLoaderService.class)
                    .putExtra(TileLoaderService.EXTRA_W, Math.round(tileW * AppTile.FOCUS_SCALE))
                    .putExtra(TileLoaderService.EXTRA_H, Math.round(tileH * AppTile.FOCUS_SCALE)));
            tilesStale = false;
        } catch (RuntimeException e) {
            tilesStale = true; // background-start restriction; retried in onStart()
        }
    }

    private void applyApps(List<AppRepo.App> list) {
        View f = getCurrentFocus();
        String focusPkg = f instanceof AppTile ? ((AppTile) f).pkg : null;
        Boolean focusDock = f instanceof AppTile ? ((AppTile) f).inDock : null;
        boolean firstLoad = apps.isEmpty();

        apps.clear();
        for (AppRepo.App a : list) apps.put(a.pkg, a);

        dock.clear();
        if (prefs.contains("dock")) {
            dock.addAll(split(prefs.getString("dock", "")));
        } else {
            for (String p : DEFAULT_DOCK) if (apps.containsKey(p)) dock.add(p);
            save();
        }
        gridOrder.clear();
        gridOrder.addAll(split(prefs.getString("grid", "")));
        for (String p : apps.keySet()) if (!gridOrder.contains(p)) gridOrder.add(p);

        if (moving != null) endMove();
        rebuild(focusPkg, focusDock);
        if (firstLoad) {
            View first = firstDockTileOrGrid();
            if (first != null) first.requestFocus();
        }
    }

    private List<String> visibleGrid() {
        List<String> out = new ArrayList<>();
        for (String p : gridOrder) {
            if (!apps.containsKey(p) || dock.contains(p)) continue;
            if (hidden.contains(p) && !showHidden) continue;
            out.add(p);
        }
        return out;
    }

    private void save() {
        prefs.edit()
                .putString("dock", TextUtils.join(",", dock))
                .putString("grid", TextUtils.join(",", gridOrder))
                .putString("hidden", TextUtils.join(",", hidden))
                .apply();
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        out.addAll(Arrays.asList(s.split(",")));
        return out;
    }

    @Override public void onWallpaperShown(Wallpapers.Entry w) {
        for (GlassView g : glasses) g.setWallpaper(w.path, glassReady ? 1800 : 0);
        glassReady = true;
        final String[] c = { w.title, w.credit };
        captionTitle.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            captionTitle.setText(c[0]);
            captionCredit.setText(c[1]);
            captionTitle.animate().alpha(1f).setDuration(900).start();
            captionCredit.setAlpha(0f);
            captionCredit.animate().alpha(1f).setDuration(900).start();
        }).start();
        captionCredit.animate().alpha(0f).setDuration(400).start();
    }

    // ------------------------------------------------------------------ focus & scrolling

    private void onFocusMoved(View v) {
        if (v == null) return;
        if (v instanceof AppTile && ((AppTile) v).inDock) {
            scrollContentTo(0, 0f);
            keepInDockView(v);
            showDockName(((AppTile) v).name);
            // Light on the glass rim follows the focused app (where it will be after scrolling).
            glass.setLight(dockPadX + dockTargetX + v.getLeft() + v.getWidth() / 2f);
        } else if (isInside(v, grid)) {
            int top = relativeTop(v, content);
            int target = -(top - Math.round(screenH * 0.30f));
            int min = -Math.max(0, content.getHeight() - screenH);
            scrollContentTo(Math.max(min, Math.min(0, target)), 0.62f);
            hideDockName();
        } else {
            scrollContentTo(0, 0f);
            hideDockName(); // Search / Settings: the name belongs to a dock app, not to them
        }
    }

    private void scrollContentTo(int y, float dimAlpha) {
        if (content.getTranslationY() != y) {
            content.animate().translationY(y).setDuration(320).setInterpolator(ease).start();
        }
        dim.animate().alpha(dimAlpha).setDuration(320).start();
        glass.setClarity(dimAlpha > 0f ? 0f : 1f);
    }

    private void showDockName(String name) {
        if (dockNameShown && name.contentEquals(dockName.getText())) return;
        dockNameShown = true;
        dockName.animate().cancel();
        if (!name.contentEquals(dockName.getText())) {
            dockName.setAlpha(0.25f);
            dockName.setText(name);
        }
        dockName.animate().alpha(1f).setDuration(220).start();
    }

    private void hideDockName() {
        if (!dockNameShown) return;
        dockNameShown = false;
        dockName.animate().cancel();
        dockName.animate().alpha(0f).setDuration(160).start();
    }

    /** Scrolls a long dock inside the glass panel so the focused tile stays visible. */
    private void keepInDockView(View tile) {
        float tx = dockTargetX;
        int left = tile.getLeft();
        float target = tx;
        if (left + tx < 0) target = -left;
        else if (left + dockSlotW + tx > dockViewportW) target = dockViewportW - dockSlotW - left;
        dockTargetX = target;
        if (target != tx) dockRow.animate().translationX(target).setDuration(260).setInterpolator(ease).start();
    }

    private void resetToDock() {
        if (menu != null && menu.isShowing()) menu.dismiss();
        if (moving != null) endMove();
        View first = firstDockTileOrGrid();
        dockTargetX = 0;
        dockRow.animate().translationX(0).setDuration(260).setInterpolator(ease).start();
        if (first != null) first.requestFocus();
        scrollContentTo(0, 0f);
    }

    private View firstDockTileOrGrid() {
        if (dockRow.getChildCount() > 0) {
            ViewGroup row = (ViewGroup) dockRow.getChildAt(0);
            if (row.getChildCount() > 0) return row.getChildAt(0);
        }
        if (grid.getChildCount() > 0) return ((ViewGroup) grid.getChildAt(0)).getChildAt(0);
        return null;
    }

    private View lastCell() {
        ViewGroup row = (ViewGroup) grid.getChildAt(grid.getChildCount() - 1);
        return row.getChildAt(row.getChildCount() - 1);
    }

    private AppTile findTile(String pkg, Boolean inDock) {
        if (inDock == null || inDock) {
            for (int r = 0; r < dockRow.getChildCount(); r++) {
                ViewGroup row = (ViewGroup) dockRow.getChildAt(r);
                for (int i = 0; i < row.getChildCount(); i++) {
                    View c = row.getChildAt(i);
                    if (c instanceof AppTile && ((AppTile) c).pkg.equals(pkg)) return (AppTile) c;
                }
            }
        }
        if (inDock == null || !inDock) {
            for (int r = 0; r < grid.getChildCount(); r++) {
                ViewGroup row = (ViewGroup) grid.getChildAt(r);
                for (int i = 0; i < row.getChildCount(); i++) {
                    View c = row.getChildAt(i);
                    if (c instanceof AppTile && ((AppTile) c).pkg.equals(pkg)) return (AppTile) c;
                }
            }
        }
        return null;
    }

    private static boolean isInside(View v, View ancestor) {
        for (ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == ancestor) return true;
        return false;
    }

    private static int relativeTop(View v, View ancestor) {
        int top = 0;
        for (View c = v; c != null && c != ancestor; c = (View) c.getParent()) top += c.getTop();
        return top;
    }

    // ------------------------------------------------------------------ editing

    private void showMenu(AppTile t) {
        AppRepo.App a = apps.get(t.pkg);
        if (a == null || (menu != null && menu.isShowing())) return;
        menu = new Dialog(this, R.style.ZenDialog);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(16), dp(14), dp(14));
        box.setBackground(round(0xF21C1C1E, dp(18)));
        TextView title = text(a.label, 15, 0xFFFFFFFF, "sans-serif-medium");
        title.setPadding(dp(10), 0, dp(10), dp(10));
        box.addView(title);

        boolean canMove = t.inDock ? dock.size() > 1 : visibleGrid().size() > 1;
        if (canMove) box.addView(menuItem("Move", () -> startMove(t)));
        if (t.inDock) {
            box.addView(menuItem("Remove from Dock", () -> {
                dock.remove(t.pkg);
                save();
                rebuild(t.pkg, false);
            }));
        } else {
            box.addView(menuItem("Add to Dock", () -> {
                dock.add(t.pkg);
                hidden.remove(t.pkg);
                save();
                rebuild(t.pkg, true);
            }));
            boolean isHidden = hidden.contains(t.pkg);
            box.addView(menuItem(isHidden ? "Unhide" : "Hide from Home", () -> {
                if (isHidden) hidden.remove(t.pkg); else hidden.add(t.pkg);
                save();
                rebuild(isHidden ? t.pkg : null, false);
                if (!isHidden && getCurrentFocus() == null) {
                    View f = firstDockTileOrGrid();
                    if (f != null) f.requestFocus();
                }
            }));
        }
        box.addView(menuItem("App info", () -> safeStart(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", t.pkg, null)))));
        box.addView(menuItem("Cancel", () -> { }));
        menu.setContentView(box, new ViewGroup.LayoutParams(dp(250), -2));
        menuArmed = false;
        menu.show();
        menu.getWindow().getDecorView().setSoundEffectsEnabled(false);
        sounds.select();
        main.post(() -> menuArmed = true); // the first item's initial focus shouldn't tick
    }

    private View menuItem(String label, Runnable action) {
        TextView v = text(label, 13, 0xFFFFFFFF, "sans-serif-medium");
        v.setPadding(dp(14), 0, dp(14), 0);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setFocusable(true);
        v.setBackground(round(0x00000000, dp(10)));
        v.setOnFocusChangeListener((x, f) -> {
            v.setBackground(round(f ? 0xFFFFFFFF : 0x00000000, dp(10)));
            v.setTextColor(f ? 0xFF000000 : 0xFFFFFFFF);
            if (f && menuArmed) sounds.tick();
        });
        v.setSoundEffectsEnabled(false);
        v.setOnClickListener(x -> {
            sounds.select();
            if (menu != null) menu.dismiss();
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(38));
        lp.topMargin = dp(4);
        v.setLayoutParams(lp);
        return v;
    }

    private void startMove(AppTile t) {
        moving = t;
        t.requestFocus();
        t.setWiggling(true);
        boolean flat = t.inDock && dockRowCount(dock.size()) == 1;
        moveHint.setText(flat ? "◀ ▶  move    ·    OK  done" : "◀ ▶ ▲ ▼  move    ·    OK  done");
        moveHint.setVisibility(View.VISIBLE);
    }

    private void endMove() {
        if (moving != null) moving.setWiggling(false);
        moving = null;
        moveHint.setVisibility(View.GONE);
        save();
    }

    private void shift(int delta) {
        String pkg = moving.pkg;
        boolean inDock = moving.inDock;
        if (inDock) {
            int i = dock.indexOf(pkg);
            int j = Math.max(0, Math.min(dock.size() - 1, i + delta));
            if (i == j) { sounds.bump(); return; }
            dock.remove(i);
            dock.add(j, pkg);
        } else {
            List<String> vis = visibleGrid();
            int i = vis.indexOf(pkg);
            int j = Math.max(0, Math.min(vis.size() - 1, i + delta));
            if (i == j) { sounds.bump(); return; }
            String anchor = vis.get(j);
            gridOrder.remove(pkg);
            int at = gridOrder.indexOf(anchor) + (j > i ? 1 : 0);
            gridOrder.add(at, pkg);
        }
        sounds.tick();
        moving.setWiggling(false);
        rebuild(pkg, inDock);
        AppTile t = findTile(pkg, inDock);
        moving = t;
        if (t != null) t.setWiggling(true);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (moving != null && isMoveKey(e.getKeyCode())) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT: shift(-1); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: shift(1); break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    shift(moving.inDock ? -dockColumns(dock.size()) : -COLUMNS);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    shift(moving.inDock ? dockColumns(dock.size()) : COLUMNS);
                    break;
                default:
                    if (e.getRepeatCount() == 0) {
                        AppTile t = moving;
                        endMove();
                        t.requestFocus();
                    }
            }
            return true;
        }
        // On TV the launcher owns the SEARCH key (keyboards, phone remotes); the
        // Shield remote's mic sends ASSIST, which the system routes to Assistant itself.
        if (e.getKeyCode() == KeyEvent.KEYCODE_SEARCH) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) openSearch();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            // Lets the next focused tile tilt in from the side the remote moved toward.
            AppTile.pendingTilt = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                    : e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
        }
        View focus = getCurrentFocus();
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_MENU
                && focus instanceof AppTile) {
            showMenu((AppTile) focus);
            return true;
        }
        // Holding OK opens the app menu. Driven by key repeats rather than View's
        // long-click timer so the held key's ACTION_UP never also launches the app.
        int code = e.getKeyCode();
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && focus instanceof AppTile) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0 && !e.isLongPress()) {
                    okHeld = false;
                } else {
                    if (!okHeld) {
                        okHeld = true;
                        showMenu((AppTile) focus);
                    }
                    return true;
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP && okHeld) {
                okHeld = false;
                return true;
            }
        }
        // Navigation sounds: tick when focus actually moves, bump when an arrow goes nowhere.
        boolean arrow = e.getAction() == KeyEvent.ACTION_DOWN && isArrow(code);
        View before = getCurrentFocus();
        boolean handled = super.dispatchKeyEvent(e);
        if (arrow) {
            if (getCurrentFocus() != before) sounds.tick();
            else if (e.getRepeatCount() == 0) sounds.bump();
        }
        return handled;
    }

    private static boolean isArrow(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    private static boolean isMoveKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ actions

    private void launch(AppRepo.App a, View from) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(a.pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(a.pkg);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).setComponent(a.component);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Bundle opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
        try {
            startActivity(i, opts);
        } catch (RuntimeException ignored) { }
    }

    private void openSearch() {
        if (safeStart(new Intent(Intent.ACTION_ASSIST))) return;
        safeStart(new Intent("android.search.action.GLOBAL_SEARCH"));
    }

    private boolean safeStart(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ view helpers

    /** A glass element that tracks the wallpaper and the on-screen dark gradients. */
    private GlassView newGlass(float radius, int overlay) {
        GlassView g = new GlassView(this, radius, density, overlay);
        g.setScrims(dp(TOP_SCRIM_DP), (TOP_SCRIM >>> 24) / 255f,
                screenH - dp(BOTTOM_SCRIM_DP), (BOTTOM_SCRIM >>> 24) / 255f);
        glasses.add(g);
        return g;
    }

    /**
     * Liquid Glass capsule (icon + label) for the top-right. A light smoke behind the label keeps
     * it readable over bright skies; focused, it fills solid white with dark text, as on iOS.
     */
    private View pill(int icon, String label, View.OnClickListener click) {
        FrameLayout p = new FrameLayout(this);
        p.setFocusable(true);
        p.setContentDescription(label);
        p.setClipChildren(false);
        p.addView(newGlass(0, 0x38000000), new FrameLayout.LayoutParams(-1, -1));
        View fill = new View(this) {
            @Override protected void onMeasure(int w, int h) {
                setMeasuredDimension(resolveSize(0, w), resolveSize(0, h)); // size follows the label
            }
        };
        fill.setBackground(round(0xFFFFFFFF, dp(16)));
        fill.setAlpha(0f);
        p.addView(fill, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), 0, dp(15), 0);
        ImageView i = new ImageView(this);
        i.setImageResource(icon);
        i.setColorFilter(0xFFFFFFFF);
        row.addView(i, new LinearLayout.LayoutParams(dp(13), dp(13)));
        TextView t = text(label, 10.5f, 0xFFFFFFFF, "sans-serif-medium");
        t.setShadowLayer(dp(3), 0, dp(0.5f), 0x8C000000);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = dp(6);
        row.addView(t, tl);
        p.addView(row, new FrameLayout.LayoutParams(-2, -1));

        p.setSoundEffectsEnabled(false);
        p.setOnClickListener(v -> { sounds.select(); click.onClick(v); });
        p.setOnFocusChangeListener((v, f) -> {
            fill.animate().alpha(f ? 1f : 0f).setDuration(160).start();
            i.setColorFilter(f ? 0xFF000000 : 0xFFFFFFFF);
            t.setTextColor(f ? 0xFF000000 : 0xFFFFFFFF);
            t.setShadowLayer(f ? 0 : dp(3), 0, f ? 0 : dp(0.5f), 0x8C000000);
            p.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(220)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
        });
        p.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(32)));
        return p;
    }

    private static void caps(TextView t, float tracking) {
        t.setAllCaps(true);
        t.setLetterSpacing(tracking);
    }

    private TextView text(String s, float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setText(s);
        style(t, sp, color, family);
        return t;
    }

    private void style(TextView t, float sp, int color, String family) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
    }

    private View gradient(int from, int to, GradientDrawable.Orientation o) {
        View v = new View(this);
        v.setBackground(new GradientDrawable(o, new int[] { from, to }));
        return v;
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Vertical LinearLayout that is as tall as its content; we scroll it by translation. */
    static final class TallLayout extends LinearLayout {
        TallLayout(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
    }

    /** LinearLayout that is as wide as its content; the two dock rows scroll together by translation. */
    static final class WideLayout extends LinearLayout {
        WideLayout(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), h);
        }
    }
}
