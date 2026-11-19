package com.satz.tvremote;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Remembers paired TVs on the phone (name + IP). Nothing leaves the device. */
public final class TvStore {
    public static final class Tv {
        public String name, host;
        public boolean paired, online;
        public IrRemote ir;
        public Tv(String name, String host) { this.name = name; this.host = host; }
    }

    private final SharedPreferences prefs;

    public TvStore(Context c) { prefs = c.getSharedPreferences("tvs", Context.MODE_PRIVATE); }

    public List<Tv> paired() {
        List<Tv> list = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Tv t = new Tv(o.getString("name"), o.getString("host"));
                t.paired = true;
                list.add(t);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private void save(List<Tv> list) {
        JSONArray a = new JSONArray();
        try {
            for (Tv t : list) a.put(new JSONObject().put("name", t.name).put("host", t.host));
        } catch (Exception ignored) {}
        prefs.edit().putString("list", a.toString()).apply();
    }

    public void add(String name, String host) {
        List<Tv> list = paired();
        for (int i = list.size() - 1; i >= 0; i--) {
            Tv t = list.get(i);
            if (t.host.equals(host) || t.name.equals(name)) list.remove(i);
        }
        list.add(0, new Tv(name, host));
        save(list);
    }

    public void remove(String host) {
        List<Tv> list = paired();
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).host.equals(host)) list.remove(i);
        save(list);
    }

    /** TV got a new IP from the router: keep the pairing, update the address. */
    public boolean updateHost(String name, String newHost) {
        List<Tv> list = paired();
        boolean changed = false;
        for (Tv t : list) if (t.name.equals(name) && !t.host.equals(newHost)) { t.host = newHost; changed = true; }
        if (changed) save(list);
        return changed;
    }

    // ---- IR remotes: brand + code-set index into IrDb ----

    public static final class IrRemote {
        public final String brand; public final int index;
        IrRemote(String b, int i) { brand = b; index = i; }
        public String id() { return "ir:" + brand + ":" + index; }
    }

    public List<IrRemote> irList() {
        List<IrRemote> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("ir", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                l.add(new IrRemote(o.getString("b"), o.getInt("i")));
            }
        } catch (Exception ignored) {}
        return l;
    }

    private void saveIr(List<IrRemote> l) {
        JSONArray a = new JSONArray();
        try { for (IrRemote r : l) a.put(new JSONObject().put("b", r.brand).put("i", r.index)); } catch (Exception ignored) {}
        prefs.edit().putString("ir", a.toString()).apply();
    }

    public void addIr(String brand, int index) {
        List<IrRemote> l = irList();
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).brand.equals(brand)) l.remove(i);
        l.add(0, new IrRemote(brand, index));
        saveIr(l);
    }

    public void removeIr(String brand, int index) {
        List<IrRemote> l = irList();
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).brand.equals(brand) && l.get(i).index == index) l.remove(i);
        saveIr(l);
    }

    public String lastHost() { return prefs.getString("last", null); }

    public void setLast(String host) { prefs.edit().putString("last", host).apply(); }
}
