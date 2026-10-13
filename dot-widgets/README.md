# Dot Widgets

App Android de widgets para la pantalla de inicio en estilo matriz de puntos, inspirada en la estética de Nothing OS. Es una implementación propia desde cero: no contiene código, gráficos ni recursos de otras apps.

## Widgets

| Widget | Estilos | Al tocarlo |
|---|---|---|
| Reloj | Digital, apilado, analógico de puntos | Abre las alarmas |
| Batería | Anillo, barra | Refresca al momento |
| Calendario | Mes, semana | Abre el calendario |
| Fecha | Grande, línea | Abre el calendario |
| Progreso | Cuadrícula, barra · periodo día / semana / mes / año | Abre el editor |
| Cuenta atrás | Días hasta (o desde) una fecha con título | Abre el editor |
| Nota | Texto propio, centrado o a la izquierda | Abre el editor |
| Tiempo | Ahora, por horas, semana · °C o °F | Actualiza el pronóstico |
| Música | Compacto, grande, carátula · carátula en puntos, puntos a color o foto | Abre el reproductor; botones ⏮ ⏯ ⏭ |

## Fondo de pantalla

En la app, la sección **Fondo de pantalla** pone un fondo liso para que los widgets resalten: negro, negro con puntos, grafito o claro con puntos. Se puede aplicar a inicio, bloqueo o ambos. La app no puede guardar una copia del fondo anterior; **Restaurar el fondo predeterminado** vuelve al de fábrica.

## Widget del tiempo

Usa [Open-Meteo](https://open-meteo.com) (gratis, sin cuenta ni clave; datos bajo CC BY 4.0, citado en la app).

- **Ubicación**, compartida por todos los widgets del tiempo: busca tu ciudad (sin permisos) o pulsa **Usar mi ubicación**, que pide la ubicación aproximada una sola vez con la app abierta. No se usa ubicación en segundo plano.
- Se actualiza cada ~30 minutos cuando hay internet (JobScheduler, agrupado por el sistema), al tocar el widget y al cambiar de ubicación.
- Sin conexión muestra los últimos datos; si tienen más de 90 minutos, indica la hora (`act. 10:30`).
- Iconos de puntos para despejado (sol o luna), parcial, nublado, niebla, llovizna, lluvia, nieve y tormenta.
- CI prueba el lector de datos contra la API real en cada compilación.

## Widget de música

Funciona con Spotify y con cualquier reproductor que publique una sesión multimedia (YouTube Music, podcasts…). Lee la sesión activa del sistema, la misma que usan los controles de la pantalla de bloqueo: no hace falta cuenta de Spotify ni clave de API.

- Necesita **acceso a notificaciones** (la app te lleva a esa pantalla). Solo se usa para leer la música en reproducción.
- Android 13+ bloquea ese permiso en apps instaladas desde un APK. Si el interruptor sale gris: Ajustes → Apps → Dot Widgets → menú ⋮ → **Permitir ajustes restringidos**, y vuelve a activarlo.
- La canción, la carátula y el estado se actualizan al instante; la barra de progreso avanza cada minuto.
- Si el título no cabe en una línea, se muestra entero por partes que se deslizan cada ~2,5 s, como un letrero.
- Si no suena nada, ▶ reanuda el último reproductor y al tocar el widget se abre Spotify.

## Personalización

Cada widget tiene su propio editor con vista previa en vivo. Se abre al añadirlo y luego desde **Tus widgets → Editar** en la app (o manteniendo pulsado el widget en Android 12+):

- 21 paletas de color, incluidas 3 de Material You (Android 12+)
- Color de acento activable
- Tipografía: puntos (Doto), sans o mono
- Opacidad del fondo (0–100 %) y cuatro tipos de esquinas
- Tamaño del contenido (60–140 %), además de redimensionar el widget
- Reloj: mostrar fecha, formato automático, 12 h o 24 h

## Cómo se actualiza

Todos los widgets se dibujan como imagen con la tipografía de puntos. Así se ven igual en cualquier launcher; antes el reloj usaba `TextClock` con una fuente propia, que muchos launchers no cargan.

- Reloj y batería: una alarma exacta en cada cambio de minuto. No despierta el teléfono: si la pantalla está apagada, se actualiza en cuanto se enciende.
- El resto: cada ~15 minutos, y al cambiar la hora, la zona horaria o el idioma, al reiniciar y al actualizar la app.

En Android 13+ el permiso de alarmas exactas se concede solo (`USE_EXACT_ALARM`). En Android 12, si lo desactivas, la app muestra un aviso para activarlo.

## Instalar en el móvil

1. En GitHub, abre **Actions → Dot Widgets APK** y entra en la última ejecución verde.
2. Descarga el artefacto `dot-widgets-apk` y descomprímelo en el móvil.
3. Abre `app-debug.apk` y permite la instalación desde esa fuente.

A partir de la 0.2 las actualizaciones se instalan encima. Si tienes la 0.1, desinstálala antes una vez (estaba firmada con otra clave).

Requiere Android 8.0 o superior.

## Compilar localmente

```sh
cd dot-widgets
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Necesita JDK 17 y el Android SDK (plataforma 35).

## Créditos

Tipografía [Doto](https://github.com/oliverlalan/Doto) de Óliver Lalan, licencia SIL Open Font License 1.1 (ver `FONT-LICENSE-OFL.txt`). Se incluyen dos instancias estáticas (peso 500 y 700, redondeado) generadas de la fuente variable.
