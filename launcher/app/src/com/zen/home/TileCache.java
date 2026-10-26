package com.zen.home;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads the tiles TileLoaderService rendered: one PNG per app plus a tab-separated index. */
final class TileCache {

    private TileCache() { }

    static File dir(Context c) {
        return new File(c.getCacheDir(), "tiles-hi");
    }

    static List<AppRepo.App> read(Context c) {
        List<AppRepo.App> out = new ArrayList<>();
        File dir = dir(c);
        File index = new File(dir, "index.txt");
        if (!index.exists()) return out;
        BitmapFactory.Options gpu = new BitmapFactory.Options();
        gpu.inPreferredConfig = Bitmap.Config.HARDWARE;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(index), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", 3);
                if (p.length < 3) continue;
                ComponentName cn = ComponentName.unflattenFromString(p[1]);
                Bitmap art = BitmapFactory.decodeFile(new File(dir, p[0] + ".png").getPath(), gpu);
                if (cn == null || art == null) continue;
                out.add(new AppRepo.App(p[0], cn, p[2], art));
            }
        } catch (Exception ignored) { }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }
}
