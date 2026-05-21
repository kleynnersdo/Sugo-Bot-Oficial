package com.latinbot.sugo;

import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class SugoNotificationListener extends NotificationListenerService {

    private final String SUGO_PACKAGE = "com.voicemaker.android";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;

        SharedPreferences prefsBot = getSharedPreferences("LatinBotPrefs", MODE_PRIVATE);
        if (!prefsBot.getBoolean("bot_activo", false)) return; // Apagado general

        if (!SUGO_PACKAGE.equals(sbn.getPackageName())) return;

        // Extraer el texto de la notificación para el Radar
        CharSequence ticker = sbn.getNotification().tickerText;
        String textoNotif = (ticker != null) ? ticker.toString() : "Notificación sin texto";

        // 1. Verificar Lista Negra (Si está bloqueada, ABORTAR)
        SharedPreferences prefsBloqueadas = getSharedPreferences("LatinBotBlacklist", MODE_PRIVATE);
        if (prefsBloqueadas.contains(textoNotif)) {
            return; // Filtro de aduana: No pasa al bot
        }

        // 2. Acumular en el Radar
        SharedPreferences prefsRadar = getSharedPreferences("LatinBotRadar", MODE_PRIVATE);
        int conteoActual = prefsRadar.getInt(textoNotif, 0);
        prefsRadar.edit().putInt(textoNotif, conteoActual + 1).apply();

        // 3. Si pasó todos los filtros, enviarla al bot original
        SugoBotService.procesarNotificacionDesdeListener(sbn.getNotification());
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {}
}