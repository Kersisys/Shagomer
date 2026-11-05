package com.stepcounter.app;

/**
 * Калибровка шагов: человек проходит известное число шагов (считает сам), а приложение сравнивает
 * с этим числом свой счёт и счёт шагомера телефона.
 *
 * Поправка (factor) = настоящие шаги / насчитанные. Все дальнейшие счёты умножаются на неё.
 * Если дополнительно известно пройденное расстояние, считается длина шага для калорий и километров.
 *
 * Защита от ошибок: слишком короткая проба, нулевой счёт и поправка вне разумных пределов
 * не принимаются, чтобы случайная сбившаяся проба не испортила счёт.
 */
final class Calibration {
    static final int MIN_REAL_STEPS = 50;
    static final double MIN_FACTOR = 0.75;
    static final double MAX_FACTOR = 1.35;
    static final double MIN_STEP_CM = 30;
    static final double MAX_STEP_CM = 120;

    private Calibration() { }

    static final class Result {
        final int real;
        final int counted;
        final double factor;     // на что умножать насчитанное
        final double errorPct;   // ошибка счёта: плюс = насчитал лишнее, минус = недосчитал
        final boolean ok;
        final String problem;    // причина, если не ok

        Result(int real, int counted, double factor, double errorPct, boolean ok, String problem) {
            this.real = real;
            this.counted = counted;
            this.factor = factor;
            this.errorPct = errorPct;
            this.ok = ok;
            this.problem = problem;
        }
    }

    static Result compare(int real, int counted) {
        if (real < MIN_REAL_STEPS) {
            return new Result(real, counted, 0, 0, false, "нужно не меньше " + MIN_REAL_STEPS + " шагов");
        }
        if (counted <= 0) {
            return new Result(real, counted, 0, 0, false, "датчик ничего не насчитал");
        }
        double factor = (double) real / counted;
        double err = 100.0 * (counted - real) / real;
        if (factor < MIN_FACTOR || factor > MAX_FACTOR) {
            return new Result(real, counted, factor, err, false,
                    "слишком большая разница, пройдите заново и посчитайте внимательнее");
        }
        return new Result(real, counted, factor, err, true, null);
    }

    /** Длина шага в см по настоящему числу шагов и пройденному расстоянию. 0, если данные неправдоподобны. */
    static double stepLengthCm(int realSteps, double distanceM) {
        if (realSteps < MIN_REAL_STEPS || distanceM <= 0) return 0;
        double cm = distanceM * 100.0 / realSteps;
        return cm >= MIN_STEP_CM && cm <= MAX_STEP_CM ? cm : 0;
    }

    /** Применяет поправку, заданную в тысячных долях (1000 = без поправки). */
    static int scale(int value, int permille) {
        return (int) Math.round(value * permille / 1000.0);
    }
}
