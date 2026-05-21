package com.latinbot.sugo;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.content.SharedPreferences;

public class SugoNotificationListener extends NotificationListenerService {

    private final String SUGO_PACKAGE = "com.voicemaker.android";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;

        SharedPreferences prefs = getSharedPreferences("LatinBotPrefs", MODE_PRIVATE);
        if (!prefs.getBoolean("bot_activo", false)) {
            return; // Si está apagado, no hace nada
        }

        if (!SUGO_PACKAGE.equals(sbn.getPackageName())) {
            return; // Solo lee la app objetivo
        }

        // --- AQUÍ SE AGREGAN LAS PALABRAS PROHIBIDAS MANUALMENTE ---
        CharSequence ticker = sbn.getNotification().tickerText;
        if (ticker != null) {
            // Convierte todo a minúsculas para que la búsqueda sea exacta
            String textoNotificacion = ticker.toString().toLowerCase();
            
            // TODAS las palabras de abajo deben ir en minúsculas obligatoriamente
            if (textoNotificacion.contains("flecha de cupido") || 
                textoNotificacion.contains("sugo team") ||
                textoNotificacion.contains("asist. anfitrion") ||
                textoNotificacion.contains("asist. juego") ||
                textoNotificacion.contains("sistema") ||
                textoNotificacion.contains("eventos") || 
                textoNotificacion.contains("te he seguido")) { 
                return; // Bloqueo inmediato: no pasa al bot
            }
        }

        // Envía directo al procesador original sin intermediarios
        SugoBotService.procesarNotificacionDesdeListener(sbn.getNotification());
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {}
}