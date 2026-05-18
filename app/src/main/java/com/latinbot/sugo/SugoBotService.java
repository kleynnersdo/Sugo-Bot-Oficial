package com.latinbot.sugo;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import android.app.Notification;
import android.app.PendingIntent;
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

    // Filtro estricto de palabras prohibidas para ignorar notificaciones basura
    private final List<String> PALABRAS_BLOQUEADAS = Arrays.asList(
        "sistema", "ganaste", "reaccionó", "eliminó", "soporte", "diamantes", "recarga", "emparejado", "oficial"
    );

    private String ultimoTextoRecibido = "";
    private long ultimoTiempoProceso = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Nota: La configuración detallada ahora se maneja de forma segura desde el archivo XML.
        mostrarAlerta("🤖 LatinBot: Servicio Conectado y Listo");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 1. FILTRAR Y INTERCEPTAR NOTIFICACIONES TRAS TRAS BAMBALINAS
        if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            evaluarYAbrirNotificacion(event);
            return;
        }

        // 2. DETECTAR CAMBIOS EN LA PANTALLA DE SUGO
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (System.currentTimeMillis() - ultimoTiempoProceso > 1500) { 
                leerYProcesarPantalla();
            }
        }
    }

    // --- ESCUDO INTELIGENTE DE NOTIFICACIONES ---
    private void evaluarYAbrirNotificacion(AccessibilityEvent event) {
        if (event.getParcelableData() != null && event.getParcelableData() instanceof Notification) {
            Notification notification = (Notification) event.getParcelableData();
            
            // Extraer el texto real que viene dentro de la notificación flotante
            CharSequence tickerText = notification.tickerText;
            String textoNotificacion = "";
            if (tickerText != null) {
                textoNotificacion = tickerText.toString().toLowerCase();
            } else if (notification.extras != null) {
                CharSequence bigText = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
                if (bigText != null) textoNotificacion = bigText.toString().toLowerCase();
            }

            // Validar si el texto contiene basura antes de abrirlo
            for (String palabra : PALABRAS_BLOQUEADAS) {
                if (!textoNotificacion.isEmpty() && textoNotificacion.contains(palabra)) {
                    // Es un mensaje basura del sistema, lo ignoramos por completo
                    return; 
                }
            }

            // Si pasa el filtro, procedemos a simular el toque para abrir el chat
            if (notification.contentIntent != null) {
                try {
                    mostrarAlerta("🔔 Mensaje válido detectado. Abriendo chat...");
                    notification.contentIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    mostrarAlerta("❌ Error al abrir la notificación.");
                }
            }
        }
    }

    private void leerYProcesarPantalla() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        String textoCapturado = extraerUltimoMensajeReal(rootNode); 

        if (textoCapturado.isEmpty() || textoCapturado.equals(ultimoTextoRecibido)) {
            return; 
        }

        ultimoTiempoProceso = System.currentTimeMillis();
        ultimoTextoRecibido = textoCapturado;
        
        mostrarAlerta("📩 Leyendo mensaje: " + textoCapturado);
        enviarARender(textoCapturado);
    }

    private String extraerUltimoMensajeReal(AccessibilityNodeInfo nodo) {
        List<String> textosEnPantalla = new ArrayList<>();
        recorrerNodosBuscandoTexto(nodo, textosEnPantalla);
        
        if (textosEnPantalla.size() > 0) {
            return textosEnPantalla.get(textosEnPantalla.size() - 1);
        }
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
                conn.setRequestProperty("Accept", "text/plain");
                conn.setDoOutput(true);

                String mensajeSeguro = mensaje.replace("\"", "\\\"").replace("\n", " ");
                String jsonInputString = "{\"message\": \"" + mensajeSeguro + "\", \"user_id\": \"telefono_1\"}";

                try(OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"));
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }

                String respuestaIA = response.toString();

                // 🚨 CRÍTICO: Obligamos a ejecutar la escritura en el Hilo Principal de la interfaz
                new Handler(Looper.getMainLooper()).post(() -> {
                    mostrarAlerta("🧠 IA respondió. Intentando escribir...");
                    escribirMensajeUniversal(respuestaIA);
                });

            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> 
                    mostrarAlerta("❌ Error de red conectando a Render.")
                );
            }
        }).start();
    }

    private void escribirMensajeUniversal(String textoResponder) {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        AccessibilityNodeInfo cajaDeTexto = encontrarCajaDeTexto(rootNode);
        if (cajaDeTexto != null) {
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textoResponder);
            cajaDeTexto.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            
            // Pequeña pausa de estabilización física
            try { Thread.sleep(500); } catch (Exception e) {} 
            
            AccessibilityNodeInfo rootActualizado = getRootInActiveWindow();
            AccessibilityNodeInfo botonEnviar = encontrarBotonEnviar(rootActualizado);
            if (botonEnviar != null) {
                botonEnviar.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                mostrarAlerta("✅ ¡Mensaje enviado con éxito!");
            } else {
                mostrarAlerta("⚠️ Caja llena, pero botón Enviar no hallado.");
            }
        } else {
            mostrarAlerta("⚠️ No se encontró la casilla de entrada.");
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
        if (nodo.isClickable()) {
            if (nodo.getViewIdResourceName() != null && nodo.getViewIdResourceName().toLowerCase().contains("send")) return nodo;
            if (nodo.getContentDescription() != null && nodo.getContentDescription().toString().toLowerCase().contains("send")) return nodo;
            if (nodo.getContentDescription() != null && nodo.getContentDescription().toString().toLowerCase().contains("enviar")) return nodo;
        }
        for (int i = 0; i < nodo.getChildCount(); i++) {
            AccessibilityNodeInfo resultado = encontrarBotonEnviar(nodo.getChild(i));
            if (resultado != null) return resultado;
        }
        return null;
    }

    private void mostrarAlerta(String mensaje) {
        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(getApplicationContext(), mensaje, Toast.LENGTH_SHORT).show()
        );
    }

    @Override
    public void onInterrupt() {}
}