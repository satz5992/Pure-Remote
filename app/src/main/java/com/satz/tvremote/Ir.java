package com.satz.tvremote;

import android.content.Context;
import android.hardware.ConsumerIrManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sends IR codes through the phone's built-in IR blaster, off the UI thread. */
public final class Ir {
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    private Ir() {}

    public static ConsumerIrManager manager(Context c) {
        return (ConsumerIrManager) c.getApplicationContext().getSystemService(Context.CONSUMER_IR_SERVICE);
    }

    public static boolean available(Context c) {
        try {
            ConsumerIrManager m = manager(c);
            return m != null && m.hasIrEmitter();
        } catch (Exception e) {
            return false;
        }
    }

    /** Transmit; if a previous code is still being sent, this press is dropped (keeps hold-to-repeat smooth). */
    public static void send(Context c, IrDb.Code code) {
        if (code == null) return;
        ConsumerIrManager m = manager(c);
        if (m == null || !BUSY.compareAndSet(false, true)) return;
        EXEC.execute(() -> {
            try { m.transmit(code.freq, code.pattern); }
            catch (Exception ignored) {}
            finally { BUSY.set(false); }
        });
    }
}
