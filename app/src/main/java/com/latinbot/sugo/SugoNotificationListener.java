package com.latinbot.sugo;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class SugoNotificationListener extends NotificationListenerService {

    private final String SUGO_PACKAGE = "com.voicemaker.android";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        if (!SUGO_PACKAGE.equals(sbn.getPackageName())) {
            return;
        }

        // Transmitir la notificación capturada directamente al motor de ejecución
        SugoBotService.procesarNotificacionDesdeListener(sbn.getNotification());
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {}
}