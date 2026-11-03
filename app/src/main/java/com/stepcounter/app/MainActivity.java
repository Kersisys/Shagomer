package com.stepcounter.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
 * Главный экран. Тёмный графит, золотой акцент, крупные скругления.
 * Сверху вниз: сколько прошёл, сколько не хватает, деньги за сегодня, кнопки,
 * общий итог, история по дням.
 */
public class MainActivity extends Activity {
    // Палитра
    private static final int BG = 0xFF2B2B2B;      // фон экрана
    private static final int CARD = 0xFF363636;    // карточки
    private static final int CARD2 = 0xFF444444;   // вложенные элементы, вторичные кнопки
    private static final int GOLD = 0xFFCBAA68;    // акцент
    private static final int DARK = 0xFF2B2B2B;    // текст на золоте
    private static final int TEXT = 0xFFF2F2F2;
    private static final int MUTED = 0xFF8D8D8D;
    private static final int CORAL = 0xFFF2645F;   // потеря денег / недобор
    private static final int TEAL = 0xFF5CD1C6;    // цель выполнена

    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private Store store;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView dateView, stateChip, stepsView, missingView, moneyView;
    private TextView totalValueView, totalInfoView, compareView;
    private Button toggle;
    private View barFill;
    private GradientDrawable barFillBg;
    private LinearLayout historyBox;

    private Boolean lastRunning;   // чтобы не перерисовывать кнопку и метку каждую секунду
    private String lastHistorySig; // то же для списка истории

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

