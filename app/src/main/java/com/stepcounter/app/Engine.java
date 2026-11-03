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
    }

    static Today today(Context ctx) {
        Store s = new Store(ctx);
        LocalDate d = LocalDate.now();
        String key = d.toString();
        Commitment c = s.commitment();
        int own = s.own(key);
        Integer hw = s.hwSeen() ? s.hw(key) : null;
        Today t = new Today();
        t.steps = c == null ? own : c.source.pick(own, hw);
        if (c != null && !d.isBefore(c.start)) {
            t.active = true;
            t.goal = c.goalFor(d);
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
                        Integer.parseInt(r[3]), r[4].equals("MET"), Long.parseLong(r[5])));
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
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
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
        if (r.met) {
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
            @Override public int own(LocalDate d) { return store.own(d.toString()); }
            @Override public Integer hw(LocalDate d) { return store.hwSeen() ? store.hw(d.toString()) : null; }
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
