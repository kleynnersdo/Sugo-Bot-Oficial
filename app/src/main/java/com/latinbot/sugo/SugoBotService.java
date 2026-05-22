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

    private static SugoBotService instanciaActiva;
    
    // ESCUDO SALVAVIDAS
    private static long tiempoInicioProcesamiento = 0; 
    private static final long TIEMPO_MAXIMO_ESPERA_MS = 10000;

    private final String RENDER_URL = "https://gaby-bot-server.onrender.com/bot";
    private final String SUGO_PACKAGE = "com.voicemaker.android";
    
    private final String ID_CAJA_TEXTO = "com.voicemaker.android:id/id_input_edit_text";
    private final String ID_BOTON_ENVIAR = "com.voicemaker.android:id/id_chat_send_btn";
    private final String ID_PERFIL_AVATAR = "com.voicemaker.android:id/id_chatting_title_avatar_iv";

    private final List<String> CADENAS_BLOQUEADAS = Arrays.asList(
        "sugo team", "asist. anfitrion", "sala chat", "te he seguido", 
        "aviso de interaccion", "ha reaccionado a", "le ha gustado tu mensaje", "le gusta tu mensaje",
        "flecha de cupido", "asist. juego", "sistema", "eventos"
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
    
    // NUEVAS VARIABLES PARA LA COLA DE ALTA CAPACIDAD
    private String ultimaFirmaProcesada = "";
    private long tiempoUltimaFirma = 0;
    
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

        // --- ESCUDO SALVAVIDAS INTACTO (Si se pega 10 seg, se auto-libera) ---
        long tiempoActual = System.currentTimeMillis();
        
        if (servicioOcupado) {
            if (tiempoInicioProcesamiento == 0) tiempoInicioProcesamiento = tiempoActual;
            
            if (tiempoActual - tiempoInicioProcesamiento > TIEMPO_MAXIMO_ESPERA_MS) {
                servicioOcupado = false; 
                esperandoCajaTexto = false;
                respuestaParaInyectar = "";
                tiempoInicioProcesamiento = 0; 
                
                performGlobalAction(GLOBAL_ACTION_BACK); 
                new Handler(Looper.getMainLooper()).postDelayed(this::procesarSiguienteTareaEnCola, 1200);
                return; 
            }
        } else {
            tiempoInicioProcesamiento = 0;
        }
        // ----------------------------------------------------------------------

        android.content.SharedPreferences prefs = getSharedPreferences("LatinBotPrefs", MODE_PRIVATE);
        if (!prefs.getBoolean("bot_activo", true)) return;

        if (event.getPackageName() != null && event.getPackageName().toString().equals(SUGO_PACKAGE)) {
            if (esperandoCajaTexto && !respuestaParaInyectar.isEmpty()) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                AccessibilityNodeInfo caja = encontrarNodoPorId(root, ID_CAJA_TEXTO);
                if (caja != null) {
                    ejecutarInyeccionYEnvio(root, caja);
                }
            }
        }
    }

    public static void procesarNotificacionDesdeListener(Notification notification) {
        if (instanciaActiva != null) {
            instanciaActiva.filtrarYEncolar(notification);
        }
    }

    private void filtrarYEncolar(Notification notification) {
        if (notification == null) return;
        
        String textoNotificacion = "";
        String tituloEmisor = "";

        // 1. EXTRAER TEXTO Y NOMBRE DEL EMISOR
        if (notification.extras != null) {
            CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
            if (text != null) textoNotificacion = text.toString();
            
            CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
            if (title != null) tituloEmisor = title.toString();
        } else if (notification.tickerText != null) {
            textoNotificacion = notification.tickerText.toString();
        }

        if (textoNotificacion.trim().isEmpty()) return;

        // 2. REVISAR PALABRAS PROHIBIDAS
        String textoLower = textoNotificacion.toLowerCase();
        for (String frase : CADENAS_BLOQUEADAS) {
            if (textoLower.contains(frase)) return; 
        }

        // 3. CREAR FIRMA ÚNICA (Emisor + Mensaje)
        String firmaUnica = tituloEmisor + "|" + textoNotificacion;

        // 4. ANTI-REBOTE (Evita que el sistema lea la misma notificación exacta 2 veces en menos de 1 segundo)
        if (firmaUnica.equals(ultimaFirmaProcesada) && (System.currentTimeMillis() - tiempoUltimaFirma < 1000)) {
            return; 
        }

        ultimaFirmaProcesada = firmaUnica;
        tiempoUltimaFirma = System.currentTimeMillis();

        // 5. ENCOLADO DIRECTO (Sin borrar duplicados de distintas personas)
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
        tiempoInicioProcesamiento = System.currentTimeMillis(); 
        respuestaParaInyectar = ""; 

        ejecutarFlujoDeRespuesta(tareaActual);
    }

    private void ejecutarFlujoDeRespuesta(BotTask tarea) {
        try {
            tarea.intent.send(); 
            
            new Thread(() -> {
                String respuestaIA = solicitarRespuestaServidor(tarea.mensaje);
                
                if (respuestaIA == null || respuestaIA.trim().isEmpty()) {
                    new Handler(Looper.getMainLooper()).post(this::forzarSalidaDeChat);
                    return;
                }

                respuestaParaInyectar = respuestaIA;

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

        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto);
        cajaDeTexto.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        
        try { Thread.sleep(350); } catch (Exception e) {} 

        AccessibilityNodeInfo botonEnviar = encontrarNodoPorId(root, ID_BOTON_ENVIAR);
        if (botonEnviar == null) botonEnviar = encontrarNodoPorId(getRootInActiveWindow(), ID_BOTON_ENVIAR);

        if (botonEnviar != null) botonEnviar.performAction(AccessibilityNodeInfo.ACTION_CLICK);

        new Handler(Looper.getMainLooper()).postDelayed(this::forzarSalidaDeChat, 600);
    }

    private void forzarSalidaDeChat() {
        esperandoCajaTexto = false;
        respuestaParaInyectar = "";
        tiempoInicioProcesamiento = 0; 

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        AccessibilityNodeInfo botonSalirPerfil = encontrarNodoPorId(rootNode, ID_PERFIL_AVATAR);
        
        if (botonSalirPerfil != null) {
            boolean exitoClic = botonSalirPerfil.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (!exitoClic && botonSalirPerfil.getParent() != null) {
                botonSalirPerfil.getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        } else performGlobalAction(GLOBAL_ACTION_BACK);
        
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