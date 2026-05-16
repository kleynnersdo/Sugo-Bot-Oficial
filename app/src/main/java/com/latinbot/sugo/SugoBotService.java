package com.latinbot.sugo;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;
import android.util.Log;
import java.util.Arrays;
import java.util.List;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class SugoBotService extends AccessibilityService {

    // --- CONFIGURACIÓN DE TU APP Y API ---
    private final String TARGET_PACKAGE = "com.voicemaker.android";
    private final String ID_INPUT = "com.voicemaker.android:id/id_input_edit_text";
    private final String ID_SEND = "com.voicemaker.android:id/id_chat_send_btn";
    
    // 🚨 REEMPLAZA ESTO POR TU URL REAL DE RENDER (asegúrate de que termine en /bot)
    private final String RENDER_URL = "https://gaby-bot-server.onrender.com/bot";

    // --- FILTRO DE SEGURIDAD (No gasta Render) ---
    private final List<String> PALABRAS_BLOQUEADAS = Arrays.asList(
        "sistema", "ganaste", "reaccionó", "eliminó", "soporte", "diamantes", "recarga", "emparejado"
    );

    private long ultimoMensajeProcesado = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null || !event.getPackageName().toString().equals(TARGET_PACKAGE)) {
            return;
        }

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Evita procesar la pantalla de forma repetitiva en milisegundos
            if (System.currentTimeMillis() - ultimoMensajeProcesado > 2000) { 
                leerYProcesarPantalla();
            }
        }
    }

    private void leerYProcesarPantalla() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        String textoCapturado = capturarUltimoMensaje(rootNode); 

        if (textoCapturado.isEmpty() || esMensajeBasura(textoCapturado)) {
            return; 
        }

        ultimoMensajeProcesado = System.currentTimeMillis();
        enviarARender(textoCapturado);
    }

    private String capturarUltimoMensaje(AccessibilityNodeInfo root) {
        // Estructura de captura base
        return "hola gaby"; 
    }

    private boolean esMensajeBasura(String texto) {
        String textoLimpio = texto.toLowerCase();
        for (String palabra : PALABRAS_BLOQUEADAS) {
            if (textoLimpio.contains(palabra)) return true;
        }
        return false;
    }

    // --- CONEXIÓN CON TU PYTHON EN RENDER ---
    private void enviarARender(String mensaje) {
        new Thread(() -> {
            try {
                URL url = new URL(RENDER_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setRequestProperty("Accept", "text/plain"); // Recibe el formato plano de tu Flask
                conn.setDoOutput(true);

                // Evitamos que comillas rompan la estructura JSON
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

                // Inyectamos la respuesta limpia de Gabriela directo a la app
                escribirMensaje(response.toString());

            } catch (Exception e) {
                Log.e("SUGO_BOT", "Error en Render: " + e.getMessage());
            }
        }).start();
    }

    // --- ACCIÓN: ESCRIBIR Y ENVIAR EN SUGO ---
    private void escribirMensaje(String textoResponder) {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        List<AccessibilityNodeInfo> inputs = rootNode.findAccessibilityNodeInfosByViewId(ID_INPUT);
        if (!inputs.isEmpty()) {
            AccessibilityNodeInfo inputNode = inputs.get(0);
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textoResponder);
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            
            try { Thread.sleep(600); } catch (Exception e) {} // Pausa natural antes de enviar
            
            List<AccessibilityNodeInfo> sends = rootNode.findAccessibilityNodeInfosByViewId(ID_SEND);
            if (!sends.isEmpty()) {
                sends.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
    }

    @Override
    public void onInterrupt() {}
}