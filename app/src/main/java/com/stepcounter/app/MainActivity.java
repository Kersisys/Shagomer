package com.stepcounter.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** Один экран: два счётчика за сегодня, кнопка старта/остановки и история за 7 дней. */
public class MainActivity extends Activity {
    private Store store;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView ownView, hwView, diffView, historyView;
    private Button toggle;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        buildUi();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView text(float sp, boolean bold, int gravity) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setGravity(gravity);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(24));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = text(16, false, Gravity.CENTER);
        title.setText("Шагов сегодня (мой алгоритм)");
        root.addView(title);

        ownView = text(64, true, Gravity.CENTER);
        root.addView(ownView);

        TextView t2 = text(14, false, Gravity.CENTER);
        t2.setText("Шагомер телефона (для сравнения)");
        t2.setPadding(0, dp(24), 0, 0);
        root.addView(t2);

        hwView = text(32, true, Gravity.CENTER);
        root.addView(hwView);

        diffView = text(14, false, Gravity.CENTER);
        diffView.setPadding(0, dp(8), 0, dp(24));
        root.addView(diffView);

        toggle = new Button(this);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onToggle(); }
        });
        root.addView(toggle);

        Button battery = new Button(this);
        battery.setText("Разрешить работу в фоне (батарея)");
        battery.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openBatterySettings(); }
        });
        root.addView(battery);

        TextView hist = text(14, true, Gravity.START);
        hist.setText("Последние 7 дней (мой / телефона)");
        hist.setPadding(0, dp(24), 0, dp(4));
        root.addView(hist, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        historyView = text(14, false, Gravity.START);
        historyView.setTypeface(Typeface.MONOSPACE);
        root.addView(historyView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(refresh);
        // Если пользователь включал счёт, но систему перезапустила службу, поднимаем её снова
        if (store.enabled() && hasPermissions()) startCounting();
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }

    private boolean hasPermissions() {
        return Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    private void onToggle() {
        if (store.enabled()) {
            store.setEnabled(false);
            stopService(new Intent(this, StepService.class));
        } else {
            requestNeeded();
        }
        render();
    }

    private void requestNeeded() {
        if (Build.VERSION.SDK_INT >= 29 && !hasPermissions()) {
            String[] perms = Build.VERSION.SDK_INT >= 33
                    ? new String[]{Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.ACTIVITY_RECOGNITION};
            requestPermissions(perms, 1);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            return;
        }
        store.setEnabled(true);
        startCounting();
        render();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        // Уведомления не обязательны для работы, а вот распознавание активности нужно
        if (hasPermissions()) {
            store.setEnabled(true);
            startCounting();
        }
        render();
    }

    private void startCounting() {
        try {
            startForegroundService(new Intent(this, StepService.class));
        } catch (Exception ignored) { }
    }

    private void openBatterySettings() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        try {
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } else {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void render() {
        String day = Store.today();
        int own = store.own(day);
        int hw = store.hw(day);
        ownView.setText(String.valueOf(own));
        hwView.setText(String.valueOf(hw));
        toggle.setText(store.enabled() ? "Остановить" : "Запустить счёт");

        if (!store.enabled()) {
            diffView.setText("Счёт выключен");
        } else if (hw > 20) {
            diffView.setText(String.format(Locale.US, "Разница с телефоном: %+.1f%%", 100.0 * (own - hw) / hw));
        } else {
            diffView.setText("Идёт счёт");
        }

        StringBuilder sb = new StringBuilder();
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat out = new SimpleDateFormat("dd.MM  ", Locale.US);
        Calendar c = Calendar.getInstance();
        for (int i = 0; i < 7; i++) {
            String d = in.format(c.getTime());
            sb.append(out.format(c.getTime()))
                    .append(String.format(Locale.US, "%6d / %d", store.own(d), store.hw(d)))
                    .append('\n');
            c.add(Calendar.DAY_OF_MONTH, -1);
        }
        historyView.setText(sb.toString());
    }
}
