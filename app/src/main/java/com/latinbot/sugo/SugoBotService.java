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

    private final List<String> PALABRAS_BLOQUEADAS = Arrays.asList(
        "sistema", "ganaste", "reaccionó", "eliminó", "soporte", "diamantes", "recarga", "emparejado"
    );

    private String ultimoTextoRecibido = "";
    private long ultimoTiempoProceso = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        
        // 🚨 CRÍTICO: Ahora escucha Pantallas Y Notificaciones
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
        
        info.packageNames = new String[]{"com.voicemaker.android", "com.fiya.android"};
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        
        setServiceInfo(info);
        mostrarAlerta("🤖 LatinBot: Interceptor de Notificaciones Activado");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 1. SI LLEGA UNA NOTIFICACIÓN, LA ABRE AUTOMÁTICAMENTE
        if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            abrirNotificacion(event);
            return;
        }

        // 2. SI LA PANTALLA CAMBIA (Ej. se abrió el chat), LEE Y RESPONDE
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (System.currentTimeMillis() - ultimoTiempoProceso > 1500) { 
                leerYProcesarPantalla();
            }
        }
    }

    // --- EL MOTOR QUE HACE CLIC EN LA NOTIFICACIÓN ---
    private void abrirNotificacion(AccessibilityEvent event) {
        if (event.getParcelableData() != null && event.getParcelableData() instanceof Notification) {
            Notification notification = (Notification) event.getParcelableData();
            if (notification.contentIntent != null) {
                try {
                    mostrarAlerta("🔔 Notificación detectada. Abriendo chat...");
                    notification.contentIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    mostrarAlerta("❌ Error al intentar abrir la notificación.");
                }
            }
        }
    }

    private void leerYProcesarPantalla() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        String textoCapturado = extraerUltimoMensajeReal(rootNode); 

        if (textoCapturado.isEmpty() || textoCapturado.equals(ultimoTextoRecibido) || esMensajeBasura(textoCapturado)) {
            return; 
        }

        ultimoTiempoProceso = System.currentTimeMillis();
        ultimoTextoRecibido = textoCapturado;
        
        mostrarAlerta("📩 Leyendo: " + textoCapturado);
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

    private boolean esMensajeBasura(String texto) {
        String textoLimpio = texto.toLowerCase();
        for (String palabra : PALABRAS_BLOQUEADAS) {
            if (textoLimpio.contains(palabra)) return true;
        }
        return false;
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

                mostrarAlerta("🧠 Respuesta generada. Inyectando...");
                escribirMensajeUniversal(response.toString());

            } catch (Exception e) {
                mostrarAlerta("❌ Error conectando al servidor.");
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
            
            try { Thread.sleep(1000); } catch (Exception e) {} 
            
            AccessibilityNodeInfo rootActualizado = getRootInActiveWindow();
            AccessibilityNodeInfo botonEnviar = encontrarBotonEnviar(rootActualizado);
            if (botonEnviar != null) {
                botonEnviar.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                mostrarAlerta("✅ ¡Bot: Mensaje enviado!");
            } else {
                mostrarAlerta("⚠️ Botón de enviar no encontrado.");
            }
        } else {
            mostrarAlerta("⚠️ Caja de texto no encontrada.");
        }
    }

    private AccessibilityNodeInfo encontrarCajaDeTexto(AccessibilityNodeInfo nodo) {
        if (nodo == null) return null;
        if (nodo.getClassName() != null && nodo.getClassName().toString().equals("android.widget.EditText")) {
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