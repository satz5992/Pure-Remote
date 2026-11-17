package com.satz.tvremote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

/** The remote itself. */
public class RemoteActivity extends Activity implements RemoteClient.Listener {
    // Android key codes (same values the TV understands)
    private static final int K_BACK = 4, K_HOME = 3, K_POWER = 26, K_UP = 19, K_DOWN = 20, K_LEFT = 21, K_RIGHT = 22,
            K_OK = 23, K_VOL_UP = 24, K_VOL_DOWN = 25, K_MUTE = 164, K_PLAY_PAUSE = 85, K_REW = 89, K_FF = 90,
            K_CH_UP = 166, K_CH_DOWN = 167, K_INPUT = 178, K_SETTINGS = 176, K_DEL = 67, K_ENTER = 66, K_0 = 7;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private RemoteClient client;
    private String host, name;
    private TextView status;
    private String volText = "", appText = "";
    private boolean ready, askedPair;
    private int retries;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_remote);
        host = getIntent().getStringExtra("host");
        name = getIntent().getStringExtra("name");
        ((TextView) findViewById(R.id.tv_name)).setText(name);
        status = findViewById(R.id.tv_status);

        findViewById(R.id.btn_devices).setOnClickListener(v ->
                startActivity(new Intent(this, MainActivity.class).putExtra("pick", true)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)));

        tap(R.id.btn_power, K_POWER);
        tap(R.id.btn_back, K_BACK);
        tap(R.id.btn_home, K_HOME);
        tap(R.id.btn_input, K_INPUT);
        tap(R.id.btn_settings, K_SETTINGS);
        tap(R.id.btn_ok, K_OK);
        tap(R.id.btn_mute, K_MUTE);
        tap(R.id.btn_play, K_PLAY_PAUSE);
        tap(R.id.btn_rew, K_REW);
        tap(R.id.btn_ff, K_FF);
        hold(R.id.btn_up, K_UP);
        hold(R.id.btn_down, K_DOWN);
        hold(R.id.btn_left, K_LEFT);
        hold(R.id.btn_right, K_RIGHT);
        hold(R.id.btn_vol_up, K_VOL_UP);
        hold(R.id.btn_vol_down, K_VOL_DOWN);
        hold(R.id.btn_ch_up, K_CH_UP);
        hold(R.id.btn_ch_down, K_CH_DOWN);

        app(R.id.app_youtube, "https://www.youtube.com");
        app(R.id.app_netflix, "https://www.netflix.com/title");
        app(R.id.app_prime, "https://app.primevideo.com");
        app(R.id.app_hotstar, "https://www.hotstar.com");

        findViewById(R.id.btn_keyboard).setOnClickListener(v -> { buzz(v); keyboard(); });
        View pad = findViewById(R.id.numpad);
        findViewById(R.id.btn_numbers).setOnClickListener(v -> {
            buzz(v);
            pad.setVisibility(pad.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
        int[] nums = {R.id.n0, R.id.n1, R.id.n2, R.id.n3, R.id.n4, R.id.n5, R.id.n6, R.id.n7, R.id.n8, R.id.n9};
        for (int i = 0; i < 10; i++) tap(nums[i], K_0 + i);
        tap(R.id.n_del, K_DEL);
        tap(R.id.n_enter, K_ENTER);
    }

    @Override
    protected void onStart() {
        super.onStart();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        connect();
    }

    @Override
    protected void onStop() {
        disconnect();
        super.onStop();
    }

    private void connect() {
        disconnect();
        ready = false;
        setStatus("Connecting…");
        client = new RemoteClient(host, Build.MODEL, Build.MANUFACTURER, this);
        new Thread(() -> {
            try {
                Tls.init(getFilesDir());
                client.connect();
            } catch (Exception e) {
                onDisconnected(e, true);
            }
        }).start();
    }

    private void disconnect() {
        if (client != null) { client.close(); client = null; }
    }

    // ---------- buttons ----------

    private void send(int key) {
        RemoteClient c = client;
        if (c != null && ready) c.key(key);
        else if (!ready) setStatus("Not connected – tap here to retry");
    }

    private void buzz(View v) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); }

    private void tap(int id, int key) {
        findViewById(id).setOnClickListener(v -> { buzz(v); send(key); });
    }

    private void app(int id, String link) {
        findViewById(id).setOnClickListener(v -> {
            buzz(v);
            RemoteClient c = client;
            if (c != null && ready) c.launch(link);
        });
    }

    /** Press-and-hold repeats (arrows, volume, channel). */
    @SuppressLint("ClickableViewAccessibility")
    private void hold(int id, int key) {
        View btn = findViewById(id);
        Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> {
            send(key);
            btn.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            ui.postDelayed(repeat[0], 110);
        };
        btn.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    buzz(v);
                    send(key);
                    ui.postDelayed(repeat[0], 450);
                    return true;
                case MotionEvent.ACTION_UP:
                    v.performClick();
                    // fall through
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    ui.removeCallbacks(repeat[0]);
                    return true;
            }
            return false;
        });
        btn.setOnClickListener(v -> {});
    }

    /** Phone volume buttons control the TV volume. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if ((k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) && ready) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) send(k == KeyEvent.KEYCODE_VOLUME_UP ? K_VOL_UP : K_VOL_DOWN);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void keyboard() {
        EditText et = new EditText(this);
        et.setHint("Type to send to TV");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setImeOptions(EditorInfo.IME_ACTION_SEND);
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Keyboard")
                .setMessage("Select a search/text box on the TV first.")
                .setView(box)
                .setPositiveButton("Send", (dd, w) -> sendText(et.getText().toString(), false))
                .setNeutralButton("Send + Enter", (dd, w) -> sendText(et.getText().toString(), true))
                .setNegativeButton("Cancel", null)
                .create();
        et.setOnEditorActionListener((v, a, ev) -> { sendText(et.getText().toString(), true); d.dismiss(); return true; });
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        et.requestFocus();
    }

    private void sendText(String t, boolean enter) {
        RemoteClient c = client;
        if (c == null || !ready) return;
        c.text(t);
        if (enter) ui.postDelayed(() -> { RemoteClient cc = client; if (cc != null) cc.key(K_ENTER); }, 250);
    }

    // ---------- connection callbacks (background thread) ----------

    @Override public void onReady(boolean tvOn) {
        ui.post(() -> {
            ready = true;
            retries = 0;
            setStatus(tvOn ? "Connected" : "Connected · TV in standby");
        });
    }

    @Override public void onVolume(int level, int max, boolean muted) {
        ui.post(() -> {
            volText = muted ? "Muted" : (max > 0 ? "Vol " + level + "/" + max : "");
            setStatus(null);
        });
    }

    @Override public void onApp(String pkg) {
        ui.post(() -> { appText = pkg == null ? "" : pkg; setStatus(null); });
    }

    @Override public void onDisconnected(Exception e, boolean neverStarted) {
        ui.post(() -> {
            if (isFinishing() || client == null) return;
            ready = false;
            String msg = e == null ? "" : String.valueOf(e.getMessage()).toLowerCase();
            boolean refused = msg.contains("refused") || msg.contains("timed out") || msg.contains("unreachable") || msg.contains("timeout");
            if (neverStarted && !refused && !askedPair) {
                // TLS accepted the socket but the TV dropped us: it doesn't know this phone.
                askedPair = true;
                new AlertDialog.Builder(this)
                        .setTitle("Pair with " + name + "?")
                        .setMessage("The TV didn't accept this phone. It may have been reset, or the pairing was removed. Pair again?")
                        .setPositiveButton("Pair", (d, w) -> PairingFlow.start(this, name, host, () -> { askedPair = false; connect(); }))
                        .setNegativeButton("Cancel", null).show();
                setStatus("Not paired");
                return;
            }
            if (retries++ < 3) {
                setStatus("Reconnecting…");
                ui.postDelayed(this::connect, 1500L * retries);
            } else {
                setStatus("Can't reach TV – tap here to retry");
            }
        });
    }

    private String base = "";

    private void setStatus(String s) {
        if (s != null) base = s;
        StringBuilder sb = new StringBuilder(base);
        if (ready && !volText.isEmpty()) sb.append("  ·  ").append(volText);
        status.setText(sb);
        if (ready) status.setOnClickListener(null);
        else status.setOnClickListener(v -> { retries = 0; askedPair = false; connect(); });
        findViewById(R.id.tv_dot).setBackgroundResource(ready ? R.drawable.dot_on : R.drawable.dot_off);
    }
}
