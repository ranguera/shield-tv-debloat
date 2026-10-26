package com.zen.home;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Current conditions plus a three-day forecast (today and the next two days).
 *
 * The forecast is Mataró, Spain, until a different city is typed in the dialog
 * (Open-Meteo geocoding). The forecast itself is Open-Meteo, which needs no key.
 * Calls happen only while Home is on screen, at most every half hour, and the last
 * good forecast is kept for six hours so a blip doesn't blank the widget.
 * Open-Meteo data is CC BY 4.0; the city dialog credits it.
 */
final class Weather {

    static final class Day {
        final String label;
        final int code, min, max;

        Day(String label, int code, int min, int max) {
            this.label = label;
            this.code = code;
            this.min = min;
            this.max = max;
        }
    }

    static final class Snapshot {
        final String place, condition;
        final int temp, code;
        final boolean isDay;
        final Day[] days;

        Snapshot(String place, String condition, int temp, int code, boolean isDay, Day[] days) {
            this.place = place;
            this.condition = condition;
            this.temp = temp;
            this.code = code;
            this.isDay = isDay;
            this.days = days;
        }
    }

    interface Listener {
        /** {@code null} when there is nothing to show (no location yet, or the lookup failed). */
        void onWeather(Snapshot snapshot);
    }

    interface CityCallback {
        void found(String city);
        void failed();
    }

    private static final String UA = "ZenHome/1.1 (Android TV; +https://github.com/ranguera/shield-tv-debloat)";
    private static final long FRESH = 30 * 60_000L;
    private static final long KEEP = 6 * 60 * 60_000L;

    /** Open-Meteo geocoding result for Mataró, Catalonia, Spain. */
    private static final String DEFAULT_CITY = "Mataró";
    private static final String DEFAULT_LABEL = "Mataró, Spain";
    private static final String DEFAULT_LAT = "41.54211";
    private static final String DEFAULT_LON = "2.4445";
    /** Bump to replace a previously saved place (including an old IP guess) with the default. */
    private static final int PLACE_REV = 1;

    private final SharedPreferences prefs;
    private final Handler main;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Listener listener;
    private volatile int fetchGen;
    private volatile boolean closed;

