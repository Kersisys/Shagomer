package com.stepcounter.app;

import java.time.LocalDate;

/**
 * Обязательство: сколько шагов в день и сколько платить за невыполнение.
 * Неизменяемый объект. Суммы хранятся в минимальных единицах валюты (копейках), без дробей.
 *
 * Главное правило против "передумал": ужесточать можно сразу, ослаблять только
 * с задержкой LOOSEN_DELAY_DAYS дней. Иначе при плохом дне можно было бы просто снизить цель.
 *
 * Перед изменением нужно рассчитать все прошедшие дни (Settler.settle), иначе старые
 * неоплаченные дни будут оценены по новым правилам. Это делает Engine.changeCommitment.
 */
final class Commitment {
    static final int LOOSEN_DELAY_DAYS = 7;
    static final int MIN_GOAL = 1000;

    final LocalDate start;
    final int goal;
    final long penaltyMinor;
    final String currency;
    final StepSource source;

    /** Отложенное ослабление: с этой даты действуют pendingGoal и pendingPenaltyMinor. Null, если нет. */
    final LocalDate pendingFrom;
    final int pendingGoal;
    final long pendingPenaltyMinor;

    Commitment(LocalDate start, int goal, long penaltyMinor, String currency, StepSource source,
               LocalDate pendingFrom, int pendingGoal, long pendingPenaltyMinor) {
        this.start = start;
        this.goal = goal;
        this.penaltyMinor = penaltyMinor;
        this.currency = currency;
        this.source = source;
        this.pendingFrom = pendingFrom;
        this.pendingGoal = pendingGoal;
        this.pendingPenaltyMinor = pendingPenaltyMinor;
    }

    static Commitment create(LocalDate start, int goal, long penaltyMinor, String currency, StepSource source) {
        validate(goal, penaltyMinor);
        return new Commitment(start, goal, penaltyMinor, currency, source, null, 0, 0);
    }

    private static void validate(int goal, long penaltyMinor) {
        if (goal < MIN_GOAL) throw new IllegalArgumentException("Цель не меньше " + MIN_GOAL + " шагов");
        if (penaltyMinor <= 0) throw new IllegalArgumentException("Штраф должен быть больше нуля");
    }

    /** Цель, которая действует в указанный день. */
    int goalFor(LocalDate day) {
        return pendingFrom != null && !day.isBefore(pendingFrom) ? pendingGoal : goal;
    }

    /** Штраф, который действует в указанный день. */
    long penaltyFor(LocalDate day) {
        return pendingFrom != null && !day.isBefore(pendingFrom) ? pendingPenaltyMinor : penaltyMinor;
    }

    /**
     * Запрос на изменение условий. Показатель, который растёт, меняется сразу.
     * Если хотя бы один показатель уменьшается, то уменьшенные значения начнут действовать
     * только через LOOSEN_DELAY_DAYS дней.
     */
    Commitment withChange(int newGoal, long newPenaltyMinor, LocalDate today) {
        validate(newGoal, newPenaltyMinor);
        Commitment base = promoted(today);
        int g = Math.max(base.goal, newGoal);
        long p = Math.max(base.penaltyMinor, newPenaltyMinor);
        boolean loosens = newGoal < g || newPenaltyMinor < p;
        if (!loosens) {
            return new Commitment(start, g, p, currency, source, null, 0, 0);
        }
        return new Commitment(start, g, p, currency, source,
                today.plusDays(LOOSEN_DELAY_DAYS), newGoal, newPenaltyMinor);
    }

    /** Если отложенное ослабление уже вступило в силу, делает его основным. */
    private Commitment promoted(LocalDate today) {
        if (pendingFrom != null && !today.isBefore(pendingFrom)) {
            return new Commitment(start, pendingGoal, pendingPenaltyMinor, currency, source, null, 0, 0);
        }
        return this;
    }

    String serialize() {
        return String.join("|", start.toString(), String.valueOf(goal), String.valueOf(penaltyMinor),
                currency, source.name(), pendingFrom == null ? "-" : pendingFrom.toString(),
                String.valueOf(pendingGoal), String.valueOf(pendingPenaltyMinor));
    }

    static Commitment parse(String s) {
        String[] a = s.split("\\|", -1);
        return new Commitment(LocalDate.parse(a[0]), Integer.parseInt(a[1]), Long.parseLong(a[2]),
                a[3], StepSource.valueOf(a[4]),
                a[5].equals("-") ? null : LocalDate.parse(a[5]),
                Integer.parseInt(a[6]), Long.parseLong(a[7]));
    }
}
