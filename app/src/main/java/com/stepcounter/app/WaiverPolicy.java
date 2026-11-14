package com.stepcounter.app;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Жёсткие квоты на погодные послабления. Не зависят от погоды и не поддаются уговорам.
 *
 * Запись в журнале: WAIVER;дата-дня;SOFT|FULL|NONE;причины;дата-решения
 * NONE тоже пишется: на каждый день проверка возможна один раз, иначе можно было бы
 * спрашивать снова и снова, пока прогноз не качнётся через порог.
 */
final class WaiverPolicy {
    private WaiverPolicy() { }

    static final int MIN_GAP_DAYS = 7;     // между двумя послаблениями
    static final int MAX_PER_30_DAYS = 3;

    /** Решения из журнала: день -> уровень (SOFT/FULL/NONE). */
    static Map<LocalDate, String> decisions(List<String[]> recs) {
        Map<LocalDate, String> m = new LinkedHashMap<>();
        for (String[] r : recs) {
            if (r[0].equals("WAIVER") && r.length >= 3) m.put(LocalDate.parse(r[1]), r[2]);
            if (r[0].equals("SHIFT") && r.length >= 2) m.put(LocalDate.parse(r[1]), "SHIFT");
        }
        return m;
    }

    /** Цель с учётом послабления. */
    static int effectiveGoal(String level, int goal) {
        if ("FULL".equals(level)) return 0;
        if ("SOFT".equals(level) || "SHIFT".equals(level)) return (goal + 1) / 2;
        return goal;
    }

    /**
     * Null, если запрос разрешён, иначе причина отказа.
     * trustedToday берётся из времени сервера, а не из часов телефона.
     */
    static String refusal(LocalDate trustedToday, LocalDate target, Commitment c, List<String[]> recs) {
        return refusal(trustedToday, target, c, recs, false);
    }

    /** sameDay=true: разрешён сегодняшний день (только для тяжёлой погоды, проверяется в Engine). */
    static String refusal(LocalDate trustedToday, LocalDate target, Commitment c, List<String[]> recs,
                          boolean sameDay) {
        if (c == null) return "Сначала задайте цель.";
        if (!target.equals(sameDay ? trustedToday : trustedToday.plusDays(1))) {
            return sameDay ? "Экстренная проверка только на сегодня." : "Проверка возможна только за день до этого дня.";
        }
        if (target.isBefore(c.start)) return "Обязательство в этот день ещё не действует: послабление не нужно.";
        Map<LocalDate, String> d = decisions(recs);
        if (d.containsKey(target)) {
            return "На " + target + " решение уже принято: " + describe(d.get(target)) + ". Повторно спросить нельзя.";
        }
        int in30 = 0;
        for (Map.Entry<LocalDate, String> e : d.entrySet()) {
            if (e.getValue().equals("NONE") || e.getValue().equals("SHIFT")) continue;
            long gap = Math.abs(ChronoUnit.DAYS.between(e.getKey(), target));
            if (gap < MIN_GAP_DAYS) {
                return "Послабления бывают не чаще раза в " + MIN_GAP_DAYS + " дней (последнее на " + e.getKey() + ").";
            }
            if (gap < 30) in30++;
        }
        if (in30 >= MAX_PER_30_DAYS) return "В этом месяце уже использовано " + MAX_PER_30_DAYS + " послабления.";
        return null;
    }

    static final int SHIFT_MAX_PER_7 = 4;
    static final int SHIFT_MAX_PER_30 = 16;

    /** Отказ для объявления рабочей смены на target (null = можно). Лимиты смен не смешиваются с погодными. */
    static String shiftRefusal(LocalDate trustedToday, LocalDate target, Commitment c, List<String[]> recs) {
        return shiftRefusal(trustedToday, target, c, recs, false);
    }

    static final int SHIFT_URGENT_CUTOFF_HOUR = 23;
    static final int SHIFT_URGENT_MAX_PER_30 = 3;

