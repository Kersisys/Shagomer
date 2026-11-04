package com.stepcounter.app;

/**
 * Суточная норма калорий и БЖУ.
 *
 * Расчёт:
 *  1. Расход в покое (формула Миффлина-Сан Жеора):
 *     10 * вес(кг) + 6.25 * рост(см) - 5 * возраст + (мужчина: +5, женщина: -161)
 *  2. Суточный расход = расход в покое * 1.3 (сидячий день с учёбой или работой и обычной ходьбой).
 *  3. Норма = суточный расход - дефицит. Но не меньше безопасного минимума.
 *  4. Бюджет на день = норма + калории от шагов сверх базовых BASE_STEPS.
 *     Базовые шаги уже входят в коэффициент 1.3, поэтому второй раз они не считаются.
 *  5. Белок 1.3 г на кг веса, жиры 28% калорий, остальное углеводы.
 *
 * Ограничения для безопасности:
 *  - До 18 лет организм ещё растёт: дефицит не больше 600 ккал, минимум 2000 ккал в день.
 *  - Взрослым: дефицит не больше 1000 ккал, минимум 1500 (мужчины) и 1200 (женщины).
 *
 * Все числа приблизительные (плюс-минус 10-20%): формула даёт среднее по людям.
 */
final class Plan {
    static final double ACTIVITY = 1.3;
    static final int BASE_STEPS = 3500;
    static final double PROTEIN_G_PER_KG = 1.3;
    static final double FAT_SHARE = 0.28;

    final boolean ready;   // false, если не хватает данных профиля
    final int bmr;         // расход в покое
    final int tdee;        // суточный расход без учёта дефицита
    final int norm;        // норма калорий на день (без шагов)
    final int bonus;       // добавка за шаги сверх базовых
    final int budget;      // сколько можно съесть сегодня
    final int proteinG, fatG, carbsG;

    private Plan(boolean ready, int bmr, int tdee, int norm, int bonus, int budget,
                 int proteinG, int fatG, int carbsG) {
        this.ready = ready;
        this.bmr = bmr;
        this.tdee = tdee;
        this.norm = norm;
        this.bonus = bonus;
        this.budget = budget;
        this.proteinG = proteinG;
        this.fatG = fatG;
        this.carbsG = carbsG;
    }

    static int minIntake(int age, boolean male) {
        if (age < 18) return 2000;
        return male ? 1500 : 1200;
    }

    static int maxDeficit(int age, boolean male) {
        return age < 18 ? 600 : 1000;
    }

    static Plan compute(int weightKg, int heightCm, int age, boolean male, int deficit, int stepsToday) {
        if (weightKg <= 0 || heightCm <= 0 || age <= 0) {
            return new Plan(false, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        double bmr = 10.0 * weightKg + 6.25 * heightCm - 5.0 * age + (male ? 5 : -161);
        double tdee = bmr * ACTIVITY;
        int safeDeficit = Math.max(0, Math.min(deficit, maxDeficit(age, male)));
        int norm = Math.max(minIntake(age, male), (int) Math.round(tdee - safeDeficit));
        int bonus = Calories.netKcal(Math.max(0, stepsToday - BASE_STEPS), weightKg, heightCm);
        int budget = norm + bonus;
        int protein = (int) Math.round(PROTEIN_G_PER_KG * weightKg);
        int fat = (int) Math.round(FAT_SHARE * budget / 9.0);
        int carbs = Math.max(0, (int) Math.round((budget - protein * 4.0 - fat * 9.0) / 4.0));
        return new Plan(true, (int) Math.round(bmr), (int) Math.round(tdee), norm, bonus, budget,
                protein, fat, carbs);
    }
}
