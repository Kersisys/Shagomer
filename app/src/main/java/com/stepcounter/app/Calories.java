package com.stepcounter.app;

/**
 * Оценка энергозатрат на ходьбу по шагам, весу и росту.
 *
 * Формула:
 *  1. длина шага (м) = рост (см) * 0.415 / 100
 *  2. расстояние (км) = шаги * длина шага / 1000
 *  3. калории = 0.5 * вес (кг) * расстояние (км)
 *
 * Коэффициент 0.5 ккал на кг и км даёт ЧИСТЫЙ расход: только то, что тело потратило
 * сверх покоя. Полный расход (вместе с тем, что тратилось бы и без ходьбы) примерно в 1.5 раза больше.
 * Точность порядка плюс-минус 20-30%: на реальный расход влияют темп, рельеф и обмен веществ.
 */
final class Calories {
    static final double STEP_LENGTH_PER_HEIGHT = 0.415;
    static final double NET_KCAL_PER_KG_KM = 0.5;

    private Calories() { }

    static double stepLengthM(int heightCm) {
        return heightCm * STEP_LENGTH_PER_HEIGHT / 100.0;
    }

    static double distanceKm(int steps, int heightCm) {
        return steps * stepLengthM(heightCm) / 1000.0;
    }

    /** Расстояние при заданной (например, откалиброванной) длине шага. */
    static double distanceKmByStep(int steps, double stepLengthM) {
        return steps * stepLengthM / 1000.0;
    }

    /** Чистый расход при заданной длине шага. 0, если данных не хватает. */
    static int netKcalByStep(int steps, int weightKg, double stepLengthM) {
        if (weightKg <= 0 || stepLengthM <= 0 || steps <= 0) return 0;
        return (int) Math.round(NET_KCAL_PER_KG_KM * weightKg * distanceKmByStep(steps, stepLengthM));
    }

    /** Чистый расход в ккал. 0, если вес или рост не указаны. */
    static int netKcal(int steps, int weightKg, int heightCm) {
        if (weightKg <= 0 || heightCm <= 0 || steps <= 0) return 0;
        return (int) Math.round(NET_KCAL_PER_KG_KM * weightKg * distanceKm(steps, heightCm));
    }
}
