package com.satz.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

/** UI for pairing: connect -> TV shows code -> user types code -> done. */
@SuppressWarnings("deprecation")
public final class PairingFlow {
    private PairingFlow() {}

    public static void start(Activity a, String name, String host, Runnable onPaired) {
        Handler ui = new Handler(Looper.getMainLooper());
        ProgressDialog pd = new ProgressDialog(a);
        pd.setMessage("Connecting to " + name + "…");
        pd.setCancelable(false);
        pd.show();
        PairingClient pc = new PairingClient();
        new Thread(() -> {
            try {
                Tls.init(a.getFilesDir());
                pc.start(host, "Pure Remote (" + Build.MODEL + ")");
                ui.post(() -> { dismiss(a, pd); askCode(a, name, host, pc, onPaired, ui); });
            } catch (Exception e) {
                pc.close();
                ui.post(() -> {
                    dismiss(a, pd);
                    if (!a.isFinishing()) new AlertDialog.Builder(a)
                            .setTitle("Couldn't reach the TV")
                            .setMessage("Make sure the TV is ON and on the same Wi-Fi as this phone.\n\n(" + e.getMessage() + ")")
                            .setPositiveButton("Retry", (d, w) -> start(a, name, host, onPaired))
                            .setNegativeButton("Cancel", null).show();
                });
            }
        }).start();
    }

    private static void dismiss(Activity a, ProgressDialog pd) {
        try { if (!a.isFinishing()) pd.dismiss(); } catch (Exception ignored) {}
    }

    private static void askCode(Activity a, String name, String host, PairingClient pc, Runnable onPaired, Handler ui) {
        if (a.isFinishing()) { pc.close(); return; }
        EditText et = new EditText(a);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6), new InputFilter.AllCaps()});
        et.setHint("e.g. 4F2A9C");
        et.setGravity(Gravity.CENTER);
        et.setTextSize(28);
        et.setLetterSpacing(0.3f);
        FrameLayout box = new FrameLayout(a);
        int pad = (int) (24 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("Enter the code shown on your TV")
                .setView(box)
                .setPositiveButton("Pair", null)
                .setNegativeButton("Cancel", (d, w) -> pc.close())
                .setCancelable(false)
                .create();
        dlg.setOnShowListener(d -> {
            et.requestFocus();
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String code = et.getText().toString().trim();
                if (!code.matches("[0-9A-Fa-f]{6}")) {
                    et.setError("6 characters (0-9, A-F)");
                    return;
                }
                v.setEnabled(false);
                new Thread(() -> {
                    try {
                        pc.finish(code);
                        pc.close();
                        ui.post(() -> {
                            new TvStore(a).add(name, host);
                            try { dlg.dismiss(); } catch (Exception ignored) {}
                            Toast.makeText(a, "Paired with " + name, Toast.LENGTH_SHORT).show();
                            onPaired.run();
                        });
                    } catch (IllegalArgumentException e) {
                        // Wrong code detected locally - connection still open, let user retry.
                        ui.post(() -> { v.setEnabled(true); et.setError(e.getMessage()); });
                    } catch (Exception e) {
                        pc.close();
                        ui.post(() -> {
                            try { dlg.dismiss(); } catch (Exception ignored) {}
                            if (!a.isFinishing()) new AlertDialog.Builder(a)
                                    .setTitle("Pairing failed")
                                    .setMessage(e.getMessage())
                                    .setPositiveButton("Try again", (dd, w) -> start(a, name, host, onPaired))
                                    .setNegativeButton("Cancel", null).show();
                        });
                    }
                }).start();
            });
        });
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
    }
}
