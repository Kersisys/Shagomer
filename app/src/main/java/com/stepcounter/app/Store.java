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
        SharedPreferences.Editor e = p.edit().putLong(KEY_LAST_HW, cur);
        if (delta > 0) e.putInt("hw_" + day, hw(day) + (int) delta);
        e.apply();
    }

    boolean enabled() { return p.getBoolean(KEY_ENABLED, false); }
    void setEnabled(boolean v) { p.edit().putBoolean(KEY_ENABLED, v).apply(); }
}
