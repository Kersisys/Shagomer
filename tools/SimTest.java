import com.stepcounter.app.StepDetector;

import java.util.Random;

/**
 * Проверка StepDetector на синтетических сигналах (без телефона).
 *
 * Запуск из корня проекта:
 *   javac -d /tmp/simout app/src/main/java/com/stepcounter/app/StepDetector.java tools/SimTest.java
 *   java -cp /tmp/simout SimTest
 *
 * Важно: синтетика проверяет логику, но не заменяет настройку на реальных записях.
 */
public class SimTest {

    static final double G = 9.81;
    // Телефон лежит под наклоном: направление гравитации
    static final double[] DOWN = norm(0.25, 0.35, 0.90);
    static final double[] SIDE = norm(0.90, -0.40, -0.10);

    static double[] norm(double x, double y, double z) {
        double n = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / n, y / n, z / n};
    }

    /** Результат прогона: сколько насчитал детектор. */
    static int run(double[][] samples, double hz, double jitterMs, long seed) {
        StepDetector d = new StepDetector();
        Random r = new Random(seed);
        int total = 0;
        double dt = 1.0 / hz;
        for (int i = 0; i < samples.length; i++) {
            double t = i * dt + (jitterMs > 0 ? r.nextGaussian() * jitterMs * 1e-3 * 0.3 : 0);
            long tNs = (long) (t * 1e9) + 1_000_000_000L;
            total += d.feed(tNs, samples[i][0], samples[i][1], samples[i][2]);
        }
        return total;
    }

    /** Ходьба: вертикальный сигнал на частоте шагов, боковой на половинной. */
    static double[][] walk(double durS, double hz, double stepHz, double amp, double noise, long seed,
                           double pauseFrom, double pauseTo) {
        Random r = new Random(seed);
        int n = (int) (durS * hz);
        double[][] s = new double[n][3];
        double phase = 0;
        double stepAmp = 1.0;
        double lastPhaseInt = 0;
        for (int i = 0; i < n; i++) {
            double t = i / hz;
            boolean paused = t >= pauseFrom && t < pauseTo;
            double f = stepHz * (1 + 0.04 * Math.sin(t * 0.3)); // темп слегка плавает
            if (!paused) phase += 2 * Math.PI * f / hz + r.nextGaussian() * 0.01;
            double cyc = phase / (2 * Math.PI);
            if (Math.floor(cyc) != lastPhaseInt) {
                lastPhaseInt = Math.floor(cyc);
                stepAmp = 1.0 + 0.2 * r.nextGaussian(); // каждый шаг чуть по-разному
                stepAmp = Math.max(0.5, stepAmp);
            }
            double vert = paused ? 0 : amp * stepAmp * (Math.sin(phase) + 0.4 * Math.sin(2 * phase + 1.0));
            double lat = paused ? 0 : 0.5 * amp * Math.sin(phase / 2);
            for (int k = 0; k < 3; k++) {
                s[i][k] = DOWN[k] * (G + vert) + SIDE[k] * lat + r.nextGaussian() * noise;
            }
        }
        return s;
    }

    static double[][] idle(double durS, double hz, double noise, long seed) {
        Random r = new Random(seed);
        int n = (int) (durS * hz);
        double[][] s = new double[n][3];
        for (int i = 0; i < n; i++)
            for (int k = 0; k < 3; k++) s[i][k] = DOWN[k] * G + r.nextGaussian() * noise;
        return s;
    }

    /** Тряска в машине: вибрация 8-18 Гц и покачивание 0.3-1.5 Гц. */
    static double[][] car(double durS, double hz, long seed) {
        Random r = new Random(seed);
        int n = (int) (durS * hz);
        double[][] s = new double[n][3];
        for (int i = 0; i < n; i++) {
            double t = i / hz;
            double v = 0.6 * Math.sin(2 * Math.PI * 11 * t) + 0.5 * Math.sin(2 * Math.PI * 17 * t + 1)
                    + 0.7 * Math.sin(2 * Math.PI * 0.5 * t) + 0.5 * Math.sin(2 * Math.PI * 1.3 * t + 2)
                    + r.nextGaussian() * 0.3;
            for (int k = 0; k < 3; k++) s[i][k] = DOWN[k] * (G + v) + r.nextGaussian() * 0.1;
        }
        return s;
    }

    /** Хаотичные встряхивания: резкие толчки в случайные моменты, средний интервал ~1 с. */
    static double[][] random(double durS, double hz, double amp, long seed) {
        Random r = new Random(seed);
        int n = (int) (durS * hz);
        double[][] s = new double[n][3];
        double[] bump = new double[n];
        double t = 0.5;
        while (t < durS) {
            int c = (int) (t * hz);
            double a = amp * (0.4 + r.nextDouble());
            for (int j = -6; j <= 6; j++) {
                if (c + j >= 0 && c + j < n) bump[c + j] += a * Math.exp(-j * j / 6.0);
            }
            t += -Math.log(1 - r.nextDouble()) * 1.0 + 0.15;
        }
        for (int i = 0; i < n; i++)
            for (int k = 0; k < 3; k++) s[i][k] = DOWN[k] * (G + bump[i]) + r.nextGaussian() * 0.1;
        return s;
    }

    public static void main(String[] args) {
        double hz = 50;
        int bad = 0;

        System.out.println("=== Ходьба: частота шагов x сила шага (60 с, шум 0.2) ===");
        System.out.printf("%-8s %-8s %-8s %-8s %-8s%n", "шаг/с", "амплит.", "истина", "насчит.", "ошибка");
        double[] freqs = {1.0, 1.4, 1.8, 2.2, 2.8};
        double[] amps = {1.5, 3.0, 6.0};
        double worst = 0;
        for (double f : freqs) {
            for (double a : amps) {
                double dur = 60;
                int truth = (int) Math.round(f * dur);
                int got = run(walk(dur, hz, f, a, 0.2, (long) (f * 100 + a), -1, -1), hz, 3, 7);
                double err = 100.0 * (got - truth) / truth;
                worst = Math.max(worst, Math.abs(err));
                System.out.printf("%-8.1f %-8.1f %-8d %-8d %+.1f%%%n", f, a, truth, got, err);
            }
        }
        System.out.printf("Худшая ошибка при ходьбе: %.1f%%%n%n", worst);
        if (worst > 6) bad++;

        System.out.println("=== Ходьба с паузой 10 с посередине (1.8 шаг/с, 60 с, амплитуда 3) ===");
        {
            int truth = (int) Math.round(1.8 * 50);
            int got = run(walk(60, hz, 1.8, 3.0, 0.2, 5, 25, 35), hz, 3, 3);
            System.out.printf("истина ~%d, насчитано %d (%+.1f%%)%n%n", truth, got, 100.0 * (got - truth) / truth);
            if (Math.abs(got - truth) > 0.08 * truth) bad++;
        }

        System.out.println("=== Ложные срабатывания (истина = 0) ===");
        int idle = run(idle(600, hz, 0.05, 1), hz, 3, 1);
        System.out.printf("Телефон лежит 10 мин:            %d%n", idle);
        int idleNoisy = run(idle(600, hz, 0.4, 2), hz, 3, 1);
        System.out.printf("Лежит, сильный шум датчика 10 мин: %d%n", idleNoisy);
        int car = run(car(600, hz, 3), hz, 3, 1);
        System.out.printf("Тряска в машине 10 мин:          %d%n", car);
        int rnd = run(random(600, hz, 5.0, 4), hz, 3, 1);
        System.out.printf("Хаотичные встряхивания 10 мин (≈600 толчков): %d%n", rnd);
        if (idle > 0 || idleNoisy > 0) bad++;
        if (car > 10 || rnd > 30) bad++;

        System.out.println();
        System.out.println(bad == 0 ? "ИТОГ: все проверки пройдены" : "ИТОГ: есть проблемы (" + bad + ")");
    }
}
