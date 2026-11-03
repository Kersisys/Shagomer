package com.stepcounter.app;

import java.util.Locale;

/** Форматирование чисел и денег для экрана и уведомлений. */
final class Fmt {
    private Fmt() { }

    /** 500 → "500", 12050 → "120.50" (минимальные единицы → рубли). */
    static String money(long minor) {
        long r = minor / 100;
        long k = minor % 100;
        return k == 0 ? String.valueOf(r) : String.format(Locale.US, "%d.%02d", r, k);
    }

    /** Штраф со знаком минус: "−500 ₽". Ноль выводится как "0 ₽". */
    static String penalty(long minor) {
        return minor <= 0 ? "0 ₽" : "−" + money(minor) + " ₽";
    }

    /** 12345 → "12 345" (неразрывный пробел, чтобы число не переносилось). */
    static String num(int n) {
        return String.format(Locale.US, "%,d", n).replace(',', ' ');
    }
}
