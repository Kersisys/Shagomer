package com.stepcounter.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Хранит шаги по дням: свои (own) и от аппаратного шагомера телефона (hw). */
final class Store {
    private static final String PREFS = "steps";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LAST_HW = "last_hw_total";

    private final SharedPreferences p;

    Store(Context c) {
        p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    int own(String day) { return p.getInt("own_" + day, 0); }
    int hw(String day) { return p.getInt("hw_" + day, 0); }

    void setOwn(String day, int v) { p.edit().putInt("own_" + day, v).apply(); }

    /**
     * Аппаратный счётчик отдаёт сумму шагов с момента загрузки телефона.
     * Считаем разницу с прошлым значением. Если значение уменьшилось, значит телефон перезагружался.
     */
    void addHwTotal(String day, float total) {
        long cur = (long) total;
        long last = p.getLong(KEY_LAST_HW, -1);
        long delta;
        if (last < 0) delta = 0;           // первое показание: просто запоминаем
        else if (cur >= last) delta = cur - last;
        else delta = cur;                  // перезагрузка
        SharedPreferences.Editor e = p.edit().putLong(KEY_LAST_HW, cur).putBoolean("hw_seen", true);
        if (delta > 0) e.putInt("hw_" + day, hw(day) + (int) delta);
        e.apply();
    }

    /** true, если аппаратный шагомер хоть раз присылал данные (значит, он у телефона есть). */
    boolean hwSeen() { return p.getBoolean("hw_seen", false); }

    /** Возраст, пол и желаемый дефицит калорий для суточной нормы. Возраст 0 = не указан. */
    int age() { return p.getInt("age", 0); }
    boolean male() { return p.getBoolean("male", true); }
    int deficit() { return p.getInt("deficit", 500); }
    void setBody(int age, boolean male, int deficit) {
        p.edit().putInt("age", age).putBoolean("male", male).putInt("deficit", deficit).apply();
    }

    // ---------- Калибровка шагов ----------

    /** Состояние пробы: 0 нет, 1 идёт, 2 остановлена (ждёт ввода настоящего числа шагов). */
    int calibState() { return p.getInt("cal_state", 0); }
    String calibDay() { return p.getString("cal_day", ""); }
    int calibOwn0() { return p.getInt("cal_own0", 0); }
    int calibHw0() { return p.getInt("cal_hw0", 0); }
    int calibOwnDelta() { return p.getInt("cal_own_delta", 0); }
    long calibStopMs() { return p.getLong("cal_stop_ms", 0); }

    void calibStart(String day, int own0, int hw0) {
        p.edit().putInt("cal_state", 1).putString("cal_day", day)
                .putInt("cal_own0", own0).putInt("cal_hw0", hw0).apply();
    }
    void calibStop(int ownDelta, long stopMs) {
        p.edit().putInt("cal_state", 2).putInt("cal_own_delta", ownDelta).putLong("cal_stop_ms", stopMs).apply();
    }
    void calibClear() { p.edit().putInt("cal_state", 0).apply(); }

    /** Поправки счётов в тысячных долях (1000 = без поправки). */
    int ownPermille() { return p.getInt("own_permille", 1000); }
    int hwPermille() { return p.getInt("hw_permille", 1000); }
    /** Откалиброванная длина шага в миллиметрах, 0 = не откалибрована (берётся по росту). */
    int stepLenMm() { return p.getInt("step_len_mm", 0); }
    boolean calibrated() { return p.getBoolean("calibrated", false); }

    void applyCalibration(int ownPermille, int hwPermille, int stepLenMm) {
        p.edit().putInt("own_permille", ownPermille).putInt("hw_permille", hwPermille)
                .putInt("step_len_mm", stepLenMm).putBoolean("calibrated", true).apply();
    }
    void resetCalibration() {
        p.edit().putInt("own_permille", 1000).putInt("hw_permille", 1000)
                .putInt("step_len_mm", 0).putBoolean("calibrated", false).apply();
    }

    /** Длина шага в метрах: откалиброванная, иначе по росту. 0, если ничего не известно. */
    double stepLengthM() {
        if (stepLenMm() > 0) return stepLenMm() / 1000.0;
        return heightCm() > 0 ? Calories.stepLengthM(heightCm()) : 0;
    }

    /** Вес и рост для расчёта калорий. 0 = не указано. */
    int weightKg() { return p.getInt("weight_kg", 0); }
    int heightCm() { return p.getInt("height_cm", 0); }
    void setProfile(int weightKg, int heightCm) {
        p.edit().putInt("weight_kg", weightKg).putInt("height_cm", heightCm).apply();
    }

    Commitment commitment() {
        String s = p.getString("commitment", null);
        return s == null ? null : Commitment.parse(s);
    }
    void setCommitment(Commitment c) { p.edit().putString("commitment", c.serialize()).apply(); }

    /** Дата, когда вечернее напоминание уже было показано (чтобы не повторять). */
    String lastReminded() { return p.getString("reminded", ""); }
    void setLastReminded(String day) { p.edit().putString("reminded", day).apply(); }

    boolean tampered() { return p.getBoolean("tampered", false); }
    void setTampered(boolean v) { p.edit().putBoolean("tampered", v).apply(); }

    boolean enabled() { return p.getBoolean(KEY_ENABLED, false); }
    void setEnabled(boolean v) { p.edit().putBoolean(KEY_ENABLED, v).apply(); }
}
