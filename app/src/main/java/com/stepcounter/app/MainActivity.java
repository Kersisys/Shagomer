package com.stepcounter.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
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
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Главный экран. Сверху вниз: сколько прошёл, сколько не хватает, сколько денег за сегодня,
 * затем общий итог, сравнение двух счётчиков, кнопки и история по дням.
 */
public class MainActivity extends Activity {
    private static final int RED = 0xFFC62828;
    private static final int GREEN = 0xFF2E7D32;
    private static final int GREY = 0xFF616161;

    private Store store;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView stepsView, missingView, moneyView, totalView, compareView, historyView;
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

    private Button button(String label, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(label);
        bt.setOnClickListener(l);
        return bt;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(40), dp(24), dp(24));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView label = text(16, false, Gravity.CENTER);
        label.setText("Сегодня прошёл");
        root.addView(label);

        stepsView = text(64, true, Gravity.CENTER);
        root.addView(stepsView);

        missingView = text(22, true, Gravity.CENTER);
        root.addView(missingView);

        moneyView = text(22, true, Gravity.CENTER);
        moneyView.setPadding(0, dp(4), 0, dp(12));
        root.addView(moneyView);

        totalView = text(14, false, Gravity.CENTER);
        totalView.setTextColor(GREY);
        root.addView(totalView);

        compareView = text(12, false, Gravity.CENTER);
        compareView.setTextColor(GREY);
        compareView.setPadding(0, dp(8), 0, dp(16));
        root.addView(compareView);

        root.addView(button("Цель и штраф", new View.OnClickListener() {
            @Override public void onClick(View v) { showGoalDialog(); }
        }));
        toggle = button("", new View.OnClickListener() {
            @Override public void onClick(View v) { onToggle(); }
        });
        root.addView(toggle);
        root.addView(button("Разрешить работу в фоне (батарея)", new View.OnClickListener() {
            @Override public void onClick(View v) { openBatterySettings(); }
        }));

        TextView hist = text(14, true, Gravity.START);
        hist.setText("Прошлые дни");
        hist.setPadding(0, dp(24), 0, dp(4));
        root.addView(hist, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        historyView = text(14, false, Gravity.START);
        historyView.setTypeface(Typeface.MONOSPACE);
        root.addView(historyView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        Engine.settle(this); // подводим итоги прошедших дней, если ещё не подведены
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

    private void showGoalDialog() {
        final EditText goal = new EditText(this);
        goal.setInputType(InputType.TYPE_CLASS_NUMBER);
        goal.setHint("Шагов в день (не меньше " + Commitment.MIN_GOAL + ")");
        final EditText penalty = new EditText(this);
        penalty.setInputType(InputType.TYPE_CLASS_NUMBER);
        penalty.setHint("Штраф за пропущенный день, ₽");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(goal);
        box.addView(penalty);

        new AlertDialog.Builder(this)
                .setTitle("Обязательство")
                .setMessage("Ужесточить можно сразу. Ослабить цель или штраф можно только с задержкой "
                        + Commitment.LOOSEN_DELAY_DAYS + " дней. Новое обязательство начнётся завтра.")
                .setView(box)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            int g = Integer.parseInt(goal.getText().toString().trim());
                            long rub = Long.parseLong(penalty.getText().toString().trim());
                            Engine.changeCommitment(MainActivity.this, g, rub * 100);
                            render();
                        } catch (NumberFormatException e) {
                            Toast.makeText(MainActivity.this, "Введите числа", Toast.LENGTH_LONG).show();
                        } catch (IllegalArgumentException e) {
                            Toast.makeText(MainActivity.this, e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .show();
    }

    private void render() {
        Commitment c = store.commitment();
        Engine.Today t = Engine.today(this);
        LocalDate today = LocalDate.now();

        stepsView.setText(Fmt.num(t.steps));

        // 1) сколько не хватает, 2) сколько денег за сегодня
        if (store.tampered()) {
            missingView.setText("Журнал повреждён");
            missingView.setTextColor(RED);
            moneyView.setText("Расчёт штрафов остановлен");
            moneyView.setTextColor(RED);
        } else if (c == null) {
            missingView.setText("Цель не задана");
            missingView.setTextColor(GREY);
            moneyView.setText("");
        } else if (!t.active) {
            missingView.setText("Начало: " + c.start);
            missingView.setTextColor(GREY);
            moneyView.setText("");
        } else if (t.missing > 0) {
            missingView.setText("Не хватает: " + Fmt.num(t.missing) + " из " + Fmt.num(t.goal));
            missingView.setTextColor(RED);
            moneyView.setText("Сегодня: " + Fmt.penalty(t.penaltyMinor));
            moneyView.setTextColor(RED);
        } else {
            missingView.setText("Цель выполнена (" + Fmt.num(t.goal) + ")");
            missingView.setTextColor(GREEN);
            moneyView.setText("Сегодня: 0 ₽");
            moneyView.setTextColor(GREEN);
        }

        // общий итог
        StringBuilder total = new StringBuilder();
        if (c != null) {
            Settler.Summary s = Engine.summary(this);
            if (s != null) {
                total.append("Всего штрафов: ").append(Fmt.penalty(s.transferredMinor + s.pendingMinor))
                        .append("  (выполнено ").append(s.metDays).append(", пропущено ").append(s.missedDays).append(")");
            }
            if (t.active && t.missing > 0) total.append("\nИтог дня подводится в полночь");
            if (c.pendingFrom != null && today.isBefore(c.pendingFrom)) {
                total.append("\nС ").append(c.pendingFrom).append(": ").append(Fmt.num(c.pendingGoal))
                        .append(" шагов, штраф ").append(Fmt.penalty(c.pendingPenaltyMinor));
            }
        }
        totalView.setText(total.toString());

        // сравнение двух счётчиков (для проверки точности моего алгоритма)
        String day = Store.today();
        int own = store.own(day);
        StringBuilder cmp = new StringBuilder("Мой алгоритм: ").append(Fmt.num(own));
        if (store.hwSeen()) {
            int hw = store.hw(day);
            cmp.append("  ·  Шагомер телефона: ").append(Fmt.num(hw));
            if (hw > 20) cmp.append(String.format(Locale.US, "  (%+.1f%%)", 100.0 * (own - hw) / hw));
        }
        compareView.setText(cmp.toString());

        toggle.setText(store.enabled() ? "Остановить счёт" : "Запустить счёт");

        // прошлые дни: шаги, и либо "ок", либо сколько не хватило и штраф
        List<Settler.Result> hist = Engine.history(this, 14);
        StringBuilder sb = new StringBuilder();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd.MM");
        for (Settler.Result r : hist) {
            sb.append(r.day.format(f)).append("  ")
                    .append(String.format(Locale.US, "%7s", Fmt.num(r.steps))).append("  ");
            if (r.met) sb.append("ок");
            else sb.append(Fmt.penalty(r.penaltyMinor)).append("  (не хватило ").append(Fmt.num(r.goal - r.steps)).append(")");
            sb.append('\n');
        }
        historyView.setText(hist.isEmpty() ? "Пока нет завершённых дней" : sb.toString());
    }
}
