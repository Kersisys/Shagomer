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
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Четыре вкладки с плавающей нижней панелью:
 *  0. Сегодня: шаги, сколько не хватает, деньги за день, калории, старт/стоп.
 *  1. Питание: бюджет калорий на день, КБЖУ, добавление блюд, свои блюда.
 *  2. История: общий итог штрафов и список прошлых дней.
 *  3. Цель: условия обязательства, профиль (вес, рост, возраст), фон, проверка точности.
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
    private static final int CORAL = 0xFFF2645F;   // потеря денег / недобор / перебор
    private static final int TEAL = 0xFF5CD1C6;    // цель выполнена

    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;
    private static final int NUM = InputType.TYPE_CLASS_NUMBER;

    private static final String[] TAB_NAMES = {"Сегодня", "Еда", "История", "Цель", "Калибровка"};
    /** Ширина пунктов нижней панели: длинным названиям нужно больше места. */
    private static final float[] TAB_WEIGHTS = {1.1f, 0.8f, 1.0f, 0.8f, 1.5f};
    private static final int TABS = TAB_NAMES.length;
    private static final int CAL_TAB = 4;

    private Store store;
    private FoodBook food;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final View[] pages = new View[TABS];
    private final TextView[] navItems = new TextView[TABS];
    private int selectedTab = 0;

    // Вкладка "Сегодня"
    private TextView dateView, stateChip, stepsView, missingView, moneyView, pctView, goalEndView;
    private TextView kcalView, foodView;
    private Button toggle;
    private View barFill;
    private GradientDrawable barFillBg;
    // Вкладка "Питание"
    private TextView eatenView, budgetLineView, remainingView, macrosView, planInfoView;
    private View foodBarFill;
    private GradientDrawable foodBarBg;
    private LinearLayout foodListBox;
    // Вкладка "История"
    private TextView totalValueView, totalInfoView;
    private LinearLayout historyBox;
    // Вкладка "Цель"
    private TextView goalValueView, goalInfoView, compareView, profileView;
    // Вкладка "Калибровка" и баннер на главной
    private LinearLayout calBanner, calInputBox, calResultCard;
    private TextView calLiveOwn, calLiveHw, calHint, calResultView, calAppliedView;
    private Button calPrimary;
    private EditText calRealField, calDistField;
    private int lastCalState = -1;
    private Calibration.Result pendOwn, pendHw; // рассчитано, но ещё не применено
    private double pendStepCm;
    private boolean pendDistBad;

    private Boolean lastRunning;   // чтобы не перерисовывать кнопку и метку каждую секунду
    private String lastHistorySig; // то же для списка истории
    private String lastFoodSig;    // и для списка съеденного

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
        food = new FoodBook(getFilesDir());
        buildUi();
        select(b != null ? b.getInt("tab", 0) : 0);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", selectedTab);
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

    private EditText field(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(inputType);
        return e;
    }

    /** Целое число из поля. Пустое поле = 0. Бросает NumberFormatException на мусоре. */
    private int intOf(EditText e) {
        String s = e.getText().toString().trim();
        return s.isEmpty() ? 0 : Integer.parseInt(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    /** Создаёт прокручиваемую страницу, регистрирует её под номером index и возвращает контейнер для содержимого. */
    private LinearLayout newPage(int index) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(32), dp(20), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        pages[index] = scroll;
        return content;
    }

    /** Заголовок страницы: мелкая подпись (необязательно) над крупным названием. */
    private LinearLayout titleBlock(String title, TextView subtitle) {
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        if (subtitle != null) titles.addView(subtitle);
        TextView t = tv(34, TEXT, true);
        t.setText(title);
        titles.addView(t);
        return titles;
    }

    /** Полоса прогресса. Возвращает контейнер; заливку и её фон кладёт в fillOut[0] и bgOut[0]. */
    private LinearLayout progressBar(int heightDp, View[] fillOut, GradientDrawable[] bgOut) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setWeightSum(1f);
        bar.setBackground(round(CARD2, heightDp / 2));
        View fill = new View(this);
        GradientDrawable bg = round(GOLD, heightDp / 2);
        fill.setBackground(bg);
        bar.addView(fill, new LinearLayout.LayoutParams(0, MATCH, 0f));
        fillOut[0] = fill;
        bgOut[0] = bg;
        return bar;
    }

    private void setFill(View fill, float p) {
        LinearLayout.LayoutParams fp = (LinearLayout.LayoutParams) fill.getLayoutParams();
        if (fp.weight != p) {
            fp.weight = p;
            fill.setLayoutParams(fp);
        }
    }

    private void buildUi() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        buildTodayPage();
        buildFoodPage();
        buildHistoryPage();
        buildGoalPage();
        buildCalibrationPage();

        FrameLayout frame = new FrameLayout(this);
        for (View p : pages) frame.addView(p, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Плавающая нижняя панель
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackground(round(CARD, 32));
        nav.setPadding(dp(6), dp(6), dp(6), dp(6));
        for (int i = 0; i < TABS; i++) {
            final int index = i;
            TextView item = tv(12, MUTED, true);
            item.setText(TAB_NAMES[i]);
            item.setGravity(Gravity.CENTER);
            item.setSingleLine(true);
            item.setPadding(0, dp(13), 0, dp(13));
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { select(index); }
            });
            navItems[i] = item;
            nav.addView(item, new LinearLayout.LayoutParams(0, WRAP, TAB_WEIGHTS[i]));
        }
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        navLp.setMargins(dp(16), dp(8), dp(16), dp(12));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.addView(frame, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        root.addView(nav, navLp);
        setContentView(root);
    }

    private void buildTodayPage() {
        LinearLayout page = newPage(0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        dateView = tv(14, MUTED, false);
        header.addView(titleBlock("Шаги", dateView), new LinearLayout.LayoutParams(0, WRAP, 1f));
        stateChip = tv(13, DARK, true);
        stateChip.setPadding(dp(16), dp(8), dp(16), dp(8));
        header.addView(stateChip, new LinearLayout.LayoutParams(WRAP, WRAP));
        page.addView(header);

        // Золотой баннер, пока шаги не откалиброваны
        calBanner = card();
        calBanner.setBackground(round(GOLD, 28));
        TextView b1 = tv(18, DARK, true);
        b1.setText("Откалибруйте шаги");
        calBanner.addView(b1);
        TextView b2 = tv(13, DARK, false);
        b2.setText("Две минуты: пройдите 100 шагов, и счёт станет точнее.");
        calBanner.addView(b2, lp(WRAP, WRAP, 4));
        calBanner.addView(pill("Откалибровать", false, new View.OnClickListener() {
            @Override public void onClick(View v) { select(CAL_TAB); }
        }), lp(MATCH, WRAP, 12));
        page.addView(calBanner);

        LinearLayout hero = card();
        TextView lbl = tv(14, MUTED, false);
        lbl.setText("Сегодня прошёл");
        hero.addView(lbl);
        stepsView = tv(56, TEXT, true);
        hero.addView(stepsView);

        View[] fill = new View[1];
        GradientDrawable[] bg = new GradientDrawable[1];
        hero.addView(progressBar(18, fill, bg), lp(MATCH, dp(18), 16));
        barFill = fill[0];
        barFillBg = bg[0];

        // Под полосой: слева процент выполнения, справа цель
        LinearLayout scale = new LinearLayout(this);
        scale.setOrientation(LinearLayout.HORIZONTAL);
        pctView = tv(13, TEXT, true);
        scale.addView(pctView, new LinearLayout.LayoutParams(0, WRAP, 1f));
        goalEndView = tv(13, MUTED, false);
        scale.addView(goalEndView, new LinearLayout.LayoutParams(WRAP, WRAP));
        hero.addView(scale, lp(MATCH, WRAP, 6));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        missingView = tv(16, MUTED, true);
        row.addView(missingView, new LinearLayout.LayoutParams(0, WRAP, 1f));
        moneyView = tv(22, TEXT, true);
        row.addView(moneyView, new LinearLayout.LayoutParams(WRAP, WRAP));
        hero.addView(row, lp(MATCH, WRAP, 14));

        kcalView = tv(14, GOLD, false);
        hero.addView(kcalView, lp(WRAP, WRAP, 10));
        foodView = tv(14, MUTED, false);
        hero.addView(foodView, lp(WRAP, WRAP, 4));
        page.addView(hero);

        toggle = pill("", true, new View.OnClickListener() {
            @Override public void onClick(View v) { onToggle(); }
        });
        page.addView(toggle, lp(MATCH, WRAP, 14));
    }

    private void buildFoodPage() {
        LinearLayout page = newPage(1);
        page.addView(titleBlock("Питание", null));

        LinearLayout c1 = card();
        TextView l1 = tv(14, MUTED, false);
        l1.setText("Съедено сегодня");
        c1.addView(l1);
        eatenView = tv(40, TEXT, true);
        c1.addView(eatenView);
        budgetLineView = tv(14, MUTED, false);
        c1.addView(budgetLineView);

        View[] fill = new View[1];
        GradientDrawable[] bg = new GradientDrawable[1];
        c1.addView(progressBar(14, fill, bg), lp(MATCH, dp(14), 14));
        foodBarFill = fill[0];
        foodBarBg = bg[0];

        remainingView = tv(16, MUTED, true);
        c1.addView(remainingView, lp(WRAP, WRAP, 10));
        macrosView = tv(14, TEXT, false);
        c1.addView(macrosView, lp(WRAP, WRAP, 8));
        planInfoView = tv(12, MUTED, false);
        c1.addView(planInfoView, lp(WRAP, WRAP, 8));
        page.addView(c1);

        page.addView(pill("Добавить блюдо", true, new View.OnClickListener() {
            @Override public void onClick(View v) { showAddDishDialog(); }
        }), lp(MATCH, WRAP, 14));
        page.addView(pill("Из моих блюд", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showDishPicker(); }
        }), lp(MATCH, WRAP, 10));

        LinearLayout c2 = card();
        TextView t2 = tv(18, TEXT, true);
        t2.setText("Что съедено");
        c2.addView(t2);
        foodListBox = new LinearLayout(this);
        foodListBox.setOrientation(LinearLayout.VERTICAL);
        c2.addView(foodListBox, lp(MATCH, WRAP, 8));
        page.addView(c2);
    }

    private void buildHistoryPage() {
        LinearLayout page = newPage(2);
        page.addView(titleBlock("История", null));

        LinearLayout sum = card();
        TextView sumLbl = tv(14, MUTED, false);
        sumLbl.setText("Всего штрафов");
        sum.addView(sumLbl);
        totalValueView = tv(30, GOLD, true);
        sum.addView(totalValueView);
        totalInfoView = tv(13, MUTED, false);
        sum.addView(totalInfoView, lp(WRAP, WRAP, 4));
        page.addView(sum);

        LinearLayout hist = card();
        TextView hTitle = tv(18, TEXT, true);
        hTitle.setText("Прошлые дни");
        hist.addView(hTitle);
        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        hist.addView(historyBox, lp(MATCH, WRAP, 8));
        page.addView(hist);
    }

    private void buildGoalPage() {
        LinearLayout page = newPage(3);
        page.addView(titleBlock("Цель", null));

        LinearLayout goal = card();
        TextView gLbl = tv(14, MUTED, false);
        gLbl.setText("Условия");
        goal.addView(gLbl);
        goalValueView = tv(28, TEXT, true);
        goal.addView(goalValueView);
        goalInfoView = tv(14, MUTED, false);
        goal.addView(goalInfoView, lp(WRAP, WRAP, 6));
        goal.addView(pill("Изменить цель и штраф", true, new View.OnClickListener() {
            @Override public void onClick(View v) { showGoalDialog(); }
        }), lp(MATCH, WRAP, 16));
        page.addView(goal);

        LinearLayout prof = card();
        TextView pTitle = tv(18, TEXT, true);
        pTitle.setText("Профиль");
        prof.addView(pTitle);
        profileView = tv(13, MUTED, false);
        prof.addView(profileView, lp(WRAP, WRAP, 6));
        prof.addView(pill("Указать данные", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showProfileDialog(); }
        }), lp(MATCH, WRAP, 14));
        page.addView(prof);

        LinearLayout bg = card();
        TextView bTitle = tv(18, TEXT, true);
        bTitle.setText("Работа в фоне");
        bg.addView(bTitle);
        TextView bInfo = tv(13, MUTED, false);
        bInfo.setText("Чтобы Android не останавливал счёт при выключенном экране, разрешите работу без ограничений батареи.");
        bg.addView(bInfo, lp(WRAP, WRAP, 6));
        bg.addView(pill("Настроить батарею", false, new View.OnClickListener() {
            @Override public void onClick(View v) { openBatterySettings(); }
        }), lp(MATCH, WRAP, 14));
        page.addView(bg);

        LinearLayout acc = card();
        TextView aTitle = tv(18, TEXT, true);
        aTitle.setText("Проверка точности");
        acc.addView(aTitle);
        compareView = tv(13, MUTED, false);
        acc.addView(compareView, lp(WRAP, WRAP, 6));
        page.addView(acc);
    }

    // ---------- Вкладка «Калибровка» ----------

    private void buildCalibrationPage() {
        LinearLayout page = newPage(CAL_TAB);
        page.addView(titleBlock("Калибровка", null));

        // Заметный золотой блок
        LinearLayout hero = card();
        hero.setBackground(round(GOLD, 28));
        TextView h1 = tv(22, DARK, true);
        h1.setText("Откалибруйте шаги");
        hero.addView(h1);
        TextView h2 = tv(14, DARK, false);
        h2.setText("Пройдите 100 шагов и посчитайте их сами. Приложение сравнит это число со своим счётом "
                + "и со счётом шагомера телефона и подгонит поправку. Если знаете расстояние, узнает и длину вашего шага.");
        hero.addView(h2, lp(WRAP, WRAP, 6));
        page.addView(hero);

        LinearLayout how = card();
        TextView howTitle = tv(18, TEXT, true);
        howTitle.setText("Как это сделать");
        how.addView(howTitle);
        TextView howSteps = tv(13, MUTED, false);
        howSteps.setText("1. Запустите счёт на вкладке «Сегодня».\n"
                + "2. Нажмите «Старт» и уберите телефон туда, где носите его обычно.\n"
                + "3. Пройдите ровно 100 шагов обычным темпом, считая вслух. Можно больше, но не меньше 50.\n"
                + "4. Остановитесь, нажмите «Стоп» и постойте на месте 10 секунд: шагомер телефона присылает данные с задержкой.\n"
                + "5. Введите, сколько шагов прошли на самом деле. Расстояние в метрах указывайте, если мерили его (например, по карте).");
        how.addView(howSteps, lp(WRAP, WRAP, 6));
        page.addView(how);

        // Живые счётчики, кнопка и ввод
        LinearLayout live = card();
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView lOwn = tv(12, MUTED, false);
        lOwn.setText("Мой алгоритм");
        left.addView(lOwn);
        calLiveOwn = tv(36, TEXT, true);
        left.addView(calLiveOwn);
        cols.addView(left, new LinearLayout.LayoutParams(0, WRAP, 1f));
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        TextView lHw = tv(12, MUTED, false);
        lHw.setText("Шагомер телефона");
        right.addView(lHw);
        calLiveHw = tv(36, TEXT, true);
        right.addView(calLiveHw);
        cols.addView(right, new LinearLayout.LayoutParams(0, WRAP, 1f));
        live.addView(cols);

        calHint = tv(13, MUTED, false);
        live.addView(calHint, lp(WRAP, WRAP, 10));
        calPrimary = pill("Старт", true, new View.OnClickListener() {
            @Override public void onClick(View v) { onCalibPrimary(); }
        });
        live.addView(calPrimary, lp(MATCH, WRAP, 14));

        calInputBox = new LinearLayout(this);
        calInputBox.setOrientation(LinearLayout.VERTICAL);
        calRealField = field("Сколько шагов вы прошли на самом деле", NUM);
        calDistField = field("Расстояние в метрах (необязательно)",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        calInputBox.addView(calRealField);
        calInputBox.addView(calDistField);
        calInputBox.addView(pill("Рассчитать", true, new View.OnClickListener() {
            @Override public void onClick(View v) { onCalibCalculate(); }
        }), lp(MATCH, WRAP, 10));
        live.addView(calInputBox, lp(MATCH, WRAP, 10));
        page.addView(live);

        // Результат расчёта
        calResultCard = card();
        TextView resTitle = tv(18, TEXT, true);
        resTitle.setText("Результат");
        calResultCard.addView(resTitle);
        calResultView = tv(14, TEXT, false);
        calResultCard.addView(calResultView, lp(WRAP, WRAP, 6));
        calResultCard.addView(pill("Применить", true, new View.OnClickListener() {
            @Override public void onClick(View v) { onCalibApply(); }
        }), lp(MATCH, WRAP, 14));
        calResultCard.addView(pill("Отмена", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pendOwn = null;
                pendHw = null;
                renderCalibration();
            }
        }), lp(MATCH, WRAP, 10));
        calResultCard.setVisibility(View.GONE);
        page.addView(calResultCard);

        // Что сейчас применено
        LinearLayout applied = card();
        TextView apTitle = tv(18, TEXT, true);
        apTitle.setText("Текущая калибровка");
        applied.addView(apTitle);
        calAppliedView = tv(13, MUTED, false);
        applied.addView(calAppliedView, lp(WRAP, WRAP, 6));
        applied.addView(pill("Сбросить калибровку", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                store.resetCalibration();
                render();
            }
        }), lp(MATCH, WRAP, 14));
        page.addView(applied);
    }

    private void onCalibPrimary() {
        int st = store.calibState();
        String today = Store.today();
        if (st == 0) {
            if (!store.enabled()) {
                toast("Сначала запустите счёт на вкладке «Сегодня»");
                return;
            }
            pendOwn = null;
            pendHw = null;
            pendStepCm = 0;
            calRealField.setText("");
            calDistField.setText("");
            store.calibStart(today, StepService.ownNow(store), store.hw(today));
        } else if (st == 1) {
            int own = StepService.ownNow(store) - store.calibOwn0();
            store.calibStop(Math.max(0, own), System.currentTimeMillis());
        } else {
            store.calibClear();
            pendOwn = null;
            pendHw = null;
            pendStepCm = 0;
        }
        renderCalibration();
    }

    private void onCalibCalculate() {
        if (store.calibState() != 2) return;
        long waitMs = 10_000 - (System.currentTimeMillis() - store.calibStopMs());
        if (waitMs > 0) {
            toast("Подождите ещё " + (waitMs / 1000 + 1) + " с: шагомер телефона присылает данные с задержкой");
            return;
        }
        int real;
        try {
            real = intOf(calRealField);
        } catch (NumberFormatException e) {
            toast("Введите число шагов");
            return;
        }
        double distM = 0;
        String ds = calDistField.getText().toString().trim().replace(',', '.');
        if (!ds.isEmpty()) {
            try {
                distM = Double.parseDouble(ds);
            } catch (NumberFormatException e) {
                toast("Расстояние: введите число");
                return;
            }
        }
        pendOwn = Calibration.compare(real, store.calibOwnDelta());
        pendHw = store.hwSeen()
                ? Calibration.compare(real, Math.max(0, store.hw(store.calibDay()) - store.calibHw0()))
                : null;
        pendStepCm = distM > 0 ? Calibration.stepLengthCm(real, distM) : 0;
        pendDistBad = distM > 0 && pendStepCm == 0;
        renderCalibration();
    }

    private void onCalibApply() {
        if (pendOwn == null) return;
        boolean ownOk = pendOwn.ok;
        boolean hwOk = pendHw != null && pendHw.ok;
        if (!ownOk && !hwOk && pendStepCm <= 0) {
            toast("Нечего применять: проверьте результат и пройдите заново");
            return;
        }
        int ownP = ownOk ? (int) Math.round(pendOwn.factor * 1000) : store.ownPermille();
        int hwP = hwOk ? (int) Math.round(pendHw.factor * 1000) : store.hwPermille();
        int stepMm = pendStepCm > 0 ? (int) Math.round(pendStepCm * 10) : store.stepLenMm();
        store.applyCalibration(ownP, hwP, stepMm);
        store.calibClear();
        pendOwn = null;
        pendHw = null;
        pendStepCm = 0;
        toast("Калибровка применена");
        render();
    }

    private String describe(String who, Calibration.Result r) {
        if (!r.ok) return who + ": " + r.problem;
        return String.format(Locale.US, "%s: насчитал %d из %d, ошибка %+.1f%%, поправка ×%.3f",
                who, r.counted, r.real, r.errorPct, r.factor);
    }

    private void renderCalibration() {
        calBanner.setVisibility(store.calibrated() ? View.GONE : View.VISIBLE);

        String today = Store.today();
        int st = store.calibState();
        if (st != 0 && !today.equals(store.calibDay())) { // проба не должна переходить через полночь
            store.calibClear();
            st = 0;
            pendOwn = null;
            pendHw = null;
            pendStepCm = 0;
        }

        int own = 0;
        if (st == 1) own = StepService.ownNow(store) - store.calibOwn0();
        else if (st == 2) own = store.calibOwnDelta();
        int hw = st != 0 && store.hwSeen() ? store.hw(today) - store.calibHw0() : 0;
        calLiveOwn.setText(Fmt.num(Math.max(0, own)));
        calLiveHw.setText(store.hwSeen() ? Fmt.num(Math.max(0, hw)) : "нет");

        String[] labels = {"Старт", "Стоп", "Начать заново"};
        calPrimary.setText(labels[st]);
        if (lastCalState != st) {
            lastCalState = st;
            stylePill(calPrimary, st != 1);
        }

        if (st == 0) {
            calHint.setText(store.enabled() ? "Нажмите «Старт», потом идите."
                    : "Сначала запустите счёт на вкладке «Сегодня».");
        } else if (st == 1) {
            calHint.setText("Идите и считайте шаги. Когда пройдёте нужное число, нажмите «Стоп».");
        } else {
            calHint.setText("Остановлено. Подождите 10 секунд на месте и введите настоящее число шагов.");
        }
        calInputBox.setVisibility(st == 2 ? View.VISIBLE : View.GONE);

        boolean showResult = st == 2 && pendOwn != null;
        calResultCard.setVisibility(showResult ? View.VISIBLE : View.GONE);
        if (showResult) {
            StringBuilder sb = new StringBuilder(describe("Мой алгоритм", pendOwn));
            sb.append('\n').append(pendHw != null ? describe("Шагомер телефона", pendHw)
                    : "Шагомер телефона: нет данных");
            if (pendStepCm > 0) {
                sb.append(String.format(Locale.US, "\nДлина шага: %.1f см", pendStepCm));
            } else if (pendDistBad) {
                sb.append("\nДлина шага: расстояние выглядит неправдоподобно, не применяется");
            }
            calResultView.setText(sb.toString());
        }

        if (store.calibrated()) {
            calAppliedView.setText(String.format(Locale.US,
                    "Мой алгоритм: ×%.3f\nШагомер телефона: ×%.3f\nДлина шага: %s",
                    store.ownPermille() / 1000.0, store.hwPermille() / 1000.0,
                    store.stepLenMm() > 0
                            ? String.format(Locale.US, "%.1f см (замерена)", store.stepLenMm() / 10.0)
                            : "по росту"));
        } else {
            calAppliedView.setText("Не выполнялась. Счёт идёт без поправок.");
        }
    }

    private void select(int i) {
        selectedTab = i;
        for (int k = 0; k < pages.length; k++) {
            boolean on = k == i;
            pages[k].setVisibility(on ? View.VISIBLE : View.GONE);
            // Калибровка в неактивном состоянии подсвечена золотым, чтобы вкладка бросалась в глаза
            navItems[k].setTextColor(on ? DARK : (k == CAL_TAB ? GOLD : MUTED));
            navItems[k].setBackground(on ? round(GOLD, 24) : null);
        }
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

    // ---------- Диалоги ----------

    private void showGoalDialog() {
        final EditText goal = field("Шагов в день (не меньше " + Commitment.MIN_GOAL + ")", NUM);
        final EditText penalty = field("Штраф за пропущенный день, ₽", NUM);

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
                            toast("Введите числа");
                        } catch (IllegalArgumentException e) {
                            toast(e.getMessage());
                        }
                    }
                })
                .show();
    }

    private void showProfileDialog() {
        final EditText w = field("Вес, кг", NUM);
        final EditText h = field("Рост, см", NUM);
        final EditText a = field("Возраст, лет", NUM);
        final EditText df = field("Дефицит калорий в день, ккал", NUM);
        if (store.weightKg() > 0) w.setText(String.valueOf(store.weightKg()));
        if (store.heightCm() > 0) h.setText(String.valueOf(store.heightCm()));
        if (store.age() > 0) a.setText(String.valueOf(store.age()));
        df.setText(String.valueOf(store.deficit()));

        final RadioGroup sex = new RadioGroup(this);
        sex.setOrientation(RadioGroup.HORIZONTAL);
        final RadioButton boy = new RadioButton(this);
        boy.setText("Парень");
        boy.setId(View.generateViewId());
        RadioButton girl = new RadioButton(this);
        girl.setText("Девушка");
        girl.setId(View.generateViewId());
        sex.addView(boy);
        sex.addView(girl);
        sex.check(store.male() ? boy.getId() : girl.getId());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(w);
        box.addView(h);
        box.addView(a);
        box.addView(sex);
        box.addView(df);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Профиль")
                .setMessage("По этим данным считается суточная норма калорий и длина шага. "
                        + "До 18 лет дефицит ограничен 600 ккал, а минимум еды 2000 ккал в день.")
                .setView(sv)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        try {
                            int kg = intOf(w);
                            int cm = intOf(h);
                            int age = intOf(a);
                            int deficit = intOf(df);
                            boolean male = sex.getCheckedRadioButtonId() == boy.getId();
                            if (kg < 30 || kg > 300 || cm < 100 || cm > 250 || age < 10 || age > 100) {
                                toast("Вес 30-300 кг, рост 100-250 см, возраст 10-100 лет");
                                return;
                            }
                            int maxDeficit = Plan.maxDeficit(age, male);
                            if (deficit < 0 || deficit > maxDeficit) {
                                toast("Дефицит от 0 до " + maxDeficit + " ккал в этом возрасте");
                                return;
                            }
                            store.setProfile(kg, cm);
                            store.setBody(age, male, deficit);
                            render();
                        } catch (NumberFormatException e) {
                            toast("Введите числа");
                        }
                    }
                })
                .show();
    }

    private void showAddDishDialog() {
        final EditText name = field("Название", InputType.TYPE_CLASS_TEXT);
        final EditText kcal = field("Калории, ккал (можно пусто, если указаны БЖУ)", NUM);
        final EditText prot = field("Белки, г", NUM);
        final EditText fat = field("Жиры, г", NUM);
        final EditText carb = field("Углеводы, г", NUM);
        final CheckBox save = new CheckBox(this);
        save.setText("Сохранить в мои блюда");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(name);
        box.addView(kcal);
        box.addView(prot);
        box.addView(fat);
        box.addView(carb);
        box.addView(save);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Добавить блюдо")
                .setMessage("КБЖУ на съеденную порцию.")
                .setView(sv)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Добавить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        try {
                            String n = name.getText().toString().trim();
                            int p = intOf(prot);
                            int f = intOf(fat);
                            int c = intOf(carb);
                            int k = intOf(kcal);
                            if (n.isEmpty()) {
                                toast("Введите название");
                                return;
                            }
                            if (k == 0 && p + f + c > 0) k = 4 * p + 9 * f + 4 * c; // калории из БЖУ
                            if (k <= 0) {
                                toast("Укажите калории или белки, жиры и углеводы");
                                return;
                            }
                            if (k > 5000 || p > 1000 || f > 1000 || c > 1000) {
                                toast("Слишком большие значения, проверьте числа");
                                return;
                            }
                            food.add(Store.today(), n, k, p, f, c);
                            if (save.isChecked()) food.saveDish(n, k, p, f, c);
                            lastFoodSig = null;
                            render();
                        } catch (NumberFormatException e) {
                            toast("Введите целые числа");
                        } catch (IllegalStateException e) {
                            toast("Не удалось сохранить запись");
                        }
                    }
                })
                .show();
    }

    private void showDishPicker() {
        final List<FoodBook.Entry> dishes;
        try {
            dishes = food.dishes();
        } catch (IllegalStateException e) {
            toast("Не удалось прочитать список блюд");
            return;
        }
        if (dishes.isEmpty()) {
            toast("Список пуст. Добавьте блюдо и отметьте «Сохранить в мои блюда»");
            return;
        }
        String[] labels = new String[dishes.size()];
        for (int i = 0; i < labels.length; i++) {
            FoodBook.Entry e = dishes.get(i);
            labels[i] = e.name + " — " + e.kcal + " ккал (Б" + e.protein + " Ж" + e.fat + " У" + e.carbs + ")";
        }
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Мои блюда (удержание: удалить)")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        askPortions(dishes.get(which));
                    }
                })
                .setNegativeButton("Закрыть", null)
                .create();
        dlg.show();
        dlg.getListView().setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                confirmDeleteDish(dishes.get(pos));
                dlg.dismiss();
                return true;
            }
        });
    }

    private void askPortions(final FoodBook.Entry dish) {
        final EditText portions = field("Порций", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        portions.setText("1");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(portions);

        new AlertDialog.Builder(this)
                .setTitle(dish.name)
                .setMessage(dish.kcal + " ккал на порцию. Сколько порций съедено?")
                .setView(box)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Добавить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        try {
                            double n = Double.parseDouble(portions.getText().toString().trim().replace(',', '.'));
                            if (n < 0.1 || n > 20) {
                                toast("Порций от 0.1 до 20");
                                return;
                            }
                            FoodBook.Entry e = dish.scaled(n);
                            food.add(Store.today(), e.name, e.kcal, e.protein, e.fat, e.carbs);
                            lastFoodSig = null;
                            render();
                        } catch (NumberFormatException e) {
                            toast("Введите число");
                        } catch (IllegalStateException e) {
                            toast("Не удалось сохранить запись");
                        }
                    }
                })
                .show();
    }

    private void confirmDeleteDish(final FoodBook.Entry dish) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить из моих блюд?")
                .setMessage(dish.name)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        try {
                            food.removeDish(dish.idx);
                        } catch (IllegalStateException e) {
                            toast("Не удалось удалить");
                        }
                    }
                })
                .show();
    }

    private void confirmDeleteEntry(final FoodBook.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle("Убрать из съеденного?")
                .setMessage(entry.name + ", " + entry.kcal + " ккал")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Убрать", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        try {
                            food.remove(entry.idx);
                            lastFoodSig = null;
                            render();
                        } catch (IllegalStateException e) {
                            toast("Не удалось удалить");
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

        renderToday(c, t, today);
        renderFood(t);
        renderTotals(c, t, today);
        renderGoal(c, today);
        renderHistory();
        renderCalibration();
    }

    private void renderToday(Commitment c, Engine.Today t, LocalDate today) {
        dateView.setText(today.format(DateTimeFormatter.ofPattern("d MMMM", new Locale("ru"))));
        stepsView.setText(Fmt.num(t.steps));

        // Метка в шапке и главная кнопка меняются только при переключении
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
        setFill(barFill, p);
        barFillBg.setColor(done ? TEAL : GOLD);
        if (t.active && t.goal > 0) {
            pctView.setText(Math.round(p * 100) + "%");
            goalEndView.setText("цель " + Fmt.num(t.goal));
        } else {
            pctView.setText("");
            goalEndView.setText("");
        }

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

        // Калории: чистый расход на ходьбу по шагам, весу и росту
        if (store.weightKg() > 0 && store.heightCm() > 0) {
            int kcal = Calories.netKcalByStep(t.steps, store.weightKg(), store.stepLengthM());
            double km = Calories.distanceKmByStep(t.steps, store.stepLengthM());
            kcalView.setText(String.format(Locale.US, "Сожжено ~%d ккал  ·  %.1f км", kcal, km));
            kcalView.setTextColor(GOLD);
        } else {
            kcalView.setText("Калории: укажите вес и рост на вкладке «Цель»");
            kcalView.setTextColor(MUTED);
        }
    }

    /** Вкладка «Питание» и строка еды на главной. */
    private void renderFood(Engine.Today t) {
        Plan plan = Plan.compute(store.weightKg(), store.heightCm(), store.age(), store.male(),
                store.deficit(), t.steps, store.stepLengthM());
        List<FoodBook.Entry> entries;
        try {
            entries = food.entries(Store.today());
        } catch (IllegalStateException e) {
            return; // файл временно недоступен: покажем при следующем обновлении
        }
        int[] tot = FoodBook.totals(entries);
        int eaten = tot[0];

        eatenView.setText(Fmt.num(eaten) + " ккал");

        if (plan.ready) {
            int left = plan.budget - eaten;
            budgetLineView.setText("Бюджет на сегодня: " + Fmt.num(plan.budget) + " ккал");
            float p = plan.budget > 0 ? Math.min(1f, (float) eaten / plan.budget) : 0f;
            setFill(foodBarFill, p);
            foodBarBg.setColor(left < 0 ? CORAL : GOLD);
            if (left >= 0) {
                remainingView.setText("Осталось " + Fmt.num(left) + " ккал");
                remainingView.setTextColor(TEAL);
            } else {
                remainingView.setText("Перебор " + Fmt.num(-left) + " ккал");
                remainingView.setTextColor(CORAL);
            }
            macrosView.setText("Б " + tot[1] + " / " + plan.proteinG + " г   Ж " + tot[2] + " / " + plan.fatG
                    + " г   У " + tot[3] + " / " + plan.carbsG + " г");
            StringBuilder info = new StringBuilder("Норма ").append(Fmt.num(plan.norm)).append(" ккал");
            info.append(" (дефицит ").append(plan.tdee - plan.norm).append(")");
            if (plan.bonus > 0) info.append(" + шаги ").append(Fmt.num(plan.bonus)).append(" ккал");
            info.append(".\nШаги прибавляют калории только сверх ").append(Fmt.num(Plan.BASE_STEPS))
                    .append(" в день: эти уже учтены в норме.");
            planInfoView.setText(info.toString());

            foodView.setText("Еда: " + Fmt.num(eaten) + " из " + Fmt.num(plan.budget) + " ккал");
            foodView.setTextColor(left < 0 ? CORAL : MUTED);
        } else {
            budgetLineView.setText("Норма не рассчитана");
            setFill(foodBarFill, 0f);
            remainingView.setText("Укажите вес, рост и возраст на вкладке «Цель»");
            remainingView.setTextColor(MUTED);
            macrosView.setText("Б " + tot[1] + " г   Ж " + tot[2] + " г   У " + tot[3] + " г");
            planInfoView.setText("");
            foodView.setText("Еда: съедено " + Fmt.num(eaten) + " ккал");
            foodView.setTextColor(MUTED);
        }

        renderFoodList(entries);
    }

    private void renderFoodList(List<FoodBook.Entry> entries) {
        StringBuilder sig = new StringBuilder();
        for (FoodBook.Entry e : entries) {
            sig.append(e.idx).append(e.name).append(e.kcal).append(';');
        }
        if (sig.toString().equals(lastFoodSig)) return;
        lastFoodSig = sig.toString();

        foodListBox.removeAllViews();
        if (entries.isEmpty()) {
            TextView none = tv(14, MUTED, false);
            none.setText("Сегодня ничего не добавлено");
            foodListBox.addView(none, lp(WRAP, WRAP, 4));
            return;
        }
        for (final FoodBook.Entry e : entries) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            TextView n = tv(15, TEXT, true);
            n.setText(e.name);
            row.addView(n);
            TextView sub = tv(12, MUTED, false);
            sub.setText(e.kcal + " ккал  ·  Б " + e.protein + "  Ж " + e.fat + "  У " + e.carbs);
            row.addView(sub);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { confirmDeleteEntry(e); }
            });
            foodListBox.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        TextView hint = tv(11, MUTED, false);
        hint.setText("Нажмите на запись, чтобы убрать");
        foodListBox.addView(hint, lp(WRAP, WRAP, 6));
    }

    private void renderTotals(Commitment c, Engine.Today t, LocalDate today) {
        StringBuilder info = new StringBuilder();
        if (c == null) {
            totalValueView.setText("0 ₽");
            info.append("Задайте цель на вкладке «Цель»");
        } else {
            Settler.Summary s = Engine.summary(this);
            if (s != null) {
                totalValueView.setText(Fmt.penalty(s.transferredMinor + s.pendingMinor));
                info.append("Выполнено дней: ").append(s.metDays).append(", пропущено: ").append(s.missedDays);
            } else {
                totalValueView.setText("—");
            }
            if (t.active && t.missing > 0) info.append("\nИтог сегодняшнего дня подводится в полночь");
        }
        totalInfoView.setText(info.toString());
    }

    private void renderGoal(Commitment c, LocalDate today) {
        StringBuilder info = new StringBuilder();
        if (c == null) {
            goalValueView.setText("Не задана");
            info.append("Укажите, сколько шагов в день и сколько платить за пропущенный день.");
        } else {
            goalValueView.setText(Fmt.num(c.goalFor(today)) + " шагов в день");
            info.append("Штраф за пропущенный день: ").append(Fmt.penalty(c.penaltyFor(today)).replace("−", ""));
            if (today.isBefore(c.start)) info.append("\nНачало: ").append(c.start);
            if (c.pendingFrom != null && today.isBefore(c.pendingFrom)) {
                info.append("\nС ").append(c.pendingFrom).append(": ").append(Fmt.num(c.pendingGoal))
                        .append(" шагов, штраф ").append(Fmt.penalty(c.pendingPenaltyMinor).replace("−", ""));
            }
            info.append("\nУжесточить можно сразу, ослабить только через ")
                    .append(Commitment.LOOSEN_DELAY_DAYS).append(" дней.");
        }
        goalInfoView.setText(info.toString());

        // Профиль
        if (store.weightKg() > 0 && store.heightCm() > 0 && store.age() > 0) {
            Plan plan = Plan.compute(store.weightKg(), store.heightCm(), store.age(), store.male(),
                    store.deficit(), 0, store.stepLengthM());
            profileView.setText(String.format(Locale.US,
                    "Вес %d кг, рост %d см, %d лет, %s.\nДлина шага %.0f см (по росту).\n"
                            + "Расход в покое %s ккал, обычный день %s ккал.\nНорма еды %s ккал (дефицит %d).",
                    store.weightKg(), store.heightCm(), store.age(), store.male() ? "парень" : "девушка",
                    store.stepLengthM() * 100,
                    Fmt.num(plan.bmr), Fmt.num(plan.tdee), Fmt.num(plan.norm), plan.tdee - plan.norm));
        } else {
            profileView.setText("Не указаны. Нужны для подсчёта калорий, нормы еды и пройденных километров.");
        }

        // Сравнение двух счётчиков (для проверки точности моего алгоритма)
        String day = Store.today();
        int own = store.own(day);
        StringBuilder cmp = new StringBuilder("Мой алгоритм: ").append(Fmt.num(own));
        if (store.hwSeen()) {
            int hw = store.hw(day);
            cmp.append("\nШагомер телефона: ").append(Fmt.num(hw));
            if (hw > 20) cmp.append(String.format(Locale.US, "\nРазница: %+.1f%%", 100.0 * (own - hw) / hw));
        } else {
            cmp.append("\nШагомер телефона пока не прислал данные");
        }
        compareView.setText(cmp.toString());
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
