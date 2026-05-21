package com.latinbot.sugo;

import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class SugoNotificationListener extends NotificationListenerService {

    private final String SUGO_PACKAGE = "com.voicemaker.android";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;

        // Validar si el switch está apagado (Por defecto ahora es true, así que siempre pasará)
        SharedPreferences prefs = getSharedPreferences("LatinBotPrefs", MODE_PRIVATE);
        if (!prefs.getBoolean("bot_activo", true)) return; 

        // Solo procesamos las de SUGO
        if (!SUGO_PACKAGE.equals(sbn.getPackageName())) return;

        // Se envía la notificación pura a su código central
        SugoBotService.procesarNotificacionDesdeListener(sbn.getNotification());
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {}
}