    // ---------- Помощники для построения интерфейса ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView tv(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif-medium", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(topDp);
        return p;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(CARD, 28));
        l.setPadding(dp(22), dp(20), dp(22), dp(20));
        l.setLayoutParams(lp(MATCH, WRAP, 14));
        return l;
    }

    private Button pill(String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(52));
        b.setMinimumHeight(dp(52));
        stylePill(b, primary);
        b.setOnClickListener(l);
        return b;
    }

    private void stylePill(Button b, boolean primary) {
        b.setTextColor(primary ? DARK : TEXT);
        b.setBackground(round(primary ? GOLD : CARD2, 26));
    }

    private void buildUi() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(32), dp(20), dp(28));

        // Заголовок: дата и название слева, состояние справа
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        dateView = tv(14, MUTED, false);
        titles.addView(dateView);
        TextView title = tv(34, TEXT, true);
        title.setText("Шаги");
        titles.addView(title);
        header.addView(titles, new LinearLayout.LayoutParams(0, WRAP, 1f));
        stateChip = tv(13, DARK, true);
        stateChip.setPadding(dp(16), dp(8), dp(16), dp(8));
        header.addView(stateChip, new LinearLayout.LayoutParams(WRAP, WRAP));
        root.addView(header);

        // Главная карточка: сколько прошёл, полоса прогресса, сколько не хватает, деньги за день
        LinearLayout hero = card();
        TextView lbl = tv(14, MUTED, false);
        lbl.setText("Сегодня прошёл");
        hero.addView(lbl);
        stepsView = tv(56, TEXT, true);
        hero.addView(stepsView);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setWeightSum(1f);
        bar.setBackground(round(CARD2, 10));
        barFill = new View(this);
        barFillBg = round(GOLD, 10);
        barFill.setBackground(barFillBg);
        bar.addView(barFill, new LinearLayout.LayoutParams(0, MATCH, 0f));
        hero.addView(bar, lp(MATCH, dp(10), 14));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        missingView = tv(16, MUTED, true);
        row.addView(missingView, new LinearLayout.LayoutParams(0, WRAP, 1f));
        moneyView = tv(22, TEXT, true);
        row.addView(moneyView, new LinearLayout.LayoutParams(WRAP, WRAP));
        hero.addView(row, lp(MATCH, WRAP, 14));
        root.addView(hero);

        // Кнопки
        toggle = pill("", true, new View.OnClickListener() {
            @Override public void onClick(View v) { onToggle(); }
        });
        root.addView(toggle, lp(MATCH, WRAP, 14));

        LinearLayout second = new LinearLayout(this);
        second.setOrientation(LinearLayout.HORIZONTAL);
        Button goalBtn = pill("Цель и штраф", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showGoalDialog(); }
        });
        second.addView(goalBtn, new LinearLayout.LayoutParams(0, WRAP, 1f));
        Button batBtn = pill("Работа в фоне", false, new View.OnClickListener() {
            @Override public void onClick(View v) { openBatterySettings(); }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        bp.leftMargin = dp(10);
        second.addView(batBtn, bp);
        root.addView(second, lp(MATCH, WRAP, 10));

        // Итог: золотой "ценник" с общей суммой штрафов
        LinearLayout sum = card();
        TextView sumLbl = tv(14, MUTED, false);
        sumLbl.setText("Всего штрафов");
        sum.addView(sumLbl);
        totalValueView = tv(30, GOLD, true);
        sum.addView(totalValueView);
        totalInfoView = tv(13, MUTED, false);
        sum.addView(totalInfoView, lp(WRAP, WRAP, 4));
        compareView = tv(12, MUTED, false);
        sum.addView(compareView, lp(WRAP, WRAP, 12));
        root.addView(sum);

        // История
        LinearLayout hist = card();
        TextView hTitle = tv(18, TEXT, true);
        hTitle.setText("Прошлые дни");
        hist.addView(hTitle);
        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        hist.addView(historyBox, lp(MATCH, WRAP, 8));
        root.addView(hist);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    // ---------- Жизненный цикл, разрешения, кнопки ----------

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

    // ---------- Отрисовка данных ----------

    private void render() {
        Commitment c = store.commitment();
        Engine.Today t = Engine.today(this);
        LocalDate today = LocalDate.now();

        dateView.setText(today.format(DateTimeFormatter.ofPattern("d MMMM", new Locale("ru"))));
        stepsView.setText(Fmt.num(t.steps));

        // Состояние счёта: метка в шапке и главная кнопка меняются только при переключении
        boolean running = store.enabled();
        if (lastRunning == null || lastRunning != running) {
            lastRunning = running;
            stateChip.setText(running ? "Идёт счёт" : "Выключено");
            stateChip.setTextColor(running ? DARK : MUTED);
            stateChip.setBackground(round(running ? GOLD : CARD2, 20));
            toggle.setText(running ? "Остановить счёт" : "Запустить счёт");
            stylePill(toggle, !running);
        }

        // Полоса прогресса
        float p = 0f;
        boolean done = t.active && t.missing == 0;
        if (t.active && t.goal > 0) p = Math.min(1f, (float) t.steps / t.goal);
        LinearLayout.LayoutParams fp = (LinearLayout.LayoutParams) barFill.getLayoutParams();
        if (fp.weight != p) {
            fp.weight = p;
            barFill.setLayoutParams(fp);
        }
        barFillBg.setColor(done ? TEAL : GOLD);

        // Сколько не хватает и сколько денег за сегодня
        if (store.tampered()) {
            missingView.setText("Журнал повреждён");
            missingView.setTextColor(CORAL);
            moneyView.setText("Расчёт остановлен");
            moneyView.setTextColor(CORAL);
        } else if (c == null) {
            missingView.setText("Цель не задана");
            missingView.setTextColor(MUTED);
            moneyView.setText("");
        } else if (!t.active) {
            missingView.setText("Начало: " + c.start);
            missingView.setTextColor(MUTED);
            moneyView.setText("");
        } else if (t.missing > 0) {
            missingView.setText("Не хватает " + Fmt.num(t.missing) + " из " + Fmt.num(t.goal));
            missingView.setTextColor(CORAL);
            moneyView.setText(Fmt.penalty(t.penaltyMinor));
            moneyView.setTextColor(CORAL);
        } else {
            missingView.setText("Цель выполнена (" + Fmt.num(t.goal) + ")");
            missingView.setTextColor(TEAL);
            moneyView.setText("0 ₽");
            moneyView.setTextColor(TEAL);
        }

        // Общий итог
        StringBuilder info = new StringBuilder();
        if (c == null) {
            totalValueView.setText("0 ₽");
            info.append("Задайте цель, чтобы начать");
        } else {
            Settler.Summary s = Engine.summary(this);
            if (s != null) {
                totalValueView.setText(Fmt.penalty(s.transferredMinor + s.pendingMinor));
                info.append("Выполнено дней: ").append(s.metDays).append(", пропущено: ").append(s.missedDays);
            } else {
                totalValueView.setText("—");
            }
            if (t.active && t.missing > 0) info.append("\nИтог дня подводится в полночь");
            if (c.pendingFrom != null && today.isBefore(c.pendingFrom)) {
                info.append("\nС ").append(c.pendingFrom).append(": ").append(Fmt.num(c.pendingGoal))
                        .append(" шагов, штраф ").append(Fmt.penalty(c.pendingPenaltyMinor));
            }
        }
        totalInfoView.setText(info.toString());

        // Сравнение двух счётчиков (для проверки точности моего алгоритма)
        String day = Store.today();
        int own = store.own(day);
        StringBuilder cmp = new StringBuilder("Мой алгоритм: ").append(Fmt.num(own));
        if (store.hwSeen()) {
            int hw = store.hw(day);
            cmp.append("  ·  Шагомер телефона: ").append(Fmt.num(hw));
            if (hw > 20) cmp.append(String.format(Locale.US, "  (%+.1f%%)", 100.0 * (own - hw) / hw));
        }
        compareView.setText(cmp.toString());

        renderHistory();
    }

    private void renderHistory() {
        List<Settler.Result> hist = Engine.history(this, 14);
        StringBuilder sig = new StringBuilder();
        for (Settler.Result r : hist) {
            sig.append(r.day).append(r.steps).append(r.met).append(r.penaltyMinor).append(';');
        }
        if (sig.toString().equals(lastHistorySig)) return;
        lastHistorySig = sig.toString();

        historyBox.removeAllViews();
        if (hist.isEmpty()) {
            TextView none = tv(14, MUTED, false);
            none.setText("Пока нет завершённых дней");
            historyBox.addView(none, lp(WRAP, WRAP, 4));
            return;
        }
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd.MM");
        for (Settler.Result r : hist) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));

            TextView date = tv(14, MUTED, false);
            date.setText(r.day.format(f));
            row.addView(date, new LinearLayout.LayoutParams(dp(56), WRAP));

            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            TextView steps = tv(16, TEXT, true);
            steps.setText(Fmt.num(r.steps));
            mid.addView(steps);
            if (!r.met) {
                TextView miss = tv(12, MUTED, false);
                miss.setText("не хватило " + Fmt.num(r.goal - r.steps));
                mid.addView(miss);
            }
            row.addView(mid, new LinearLayout.LayoutParams(0, WRAP, 1f));

            TextView res = tv(16, r.met ? TEAL : CORAL, true);
            res.setText(r.met ? "ок" : Fmt.penalty(r.penaltyMinor));
            row.addView(res, new LinearLayout.LayoutParams(WRAP, WRAP));

            historyBox.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
    }
}
