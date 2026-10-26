package com.zen.home;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Once a week, replaces the wallpapers with 12 photos Home hasn't shown before, picked at
 * random from Wikimedia Commons' "Featured pictures/Places/Natural" galleries: landscapes
 * that Commons reviewers selected, all freely licensed (credited in the caption).
 *
 * Runs as a job in its own short-lived ":fetch" process, like the tile renderer, so the 4K
 * decoding never adds to Home's memory. Photos are center-cropped to 3840x2160 once here,
 * so Home decodes them exactly like the bundled ones. The previous week's files stay on disk
 * until the next refresh, because Home may still be showing one of them.
 */
public final class WallpaperFetcher extends JobService {

    static final String ACTION_READY = "com.zen.home.WALLPAPERS_READY";
    private static final String TAG = "ZenWallpapers";
    private static final int JOB_DAILY_CHECK = 101, JOB_NOW = 102;
    private static final long MINUTE = 60_000L, HOUR = 60 * MINUTE, DAY = 24 * HOUR;
    static final long REFRESH_EVERY = 7 * DAY;
    private static final int SET_SIZE = 12, OUT_W = 3840, OUT_H = 2160, MAX_SEEN = 3000;
    private static final String API = "https://commons.wikimedia.org/w/api.php";
    private static final String GALLERIES = "Featured pictures/Places/Natural";
    // Wikimedia asks API clients to identify themselves.
    private static final String UA = "ZenHome/1.1 (https://github.com/ranguera/shield-tv-debloat)";

    private static final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean stopped;

    /**
     * Called whenever Home starts. A daily check catches a due refresh even if Home is never
     * opened; the one-off job makes the first download (and an overdue one) happen right away.
     */
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        ComponentName me = new ComponentName(c, WallpaperFetcher.class);
        try {
            if (js.getPendingJob(JOB_DAILY_CHECK) == null) {
                js.schedule(new JobInfo.Builder(JOB_DAILY_CHECK, me)
                        .setPeriodic(DAY, 6 * HOUR)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .build());
            }
            if (due(c) && js.getPendingJob(JOB_NOW) == null) {
                js.schedule(new JobInfo.Builder(JOB_NOW, me)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setBackoffCriteria(30 * MINUTE, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                        .build());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "could not schedule", e);
        }
    }

