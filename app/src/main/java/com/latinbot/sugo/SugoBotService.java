package com.latinbot.sugo;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.app.Notification;
import android.app.PendingIntent;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class SugoBotService extends AccessibilityService {

    private final String RENDER_URL = "https://gaby-bot-server.onrender.com/bot";
    private final String SUGO_PACKAGE = "com.voicemaker.android";
    
    // IDs de control provistos
    private final String ID_CAJA_TEXTO = "com.voicemaker.android:id/id_input_edit_text";
    private final String ID_BOTON_ENVIAR = "com.voicemaker.android:id/id_chat_send_btn";
    private final String ID_PERFIL_AVATAR = "com.voicemaker.android:id/id_chatting_title_avatar_iv";

    // Lista de notificaciones bloqueadas actualizada estrictamente por el Jefe
    private final List<String> CADENAS_BLOQUEADAS = Arrays.asList(
        "sugo team", 
        "asist. anfitrion", 
        "sala chat", 
        "te he seguido", 
        "aviso de interaccion", 
        "ha reaccionado a", 
        "le ha gustado tu mensaje", 
        "le gusta tu mensaje"
    );

    private static class BotTask {
        PendingIntent intent;
        String mensaje;
        BotTask(PendingIntent intent, String mensaje) {
            this.intent = intent;
            this.mensaje = mensaje;
        }
    }

    private final Queue<BotTask> colaDeTareas = new LinkedList<>();
    private boolean servicioOcupado = false;
    private String ultimoMensajeProcesado = "";
    private long tiempoUltimoMensaje = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        // Validar estrictamente el paquete de origen
        if (event.getPackageName() == null || !event.getPackageName().toString().equals(SUGO_PACKAGE)) {
            return;
        }
        
        try {
            // El único disparador del bot son las notificaciones entrantes de la barra superior
            if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                filtrarYEncolarNotificacion(event);
            }
        } catch (Exception e) {
            // Evitar detenciones del servicio
        }
    }

    private void filtrarYEncolarNotificacion(AccessibilityEvent event) {
        if (event.getParcelableData() != null && event.getParcelableData() instanceof Notification) {
            Notification notification = (Notification) event.getParcelableData();
            
            String textoNotificacion = "";
            if (notification.extras != null) {
                CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
                if (text != null) textoNotificacion = text.toString();
            } else if (notification.tickerText != null) {
                textoNotificacion = notification.tickerText.toString();
            }

            if (textoNotificacion.trim().isEmpty()) return;

            // Filtro estricto de exclusiones en minúsculas
            String textoLower = textoNotificacion.toLowerCase();
            for (String frase : CADENAS_BLOQUEADAS) {
                if (textoLower.contains(frase)) return; 
            }

            // Evitar procesamiento duplicado inmediato
            if (textoNotificacion.equals(ultimoMensajeProcesado) && (System.currentTimeMillis() - tiempoUltimoMensaje < 4000)) {
                return; 
            }

            // Evitar duplicados dentro de la misma cola en espera
            synchronized (colaDeTareas) {
                for (BotTask tarea : colaDeTareas) {
                    if (tarea.mensaje.equals(textoNotificacion)) return;
                }
            }

            ultimoMensajeProcesado = textoNotificacion;
            tiempoUltimoMensaje = System.currentTimeMillis();

            if (notification.contentIntent != null) {
                synchronized (colaDeTareas) {
                    colaDeTareas.add(new BotTask(notification.contentIntent, textoNotificacion));
                }
                procesarSiguienteTareaEnCola();
            }
        }
    }

    private void procesarSiguienteTareaEnCola() {
        if (servicioOcupado) return; 

        BotTask tareaActual;
        synchronized (colaDeTareas) {
            if (colaDeTareas.isEmpty()) {
                servicioOcupado = false;
                return;
            }
            tareaActual = colaDeTareas.poll(); 
        }

        servicioOcupado = true;
        ejecutarFlujoDeRespuesta(tareaActual);
    }

    private void ejecutarFlujoDeRespuesta(BotTask tarea) {
        try {
            tarea.intent.send(); // Abrir pantalla de chat
            
            new Thread(() -> {
                String respuestaIA = solicitarRespuestaServidor(tarea.mensaje);
                
                if (respuestaIA == null || respuestaIA.trim().isEmpty()) {
                    forzarSalidaDeChat();
                    return;
                }

                // Ejecución lineal sin bucles de retorno en el hilo principal
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    inyectarTextoYEnviarUnicaVez(respuestaIA);
                }, 1300); // Espera estratégica para asegurar carga visual de SUGO

            }).start();

        } catch (Exception e) {
            forzarSalidaDeChat();
        }
    }

    private void inyectarTextoYEnviarUnicaVez(String textoAResponder) {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            forzarSalidaDeChat();
            return;
        }

        AccessibilityNodeInfo cajaDeTexto = encontrarNodoPorId(rootNode, ID_CAJA_TEXTO);
        
        if (cajaDeTexto != null) {
            // NUEVA FUNCIÓN: Inyección directa mediante Bundle sin usar el portapapeles del teléfono
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textoAResponder);
            cajaDeTexto.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            
            // Pausa física mínima para asentamiento del texto en la interfaz
            try { Thread.sleep(300); } catch (Exception e) {} 
            
            AccessibilityNodeInfo rootActualizado = getRootInActiveWindow();
            AccessibilityNodeInfo botonEnviar = encontrarNodoPorId(rootActualizado, ID_BOTON_ENVIAR);
            
            if (botonEnviar != null) {
                botonEnviar.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
        
        // No hay reintentos. Se asume el envío único e inmediatamente se procede a la salida.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            forzarSalidaDeChat();
        }, 500);
    }

    private void forzarSalidaDeChat() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        AccessibilityNodeInfo botonSalirPerfil = encontrarNodoPorId(rootNode, ID_PERFIL_AVATAR);
        
        if (botonSalirPerfil != null) {
            // Intentar hacer clic en el avatar para salir
            boolean exitoClic = botonSalirPerfil.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            
            // Si el nodo del avatar no es directamente clickeable, intentar con su contenedor padre
            if (!exitoClic && botonSalirPerfil.getParent() != null) {
                botonSalirPerfil.getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        } else {
            // Respaldo global del sistema por si el chat cambió drásticamente de estado
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
        
        // Liberar el estado del servicio y avanzar al siguiente elemento de la cola de tareas
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            servicioOcupado = false;
            procesarSiguienteTareaEnCola();
        }, 1100); // Tiempo óptimo para que la UI regrese a la vista global
    }

    private AccessibilityNodeInfo encontrarNodoPorId(AccessibilityNodeInfo root, String idCompleto) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> nodos = root.findAccessibilityNodeInfosByViewId(idCompleto);
        if (nodos != null && !nodos.isEmpty()) {
            return nodos.get(0);
        }
        return null;
    }

    private String solicitarRespuestaServidor(String mensajeUsuario) {
        try {
            URL url = new URL(RENDER_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; utf-8");
            conn.setDoOutput(true);

            String mensajeSeguro = mensajeUsuario.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
            String jsonInputString = "{\"message\": \"" + mensajeSeguro + "\", \"user_id\": \"telefono_1\"}";

            try(OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonInputString.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"));
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) response.append(responseLine.trim());
            return response.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onInterrupt() {}
}