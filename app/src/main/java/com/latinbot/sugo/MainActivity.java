package com.latinbot.sugo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Map;

public class MainActivity extends Activity {

    private SharedPreferences prefsBot, prefsBloqueadas, prefsRadar;
    private LinearLayout panelRadar, panelBloqueadas;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefsBot = getSharedPreferences("LatinBotPrefs", MODE_PRIVATE);
        prefsBloqueadas = getSharedPreferences("LatinBotBlacklist", MODE_PRIVATE);
        prefsRadar = getSharedPreferences("LatinBotRadar", MODE_PRIVATE);

        panelRadar = findViewById(R.id.panel_radar);
        panelBloqueadas = findViewById(R.id.panel_bloqueadas);
        Switch switchEstado = findViewById(R.id.switch_estado_bot);

        // Iniciar estado
        boolean activo = prefsBot.getBoolean("bot_activo", false);
        switchEstado.setChecked(activo);
        switchEstado.setText(activo ? "Motor 24/7 ENCENDIDO" : "Motor APAGADO");

        switchEstado.setOnCheckedChangeListener((btn, isChecked) -> {
            prefsBot.edit().putBoolean("bot_activo", isChecked).apply();
            switchEstado.setText(isChecked ? "Motor 24/7 ENCENDIDO" : "Motor APAGADO");
            Intent serviceIntent = new Intent(this, KeepAliveService.class);
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent);
                else startService(serviceIntent);
            } else {
                stopService(serviceIntent);
            }
        });

        // Botones de sistema
        findViewById(R.id.btn_permisos).setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });

        findViewById(R.id.btn_bateria).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(intent);
        });

        findViewById(R.id.btn_limpiar_radar).setOnClickListener(v -> cargarListas());

        cargarListas();
    }

    private void cargarListas() {
        panelRadar.removeAllViews();
        panelBloqueadas.removeAllViews();

        // Cargar Radar Activo
        Map<String, ?> radar = prefsRadar.getAll();
        for (Map.Entry<String, ?> entry : radar.entrySet()) {
            String textoNotif = entry.getKey();
            int cantidad = (int) entry.getValue();
            
            // Si está bloqueada, no la mostramos en el radar activo
            if (prefsBloqueadas.contains(textoNotif)) continue;

            TextView tv = new TextView(this);
            tv.setText(textoNotif + " (" + cantidad + ")");
            tv.setPadding(0, 10, 0, 10);
            tv.setTextSize(16f);
            tv.setOnClickListener(v -> mostrarDialogoBloqueo(textoNotif));
            panelRadar.addView(tv);
        }

        // Cargar Lista Negra
        Map<String, ?> bloqueadas = prefsBloqueadas.getAll();
        for (Map.Entry<String, ?> entry : bloqueadas.entrySet()) {
            String textoBloqueado = entry.getKey();
            TextView tv = new TextView(this);
            tv.setText("❌ " + textoBloqueado);
            tv.setPadding(0, 10, 0, 10);
            tv.setTextSize(16f);
            tv.setTextColor(0xFFD32F2F);
            tv.setOnClickListener(v -> mostrarDialogoDesbloqueo(textoBloqueado));
            panelBloqueadas.addView(tv);
        }
    }

    private void mostrarDialogoBloqueo(String textoNotif) {
        new AlertDialog.Builder(this)
            .setTitle("Bloquear Notificación")
            .setMessage("¿Desea enviar a la lista negra:\n'" + textoNotif + "'?\n\nEl bot la ignorará por completo.")
            .setPositiveButton("Bloquear", (dialog, which) -> {
                prefsBloqueadas.edit().putBoolean(textoNotif, true).apply();
                cargarListas();
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void mostrarDialogoDesbloqueo(String textoNotif) {
        new AlertDialog.Builder(this)
            .setTitle("Desbloquear")
            .setMessage("¿Permitir que el bot vuelva a reaccionar a:\n'" + textoNotif + "'?")
            .setPositiveButton("Permitir", (dialog, which) -> {
                prefsBloqueadas.edit().remove(textoNotif).apply();
                cargarListas();
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }
}