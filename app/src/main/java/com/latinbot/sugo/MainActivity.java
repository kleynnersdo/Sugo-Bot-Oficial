package com.latinbot.sugo;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Switch;

public class MainActivity extends Activity {
    
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("LatinBotPrefs", MODE_PRIVATE);

        Button btnNotificaciones = findViewById(R.id.btn_notificaciones);
        Button btnAccesibilidad = findViewById(R.id.btn_accesibilidad);
        Button btnBateria = findViewById(R.id.btn_bateria);
        Switch switchEstadoBot = findViewById(R.id.switch_estado_bot);

        // Solicitar permiso de notificaciones de forma nativa para que se muestre el servicio 24/7
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        // Leer estado inicial
        boolean botActivo = prefs.getBoolean("bot_activo", false); 
        switchEstadoBot.setChecked(botActivo);
        switchEstadoBot.setText(botActivo ? "Bot ENCENDIDO (24/7)" : "Bot APAGADO");

        // Botón 1: Notificaciones
        btnNotificaciones.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });

        // Botón 2: Accesibilidad
        btnAccesibilidad.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        // Botón 3: Batería Inmortal
        btnBateria.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        });

        // Interruptor General
        switchEstadoBot.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("bot_activo", isChecked).apply();
            switchEstadoBot.setText(isChecked ? "Bot ENCENDIDO (24/7)" : "Bot APAGADO");
            
            Intent serviceIntent = new Intent(this, KeepAliveService.class);
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            } else {
                stopService(serviceIntent);
            }
        });
    }
}