package com.stepcounter.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Склеивает чистое ядро (Settler, Ledger) с Android: хранилищем, файлом журнала и провайдером платежей. */
final class Engine {
    private Engine() { }

    /** С этого часа (локальное время), если цель ещё не выполнена, приходит напоминание. */
    static final int REMIND_HOUR = 20;
    private static final String CH_RESULTS = "results";

    /** Состояние сегодняшнего дня для экрана и уведомления. */
    static final class Today {
        boolean active;       // обязательство уже началось
        int steps;            // сколько прошёл (по официальному источнику)
        int goal;             // цель на сегодня (если active)
        int missing;          // сколько не хватает
        long penaltyMinor;    // штраф, если день закончится так (0, если цель выполнена)
        String waiver = "NONE"; // погодное послабление на сегодня: NONE, SOFT, FULL
    }

    /** Уровень погодного послабления на день (NONE, если нет или журнал недоступен). */
    static synchronized String waiverFor(Context ctx, LocalDate d) {
        try {
            String w = WaiverPolicy.decisions(ledger(ctx).records()).get(d);
            return w == null ? "NONE" : w;
        } catch (RuntimeException e) {
            return "NONE";
        }
    }

    /** Решения по погоде из журнала, новые сверху (максимум max). */
    static synchronized List<String[]> waiverLog(Context ctx, int max) {
        List<String[]> out = new ArrayList<>();
        try {
            List<String[]> recs = ledger(ctx).records();
            for (int i = recs.size() - 1; i >= 0 && out.size() < max; i--) {
                String[] r = recs.get(i);
                if (r[0].equals("WAIVER") && r.length >= 5) out.add(r);
                else if (r[0].equals("SHIFT") && r.length >= 3) out.add(new String[] {"WAIVER", r[1], "SHIFT", "смена", r[2]});
            }
        } catch (RuntimeException ignored) { }
        return out;
    }

    static Today today(Context ctx) {
        Store s = new Store(ctx);
        LocalDate d = LocalDate.now();
        String key = d.toString();
        Commitment c = s.commitment();
        int own = Calibration.scale(s.own(key), s.ownPermille());
        Integer hw = s.hwSeen() ? Calibration.scale(s.hw(key), s.hwPermille()) : null;
        Today t = new Today();
        t.steps = c == null ? own : c.source.pick(own, hw);
        if (c != null && !d.isBefore(c.start)) {
            t.active = true;
            t.waiver = waiverFor(ctx, d);
            t.goal = WaiverPolicy.effectiveGoal(t.waiver, c.goalFor(d));
            t.missing = Math.max(0, t.goal - t.steps);
            t.penaltyMinor = t.missing > 0 ? c.penaltyFor(d) : 0;
        }
        return t;
    }

    /** Последние завершённые дни из журнала, новые сверху. */
    static synchronized List<Settler.Result> history(Context ctx, int max) {
        List<Settler.Result> out = new ArrayList<>();
        try {
            List<String[]> recs = ledger(ctx).records();
            for (int i = recs.size() - 1; i >= 0 && out.size() < max; i--) {
                String[] r = recs.get(i);
                if (!r[0].equals("DAY")) continue;
                out.add(new Settler.Result(LocalDate.parse(r[1]), Integer.parseInt(r[2]),
                        Integer.parseInt(r[3]), r[4].equals("MET"), Long.parseLong(r[5]),
                        r.length > 7 ? Settler.waiverOfFlag(r[7]) : "NONE"));
            }
        } catch (Ledger.TamperedException e) {
            new Store(ctx).setTampered(true);
        }
        return out;
    }

