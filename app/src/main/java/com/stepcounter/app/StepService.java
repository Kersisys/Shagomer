package com.stepcounter.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Служба переднего плана. Читает акселерометр (пакетами, чтобы экономить батарею)
 * и аппаратный шагомер, и сохраняет оба счёта по дням.
 */
public class StepService extends Service implements SensorEventListener {
    private static final String CHANNEL = "steps";
    private static final int NOTIF_ID = 1;
    /** Телефон может копить события до 2 секунд и отдавать пачкой, пока процессор спит. */
    private static final int MAX_LATENCY_US = 2_000_000;
    private static final int SAMPLING_US = 20_000; // 50 Гц

    private final StepDetector detector = new StepDetector();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SensorManager sm;
    private Store store;
    private NotificationManager nm;

    private String day;
    private int ownToday;
    private int unsaved;
    private long lastNotifyMs;
    private boolean registered;

    private final Runnable periodic = new Runnable() {
        @Override public void run() {
            flush();
            updateNotification();
            handler.postDelayed(this, 30_000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        day = Store.today();
        ownToday = store.own(day);

        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Счётчик шагов", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
        } else {
            startForeground(NOTIF_ID, n);
        }

        if (!registered) {
            registered = true;
            Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc != null) {
                detector.reset();
                sm.registerListener(this, acc, SAMPLING_US, MAX_LATENCY_US);
            }
            Sensor hw = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (hw != null) {
                sm.registerListener(this, hw, SensorManager.SENSOR_DELAY_NORMAL, 10_000_000);
            }
        }

        handler.removeCallbacks(periodic);
        handler.postDelayed(periodic, 30_000);
        return START_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        rollDayIfNeeded();
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            int s = detector.feed(e.timestamp, e.values[0], e.values[1], e.values[2]);
            if (s > 0) {
                ownToday += s;
                unsaved += s;
                // Пишем на диск не на каждый шаг, а каждые 10 шагов
                if (unsaved >= 10) flush();
                long now = System.currentTimeMillis();
                if (now - lastNotifyMs > 5000) {
                    lastNotifyMs = now;
                    updateNotification();
                }
            }
        } else if (e.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            store.addHwTotal(day, e.values[0]);
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void rollDayIfNeeded() {
        String d = Store.today();
        if (!d.equals(day)) {
            flush();
            day = d;
            ownToday = store.own(day);
        }
    }

    private void flush() {
        store.setOwn(day, ownToday);
        unsaved = 0;
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Шагов сегодня: " + ownToday)
                .setContentText("Считаю в фоне")
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        nm.notify(NOTIF_ID, buildNotification());
    }

    @Override
    public void onDestroy() {
        sm.unregisterListener(this);
        handler.removeCallbacksAndMessages(null);
        flush();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
