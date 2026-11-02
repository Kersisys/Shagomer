package com.stepcounter.app;

/**
 * Детектор шагов по данным акселерометра.
 *
 * Чистая Java без зависимостей от Android, поэтому его можно гонять и тестировать на компьютере.
 * Не потокобезопасен: вызывайте feed() всегда из одного потока.
 *
 * Принцип работы:
 *  1. Берём модуль вектора ускорения (не зависит от того, как телефон лежит).
 *  2. Вычитаем медленно плавающую "базовую линию" (это гравитация), остаётся только движение.
 *  3. Сглаживаем низкочастотным фильтром (~4 Гц), чтобы убрать дрожь и вибрацию.
 *  4. Ищем локальные максимумы. Максимум считается шагом-кандидатом, если он достаточно
 *     "выступает" над предыдущей впадиной (prominence), а порог адаптируется к силе шагов.
 *  5. Шаги засчитываются только когда обнаружен ритм: подряд ENTER_PEAKS пиков с похожими
 *     интервалами. Так отсекаются случайные встряхивания, тряска в транспорте, жесты.
 *     Первые пики серии засчитываются задним числом, поэтому начало ходьбы не теряется.
 */
public final class StepDetector {

    // ---- Параметры. Подбираются по реальным записям (см. README) ----

    /** Постоянная времени базовой линии (гравитации), секунды. */
    static final double BASELINE_TAU_S = 1.0;
    /** Постоянная времени сглаживания, секунды (0.04 с даёт срез около 4 Гц). */
    static final double SMOOTH_TAU_S = 0.04;
    /** Минимальная "выступающая" высота пика, м/с². Ниже этого шумом считаются любые колебания. */
    static final double MIN_PROMINENCE = 1.0;
    /** Во время ходьбы порог = эта доля от средней высоты недавних шагов. */
    static final double ADAPT_FRACTION = 0.45;
    /** Допустимое отклонение интервала между шагами от предыдущего интервала при входе в режим ходьбы. */
    static final double RHYTHM_TOLERANCE = 0.25;
    /** Сколько подряд ритмичных пиков нужно для признания ходьбы. */
    static final int ENTER_PEAKS = 5;
    /** Минимальный интервал между шагами (бег до ~3.5 шага/с). */
    static final long MIN_STEP_NS = 280_000_000L;
    /** Максимальный интервал между шагами (очень медленная ходьба). */
    static final long MAX_STEP_NS = 1_400_000_000L;
    /** Если данные не приходили дольше этого, сбрасываем фильтры. */
    static final long GAP_RESET_NS = 500_000_000L;

    // ---- Состояние фильтров ----
    private boolean started;
    private long lastT;
    private double baseline;
    private double smooth;
    private double sA, sB;      // сглаженные значения: два и один отсчёт назад
    private long tB;            // время отсчёта sB
    private int count;          // сколько сглаженных отсчётов накоплено после сброса
    private double trough;      // минимум сглаженного сигнала с прошлого пика

    // ---- Состояние поиска шагов ----
    private boolean havePeak;
    private long lastPeakT;
    private long prevInterval;
    private int seq;
    private double seqProm;
    private boolean walking;
    private double avgProm;
    private double avgInterval;

    /** true, если сейчас распознана ходьба. */
    public boolean isWalking() {
        return walking;
    }

    /** Полный сброс (например, при перезапуске сервиса). */
    public void reset() {
        started = false;
        resetPeaks();
    }

    private void resetPeaks() {
        havePeak = false;
        prevInterval = 0;
        seq = 0;
        seqProm = 0;
        walking = false;
        avgProm = 0;
        avgInterval = 0;
    }

    /**
     * Подаёт один отсчёт акселерометра.
     *
     * @param tNs время отсчёта в наносекундах (SensorEvent.timestamp)
     * @param x   ускорение по осям, м/с²
     * @return сколько новых шагов засчитано этим отсчётом (обычно 0 или 1,
     *         а в момент распознавания ходьбы сразу ENTER_PEAKS)
     */
    public int feed(long tNs, double x, double y, double z) {
        double m = Math.sqrt(x * x + y * y + z * z);

        if (started && tNs <= lastT) {
            return 0; // дубликат или время пошло назад
        }
        if (!started || tNs - lastT > GAP_RESET_NS) {
            started = true;
            lastT = tNs;
            baseline = m;
            smooth = 0;
            sA = 0;
            sB = 0;
            tB = tNs;
            count = 0;
            trough = Double.POSITIVE_INFINITY;
            resetPeaks();
            return 0;
        }

        double dt = (tNs - lastT) * 1e-9;
        lastT = tNs;

        baseline += dt / (BASELINE_TAU_S + dt) * (m - baseline);
        double a = m - baseline;
        smooth += dt / (SMOOTH_TAU_S + dt) * (a - smooth);
        double cur = smooth;

        int steps = 0;
        count++;
        if (count >= 3) {
            if (sA < trough) trough = sA;
            // sB - локальный максимум, если он выше предыдущего и не ниже текущего
            if (sB > sA && sB >= cur) {
                steps = onPeak(tB, sB - trough);
                trough = cur;
            }
        }
        sA = sB;
        sB = cur;
        tB = tNs;
        return steps;
    }

    private int onPeak(long t, double prominence) {
        double threshold = walking
                ? Math.max(MIN_PROMINENCE, ADAPT_FRACTION * avgProm)
                : MIN_PROMINENCE;
        if (prominence < threshold) return 0;

        if (!havePeak) {
            havePeak = true;
            lastPeakT = t;
            seq = 1;
            seqProm = prominence;
            prevInterval = 0;
            return 0;
        }

        long dt = t - lastPeakT;
        if (dt < MIN_STEP_NS) return 0; // слишком близко: вторичный горб того же шага

        lastPeakT = t;

        if (dt > MAX_STEP_NS) {
            // ритм потерян: начинаем серию заново
            walking = false;
            seq = 1;
            seqProm = prominence;
            prevInterval = 0;
            avgProm = 0;
            avgInterval = 0;
            return 0;
        }

        if (walking) {
            if (Math.abs(dt - avgInterval) > 0.5 * avgInterval) {
                // ритм сломался (толчок вне темпа): выходим из режима ходьбы
                walking = false;
                seq = 2;
                seqProm = 2 * prominence;
                prevInterval = dt;
                return 0;
            }
            avgProm = 0.8 * avgProm + 0.2 * prominence;
            avgInterval = 0.8 * avgInterval + 0.2 * dt;
            prevInterval = dt;
            return 1;
        }

        if (prevInterval > 0 && Math.abs(dt - prevInterval) > RHYTHM_TOLERANCE * prevInterval) {
            // интервал не похож на предыдущий: считаем этот пик и предыдущий началом новой серии
            seq = 2;
            seqProm = 2 * prominence;
            prevInterval = dt;
            return 0;
        }

        seq++;
        seqProm += prominence;
        prevInterval = dt;
        if (seq >= ENTER_PEAKS) {
            walking = true;
            avgProm = seqProm / seq;
            avgInterval = dt;
            return seq; // засчитываем задним числом все пики серии
        }
        return 0;
    }
}