    private static void notifyUser(Context ctx, int id, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(
                    CH_RESULTS, "Итоги дня и напоминания", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent pi = PendingIntent.getActivity(ctx, 0,
                    new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(ctx, CH_RESULTS)
                    .setSmallIcon(R.drawable.ic_stat_steps)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(id, n);
        } catch (SecurityException ignored) {
            // нет разрешения на уведомления: счёт всё равно работает
        }
    }

    /** Уведомление об итоге закончившегося дня. */
    private static void notifyResult(Context ctx, Settler.Result r) {
        String date = r.day.format(DateTimeFormatter.ofPattern("dd.MM"));
        int id = (int) r.day.toEpochDay(); // свой номер на каждый день, чтобы итоги не затирали друг друга
        String walked = "Прошёл " + Fmt.num(r.steps) + " из " + Fmt.num(r.goal);
        if (r.waiver.equals("SOFT")) walked += " (цель смягчена погодой)";
        if (r.waiver.equals("SHIFT")) walked += " (рабочая смена)";
        if (r.waiver.equals("FULL")) {
            notifyUser(ctx, id, "Выходной по погоде · " + date, "Цель была снята. Прошёл " + Fmt.num(r.steps) + ". Штраф 0 ₽");
        } else if (r.met) {
            notifyUser(ctx, id, "Цель выполнена · " + date, walked + ". Штраф 0 ₽");
        } else {
            notifyUser(ctx, id, Fmt.penalty(r.penaltyMinor) + " за " + date,
                    walked + ", не хватило " + Fmt.num(r.goal - r.steps) + " шагов");
        }
    }

    /** Вечернее напоминание, один раз в день, только если цель ещё не выполнена. */
    static synchronized void remindIfNeeded(Context ctx) {
        Today t = today(ctx);
        if (!t.active || t.missing <= 0) return;
        if (LocalTime.now().getHour() < REMIND_HOUR) return;
        Store s = new Store(ctx);
        String key = LocalDate.now().toString();
        if (key.equals(s.lastReminded())) return;
        s.setLastReminded(key);
        notifyUser(ctx, 2, "Осталось " + Fmt.num(t.missing) + " шагов",
                "Если день закончится так, будет " + Fmt.penalty(t.penaltyMinor));
    }

    /** Провайдер платежей. Сейчас заглушка: деньги не двигаются. Здесь подключается банк. */
    private static PaymentProvider provider() {
        return new VirtualVault();
    }

    private static Ledger ledger(Context c) {
        return new Ledger(new File(c.getApplicationContext().getFilesDir(), "ledger.log"));
    }

    private static Settler.StepsLookup lookup(final Store store) {
        return new Settler.StepsLookup() {
            // Счёты умножаются на поправки из калибровки (без калибровки поправка равна 1)
            @Override public int own(LocalDate d) {
                return Calibration.scale(store.own(d.toString()), store.ownPermille());
            }
            @Override public Integer hw(LocalDate d) {
                return store.hwSeen() ? Calibration.scale(store.hw(d.toString()), store.hwPermille()) : null;
            }
        };
    }

    /**
     * Подводит итоги всех завершённых дней и отправляет штрафы. Безопасно вызывать сколько угодно раз:
     * из службы, при загрузке телефона, при открытии приложения.
     */
    static synchronized List<Settler.Result> settle(Context ctx) {
        Store store = new Store(ctx);
        Commitment c = store.commitment();
        if (c == null) return Collections.emptyList();
        try {
            List<Settler.Result> r = Settler.settle(LocalDate.now(), c, lookup(store), ledger(ctx), provider());
            store.setTampered(false);
            for (Settler.Result day : r) notifyResult(ctx, day);
            return r;
        } catch (Ledger.TamperedException e) {
            store.setTampered(true);
            return Collections.emptyList();
        }
    }

    /**
     * Создаёт или меняет обязательство. Новое начинается завтра, чтобы не штрафовать за уже идущий день.
     * Перед изменением подводит итоги прошедших дней по СТАРЫМ правилам.
     */
    static synchronized void changeCommitment(Context ctx, int goal, long penaltyMinor) {
        settle(ctx);
        Store store = new Store(ctx);
        Commitment c = store.commitment();
        LocalDate today = LocalDate.now();
        c = c == null
                ? Commitment.create(today.plusDays(1), goal, penaltyMinor, "RUB", StepSource.HW)
                : c.withChange(goal, penaltyMinor, today);
        store.setCommitment(c);
    }

    private static final java.time.ZoneId KRAS = java.time.ZoneId.of("Asia/Novokuznetsk");
    private static final long MAX_CLOCK_SKEW_MS = 10 * 60 * 1000L;

    /**
     * Просит погодное послабление на ЗАВТРА. Блокирующий: вызывать из фонового потока.
     * Возвращает текст для пользователя. Решение принимают объективные пороги и квоты
     * (WeatherRules, WaiverPolicy), время берётся от сервера, а не с часов телефона.
     */
    static String requestWaiver(Context ctx) { return requestWaiver(ctx, false); }

    /** today=true: экстренно на СЕГОДНЯ, работает только если прогноз на сегодня сам даёт «снять». */
    static String requestWaiver(Context ctx, boolean today) {
        Store s = new Store(ctx);
        if (s.commitment() == null) return "Сначала задайте цель.";
        settle(ctx);
        WeatherClient.Data d;
        try {
            d = WeatherClient.fetch();
        } catch (java.io.IOException e) {
            return "Нет связи с сервисом погоды (" + e.getMessage() + "). Попробуйте позже.";
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - d.serverMs) > MAX_CLOCK_SKEW_MS) {
            return "Часы телефона отличаются от реального времени больше чем на 10 минут. Включите автоматическое время.";
        }
        java.time.ZoneId dev = java.time.ZoneId.systemDefault();
        if (!dev.getRules().getOffset(java.time.Instant.ofEpochMilli(now))
                .equals(KRAS.getRules().getOffset(java.time.Instant.ofEpochMilli(now)))) {
            return "Часовой пояс телефона не совпадает с Кемерово (UTC+7). Для проверки он должен совпадать.";
        }
        LocalDate trusted = java.time.Instant.ofEpochMilli(d.serverMs).atZone(KRAS).toLocalDate();
        LocalDate target = today ? trusted : trusted.plusDays(1);
        synchronized (Engine.class) {
            try {
                Ledger l = ledger(ctx);
                Commitment c = s.commitment();
                String no = WaiverPolicy.refusal(trusted, target, c, l.records(), today);
                if (no != null) return no;
                WeatherRules.Assessment a;
                try {
                    a = WeatherRules.assess(d.forecast, d.air, target);
                } catch (IllegalArgumentException e) {
                    return "Не удалось разобрать прогноз: " + e.getMessage();
                }
                if (today && a.level != WeatherRules.Level.FULL) {
                    return "Сегодня снять цель можно только при тяжёлой погоде. Сейчас: " + a.reasons
                            + ". Ничего не записано, цель прежняя.";
                }
                l.append("WAIVER", target.toString(), a.level.name(), a.reasons, trusted.toString());
                String when = target.format(DateTimeFormatter.ofPattern("dd.MM"));
                int goal = c.goalFor(target);
                switch (a.level) {
                    case FULL: return "На " + when + " цель снята: " + a.reasons + ". Штрафа не будет.";
                    case SOFT: return "На " + when + " цель смягчена до "
                            + Fmt.num(WaiverPolicy.effectiveGoal("SOFT", goal)) + " шагов: " + a.reasons + ".";
                    default: return "На " + when + " погода обычная, цель остаётся " + Fmt.num(goal)
                            + ". Повторно за этот день спросить нельзя.";
                }
            } catch (Ledger.TamperedException e) {
                s.setTampered(true);
                return "Журнал повреждён, расчёт остановлен.";
            }
        }
    }

