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
