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
    
    private final List<String> CADENAS_BLOQUEADAS = Arrays.asList(
        "te he seguido", 
        "podemos ser amigos", 
        "sugo team", 
        "soporte", 
        "ha reaccionado a tu mensaje"
    );

    // Estructura de datos para almacenar las tareas pendientes (Estructura de Cola)
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
    private String ultimoMensajeGlobal = "";
    private long tiempoUltimoMensaje = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        logFlotante("🤖 Sistema de Cola de Respuestas Activado.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        
        try {
            // El bot es estrictamente guiado por eventos de notificación entrantes
            if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                filtrarYEncolarNotificacion(event);
            }
        } catch (Exception e) {
            // Evitar cierres por excepciones de puntero nulo en la lectura del sistema
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

            // Filtro Anti-Spam (Evitar duplicación en ráfaga corta)
            if (textoNotificacion.equals(ultimoMensajeGlobal) && (System.currentTimeMillis() - tiempoUltimoMensaje < 4000)) {
                return; 
            }
            
            ultimoMensajeGlobal = textoNotificacion;
            tiempoUltimoMensaje = System.currentTimeMillis();

            if (notification.contentIntent != null) {
                // Añadimos de forma segura la estructura a la cola secuencial
                synchronized (colaDeTareas) {
                    colaDeTareas.add(new BotTask(notification.contentIntent, textoNotificacion));
                }
                procesarSiguienteTareaEnCola();
            }
        }
    }

    private void procesarSiguienteTareaEnCola() {
        if (servicioOcupado) return; // Bloqueo si hay una operación de chat en curso

        BotTask tareaActual;
        synchronized (colaDeTareas) {
            if (colaDeTareas.isEmpty()) {
                servicioOcupado = false;
                return;
            }
            tareaActual = colaDeTareas.poll(); // Extrae el primer elemento de la cola
        }

        servicioOcupado = true;
        ejecutarFlujoDeRespuesta(tareaActual);
    }

    private void ejecutarFlujoDeRespuesta(BotTask tarea) {
        try {
            // Paso 1: Abrir la ventana del chat de forma remota
            tarea.intent.send();
            
            // Paso 2: Consultar al servidor Python en un hilo secundario
            new Thread(() -> {
                String respuestaIA = solicitarRespuestaServidor(tarea.mensaje);
                
                if (respuestaIA == null || respuestaIA.trim().isEmpty()) {
                    // Si el servidor falla, liberamos el chat y continuamos con la cola
                    forzarSalidaDeChat();
                    return;
                }

                // Paso 3: Retornar al hilo principal de la interfaz para escribir (Ajustado para teléfonos lentos)
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    inyectarTextoYVerificarEnvio(respuestaIA, 0);
                }, 1500); // Tiempo prudencial para la carga inicial de la ventana de chat

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

        AccessibilityNodeInfo cajaDeTexto = encontrarCajaDeTexto(rootNode);
        if (cajaDeTexto != null) {
            // Copiar el texto limpio de la IA al portapapeles del sistema
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("IA_Data", textoAResponder);
                clipboard.setPrimaryClip(clip);

                // Ejecutar foco y acción nativa de pegado
                cajaDeTexto.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                cajaDeTexto.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                
                // Retardo de procesamiento físico de entrada de caracteres
                try { Thread.sleep(400); } catch (Exception e) {} 
                
                AccessibilityNodeInfo rootActualizado = getRootInActiveWindow();
                AccessibilityNodeInfo botonEnviar = encontrarBotonEnviar(rootActualizado);
                
                if (botonEnviar != null) {
                    botonEnviar.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    
                    // --- CONTROL DE VERIFICACIÓN SEGURO (Mata el fallo de MacroDroid) ---
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        AccessibilityNodeInfo rootVerificacion = getRootInActiveWindow();
                        AccessibilityNodeInfo cajaVerificar = encontrarCajaDeTexto(rootVerificacion);
                        
                        // Si la casilla sigue conteniendo texto, el envío falló debido a lentitud del hardware
                        if (cajaVerificar != null && cajaVerificar.getText() != null && cajaVerificar.getText().toString().length() > 0) {
                            if (reintentos < 2) {
                                // Forzar reintento incrementando el contador
                                inyectarTextoYVerificarEnvio(textoAResponder, reintentos + 1);
                            } else {
                                forzarSalidaDeChat();
                            }
                        } else {
                            // Casilla vacía = Mensaje enviado con éxito total. Procedemos a salir.
                            forzarSalidaDeChat();
                        }
                    }, 800); // Ventana de tiempo para que la UI procese el envío
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
            }, 1000); // Esperar un segundo extra si el teléfono está congelado
        } else {
            forzarSalidaDeChat();
        }
    }

    private void forzarSalidaDeChat() {
        // Ejecución de la acción nativa del sistema para simular clic hacia atrás
        performGlobalAction(GLOBAL_ACTION_BACK);
        
        // Retardo para que la pantalla de SUGO se cierre antes de tomar el próximo elemento de la cola
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            servicioOcupado = false;
            procesarSiguienteTareaEnCola();
        }, 1200);
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
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            return response.toString();
        } catch (Exception e) {
            return "";
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

    private void logFlotante(String mensaje) {
        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(getApplicationContext(), mensaje, Toast.LENGTH_SHORT).show()
        );
    }

    @Override
    public void onInterrupt() {}
}