    static boolean due(Context c) {
        return System.currentTimeMillis() - Wallpapers.fetchedAt(c) >= REFRESH_EVERY;
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (!due(this) || !busy.compareAndSet(false, true)) {
            exitSoon();
            return false;
        }
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            boolean ok = false;
            try {
                ok = fetch();
            } catch (Exception e) {
                Log.w(TAG, "refresh failed", e);
            } finally {
                busy.set(false);
            }
            if (ok) sendBroadcast(new Intent(ACTION_READY).setPackage(getPackageName()));
            jobFinished(params, !ok && !stopped);
            exitSoon();
        }, "zen-fetch").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        stopped = true;
        return true;
    }

    /** Hand the process's memory back once the scheduler has seen the result. */
    private void exitSoon() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!busy.get()) Process.killProcess(Process.myPid());
        }, 3000);
    }

    // ------------------------------------------------------------------ refresh

    private static final class Photo {
        final String name, thumb, title, credit;
        Photo(String name, String thumb, String title, String credit) {
            this.name = name;
            this.thumb = thumb;
            this.title = title;
            this.credit = credit;
        }
    }

    private boolean fetch() throws Exception {
        File dir = Wallpapers.dir(this);
        dir.mkdirs();
        File seenFile = new File(dir, "seen.txt");
        Set<String> seen = new LinkedHashSet<>(readLines(seenFile));

        List<String> names = new ArrayList<>(galleryFiles());
        Collections.shuffle(names);
        List<String> fresh = new ArrayList<>();
        for (String n : names) if (!seen.contains(n)) fresh.add(n);
        if (fresh.size() < SET_SIZE * 3) { // shown nearly everything: start over
            seen.clear();
            fresh = names;
        }
        Log.i(TAG, names.size() + " gallery photos, " + fresh.size() + " not shown yet");

        String prefix = "w" + System.currentTimeMillis() + "-";
        List<String> saved = new ArrayList<>();
        StringBuilder index = new StringBuilder();
        boolean exhausted = true;
        for (int i = 0; i < fresh.size() && !stopped; i += 50) {
            for (Photo p : describe(fresh.subList(i, Math.min(i + 50, fresh.size())))) {
                if (saved.size() >= SET_SIZE || stopped) break;
                String file = prefix + String.format(java.util.Locale.US, "%02d", saved.size()) + ".jpg";
                if (!download(p.thumb, new File(dir, file))) continue;
                saved.add(file);
                seen.add(p.name);
                index.append(file).append('|').append(p.title).append('|').append(p.credit).append('\n');
            }
            if (saved.size() >= SET_SIZE) { exhausted = false; break; }
        }
        boolean enough = saved.size() == SET_SIZE || (exhausted && saved.size() >= Wallpapers.MIN_SET);
        if (stopped || !enough) {
            for (String f : saved) new File(dir, f).delete();
            Log.w(TAG, "only " + saved.size() + " photos downloaded; will retry");
            return false;
        }

        // Keep this set and the one Home may still be showing; everything older goes.
        Set<String> keep = new HashSet<>(saved);
        for (Wallpapers.Entry e : Wallpapers.readIndex(this)) keep.add(new File(e.path).getName());
        writeAtomically(Wallpapers.index(this), index.toString());
        List<String> seenList = new ArrayList<>(seen);
        writeAtomically(seenFile, String.join("\n",
                seenList.subList(Math.max(0, seenList.size() - MAX_SEEN), seenList.size())) + "\n");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if ((n.endsWith(".jpg") && !keep.contains(n)) || n.endsWith(".part") || n.endsWith(".tmp")) f.delete();
            }
        }
        Log.i(TAG, "new wallpaper set: " + saved.size() + " photos");
        return true;
    }

    /** Every JPEG on the gallery pages (the main page and its per-country subpages). */
    private Set<String> galleryFiles() throws Exception {
        Set<String> out = new LinkedHashSet<>();
        List<String> cont = new ArrayList<>();
        for (int round = 0; round < 100 && !stopped; round++) {
            List<String> args = new ArrayList<>(java.util.Arrays.asList(
                    "generator", "allpages", "gapnamespace", "4", "gapprefix", GALLERIES, "gaplimit", "50",
                    "prop", "images", "imlimit", "max"));
            args.addAll(cont);
            JSONObject d = query(args.toArray(new String[0]));
            JSONArray pages = d.optJSONObject("query") != null ? d.getJSONObject("query").optJSONArray("pages") : null;
            if (pages != null) {
                for (int i = 0; i < pages.length(); i++) {
                    JSONArray images = pages.getJSONObject(i).optJSONArray("images");
                    if (images == null) continue;
                    for (int j = 0; j < images.length(); j++) {
                        String t = images.getJSONObject(j).getString("title");
                        String lower = t.toLowerCase(java.util.Locale.ROOT);
                        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) out.add(t);
                    }
                }
            }
            JSONObject c = d.optJSONObject("continue");
            if (c == null) break;
            cont.clear();
            for (Iterator<String> k = c.keys(); k.hasNext(); ) {
                String key = k.next();
                cont.add(key);
                cont.add(c.getString(key));
            }
        }
        return out;
    }

    /** Size, a 3840-wide thumbnail URL and captions; keeps landscape photos that fill a 4K panel. */
    private List<Photo> describe(List<String> titles) throws Exception {
        JSONObject d = query("prop", "imageinfo", "titles", String.join("|", titles),
                "iiprop", "url|size|mime|user|extmetadata",
                "iiextmetadatafilter", "ObjectName|Artist|LicenseShortName",
                "iiurlwidth", String.valueOf(OUT_W));
        List<Photo> out = new ArrayList<>();
        JSONArray pages = d.getJSONObject("query").optJSONArray("pages");
        if (pages == null) return out;
        for (int i = 0; i < pages.length(); i++) {
            JSONObject page = pages.getJSONObject(i);
            JSONArray infos = page.optJSONArray("imageinfo");
            if (infos == null || infos.length() == 0) continue;
            JSONObject ii = infos.getJSONObject(0);
            int w = ii.optInt("width"), h = ii.optInt("height");
            int tw = ii.optInt("thumbwidth"), th = ii.optInt("thumbheight");
            String thumb = ii.optString("thumburl", "");
            // 3:2 and 16:9-ish only: wider panoramas and taller frames lose too much to the crop.
            if (!"image/jpeg".equals(ii.optString("mime")) || thumb.isEmpty() || h == 0
                    || w < OUT_W || h < OUT_H || tw != OUT_W || th < OUT_H) continue;
            float ratio = w / (float) h;
            if (ratio < 1.4f || ratio > 1.8f) continue;
            JSONObject meta = ii.optJSONObject("extmetadata");
            String title = page.getString("title");
            String caption = Captions.title(metaValue(meta, "ObjectName"), title);
            String credit = Captions.credit(metaValue(meta, "Artist"), ii.optString("user"),
                    metaValue(meta, "LicenseShortName"));
            out.add(new Photo(title, thumb, clean(caption), clean(credit)));
        }
        Collections.shuffle(out);
        return out;
    }

    private static String metaValue(JSONObject meta, String key) {
        JSONObject o = meta == null ? null : meta.optJSONObject(key);
        return o == null ? "" : o.optString("value", "");
    }

    private static String clean(String s) { return s.replace('|', '/').replace('\n', ' '); }

    /** Downloads the thumbnail and stores it center-cropped to exactly 3840x2160. */
    private boolean download(String url, File out) {
        File part = new File(out.getPath() + ".part");
        File tmp = new File(out.getPath() + ".tmp");
        HttpURLConnection c = null;
        try {
            c = open(url);
            if (c.getResponseCode() != 200) return false;
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (stopped) return false;
                    os.write(buf, 0, n);
                }
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(part.getPath(), bounds);
            int w = bounds.outWidth, h = bounds.outHeight;
            if (w < OUT_W || h < OUT_H) return false;
            int cw = w, ch = Math.round(w * 9f / 16f);
            if (ch > h) { ch = h; cw = Math.round(h * 16f / 9f); }
            Rect crop = new Rect((w - cw) / 2, (h - ch) / 2, (w - cw) / 2 + cw, (h - ch) / 2 + ch);
            BitmapRegionDecoder dec = BitmapRegionDecoder.newInstance(part.getPath(), false);
            Bitmap b;
            try {
                b = dec.decodeRegion(crop, null);
            } finally {
                dec.recycle();
            }
            if (b == null) return false;
            if (b.getWidth() != OUT_W || b.getHeight() != OUT_H) {
                Bitmap s = Bitmap.createScaledBitmap(b, OUT_W, OUT_H, true);
                b.recycle();
                b = s;
            }
            try (OutputStream os = new FileOutputStream(tmp)) {
                b.compress(Bitmap.CompressFormat.JPEG, 90, os);
            } finally {
                b.recycle();
            }
            return tmp.renameTo(out);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "download failed: " + url, e);
            return false;
        } finally {
            if (c != null) c.disconnect();
            part.delete();
            tmp.delete();
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static JSONObject query(String... kv) throws Exception {
        StringBuilder u = new StringBuilder(API).append("?action=query&format=json&formatversion=2");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            u.append('&').append(kv[i]).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        }
        HttpURLConnection c = open(u.toString());
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) body.write(buf, 0, n);
            }
            return new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        return c;
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) if (!line.isEmpty()) out.add(line);
        } catch (IOException ignored) { }
        return out;
    }

    private static void writeAtomically(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(text);
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed: " + f);
    }
}
