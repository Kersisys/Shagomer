package com.stepcounter.app;

/**
 * Какое число шагов считать "официальным" для проверки цели.
 * Когда на кону деньги, источник важен: свой алгоритм можно обмануть тряской проще,
 * чем аппаратный шагомер, а у аппаратного может не быть данных.
 */
enum StepSource {
    /** Только мой алгоритм. */
    OWN,
    /** Шагомер телефона; если его нет, то мой алгоритм. */
    HW,
    /** Меньшее из двух (самый строгий вариант, защита от накрутки). */
    MIN,
    /** Большее из двух (самый мягкий вариант). */
    MAX;

    /** @param hw шаги по шагомеру телефона или null, если его нет */
    int pick(int own, Integer hw) {
        if (hw == null) return own;
        switch (this) {
            case OWN: return own;
            case HW: return hw;
            case MIN: return Math.min(own, hw);
            default: return Math.max(own, hw);
        }
    }
}
