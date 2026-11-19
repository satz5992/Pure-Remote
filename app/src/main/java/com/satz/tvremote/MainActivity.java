package com.satz.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** TV picker: finds Android/Google TVs on the Wi-Fi, or add one by IP. */
@SuppressWarnings("deprecation")
public class MainActivity extends Activity {
    private static final String SERVICE = "_androidtvremote2._tcp.";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<TvStore.Tv> items = new ArrayList<>();
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private final List<TvStore.Tv> found = new ArrayList<>();
    private boolean resolving;
    private TvStore store;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock lock;
    private Adapter adapter;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        store = new TvStore(this);
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        status = findViewById(R.id.status);
        ListView list = findViewById(R.id.list);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));
        list.setOnItemClickListener((p, v, pos, id) -> open(items.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> { options(items.get(pos)); return true; });
        findViewById(R.id.add_ip).setOnClickListener(v -> addByIp());
        findViewById(R.id.add_ir).setOnClickListener(v -> {
            if (Ir.available(this)) startActivity(new Intent(this, IrSetupActivity.class));
            else new AlertDialog.Builder(this).setTitle("No IR blaster")
                    .setMessage("This phone doesn't have an IR emitter, so it can't act as an infrared remote.\n\nIf your TV is an Android / Google TV, use the Wi-Fi remote instead – it appears in the list automatically.")
                    .setPositiveButton("OK", null).show();
        });
        new Thread(() -> { try { Tls.init(getFilesDir()); } catch (Exception ignored) {} }).start();

        // Jump straight to the last used TV.
        String last = store.lastHost();
        if (b == null && last != null && !getIntent().getBooleanExtra("pick", false)) {
            if (last.startsWith("ir:")) {
                for (TvStore.IrRemote r : store.irList()) if (r.id().equals(last)) { openIr(r); break; }
            } else for (TvStore.Tv t : store.paired()) if (t.host.equals(last)) { openRemote(t); break; }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        refresh();
        startDiscovery();
    }

    @Override
    protected void onStop() {
        stopDiscovery();
        super.onStop();
    }

    private void refresh() {
        items.clear();
        for (TvStore.IrRemote r : store.irList()) {
            TvStore.Tv t = new TvStore.Tv(("Xiaomi".equals(r.brand) ? "Xiaomi / Mi" : r.brand) + " TV", "");
            t.ir = r;
            t.paired = true;
            t.online = true;
            items.add(t);
        }
        List<TvStore.Tv> paired = store.paired();
        for (TvStore.Tv p : paired) {
            for (TvStore.Tv f : found) if (f.host.equals(p.host)) p.online = true;
            items.add(p);
        }
        outer:
        for (TvStore.Tv f : found) {
            for (TvStore.Tv p : paired) if (p.host.equals(f.host)) continue outer;
            items.add(f);
        }
        adapter.notifyDataSetChanged();
    }

    private void openIr(TvStore.IrRemote r) {
        store.setLast(r.id());
        startActivity(new Intent(this, IrRemoteActivity.class).putExtra("brand", r.brand).putExtra("index", r.index));
    }

    private void open(TvStore.Tv t) {
        if (t.ir != null) openIr(t.ir);
        else if (t.paired) openRemote(t);
        else PairingFlow.start(this, t.name, t.host, () -> openRemote(t));
    }

    private void openRemote(TvStore.Tv t) {
        store.setLast(t.host);
        startActivity(new Intent(this, RemoteActivity.class).putExtra("host", t.host).putExtra("name", t.name));
    }

    private void options(TvStore.Tv t) {
        if (t.ir != null) {
            new AlertDialog.Builder(this).setTitle(t.name)
                    .setItems(new String[]{"Find a different code", "Remove"}, (d, w) -> {
                        if (w == 0) startActivity(new Intent(this, IrSetupActivity.class));
                        else { store.removeIr(t.ir.brand, t.ir.index); refresh(); }
                    }).show();
            return;
        }
        if (!t.paired) { open(t); return; }
        new AlertDialog.Builder(this).setTitle(t.name)
                .setItems(new String[]{"Pair again", "Forget this TV"}, (d, w) -> {
                    if (w == 0) PairingFlow.start(this, t.name, t.host, () -> openRemote(t));
                    else { store.remove(t.host); refresh(); }
                }).show();
    }

    private void addByIp() {
        EditText et = new EditText(this);
        et.setHint("192.168.1.25");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("TV IP address")
                .setMessage("On the TV: Settings → Network & Internet → your Wi-Fi → IP address")
                .setView(box)
                .setPositiveButton("Connect", (d, w) -> {
                    String ip = et.getText().toString().trim();
                    if (ip.isEmpty()) return;
                    TvStore.Tv t = new TvStore.Tv("TV " + ip, ip);
                    PairingFlow.start(this, t.name, t.host, () -> { t.paired = true; refresh(); openRemote(t); });
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---------- Wi-Fi discovery (mDNS) ----------

    private void startDiscovery() {
        if (discovery != null) return;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            lock = wm.createMulticastLock("pure-remote");
            lock.setReferenceCounted(false);
            lock.acquire();
        } catch (Exception ignored) {}
        status.setText(R.string.searching);
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String s, int e) { ui.post(() -> status.setText(R.string.search_failed)); }
            @Override public void onStopDiscoveryFailed(String s, int e) {}
            @Override public void onDiscoveryStarted(String s) {}
            @Override public void onDiscoveryStopped(String s) {}
            @Override public void onServiceFound(NsdServiceInfo info) {
                ui.post(() -> { resolveQueue.add(info); resolveNext(); });
            }
            @Override public void onServiceLost(NsdServiceInfo info) {
                ui.post(() -> {
                    for (int i = found.size() - 1; i >= 0; i--) if (found.get(i).name.equals(info.getServiceName())) found.remove(i);
                    refresh();
                });
            }
        };
        try {
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            discovery = null;
            status.setText(R.string.search_failed);
        }
        ui.postDelayed(() -> { if (discovery != null && found.isEmpty()) status.setText(R.string.not_found_yet); }, 8000);
    }

    private void stopDiscovery() {
        if (discovery != null) {
            try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {}
            discovery = null;
        }
        resolveQueue.clear();
        resolving = false;
        if (lock != null) try { lock.release(); } catch (Exception ignored) {}
    }

    private void resolveNext() {
        if (resolving || resolveQueue.isEmpty() || discovery == null) return;
        resolving = true;
        NsdServiceInfo info = resolveQueue.poll();
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo si, int err) {
                    ui.post(() -> {
                        resolving = false;
                        if (err == NsdManager.FAILURE_ALREADY_ACTIVE) ui.postDelayed(() -> { resolveQueue.add(si); resolveNext(); }, 300);
                        else resolveNext();
                    });
                }
                @Override public void onServiceResolved(NsdServiceInfo si) {
                    String host = pickHost(si);
                    ui.post(() -> {
                        resolving = false;
                        if (host != null) onFound(si.getServiceName(), host);
                        resolveNext();
                    });
                }
            });
        } catch (Exception e) {
            resolving = false;
        }
    }

    private static String pickHost(NsdServiceInfo si) {
        List<InetAddress> addrs = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 34) addrs.addAll(si.getHostAddresses());
        else if (si.getHost() != null) addrs.add(si.getHost());
        for (InetAddress a : addrs) if (a instanceof Inet4Address) return a.getHostAddress();
        return addrs.isEmpty() ? null : addrs.get(0).getHostAddress();
    }

    private void onFound(String name, String host) {
        for (TvStore.Tv f : found) if (f.host.equals(host)) return;
        store.updateHost(name, host);
        found.add(new TvStore.Tv(name, host));
        status.setText(getString(R.string.found_n, found.size()));
        refresh();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_tv, parent, false);
            TvStore.Tv t = items.get(i);
            ((TextView) v.findViewById(R.id.name)).setText(t.name);
            String sub = t.ir != null ? "IR remote · code " + (t.ir.index + 1)
                    : t.host + "  ·  " + (t.paired ? (t.online ? "Paired · Online" : "Paired") : "Tap to pair");
            ((TextView) v.findViewById(R.id.sub)).setText(sub);
            v.findViewById(R.id.dot).setBackgroundResource(t.online || !t.paired ? R.drawable.dot_on : R.drawable.dot_off);
            return v;
        }
    }
}