    Weather(SharedPreferences prefs, Handler main) {
        this.prefs = prefs;
        this.main = main;
        ensureDefaultPlace();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void close() {
        closed = true;
        fetchGen++;
        listener = null;
        io.shutdownNow();
    }

    /** Last forecast still worth showing, or null. */
    Snapshot cached() {
        long age = System.currentTimeMillis() - prefs.getLong("wx_time", 0);
        if (age < 0 || age > KEEP) return null;
        String js = prefs.getString("wx_json", null);
        if (js == null) return null;
        return parse(js, prefs.getString("wx_city", ""));
    }

    /** Show the cache immediately, then refresh it if it is missing or older than half an hour. */
    void refresh(boolean force) {
        if (closed) return;
        final int g = ++fetchGen;
        Snapshot have = cached();
        if (have != null && listener != null) listener.onWeather(have);
        long age = System.currentTimeMillis() - prefs.getLong("wx_time", 0);
        if (!force && have != null && age < FRESH) return;
        try {
            io.execute(() -> load(g));
        } catch (RuntimeException ignored) { }
    }

    /** Resolve {@code name} and, on success, replace the saved place. The caller refetches. */
    void findCity(String name, CityCallback cb) {
        if (closed) return;
        try {
            io.execute(() -> {
                Place p = geocode(name);
                main.post(() -> {
                    if (closed) return;
                    if (p == null) {
                        if (cb != null) cb.failed();
                        return;
                    }
                    prefs.edit()
                            .putString("wx_city", p.city)
                            .putString("wx_label", p.label)
                            .putString("wx_lat", Double.toString(p.lat))
                            .putString("wx_lon", Double.toString(p.lon))
                            .putInt("wx_place_rev", PLACE_REV)
                            .remove("wx_json")
                            .remove("wx_time")
                            .apply();
                    fetchGen++; // drop a forecast that was still in flight for the previous place
                    if (cb != null) cb.found(p.city);
                });
            });
        } catch (RuntimeException e) {
            if (cb != null) cb.failed();
        }
    }

    String savedLabel() {
        return prefs.getString("wx_label", "");
    }

    private void load(int g) {
        if (g != fetchGen || closed) return;
        ensureDefaultPlace();
        if (g != fetchGen || closed || !hasCoords()) {
            post(g, null);
            return;
        }
        String body = httpGet(forecastUrl());
        if (g != fetchGen || closed) return;
        if (body != null && body.contains("\"current\"")) {
            prefs.edit().putString("wx_json", body).putLong("wx_time", System.currentTimeMillis()).apply();
            post(g, parse(body, prefs.getString("wx_city", "")));
        } else {
            post(g, cached());
        }
    }

    private void post(int g, Snapshot s) {
        main.post(() -> {
            if (g != fetchGen || listener == null) return;
            listener.onWeather(s);
        });
    }

    private boolean hasCoords() {
        return prefs.contains("wx_lat") && prefs.contains("wx_lon");
    }

    /** First launch, and any saved place from before this default, uses Mataró. */
    private void ensureDefaultPlace() {
        if (prefs.getInt("wx_place_rev", 0) >= PLACE_REV && hasCoords()) return;
        prefs.edit()
                .putString("wx_city", DEFAULT_CITY)
                .putString("wx_label", DEFAULT_LABEL)
                .putString("wx_lat", DEFAULT_LAT)
                .putString("wx_lon", DEFAULT_LON)
                .putInt("wx_place_rev", PLACE_REV)
                .remove("wx_json")
                .remove("wx_time")
                .apply();
    }

    private Place geocode(String name) {
        try {
            String url = "https://geocoding-api.open-meteo.com/v1/search?count=1&format=json&language="
                    + language() + "&name=" + URLEncoder.encode(name, "UTF-8");
            String body = httpGet(url);
            if (body == null) return null;
            JSONArray r = new JSONObject(body).optJSONArray("results");
            if (r == null || r.length() == 0) return null;
            JSONObject o = r.getJSONObject(0);
            double lat = o.getDouble("latitude"), lon = o.getDouble("longitude");
            if (!valid(lat, lon)) return null;
            String city = o.optString("name", name);
            String country = o.optString("country", "");
            return new Place(city, country.isEmpty() ? city : city + ", " + country, lat, lon);
        } catch (Exception e) {
            return null;
        }
    }

    private String forecastUrl() {
        String unit = "US".equals(Locale.getDefault().getCountry()) ? "&temperature_unit=fahrenheit" : "";
        return "https://api.open-meteo.com/v1/forecast?latitude=" + prefs.getString("wx_lat", "0")
                + "&longitude=" + prefs.getString("wx_lon", "0")
                + "&current=temperature_2m,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&timezone=auto&forecast_days=3" + unit;
    }

    private static Snapshot parse(String body, String place) {
        try {
            JSONObject o = new JSONObject(body);
            JSONObject cur = o.getJSONObject("current");
            JSONObject daily = o.getJSONObject("daily");
            int temp = (int) Math.round(cur.getDouble("temperature_2m"));
            int code = cur.optInt("weather_code", 0);
            boolean isDay = cur.optInt("is_day", 1) == 1;
            JSONArray dates = daily.getJSONArray("time");
            JSONArray codes = daily.getJSONArray("weather_code");
            JSONArray maxs = daily.getJSONArray("temperature_2m_max");
            JSONArray mins = daily.getJSONArray("temperature_2m_min");
            if (dates.length() < 3) return null;
            Day[] days = new Day[3];
            for (int i = 0; i < 3; i++) {
                days[i] = new Day(dayLabel(i, dates.getString(i)), codes.optInt(i, 0),
                        (int) Math.round(mins.getDouble(i)), (int) Math.round(maxs.getDouble(i)));
            }
            return new Snapshot(place == null ? "" : place, condition(code), temp, code, isDay, days);
        } catch (Exception e) {
            return null;
        }
    }

    private static String dayLabel(int index, String iso) {
        if (index == 0) return "Today";
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso);
            if (d == null) return "";
            return new SimpleDateFormat("EEE", Locale.getDefault()).format(d);
        } catch (Exception e) {
            return "";
        }
    }

    static String condition(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Storm";
        return "Cloudy";
    }

    private static boolean valid(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180 && !(lat == 0 && lon == 0);
    }

    private static String language() {
        String lang = Locale.getDefault().getLanguage();
        if (lang != null && lang.matches("[a-zA-Z]{2,3}")) return lang.toLowerCase(Locale.US);
        return "en";
    }

    private static String httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 200_000) return null;
                sb.append(line);
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static final class Place {
        final String city, label;
        final double lat, lon;

        Place(String city, String label, double lat, double lon) {
            this.city = city;
            this.label = label;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** Stroke icons on a 24-unit grid, in the style of the other Zen Home. */
    static final class Icon extends Drawable {
        static final int CLEAR = 0, CLEAR_N = 1, PARTLY = 2, PARTLY_N = 3, CLOUD = 4, FOG = 5,
                DRIZZLE = 6, RAIN = 7, SNOW = 8, STORM = 9;
        private final int type;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        static Drawable forCode(int code, boolean day) {
            return new Icon(kind(code, day));
        }

        private static int kind(int c, boolean day) {
            if (c == 0) return day ? CLEAR : CLEAR_N;
            if (c == 1 || c == 2) return day ? PARTLY : PARTLY_N;
            if (c == 3) return CLOUD;
            if (c == 45 || c == 48) return FOG;
            if (c >= 51 && c <= 57) return DRIZZLE;
            if ((c >= 61 && c <= 67) || (c >= 80 && c <= 82)) return RAIN;
            if ((c >= 71 && c <= 77) || c == 85 || c == 86) return SNOW;
            if (c >= 95) return STORM;
            return CLOUD;
        }

        private Icon(int type) {
            this.type = type;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(0xFFFFFFFF);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeWidth(1.7f);
            stroke.setShadowLayer(1.2f, 0, 0.4f, 0x66000000);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xFFFFFFFF);
            fill.setShadowLayer(1.2f, 0, 0.4f, 0x66000000);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float w = Math.min(b.width(), b.height());
            if (w <= 0) return;
            canvas.save();
            canvas.translate(b.left + (b.width() - w) / 2f, b.top + (b.height() - w) / 2f);
            canvas.scale(w / 24f, w / 24f);
            switch (type) {
                case CLEAR: sun(canvas, 12, 12, 4.2f, 2.6f); break;
                case CLEAR_N: moon(canvas, 12, 12.5f, 7f); break;
                case PARTLY:
                case PARTLY_N: {
                    Path cl = cloud(1.5f, 2.5f);
                    boolean day = type == PARTLY;
                    canvas.save();
                    canvas.clipPath(cl, Region.Op.DIFFERENCE);
                    if (day) sun(canvas, 8.5f, 8.5f, 3.4f, 1.9f);
                    else moon(canvas, 8.5f, 8.5f, 4.8f);
                    canvas.restore();
                    canvas.drawPath(cl, stroke);
                    break;
                }
                case CLOUD: canvas.drawPath(cloud(0, 0.5f), stroke); break;
                case FOG:
                    canvas.drawPath(cloud(0, -2.5f), stroke);
                    canvas.drawLine(4.5f, 18.5f, 19.5f, 18.5f, stroke);
                    canvas.drawLine(7f, 21.5f, 17f, 21.5f, stroke);
                    break;
                case DRIZZLE:
                    canvas.drawPath(cloud(0, -3f), stroke);
                    for (float x : new float[] { 8.5f, 12.5f, 16.5f }) canvas.drawCircle(x, 19.5f, 0.9f, fill);
                    break;
                case RAIN:
                    canvas.drawPath(cloud(0, -3f), stroke);
                    for (float x : new float[] { 9f, 13f, 17f }) canvas.drawLine(x, 17.5f, x - 1.4f, 21.5f, stroke);
                    break;
                case SNOW:
                    canvas.drawPath(cloud(0, -3f), stroke);
                    for (float x : new float[] { 8.5f, 12.5f, 16.5f }) {
                        canvas.drawLine(x - 1.4f, 19.5f, x + 1.4f, 19.5f, stroke);
                        canvas.drawLine(x, 18.1f, x, 20.9f, stroke);
                    }
                    break;
                case STORM: {
                    canvas.drawPath(cloud(0, -3f), stroke);
                    Path bolt = new Path();
                    bolt.moveTo(13.2f, 15.2f);
                    bolt.lineTo(10f, 19.5f);
                    bolt.lineTo(12.4f, 19.5f);
                    bolt.lineTo(11.2f, 23f);
                    bolt.lineTo(14.8f, 18.2f);
                    bolt.lineTo(12.4f, 18.2f);
                    bolt.close();
                    canvas.drawPath(bolt, fill);
                    break;
                }
                default: break;
            }
            canvas.restore();
        }

        private static Path cloud(float dx, float dy) {
            Path a = new Path();
            a.addCircle(8.5f + dx, 13.5f + dy, 3.8f, Path.Direction.CW);
            Path b = new Path();
            b.addCircle(13.5f + dx, 11f + dy, 5f, Path.Direction.CW);
            Path c = new Path();
            c.addCircle(18f + dx, 14f + dy, 3.3f, Path.Direction.CW);
            Path r = new Path();
            r.addRoundRect(new RectF(4.7f + dx, 13.5f + dy, 21.3f + dx, 17.3f + dy), 1.9f, 1.9f, Path.Direction.CW);
            a.op(b, Path.Op.UNION);
            a.op(c, Path.Op.UNION);
            a.op(r, Path.Op.UNION);
            return a;
        }

        private void sun(Canvas canvas, float cx, float cy, float r, float rays) {
            canvas.drawCircle(cx, cy, r, stroke);
            for (int i = 0; i < 8; i++) {
                double an = Math.PI / 4 * i;
                float c = (float) Math.cos(an), s = (float) Math.sin(an);
                canvas.drawLine(cx + c * (r + 2f), cy + s * (r + 2f), cx + c * (r + 2f + rays), cy + s * (r + 2f + rays), stroke);
            }
        }

        private void moon(Canvas canvas, float cx, float cy, float r) {
            Path m = new Path();
            m.addCircle(cx, cy, r, Path.Direction.CW);
            Path cut = new Path();
            cut.addCircle(cx + r * 0.62f, cy - r * 0.42f, r * 0.86f, Path.Direction.CW);
            m.op(cut, Path.Op.DIFFERENCE);
            canvas.drawPath(m, stroke);
        }

        @Override public void setAlpha(int alpha) {
            stroke.setAlpha(alpha);
            fill.setAlpha(alpha);
        }

        @Override public void setColorFilter(ColorFilter filter) {
            stroke.setColorFilter(filter);
            fill.setColorFilter(filter);
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
