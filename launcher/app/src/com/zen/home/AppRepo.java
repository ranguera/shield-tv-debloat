package com.zen.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finds launchable apps and pre-renders their tile art at tile size (keeps RAM low). */
final class AppRepo {

    static final class App {
        final String pkg;
        final ComponentName component;
        final String label;
        final Bitmap art;

        App(String pkg, ComponentName component, String label, Bitmap art) {
            this.pkg = pkg;
            this.component = component;
            this.label = label;
            this.art = art;
        }
    }

    private AppRepo() { }

    static List<App> load(Context ctx, int artW, int artH) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, ResolveInfo> byPkg = new LinkedHashMap<>();
        // Leanback (TV) entries win over phone-style launcher entries for the same package.
        for (String cat : new String[] { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER }) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName())) continue;
                if (!byPkg.containsKey(pkg)) byPkg.put(pkg, ri);
            }
        }
        List<App> out = new ArrayList<>();
        for (Map.Entry<String, ResolveInfo> e : byPkg.entrySet()) {
            ActivityInfo ai = e.getValue().activityInfo;
            String label = String.valueOf(e.getValue().loadLabel(pm));
            // Load art at the density that matches the tile size: mdpi banners are 320x180,
            // the tile is ~296x166. ActivityInfo.loadBanner() would decode xhdpi (640x360+)
            // copies that the framework's resource caches keep alive (~70 MB for 55 apps).
            Resources res = null;
            try {
                res = pm.getResourcesForApplication(ai.applicationInfo);
            } catch (PackageManager.NameNotFoundException ignored) { }
            int bannerId = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
            // Decode the largest authored banner, not the mdpi one. Scaling mdpi (320x180)
            // up to the tile, then again for 4K output, is what made Netflix and Prime look soft.
            Bitmap art = null;
            Bitmap raw = decodeUnscaled(res, bannerId);
            if (raw != null) {
                art = blit(raw, artW, artH, true);
            } else {
                Drawable banner = load(res, bannerId, DisplayMetrics.DENSITY_XXXHIGH);
                if (banner != null) art = renderBanner(banner, artW, artH);
            }
            if (art == null) {
                int iconId = e.getValue().getIconResource();
                Bitmap iconBmp = decodeUnscaled(res, iconId);
                if (iconBmp != null) {
                    art = iconTile(iconBmp, artW, artH);
                } else {
                    Drawable icon = load(res, iconId, DisplayMetrics.DENSITY_XXXHIGH);
                    art = renderIcon(icon != null ? icon : e.getValue().loadIcon(pm), artW, artH);
                }
            }
            out.add(new App(e.getKey(), new ComponentName(ai.packageName, ai.name), label, art));
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /** The bitmap file as authored, with no density scaling. Null for XML drawables. */
    private static Bitmap decodeUnscaled(Resources res, int id) {
        if (res == null || id == 0) return null;
        TypedValue value = new TypedValue();
        try {
            res.getValueForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, value, true);
            if (value.string == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = res.openRawResource(id, value)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Draw {@code src} into a tile. Center-crops when {@code crop} is set, otherwise fits. */
    private static Bitmap blit(Bitmap src, int w, int h, boolean crop) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setDensity(Bitmap.DENSITY_NONE);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF1C1C1E);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        float sx = w / (float) src.getWidth(), sy = h / (float) src.getHeight();
        float scale = crop ? Math.max(sx, sy) : Math.min(sx, sy);
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        float left = (w - dw) / 2f, top = (h - dh) / 2f;
        c.drawBitmap(src, new Rect(0, 0, src.getWidth(), src.getHeight()),
                new RectF(left, top, left + dw, top + dh), p);
        src.recycle();
        return out;
    }

    private static Bitmap iconTile(Bitmap icon, int w, int h) {
        int tint = tintFor(icon);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setDensity(Bitmap.DENSITY_NONE);
        Canvas c = new Canvas(out);
        c.drawColor(tint);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        int s = (int) (h * 0.58f);
        float left = (w - s) / 2f, top = (h - s) / 2f;
        c.drawBitmap(icon, new Rect(0, 0, icon.getWidth(), icon.getHeight()),
                new RectF(left, top, left + s, top + s), p);
        icon.recycle();
        return out;
    }

    private static int tintFor(Bitmap icon) {
        Bitmap small = Bitmap.createScaledBitmap(icon, 12, 12, true);
        int tint = tintFrom(small);
        if (small != icon) small.recycle();
        return tint;
    }

    private static Drawable load(Resources res, int id, int density) {
        if (res == null || id == 0) return null;
        try {
            return res.getDrawableForDensity(id, density, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Bitmap renderBanner(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.setDensity(Bitmap.DENSITY_NONE);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF1C1C1E);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    /** Apps without a TV banner: icon centred on a tile tinted from the icon's own colour. */
    private static Bitmap renderIcon(Drawable icon, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.setDensity(Bitmap.DENSITY_NONE);
        Canvas c = new Canvas(b);
        c.drawColor(tintFor(icon));
        int s = (int) (h * 0.58f);
        int l = (w - s) / 2, t = (h - s) / 2;
        icon.setBounds(l, t, l + s, t + s);
        icon.draw(c);
        return b;
    }

    private static int tintFor(Drawable icon) {
        Bitmap small = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, 12, 12);
        icon.draw(new Canvas(small));
        int tint = tintFrom(small);
        small.recycle();
        return tint;
    }

    private static int tintFrom(Bitmap small) {
        long r = 0, g = 0, bl = 0, n = 0;
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 12; x++) {
                int p = small.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                r += Color.red(p); g += Color.green(p);                 bl += Color.blue(p); n++;
            }
        }
        if (n == 0) return 0xFF2C2C2E;
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
        hsv[1] = Math.min(hsv[1], 0.7f);
        hsv[2] = Math.min(0.42f, Math.max(0.18f, hsv[2] * 0.5f));
        return Color.HSVToColor(hsv);
    }
}
