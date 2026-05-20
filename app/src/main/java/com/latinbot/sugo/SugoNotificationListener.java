package com.latinbot.sugo;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class SugoNotificationListener extends NotificationListenerService {

    private final String SUGO_PACKAGE = "com.voicemaker.android";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        // Si la notificación no es de SUGO, la ignoramos de inmediato
        if (!SUGO_PACKAGE.equals(sbn.getPackageName())) {
            return;
        }

        // Enviamos la notificación cruda directamente al Motor de Ejecución
        SugoBotService.procesarNotificacionDesdeListener(sbn.getNotification());
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // No necesitamos hacer nada cuando se borra una notificación
    }
}