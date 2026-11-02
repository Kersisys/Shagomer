package com.stepcounter.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** После перезагрузки телефона запускает сервис, если пользователь его включал. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && new Store(context).enabled()) {
            try {
                context.startForegroundService(new Intent(context, StepService.class));
            } catch (Exception ignored) {
                // Система может запретить старт из фона: тогда достаточно открыть приложение.
            }
        }
    }
}
