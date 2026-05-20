package com.latinbot.sugo;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.content.SharedPreferences;

public class SugoNotificationListener extends NotificationListenerService {

    private final String SUGO_PACKAGE = "com.voicemaker.android";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        // --- SISTEMA DE APAGADO ---
        SharedPreferences prefs = getSharedPreferences("LatinBotPrefs", MODE_PRIVATE);
        if (!prefs.getBoolean("bot_activo", true)) {
            return; // Si el switch está apagado, ignoramos el mensaje por completo
        }

        if (!SUGO_PACKAGE.equals(sbn.getPackageName())) {
            return;
        }

        SugoBotService.procesarNotificacionDesdeListener(sbn.getNotification());
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {}
}