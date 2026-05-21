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

    private static boolean isProcessing = false; //
    private static long tiempoInicioProcesamiento = 0; 
    private static final long TIEMPO_MAXIMO_ESPERA_MS = 10000;

    private static SugoBotService instanciaActiva;

    private final String RENDER_URL = "https://gaby-bot-server.onrender.com/bot";
    private final String SUGO_PACKAGE = "com.voicemaker.android";
    
    // IDs estricto provistos por el Jefe
    private final String ID_CAJA_TEXTO = "com.voicemaker.android:id/id_input_edit_text";
    private final String ID_BOTON_ENVIAR = "com.voicemaker.android:id/id_chat_send_btn";
    private final String ID_PERFIL_AVATAR = "com.voicemaker.android:id/id_chatting_title_avatar_iv";

    private final List<String> CADENAS_BLOQUEADAS = Arrays.asList(
        "sugo team", "asist. anfitrion", "sala chat", "te he seguido", 
        "aviso de interaccion", "ha reaccionado a", "le ha gustado tu mensaje", "le gusta tu mensaje"
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

    // Variables de control de flujo reactivo
    private boolean esperandoCajaTexto = false;
    private String respuestaParaInyectar = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instanciaActiva = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        long tiempoActual = System.currentTimeMillis();
        
        if (isProcessing) {
            // Si el bot está ocupado pero el cronómetro está en 0, significa que acaba de empezar.
            // Iniciamos el conteo aquí mismo de forma automática.
            if (tiempoInicioProcesamiento == 0) {
                tiempoInicioProcesamiento = tiempoActual;
            }
            
            // Si ya pasaron más de 10 segundos atrapado en la misma pantalla...
            if (tiempoActual - tiempoInicioProcesamiento > TIEMPO_MAXIMO_ESPERA_MS) {
                isProcessing = false; 
                tiempoInicioProcesamiento = 0; // Reiniciamos el reloj
                performGlobalAction(GLOBAL_ACTION_BACK); // Forzar "Atrás" para cerrar la alerta del sistema
                return; // Libera la cola e ignora el bloqueo
            }
        } else {
            // Si el bot no está ocupado, nos aseguramos de que el reloj esté en 0
            tiempoInicioProcesamiento = 0;
        }

        // --- SISTEMA DE APAGADO ---
        android.content.SharedPreferences prefs = getSharedPreferences("LatinBotPrefs", MODE_PRIVATE);
        if (!prefs.getBoolean("bot_activo", true)) {
            return; // Si está apagado, el bot no inyecta texto ni da clics
        }

        // Mantener el caché de Android activo procesando los cambios de pantalla de SUGO
        if (event.getPackageName() != null && event.getPackageName().toString().equals(SUGO_PACKAGE)) {
            
            // Si el bot abrió el chat y la respuesta de la IA está lista esperando ser escrita
            if (esperandoCajaTexto && !respuestaParaInyectar.isEmpty()) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                AccessibilityNodeInfo caja = encontrarNodoPorId(root, ID_CAJA_TEXTO);
                if (caja != null) {
                    ejecutarInyeccionYEnvio(root, caja);
                }
            }
        }
    }

    // Puerto de entrada seguro desde el NotificationListener
    public static void procesarNotificacionDesdeListener(Notification notification) {
        if (instanciaActiva != null) {
            instanciaActiva.filtrarYEncolar(notification);
        }
    }

    private void filtrarYEncolar(Notification notification) {
        if (notification == null) return;
        
        String textoNotificacion = "";
        if (notification.extras != null) {
            CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
            if (text != null) textoNotificacion = text.toString();
        } else if (notification.tickerText != null) {
            textoNotificacion = notification.tickerText.toString();
        }

        if (textoNotificacion.trim().isEmpty()) return;

        String textoLower = textoNotificacion.toLowerCase();
        for (String frase : CADENAS_BLOQUEADAS) {
            if (textoLower.contains(frase)) return; 
        }

        if (textoNotificacion.equals(ultimoMensajeProcesado) && (System.currentTimeMillis() - tiempoUltimoMensaje < 4000)) {
            return; 
        }

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
            new Handler(Looper.getMainLooper()).post(this::procesarSiguienteTareaEnCola);
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
        esperandoCajaTexto = true;
        respuestaParaInyectar = ""; // Reset de seguridad

        ejecutarFlujoDeRespuesta(tareaActual);
    }

    private void ejecutarFlujoDeRespuesta(BotTask tarea) {
        try {
            tarea.intent.send(); // Abrir el chat automáticamente
            
            // Consultar el servidor Render en segundo plano
            new Thread(() -> {
                String respuestaIA = solicitarRespuestaServidor(tarea.mensaje);
                
                if (respuestaIA == null || respuestaIA.trim().isEmpty()) {
                    new Handler(Looper.getMainLooper()).post(this::forzarSalidaDeChat);
                    return;
                }

                respuestaParaInyectar = respuestaIA;

                // Sincronización activa: si la pantalla ya cargó, inyectamos de inmediato
                new Handler(Looper.getMainLooper()).post(() -> {
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    AccessibilityNodeInfo caja = encontrarNodoPorId(root, ID_CAJA_TEXTO);
                    if (caja != null && esperandoCajaTexto) {
                        ejecutarInyeccionYEnvio(root, caja);
                    }
                });

            }).start();

        } catch (Exception e) {
            forzarSalidaDeChat();
        }
    }

    private void ejecutarInyeccionYEnvio(AccessibilityNodeInfo root, AccessibilityNodeInfo cajaDeTexto) {
        esperandoCajaTexto = false; 
        String texto = respuestaParaInyectar;
        respuestaParaInyectar = ""; 

        // Inyección directa de texto sin usar portapapeles
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto);
        cajaDeTexto.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        
        try { Thread.sleep(350); } catch (Exception e) {} 

        AccessibilityNodeInfo botonEnviar = encontrarNodoPorId(root, ID_BOTON_ENVIAR);
        if (botonEnviar == null) {
            botonEnviar = encontrarNodoPorId(getRootInActiveWindow(), ID_BOTON_ENVIAR);
        }

        if (botonEnviar != null) {
            botonEnviar.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }

        // Proceder a la salida controlada
        new Handler(Looper.getMainLooper()).postDelayed(this::forzarSalidaDeChat, 600);
    }

    private void forzarSalidaDeChat() {
        esperandoCajaTexto = false;
        respuestaParaInyectar = "";

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        AccessibilityNodeInfo botonSalirPerfil = encontrarNodoPorId(rootNode, ID_PERFIL_AVATAR);
        
        if (botonSalirPerfil != null) {
            boolean exitoClic = botonSalirPerfil.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (!exitoClic && botonSalirPerfil.getParent() != null) {
                botonSalirPerfil.getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
        
        // Liberar hilo de control y avanzar de forma segura
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            servicioOcupado = false;
            procesarSiguienteTareaEnCola();
        }, 1200); 
    }

    private AccessibilityNodeInfo encontrarNodoPorId(AccessibilityNodeInfo root, String idCompleto) {
        if (root == null) return null;
        try {
            List<AccessibilityNodeInfo> nodos = root.findAccessibilityNodeInfosByViewId(idCompleto);
            if (nodos != null && !nodos.isEmpty()) return nodos.get(0);
        } catch (Exception e) {}
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
    public void onInterrupt() {
        instanciaActiva = null;
    }
}