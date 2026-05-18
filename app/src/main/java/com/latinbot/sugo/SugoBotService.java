package com.latinbot.sugo;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class SugoBotService extends AccessibilityService {

    private final String RENDER_URL = "https://gaby-bot-server.onrender.com/bot";
    private final List<String> PALABRAS_BLOQUEADAS = Arrays.asList(
        "sistema", "ganaste", "soporte", "diamantes", "recarga", "oficial"
    );

    private String ultimoTextoRecibido = "";
    private long ultimoTiempoProceso = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        mostrarAlerta("🤖 LatinBot: Servicio Re-conectado y blindado.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                evaluarYAbrirNotificacion(event);
                return;
            }

            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (System.currentTimeMillis() - ultimoTiempoProceso > 2000) { 
                    leerYProcesarPantalla();
                }
            }
        } catch (Exception e) {
            // Evita que el servicio colapse por completo si hay un error
        }
    }

    private void evaluarYAbrirNotificacion(AccessibilityEvent event) {
        if (event.getParcelableData() != null && event.getParcelableData() instanceof Notification) {
            Notification notification = (Notification) event.getParcelableData();
            
            String textoNotificacion = "";
            if (notification.tickerText != null) {
                textoNotificacion = notification.tickerText.toString().toLowerCase();
            } else if (notification.extras != null) {
                CharSequence bigText = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
                if (bigText != null) textoNotificacion = bigText.toString().toLowerCase();
            }

            // Si hay texto, filtramos. Si no hay texto, abrimos igual por precaución.
            if (!textoNotificacion.isEmpty()) {
                for (String palabra : PALABRAS_BLOQUEADAS) {
                    if (textoNotificacion.contains(palabra)) return; // Ignora basura
                }
            }

            if (notification.contentIntent != null) {
                try {
                    notification.contentIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    // Fallo al abrir
                }
            }
        }
    }

    private void leerYProcesarPantalla() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        String textoCapturado = extraerUltimoMensajeReal(rootNode); 

        if (textoCapturado.isEmpty() || textoCapturado.equals(ultimoTextoRecibido)) return; 

        ultimoTiempoProceso = System.currentTimeMillis();
        ultimoTextoRecibido = textoCapturado;
        
        mostrarAlerta("📩 Leyendo: " + textoCapturado);
        enviarARender(textoCapturado);
    }

    private String extraerUltimoMensajeReal(AccessibilityNodeInfo nodo) {
        List<String> textosEnPantalla = new ArrayList<>();
        recorrerNodosBuscandoTexto(nodo, textosEnPantalla);
        if (textosEnPantalla.size() > 0) return textosEnPantalla.get(textosEnPantalla.size() - 1);
        return "";
    }

    private void recorrerNodosBuscandoTexto(AccessibilityNodeInfo nodo, List<String> lista) {
        if (nodo == null) return;
        if (nodo.getClassName() != null && nodo.getClassName().toString().equals("android.widget.TextView")) {
            if (nodo.getText() != null) {
                String txt = nodo.getText().toString().trim();
                if (txt.length() > 1 && !txt.equalsIgnoreCase("Type a message") && !txt.equalsIgnoreCase("Escribe un mensaje")) {
                    lista.add(txt);
                }
            }
        }
        for (int i = 0; i < nodo.getChildCount(); i++) {
            recorrerNodosBuscandoTexto(nodo.getChild(i), lista);
        }
    }

    private void enviarARender(String mensaje) {
        new Thread(() -> {
            try {
                URL url = new URL(RENDER_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setDoOutput(true);

                String jsonInputString = "{\"message\": \"" + mensaje.replace("\"", "\\\"") + "\", \"user_id\": \"telefono_1\"}";

                try(OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"));
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) response.append(responseLine.trim());

                String respuestaIA = response.toString();

                new Handler(Looper.getMainLooper()).post(() -> {
                    escribirMensajeConPortapapeles(respuestaIA);
                });

            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> mostrarAlerta("❌ Fallo conexión a Render"));
            }
        }).start();
    }

    // EL NUEVO MÉTODO MACRODROID: Usa el portapapeles del teléfono para forzar el pegado
    private void escribirMensajeConPortapapeles(String textoResponder) {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        AccessibilityNodeInfo cajaDeTexto = encontrarCajaDeTexto(rootNode);
        if (cajaDeTexto != null) {
            
            // 1. Copiar al Portapapeles
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("IA", textoResponder);
            clipboard.setPrimaryClip(clip);

            // 2. Tocar la casilla y Pegar físicamente
            cajaDeTexto.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            cajaDeTexto.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            
            try { Thread.sleep(600); } catch (Exception e) {} 
            
            // 3. Enviar
            AccessibilityNodeInfo rootActualizado = getRootInActiveWindow();
            AccessibilityNodeInfo botonEnviar = encontrarBotonEnviar(rootActualizado);
            if (botonEnviar != null) {
                botonEnviar.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                mostrarAlerta("✅ Enviado!");
            }
        }
    }

    private AccessibilityNodeInfo encontrarCajaDeTexto(AccessibilityNodeInfo nodo) {
        if (nodo == null) return null;
        if (nodo.getClassName() != null && (nodo.getClassName().toString().equals("android.widget.EditText") || nodo.isEditable())) {
            return nodo;
        }
        for (int i = 0; i < nodo.getChildCount(); i++) {
            AccessibilityNodeInfo resultado = encontrarCajaDeTexto(nodo.getChild(i));
            if (resultado != null) return resultado;
        }
        return null;
    }

    private AccessibilityNodeInfo encontrarBotonEnviar(AccessibilityNodeInfo nodo) {
        if (nodo == null) return null;
        if (nodo.isClickable() && (
            (nodo.getViewIdResourceName() != null && nodo.getViewIdResourceName().toLowerCase().contains("send")) ||
            (nodo.getContentDescription() != null && nodo.getContentDescription().toString().toLowerCase().contains("send")) ||
            (nodo.getContentDescription() != null && nodo.getContentDescription().toString().toLowerCase().contains("enviar"))
        )) return nodo;
        
        for (int i = 0; i < nodo.getChildCount(); i++) {
            AccessibilityNodeInfo resultado = encontrarBotonEnviar(nodo.getChild(i));
            if (resultado != null) return resultado;
        }
        return null;
    }

    private void mostrarAlerta(String mensaje) {
        Toast.makeText(getApplicationContext(), mensaje, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onInterrupt() {}
}