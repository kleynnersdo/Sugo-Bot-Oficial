package com.latinbot.sugo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class KeepAliveService extends Service {

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        // Crear el canal de notificación silencioso
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "latinbot_channel", "Motor 24/7", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }

        // Atar la app al procesador para que no duerma
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LatinBot::InmortalWakeLock");
        wakeLock.acquire(); 
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, "latinbot_channel")
                    .setContentTitle("LatinBot IA")
                    .setContentText("Motor de fondo trabajando 24/7")
                    .setSmallIcon(android.R.drawable.ic_menu_preferences)
                    .build();
        } else {
            notification = new Notification.Builder(this)
                    .setContentTitle("LatinBot IA")
                    .setContentText("Motor de fondo trabajando 24/7")
                    .setSmallIcon(android.R.drawable.ic_menu_preferences)
                    .build();
        }
        
        // Convierte el servicio en prioritario
        startForeground(1, notification);
        return START_STICKY; // Si el sistema la mata por falta de RAM extrema, la revive al instante
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release(); // Libera el procesador al apagar el bot
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}