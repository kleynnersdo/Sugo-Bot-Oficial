package com.latinbot.sugo;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Switch;

public class MainActivity extends Activity {
    
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Carga la interfaz gráfica

        // Memoria interna para guardar el estado del botón ON/OFF
        prefs = getSharedPreferences("LatinBotPrefs", MODE_PRIVATE);

        Button btnNotificaciones = findViewById(R.id.btn_notificaciones);
        Button btnAccesibilidad = findViewById(R.id.btn_accesibilidad);
        Switch switchEstadoBot = findViewById(R.id.switch_estado_bot);

        // Leer el estado guardado (Por defecto estará encendido)
        boolean botActivo = prefs.getBoolean("bot_activo", true); 
        switchEstadoBot.setChecked(botActivo);
        switchEstadoBot.setText(botActivo ? "Bot ENCENDIDO" : "Bot APAGADO");

        // Acción del Botón 1: Menú de Notificaciones
        btnNotificaciones.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                startActivity(intent);
            } catch (Exception e) {}
        });

        // Acción del Botón 2: Menú de Accesibilidad
        btnAccesibilidad.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(intent);
            } catch (Exception e) {}
        });

        // Acción del Interruptor ON/OFF
        switchEstadoBot.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("bot_activo", isChecked).apply();
            switchEstadoBot.setText(isChecked ? "Bot ENCENDIDO" : "Bot APAGADO");
        });
    }
}