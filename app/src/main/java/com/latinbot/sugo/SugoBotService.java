package com.latinbot.sugo;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Handler;
import android.os.Looper;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
    
    // IDs proporcionados por el usuario
    private final String ID_CAJA_TEXTO = "com.voicemaker.android:id/id_input_edit_text";
    private final String ID_BOTON_ENVIAR = "com.voicemaker.android:id/id_chat_send_btn";
    private final String ID_PERFIL_AVATAR = "com.voicemaker.android:id/id_chatting_title_avatar_iv";

    private final List<String> CADENAS_BLOQUEADAS = Arrays.asList(
        "te he seguido", 
        "podemos ser amigos", 
        "sugo team", 
        "soporte", 
        "ha reaccionado a tu mensaje"
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

        // FILTRO DE SEGURIDAD INTERNO: Solo procesar el paquete objetivo de SUGO
        if (event.getPackageName() == null || !event.getPackageName().toString().equals(SUGO_PACKAGE)) {
            return;
        }
        
        try {
            if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                filtrarYEncolarNotificacion(event);
            }
        } catch (Exception e) {
            // Protección del hilo principal del servicio
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

            // Filtro de cadenas prohibidas
            String textoLower = textoNotificacion.toLowerCase();
            for (String frase : CADENAS_BLOQUEADAS) {
                if (textoLower.contains(frase)) return; 
            }

            // Filtro anti-spam en ráfaga
            if (textoNotificacion.equals(ultimoMensajeProcesado) && (System.currentTimeMillis() - tiempoUltimoMensaje < 4000)) {
                return; 
            }

            // VERIFICACIÓN DE LA COLA: Si el mensaje ya está esperando ser respondido, no lo duplicamos
            synchronized (colaDeTareas) {
                for (BotTask tarea : colaDeTareas) {
                    if (tarea.mensaje.equals(textoNotificacion)) {
                        return; // Duplicado detectado en cola, se descarta
                    }
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
            tarea.intent.send(); // Abrir el chat correspondiente
            
            new Thread(() -> {
                String respuestaIA = solicitarRespuestaServidor(tarea.mensaje);
                
                if (respuestaIA == null || respuestaIA.trim().isEmpty()) {
                    forzarSalidaDeChat();
                    return;
                }

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    inyectarTextoYVerificarEnvio(respuestaIA, 0);
                }, 1200); // Tiempo para asegurar la carga visual del chat

            }).start();

        } catch (Exception e) {
            forzarSalidaDeChat();
        }
    }

    private void inyectarTextoYVerificarEnvio(String textoAResponder, int reintentos) {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            reintentarOAbandonar(textoAResponder, reintentos);
            return;
        }

        // Búsqueda directa por ID del cuadro de texto proporcionado
        AccessibilityNodeInfo cajaDeTexto = encontrarNodoPorId(rootNode, ID_CAJA_TEXTO);
        
        if (cajaDeTexto != null) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("IA_Data", textoAResponder);
                clipboard.setPrimaryClip(clip);

                cajaDeTexto.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                cajaDeTexto.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                
                try { Thread.sleep(300); } catch (Exception e) {} 
                
                // Búsqueda directa por ID del botón enviar proporcionado
                AccessibilityNodeInfo rootActualizado = getRootInActiveWindow();
                AccessibilityNodeInfo botonEnviar = encontrarNodoPorId(rootActualizado, ID_BOTON_ENVIAR);
                
                if (botonEnviar != null) {
                    botonEnviar.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    
                    // Verificación de éxito de envío
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        AccessibilityNodeInfo rootVerificacion = getRootInActiveWindow();
                        AccessibilityNodeInfo cajaVerificar = encontrarNodoPorId(rootVerificacion, ID_CAJA_TEXTO);
                        
                        if (cajaVerificar != null && cajaVerificar.getText() != null && cajaVerificar.getText().toString().length() > 0) {
                            if (reintentos < 2) {
                                inyectarTextoYVerificarEnvio(textoAResponder, reintentos + 1);
                            } else {
                                forzarSalidaDeChat();
                            }
                        } else {
                            // Éxito: Caja vacía. Procedemos a salir inmediatamente.
                            forzarSalidaDeChat();
                        }
                    }, 600);
                } else {
                    reintentarOAbandonar(textoAResponder, reintentos);
                }
            }
        } else {
            reintentarOAbandonar(textoAResponder, reintentos);
        }
    }

    private void reintentarOAbandonar(String texto, int reintentos) {
        if (reintentos < 2) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                inyectarTextoYVerificarEnvio(texto, reintentos + 1);
            }, 800);
        } else {
            forzarSalidaDeChat();
        }
    }

    private void forzarSalidaDeChat() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        // Intentar usar el ID de la foto de perfil/avatar para salir como se solicitó
        AccessibilityNodeInfo botonSalirPerfil = encontrarNodoPorId(rootNode, ID_PERFIL_AVATAR);
        
        if (botonSalirPerfil != null) {
            botonSalirPerfil.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } else {
            // Mecanismo de respaldo nativo si el ID no responde o no es clickeable directamente
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
        
        // Pausa para asegurar el cierre de la pantalla antes de liberar el semáforo de la cola
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            servicioOcupado = false;
            procesarSiguienteTareaEnCola();
        }, 1000);
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