    /** urgent=true: вызвали сегодня (смена на СЕГОДНЯ), не чаще SHIFT_URGENT_MAX_PER_30 раз за 30 дней. */
    static String shiftRefusal(LocalDate trustedToday, LocalDate target, Commitment c, List<String[]> recs,
                               boolean urgent) {
        if (c == null) return "Сначала задайте цель.";
        if (!target.equals(urgent ? trustedToday : trustedToday.plusDays(1))) {
            return urgent ? "Срочная смена только на сегодня." : "Смену можно объявить только за день до неё.";
        }
        if (urgent) {
            int u = 0;
            for (String[] r : recs) {
                if (r[0].equals("SHIFT") && r.length >= 3 && r[1].equals(r[2])
                        && Math.abs(ChronoUnit.DAYS.between(LocalDate.parse(r[1]), target)) < 30) u++;
            }
            if (u >= SHIFT_URGENT_MAX_PER_30) {
                return "Срочных смен не более " + SHIFT_URGENT_MAX_PER_30 + " за 30 дней.";
            }
        }
        if (target.isBefore(c.start)) return "Обязательство в этот день ещё не действует.";
        Map<LocalDate, String> d = decisions(recs);
        String cur = d.get(target);
        if (cur != null && !cur.equals("NONE")) return "На " + target + " уже есть решение: " + describe(cur) + ".";
        java.util.Set<LocalDate> shifts = new java.util.HashSet<>();
        for (Map.Entry<LocalDate, String> e : d.entrySet()) if (e.getValue().equals("SHIFT")) shifts.add(e.getKey());
        int in30 = 0;
        for (LocalDate s : shifts) if (Math.abs(ChronoUnit.DAYS.between(s, target)) < 30) in30++;
        if (in30 >= SHIFT_MAX_PER_30) return "Лимит смен: не более " + SHIFT_MAX_PER_30 + " за 30 дней.";
        for (int w = -6; w <= 0; w++) {
            int cnt = 1;
            for (int k = 0; k < 7; k++) {
                LocalDate day = target.plusDays(w + k);
                if (!day.equals(target) && shifts.contains(day)) cnt++;
            }
            if (cnt > SHIFT_MAX_PER_7) return "Лимит смен: не более " + SHIFT_MAX_PER_7 + " в любые 7 дней.";
        }
        return null;
    }

    static String describe(String level) {
        if ("SHIFT".equals(level)) return "рабочая смена, цель вдвое меньше";
        if ("FULL".equals(level)) return "цель снята";
        if ("SOFT".equals(level)) return "цель вдвое меньше";
        return "погода обычная, цель без изменений";
    }

    /** Соль контрольного числа. Должна совпадать с текстом инструкции для ИИ (DEEPSEEK_PROMPT.md). */
    static final int SALT = 53;

    /** Контрольное число кода: (день*7 + месяц*11 + метка*1 + уровень + соль) mod 97. Считается в уме. */
    static int checksum(int day, int month, int nonce, String level) {
        int l = level.equals("FULL") ? 41 : level.equals("EMERG") ? 29 : 19;
        return (day * 7 + month * 11 + nonce + l + SALT) % 97;
    }

    /** Экстренный код (болезнь сегодня) можно запросить только до этого часа, по Кемерову. */
    static final int EMERGENCY_CUTOFF_HOUR = 18;
    static final int EMERGENCY_GAP_DAYS = 30;

    /** Код вида PG1111F482-37: ДДММ, S/F/E (смягчить/снять/экстренно сегодня), метка 3 цифры, контроль 2 цифры. */
    static String makeCode(LocalDate target, String level, int nonce) {
        return String.format("PG%02d%02d%s%03d-%02d", target.getDayOfMonth(), target.getMonthValue(),
                level.equals("FULL") ? "F" : level.equals("EMERG") ? "E" : "S", nonce,
                checksum(target.getDayOfMonth(), target.getMonthValue(), nonce, level));
    }

    /** true, если код экстренный (буква E): тогда он выписывается на СЕГОДНЯ. */
    static boolean isEmergency(String raw) {
        String c = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT).replace(" ", "");
        return c.length() > 6 && c.charAt(6) == 'E';
    }

    /** Есть ли в журнале экстренное послабление за последние EMERGENCY_GAP_DAYS дней до даты. */
    static boolean emergencyRecently(List<String[]> recs, LocalDate day) {
        for (String[] r : recs) {
            if (r[0].equals("WAIVER") && r.length >= 4 && r[3].startsWith("экстренный")) {
                if (Math.abs(ChronoUnit.DAYS.between(LocalDate.parse(r[1]), day)) < EMERGENCY_GAP_DAYS) return true;
            }
        }
        return false;
    }

    /** Разбор кода для конкретного дня. Возвращает {уровень, метка} или бросает IllegalArgumentException. */
    static String[] parseCode(String raw, LocalDate target) {
        String c = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT).replace(" ", "");
        if (!c.matches("PG\\d{4}[SFE]\\d{3}-\\d{2}")) throw new IllegalArgumentException("Неверный формат кода.");
        int day = Integer.parseInt(c.substring(2, 4)), month = Integer.parseInt(c.substring(4, 6));
        char kind = c.charAt(6);
        String level = kind == 'F' ? "FULL" : "SOFT";
        String sumLevel = kind == 'E' ? "EMERG" : level;
        int nonce = Integer.parseInt(c.substring(7, 10)), chk = Integer.parseInt(c.substring(11, 13));
        if (chk != checksum(day, month, nonce, sumLevel)) throw new IllegalArgumentException("Контрольное число не сходится.");
        if (day != target.getDayOfMonth() || month != target.getMonthValue()) {
            throw new IllegalArgumentException("Код выдан на другой день, нужен " + target.getDayOfMonth() + "."
                    + String.format("%02d", target.getMonthValue()));
        }
        return new String[] {level, String.format("%03d", nonce)};
    }
}
