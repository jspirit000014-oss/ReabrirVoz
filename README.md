# Reabrir Voz

App de Android que vigila la app de Copilot (paquete `com.microsoft.copilot`) y,
cuando la sesión de voz se apaga sola, vuelve a tocar el botón de voz.

## Compilar
1. Sube esta carpeta a un repositorio de GitHub (rama `main`).
2. En la pestaña Actions corre "Build APK" (se ejecuta solo con cada push).
3. Descarga el artefacto `ReabrirVoz-debug` (contiene `app-debug.apk`) e instálalo.

## Usar
1. Abre Reabrir Voz > botón 1 > activa el servicio de accesibilidad.
2. Botón 3: quita el ahorro de batería a la app.
3. Abre Copilot e inicia la voz. Cuando se apague, la app la reabre sola.

## Modo segundo plano (otra app)
Interruptor "Reabrir también cuando estoy en otra app" (activado por defecto).
1. Sales de Copilot con la voz activa: la app empieza a vigilar el micrófono.
2. Si el micrófono se apaga 4 s seguidos, abre Copilot, toca el botón de voz y,
   cuando la voz vuelve, te devuelve a la app donde estabas.
3. Botón 4: permiso "Mostrar sobre otras apps", para que Android deje abrir Copilot desde el fondo.
- Si Copilot corta el micrófono al salir (o Android lo silencia), no se activa y el registro lo dice.
- Máximo 3 reaperturas cada 10 minutos. Con la pantalla apagada no actúa.
- El micrófono se mide en general: si otra app usa el micrófono, la app cree que Copilot sigue activo.

## Siempre activo
Interruptor "Siempre activo en otras apps" (activado por defecto): si estás en otra app y el
micrófono lleva 5 s sin usarse, abre la voz de Copilot sin esperar a que hubiera una sesión.
Máximo 10 reaperturas cada 10 minutos; si Copilot no mantiene el micrófono en segundo plano, pausa 10 minutos.
Para detenerlo apaga "Reabrir la voz automáticamente".

## Si no toca el botón correcto
Los textos que busca son una suposición. Con la voz activa, deja que se apague,
vuelve a Reabrir Voz y usa "Compartir volcado": ahí aparece cómo se llama cada
botón de Copilot y se ajustan los textos en la pantalla de la app.

## Notas
- Cada reapertura es una sesión nueva de Copilot.
- Máximo 8 toques por minuto, para evitar bucles.
- Si tocas tú "terminar", espera 60 s antes de reabrir.
