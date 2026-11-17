package com.satz.tvremote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocket;

/** Remote-control connection (port 6466) to a paired Android / Google TV. */
public final class RemoteClient {
    public static final int PORT = 6466;
    public static final int SHORT = 3, START_LONG = 1, END_LONG = 2;

    // Features: PING | KEY | IME | POWER | VOLUME | APP_LINK
    private static final int FEATURES = 1 | 2 | 4 | 32 | 64 | 512;

    public interface Listener {
        void onReady(boolean tvOn);
        void onDisconnected(Exception e, boolean neverStarted);
        void onVolume(int level, int max, boolean muted);
        void onApp(String pkg);
    }

    private final String host;
    private final String model, vendor;
    private final Listener listener;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile SSLSocket sock;
    private volatile OutputStream out;
    private volatile boolean closed, started;
    private int features = FEATURES;
    private long imeCounter, fieldCounter;
    private boolean haveIme, haveField;

    public RemoteClient(String host, String model, String vendor, Listener l) {
        this.host = host; this.model = model; this.vendor = vendor; this.listener = l;
    }

    public void connect() {
        Thread t = new Thread(this::run, "tv-remote-reader");
        t.setDaemon(true);
        t.start();
    }

    public boolean isStarted() { return started && !closed; }

    private void run() {
        try {
            sock = Tls.connect(host, PORT, 6000);
            sock.setSoTimeout(40000); // TV pings every few seconds
            InputStream in = sock.getInputStream();
            out = sock.getOutputStream();
            while (!closed) handle(Proto.M.parse(Proto.readFrame(in)));
        } catch (Exception e) {
            if (!closed) {
                closed = true;
                listener.onDisconnected(e, !started);
            }
        } finally {
            try { if (sock != null) sock.close(); } catch (IOException ignored) {}
        }
    }

    private void handle(Proto.M m) {
        if (m.has(1)) {
            Proto.M cfg = m.m(1);
            if (cfg != null && cfg.has(1)) features = (int) (FEATURES & cfg.i(1, FEATURES));
            send(new Proto.W().m(1, new Proto.W().v(1, features).m(2, new Proto.W()
                    .s(1, model).s(2, vendor).v(3, 1).s(4, "1").s(5, "atvremote").s(6, "1.0.0"))));
        } else if (m.has(2)) {
            send(new Proto.W().m(2, new Proto.W().v(1, features)));
        } else if (m.has(8)) {
            Proto.M p = m.m(8);
            send(new Proto.W().m(9, new Proto.W().v(1, p == null ? 0 : p.i(1, 0))));
        } else if (m.has(20)) {
            Proto.M k = m.m(20);
            if (k != null) {
                Proto.M app = k.m(1);
                Proto.M field = k.m(2);
                if (app != null) {
                    imeCounter = app.i(1, 0); haveIme = imeCounter != 0;
                    String pkg = app.s(12);
                    if (pkg != null) listener.onApp(pkg);
                }
                if (field != null) { fieldCounter = field.i(1, 0); haveField = fieldCounter != 0; }
            }
        } else if (m.has(22)) {
            Proto.M r = m.m(22);
            Proto.M st = r == null ? null : r.m(2);
            if (st != null) { fieldCounter = st.i(1, 0); haveField = true; }
        } else if (m.has(21)) {
            Proto.M b = m.m(21);
            if (b != null) {
                if (!haveIme) imeCounter = b.i(1, 0);
                if (!haveField) fieldCounter = b.i(2, 0);
            }
        } else if (m.has(50)) {
            Proto.M v = m.m(50);
            if (v != null) listener.onVolume((int) v.i(7, 0), (int) v.i(6, 0), v.i(8, 0) != 0);
        } else if (m.has(40)) {
            Proto.M s = m.m(40);
            started = true;
            listener.onReady(s != null && s.i(1, 0) != 0);
        }
    }

    public void key(int keyCode) { key(keyCode, SHORT); }

    public void key(int keyCode, int direction) {
        send(new Proto.W().m(10, new Proto.W().v(1, keyCode).v(2, direction)));
    }

    public void launch(String appLink) {
        send(new Proto.W().m(90, new Proto.W().s(1, appLink)));
    }

    public void text(String text) {
        if (text == null || text.isEmpty()) return;
        int n = text.length();
        send(new Proto.W().m(21, new Proto.W().v(1, imeCounter).v(2, fieldCounter)
                .m(3, new Proto.W().v(1, 1).m(2, new Proto.W().v(1, n).v(2, n).s(3, text)))));
    }

    private void send(Proto.W msg) {
        final byte[] data = msg.bytes();
        if (closed) return;
        try {
            writer.execute(() -> {
                OutputStream o = out;
                if (o == null || closed) return;
                try { Proto.writeFrame(o, data); } catch (IOException ignored) { /* reader thread reports */ }
            });
        } catch (Exception ignored) {}
    }

    public void close() {
        closed = true;
        writer.shutdownNow();
        new Thread(() -> { try { if (sock != null) sock.close(); } catch (IOException ignored) {} }).start();
    }
}