    /** Объявляет рабочую смену на ЗАВТРА (цель вдвое меньше). Блокирующий: из фонового потока. */
    static String declareShift(Context ctx) { return declareShift(ctx, false); }

    /** urgent=true: вызвали на смену сегодня, до SHIFT_URGENT_CUTOFF_HOUR по Кемерову. */
    static String declareShift(Context ctx, boolean urgent) {
        Store s = new Store(ctx);
        if (s.commitment() == null) return "Сначала задайте цель.";
        settle(ctx);
        long srv;
        try {
            srv = WeatherClient.serverNow();
        } catch (java.io.IOException e) {
            return "Нет связи для проверки времени (" + e.getMessage() + "). Попробуйте позже.";
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - srv) > MAX_CLOCK_SKEW_MS) return "Часы телефона отличаются от реального времени больше чем на 10 минут.";
        LocalDate trusted = java.time.Instant.ofEpochMilli(srv).atZone(KRAS).toLocalDate();
        if (urgent && java.time.Instant.ofEpochMilli(srv).atZone(KRAS).getHour() >= WaiverPolicy.SHIFT_URGENT_CUTOFF_HOUR) {
            return "Срочную смену можно отметить только до " + WaiverPolicy.SHIFT_URGENT_CUTOFF_HOUR + ":00 по Кемерову.";
        }
        LocalDate target = urgent ? trusted : trusted.plusDays(1);
        synchronized (Engine.class) {
            try {
                Ledger l = ledger(ctx);
                String no = WaiverPolicy.shiftRefusal(trusted, target, s.commitment(), l.records(), urgent);
                if (no != null) return no;
                l.append("SHIFT", target.toString(), trusted.toString());
                return "На " + target.format(DateTimeFormatter.ofPattern("dd.MM")) + " отмечена рабочая смена: цель "
                        + Fmt.num(WaiverPolicy.effectiveGoal("SHIFT", s.commitment().goalFor(target))) + " шагов.";
            } catch (Ledger.TamperedException e) {
                s.setTampered(true);
                return "Журнал повреждён, расчёт остановлен.";
            }
        }
    }

    /** Применяет код послабления на ЗАВТРА (код выдаёт ИИ-судья). Блокирующий: из фонового потока. */
    static String redeemCode(Context ctx, String code) {
        Store s = new Store(ctx);
        if (s.commitment() == null) return "Сначала задайте цель.";
        settle(ctx);
        long srv;
        try {
            srv = WeatherClient.serverNow();
        } catch (java.io.IOException e) {
            return "Нет связи для проверки времени (" + e.getMessage() + "). Попробуйте позже.";
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - srv) > MAX_CLOCK_SKEW_MS) return "Часы телефона отличаются от реального времени больше чем на 10 минут.";
        LocalDate trusted = java.time.Instant.ofEpochMilli(srv).atZone(KRAS).toLocalDate();
        boolean emerg = WaiverPolicy.isEmergency(code);
        if (emerg && java.time.Instant.ofEpochMilli(srv).atZone(KRAS).getHour() >= WaiverPolicy.EMERGENCY_CUTOFF_HOUR) {
            return "Экстренный код принимается только до " + WaiverPolicy.EMERGENCY_CUTOFF_HOUR + ":00 по Кемерову.";
        }
        LocalDate target = emerg ? trusted : trusted.plusDays(1);
        String[] parsed;
        try {
            parsed = WaiverPolicy.parseCode(code, target);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        synchronized (Engine.class) {
            try {
                Ledger l = ledger(ctx);
                String no = WaiverPolicy.refusal(trusted, target, s.commitment(), l.records(), emerg);
                if (no != null) return no;
                if (emerg && WaiverPolicy.emergencyRecently(l.records(), target)) {
                    return "Экстренное послабление можно раз в " + WaiverPolicy.EMERGENCY_GAP_DAYS + " дней.";
                }
                l.append("WAIVER", target.toString(), parsed[0], (emerg ? "экстренный код " : "код ") + parsed[1],
                        trusted.toString());
                return "На " + target.format(DateTimeFormatter.ofPattern("dd.MM")) + ": "
                        + WaiverPolicy.describe(parsed[0]) + ".";
            } catch (Ledger.TamperedException e) {
                s.setTampered(true);
                return "Журнал повреждён, расчёт остановлен.";
            }
        }
    }

    /** Сводка для экрана. Null, если журнал повреждён или обязательства нет. */
    static synchronized Settler.Summary summary(Context ctx) {
        try {
            return Settler.summarize(ledger(ctx));
        } catch (Ledger.TamperedException e) {
            new Store(ctx).setTampered(true);
            return null;
        }
    }
}
