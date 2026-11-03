package com.stepcounter.app;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Подводит итоги завершённых дней и отправляет штрафы.
 * Чистая Java: время, шаги, журнал и платёжный провайдер передаются снаружи, поэтому логику
 * можно проверять тестами без телефона.
 *
 * Свойства, на которых держится всё остальное:
 *  - Идемпотентность. Повторный вызов ничего не дублирует: день записывается в журнал один раз,
 *    перевод по дню отправляется с ключом "penalty-<дата>".
 *  - Догоняющий расчёт. Если телефон был выключен несколько дней, все пропущенные дни
 *    будут оценены при следующем вызове. Дни без шагов считаются невыполненными.
 *  - Сегодняшний день никогда не оценивается: он ещё не закончился.
 */
final class Settler {

    interface StepsLookup {
        int own(LocalDate day);
        /** Шаги по шагомеру телефона или null, если у телефона нет такого датчика. */
        Integer hw(LocalDate day);
    }

    static final class Result {
        final LocalDate day;
        final int steps;
        final int goal;
        final boolean met;
        final long penaltyMinor;

        Result(LocalDate day, int steps, int goal, boolean met, long penaltyMinor) {
            this.day = day;
            this.steps = steps;
            this.goal = goal;
            this.met = met;
            this.penaltyMinor = penaltyMinor;
        }
    }

    static final class Summary {
        long transferredMinor;  // уже ушло в копилку
        long pendingMinor;      // штраф начислен, но перевод ещё не подтверждён
        int metDays;
        int missedDays;
    }

    private Settler() { }

    static List<Result> settle(LocalDate today, Commitment c, StepsLookup steps,
                               Ledger ledger, PaymentProvider pay) {
        LocalDate last = c.start.minusDays(1);
        for (String[] r : ledger.records()) {
            if (r[0].equals("DAY")) {
                LocalDate d = LocalDate.parse(r[1]);
                if (d.isAfter(last)) last = d;
            }
        }

        List<Result> out = new ArrayList<>();
        for (LocalDate d = last.plusDays(1); d.isBefore(today); d = d.plusDays(1)) {
            int own = steps.own(d);
            Integer hw = steps.hw(d);
            int n = c.source.pick(own, hw);
            int goal = c.goalFor(d);
            boolean met = n >= goal;
            long penalty = met ? 0 : c.penaltyFor(d);
            ledger.append("DAY", d.toString(), String.valueOf(n), String.valueOf(goal),
                    met ? "MET" : "MISSED", String.valueOf(penalty), c.currency,
                    diverges(own, hw) ? "DIVERGE" : "-");
            out.add(new Result(d, n, goal, met, penalty));
        }

        sendPendingTransfers(ledger, pay);
        return out;
    }

    /** Отправляет все штрафы, по которым ещё нет подтверждённого перевода. Ошибка = повтор позже. */
    private static void sendPendingTransfers(Ledger ledger, PaymentProvider pay) {
        List<String[]> recs = ledger.records();
        Set<String> done = new HashSet<>();
        for (String[] r : recs) if (r[0].equals("TRANSFER")) done.add(r[1]);

        for (String[] r : recs) {
            if (!r[0].equals("DAY")) continue;
            long penalty = Long.parseLong(r[5]);
            if (penalty <= 0 || done.contains(r[1])) continue;
            PaymentProvider.Result res = pay.transfer("penalty-" + r[1], penalty, r[6]);
            if (res.ok) {
                ledger.append("TRANSFER", r[1], String.valueOf(penalty), r[6], res.ref);
                done.add(r[1]);
            }
        }
    }

    static Summary summarize(Ledger ledger) {
        Summary s = new Summary();
        Set<String> done = new HashSet<>();
        List<String[]> recs = ledger.records();
        for (String[] r : recs) {
            if (r[0].equals("TRANSFER")) {
                done.add(r[1]);
                s.transferredMinor += Long.parseLong(r[2]);
            }
        }
        for (String[] r : recs) {
            if (!r[0].equals("DAY")) continue;
            if (r[4].equals("MET")) s.metDays++;
            else s.missedDays++;
            long penalty = Long.parseLong(r[5]);
            if (penalty > 0 && !done.contains(r[1])) s.pendingMinor += penalty;
        }
        return s;
    }

    /** Подозрительно: два датчика сильно расходятся (возможна накрутка тряской или сбой). Только пометка. */
    static boolean diverges(int own, Integer hw) {
        if (hw == null) return false;
        int max = Math.max(own, hw);
        return max >= 1000 && Math.abs(own - hw) > 0.35 * max;
    }
}
