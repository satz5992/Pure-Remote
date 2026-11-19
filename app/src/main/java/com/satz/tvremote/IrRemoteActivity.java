package com.satz.tvremote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/** Same remote layout, but buttons fire IR codes from the phone's IR blaster. */
public class IrRemoteActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private IrDb.Remote remote;
    private boolean playing;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_remote);
        String brand = getIntent().getStringExtra("brand");
        int index = getIntent().getIntExtra("index", 0);
        ((TextView) findViewById(R.id.tv_name)).setText(("Xiaomi".equals(brand) ? "Xiaomi / Mi" : brand) + " TV");
        TextView status = findViewById(R.id.tv_status);
        status.setText("IR remote · code " + (index + 1) + " · point phone at TV");
        findViewById(R.id.tv_dot).setBackgroundResource(R.drawable.dot_on);
        findViewById(R.id.btn_devices).setOnClickListener(v ->
                startActivity(new Intent(this, MainActivity.class).putExtra("pick", true)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)));

        if (!Ir.available(this)) {
            status.setText("This phone has no IR blaster");
        }

        new Thread(() -> {
            try {
                IrDb.Remote r = IrDb.remote(this, brand, index);
                ui.post(() -> { remote = r; bind(); });
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Couldn't load codes: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void bind() {
        if (remote == null) return;
        tap(R.id.btn_power, "power");
        tap(R.id.btn_back, "back", "exit");
        tap(R.id.btn_home, "home", "menu");
        tap(R.id.btn_input, "input");
        tap(R.id.btn_settings, "settings", "menu");
        tap(R.id.btn_ok, "ok");
        tap(R.id.btn_mute, "mute");
        tap(R.id.btn_rew, "rew");
        tap(R.id.btn_ff, "ff");
        hold(R.id.btn_up, "up");
        hold(R.id.btn_down, "down");
        hold(R.id.btn_left, "left");
        hold(R.id.btn_right, "right");
        hold(R.id.btn_vol_up, "vol_up");
        hold(R.id.btn_vol_down, "vol_down");
        hold(R.id.btn_ch_up, "ch_up");
        hold(R.id.btn_ch_down, "ch_down");
        tap(R.id.app_youtube, "youtube");
        tap(R.id.app_netflix, "netflix");
        tap(R.id.app_prime, "prime");
        findViewById(R.id.app_hotstar).setVisibility(View.GONE);

        // Play/Pause: single toggle code if available, else alternate play / pause.
        View play = findViewById(R.id.btn_play);
        boolean hasPlay = remote.has("play_pause") || remote.has("play") || remote.has("pause");
        play.setAlpha(hasPlay ? 1f : 0.25f);
        play.setOnClickListener(v -> {
            buzz(v);
            if (remote.has("play_pause")) send("play_pause");
            else { playing = !playing; send(playing ? "play" : "pause", playing ? "pause" : "play"); }
        });

        // Keyboard isn't possible over IR - replace with Info / Guide.
        View kb = findViewById(R.id.btn_keyboard);
        if (remote.has("info") || remote.has("guide")) {
            ((android.widget.ImageButton) kb).setImageResource(R.drawable.ic_info);
            kb.setContentDescription("Info");
            tap(R.id.btn_keyboard, "info", "guide");
        } else kb.setVisibility(View.INVISIBLE);

        View pad = findViewById(R.id.numpad);
        View numBtn = findViewById(R.id.btn_numbers);
        numBtn.setAlpha(remote.has("1") ? 1f : 0.25f);
        numBtn.setOnClickListener(v -> {
            buzz(v);
            if (remote.has("1")) pad.setVisibility(pad.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
        int[] nums = {R.id.n0, R.id.n1, R.id.n2, R.id.n3, R.id.n4, R.id.n5, R.id.n6, R.id.n7, R.id.n8, R.id.n9};
        for (int i = 0; i < 10; i++) tap(nums[i], String.valueOf(i));
        tap(R.id.n_del, "back", "exit");
        tap(R.id.n_enter, "ok");
        ((TextView) findViewById(R.id.n_del)).setText("Back");
        ((TextView) findViewById(R.id.n_enter)).setText("OK");
    }

    private void buzz(View v) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); }

    /** Sends the first key the code set has. */
    private boolean send(String... keys) {
        if (remote == null) return false;
        for (String k : keys) {
            IrDb.Code c = remote.get(k);
            if (c != null) { Ir.send(this, c); return true; }
        }
        return false;
    }

    private boolean hasAny(String... keys) {
        for (String k : keys) if (remote.has(k)) return true;
        return false;
    }

    private void tap(int id, String... keys) {
        View v = findViewById(id);
        boolean ok = hasAny(keys);
        v.setAlpha(ok ? 1f : 0.25f);
        v.setOnClickListener(x -> {
            buzz(x);
            if (!send(keys)) Toast.makeText(this, "This TV's code set has no such button", Toast.LENGTH_SHORT).show();
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void hold(int id, String key) {
        View btn = findViewById(id);
        btn.setAlpha(remote.has(key) ? 1f : 0.25f);
        Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> {
            send(key);
            ui.postDelayed(repeat[0], 140);
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

    /** Phone volume buttons send TV volume over IR. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (remote != null && (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) send(k == KeyEvent.KEYCODE_VOLUME_UP ? "vol_up" : "vol_down");
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacksAndMessages(null);
        super.onPause();
    }
}
