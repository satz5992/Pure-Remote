package com.satz.tvremote;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Offline IR code library for TVs (from the CC0 Flipper-IRDB), pre-encoded to raw
 * mark/space timings so the app just plays them through the phone's IR blaster.
 */
public final class IrDb {
    public static final class Code {
        public final int freq;
        public final int[] pattern;
        Code(int freq, int[] pattern) { this.freq = freq; this.pattern = pattern; }
    }

    public static final class Remote {
        public final String brand, model;
        public final int index; // position within brand
        public final Map<String, Code> keys = new HashMap<>();
        Remote(String brand, String model, int index) { this.brand = brand; this.model = model; this.index = index; }
        public Code get(String k) { return keys.get(k); }
        public boolean has(String k) { return keys.containsKey(k); }
    }

    public static final class Brand {
        public final String name;
        public final List<Remote> remotes = new ArrayList<>();
        Brand(String n) { name = n; }
    }

    private static List<Brand> brands;

    public static synchronized List<Brand> load(Context c) throws Exception {
        if (brands != null) return brands;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream in = new GZIPInputStream(c.getAssets().open("ir_tv.dat"))) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        }
        JSONObject root = new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8));
        JSONArray p = root.getJSONArray("p");
        Code[] codes = new Code[p.length()];
        for (int i = 0; i < codes.length; i++) {
            JSONArray a = p.getJSONArray(i);
            int[] t = new int[a.length() - 1];
            for (int j = 0; j < t.length; j++) t[j] = a.getInt(j + 1);
            codes[i] = new Code(a.getInt(0), t);
        }
        List<Brand> list = new ArrayList<>();
        JSONArray b = root.getJSONArray("b");
        for (int i = 0; i < b.length(); i++) {
            JSONObject bo2 = b.getJSONObject(i);
            Brand br = new Brand(bo2.getString("n"));
            JSONArray rs = bo2.getJSONArray("r");
            for (int j = 0; j < rs.length(); j++) {
                JSONObject r = rs.getJSONObject(j);
                Remote rem = new Remote(br.name, r.getString("m"), j);
                JSONObject k = r.getJSONObject("k");
                for (Iterator<String> it = k.keys(); it.hasNext(); ) {
                    String key = it.next();
                    rem.keys.put(key, codes[k.getInt(key)]);
                }
                br.remotes.add(rem);
            }
            list.add(br);
        }
        brands = list;
        return list;
    }

    public static Brand brand(Context c, String name) throws Exception {
        for (Brand b : load(c)) if (b.name.equals(name)) return b;
        return null;
    }

    public static Remote remote(Context c, String brand, int index) throws Exception {
        Brand b = brand(c, brand);
        if (b == null || index < 0 || index >= b.remotes.size()) return null;
        return b.remotes.get(index);
    }

    /** Every remote that has a power code, with duplicate power codes removed - for "find my TV". */
    public static List<Remote> allWithUniquePower(Context c) throws Exception {
        List<Remote> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        // Popular brands first so common TVs are found quickly.
        List<Brand> ordered = new ArrayList<>();
        for (String n : POPULAR) for (Brand b : load(c)) if (b.name.equalsIgnoreCase(n)) ordered.add(b);
        for (Brand b : load(c)) if (!ordered.contains(b)) ordered.add(b);
        for (Brand b : ordered) {
            for (Remote r : b.remotes) {
                Code pw = r.get("power");
                if (pw == null) continue;
                String sig = pw.freq + ":" + java.util.Arrays.toString(pw.pattern);
                if (seen.contains(sig)) continue;
                seen.add(sig);
                out.add(r);
            }
        }
        return out;
    }

    public static final String[] POPULAR = {"Samsung", "LG", "Sony", "Xiaomi", "TCL", "Panasonic", "Philips",
            "Hisense", "Haier", "Sharp", "Toshiba", "Thomson", "Akai", "Hitachi", "Ffalcon", "Android"};
}
