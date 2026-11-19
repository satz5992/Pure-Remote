package com.satz.tvremote;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Step 1: choose brand. Step 2: try code sets until the TV responds. */
public class IrSetupActivity extends Activity {
    private static final String AUTO = "\u0000auto";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> rows = new ArrayList<>();   // brand names (AUTO = find-my-TV)
    private final List<String> counts = new ArrayList<>();
    private List<IrDb.Brand> all = new ArrayList<>();
    private BrandAdapter adapter;

    private List<IrDb.Remote> candidates;
    private String brandLabel;
    private boolean autoMode;  // "don't know my brand"
    private int pos;
    private boolean autoRunning;
    private View brandPanel, testPanel;
    private TextView tBrand, tCode, tHint, tAuto;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_ir_setup);
        if (!Ir.available(this)) {
            Toast.makeText(this, "This phone has no IR blaster. Use the Wi-Fi remote instead.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        brandPanel = findViewById(R.id.brand_panel);
        testPanel = findViewById(R.id.test_panel);
        tBrand = findViewById(R.id.t_brand);
        tCode = findViewById(R.id.t_code);
        tHint = findViewById(R.id.t_hint);
        tAuto = findViewById(R.id.t_auto);

        ListView list = findViewById(R.id.brands);
        adapter = new BrandAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, i, id) -> pick(rows.get(i)));
        EditText search = findViewById(R.id.search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable s) { filter(s.toString()); }
        });

        findViewById(R.id.t_power).setOnClickListener(v -> test(v, "power"));
        findViewById(R.id.t_vol).setOnClickListener(v -> test(v, "vol_up"));
        findViewById(R.id.t_mute).setOnClickListener(v -> test(v, "mute"));
        findViewById(R.id.t_next).setOnClickListener(v -> { stopAuto(); move(1, true); });
        findViewById(R.id.t_prev).setOnClickListener(v -> { stopAuto(); move(-1, true); });
        findViewById(R.id.t_works).setOnClickListener(v -> { stopAuto(); save(); });
        tAuto.setOnClickListener(v -> { if (autoRunning) stopAuto(); else startAuto(); });

        new Thread(() -> {
            try {
                List<IrDb.Brand> l = IrDb.load(this);
                ui.post(() -> { all = l; filter(""); });
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Couldn't load IR codes: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void filter(String q) {
        q = q.trim().toLowerCase(Locale.ROOT);
        rows.clear();
        counts.clear();
        if (q.isEmpty()) { rows.add(AUTO); counts.add(""); }
        List<IrDb.Brand> ordered = new ArrayList<>();
        if (q.isEmpty()) for (String n : IrDb.POPULAR) for (IrDb.Brand br : all) if (br.name.equalsIgnoreCase(n)) ordered.add(br);
        for (IrDb.Brand br : all) if (!ordered.contains(br)) ordered.add(br);
        for (IrDb.Brand br : ordered) {
            if (!q.isEmpty() && !br.name.toLowerCase(Locale.ROOT).contains(q)) continue;
            rows.add(br.name);
            counts.add(br.remotes.size() + (br.remotes.size() == 1 ? " code set" : " code sets"));
        }
        if (!q.isEmpty() && rows.isEmpty()) { rows.add(AUTO); counts.add(""); }
        adapter.notifyDataSetChanged();
    }

    private void pick(String name) {
        try {
            if (AUTO.equals(name)) {
                autoMode = true;
                brandLabel = "Find my TV";
                candidates = IrDb.allWithUniquePower(this);
            } else {
                autoMode = false;
                brandLabel = name.equals("Xiaomi") ? "Xiaomi / Mi" : name;
                candidates = IrDb.brand(this, name).remotes;
            }
        } catch (Exception e) {
            return;
        }
        pos = 0;
        brandPanel.setVisibility(View.GONE);
        testPanel.setVisibility(View.VISIBLE);
        show();
    }

    private void show() {
        IrDb.Remote r = candidates.get(pos);
        tBrand.setText(brandLabel);
        tCode.setText(autoMode
                ? "Code " + (pos + 1) + " of " + candidates.size() + "  ·  " + r.brand
                : "Code " + (pos + 1) + " of " + candidates.size());
        tHint.setText(autoMode
                ? "Point the phone at the TV and tap Power.\nIf the TV switches off (or on), tap the blue button."
                : "Point the phone at the TV and tap Power or Vol +.\nIf the TV responds, tap the blue button. If not, try the next code.");
        findViewById(R.id.t_vol).setAlpha(r.has("vol_up") ? 1f : 0.3f);
        findViewById(R.id.t_mute).setAlpha(r.has("mute") ? 1f : 0.3f);
        findViewById(R.id.t_prev).setAlpha(pos > 0 ? 1f : 0.4f);
    }

    private void test(View v, String key) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        IrDb.Code c = candidates.get(pos).get(key);
        if (c == null) { Toast.makeText(this, "This code set has no such button – try Power", Toast.LENGTH_SHORT).show(); return; }
        Ir.send(this, c);
    }

    private boolean move(int d, boolean wrapToast) {
        int n = pos + d;
        if (n < 0) return false;
        if (n >= candidates.size()) {
            if (wrapToast) Toast.makeText(this, "That was the last code. Starting again from code 1.", Toast.LENGTH_LONG).show();
            n = 0;
        }
        pos = n;
        show();
        return true;
    }

    private final Runnable autoStep = new Runnable() {
        @Override public void run() {
            if (!autoRunning) return;
            Ir.send(IrSetupActivity.this, candidates.get(pos).get("power") != null
                    ? candidates.get(pos).get("power") : candidates.get(pos).get("vol_up"));
            ui.postDelayed(() -> {
                if (!autoRunning) return;
                if (pos + 1 >= candidates.size()) { stopAuto(); Toast.makeText(IrSetupActivity.this, "Tried every code.", Toast.LENGTH_LONG).show(); return; }
                move(1, false);
                ui.postDelayed(autoStep, 300);
            }, 2200);
        }
    };

    private void startAuto() {
        autoRunning = true;
        autoUsed = true;
        confirmed = false;
        tAuto.setText("■  Stop auto-try");
        tHint.setText("Sending Power with each code every 2 seconds.\nThe moment the TV reacts, tap the blue button – then check it with Power.");
        ui.post(autoStep);
    }

    private void stopAuto() {
        if (!autoRunning) return;
        autoRunning = false;
        ui.removeCallbacksAndMessages(null);
        tAuto.setText("▶  Auto-try every code");
    }

    private void save() {
        if (autoUsed && !confirmed) {
            // During auto-try the reaction may come from the previous code. Let the user confirm.
            confirmed = true;
            tHint.setText("Check: tap Power now. If nothing happens, tap ◀ Previous and test again. Then tap the blue button.");
            return;
        }
        IrDb.Remote r = candidates.get(pos);
        new TvStore(this).addIr(r.brand, r.index);
        String last = "ir:" + r.brand + ":" + r.index;
        new TvStore(this).setLast(last);
        startActivity(new Intent(this, IrRemoteActivity.class).putExtra("brand", r.brand).putExtra("index", r.index));
        finish();
    }

    private boolean confirmed, autoUsed;

    @Override
    public void onBackPressed() {
        if (testPanel != null && testPanel.getVisibility() == View.VISIBLE) {
            stopAuto();
            confirmed = false;
            autoUsed = false;
            testPanel.setVisibility(View.GONE);
            brandPanel.setVisibility(View.VISIBLE);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        stopAuto();
        super.onPause();
    }

    private final class BrandAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(IrSetupActivity.this).inflate(R.layout.item_tv, parent, false);
            String n = rows.get(i);
            TextView name = v.findViewById(R.id.name), sub = v.findViewById(R.id.sub);
            if (AUTO.equals(n)) {
                name.setText("Don't know / brand not listed");
                sub.setText("Find my TV – tries every power code");
            } else {
                name.setText(n.equals("Xiaomi") ? "Xiaomi / Mi" : n);
                sub.setText(counts.get(i));
            }
            v.findViewById(R.id.dot).setVisibility(View.GONE);
            return v;
        }
    }
}
