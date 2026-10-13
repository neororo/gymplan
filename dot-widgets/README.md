# Dot Widgets

App Android de widgets para la pantalla de inicio en estilo matriz de puntos, inspirada en la estética de Nothing OS. Es una implementación propia desde cero: no contiene código, gráficos ni recursos de otras apps.

## Widgets

| Widget | Tamaño inicial | Qué muestra | Al tocarlo |
|---|---|---|---|
| Reloj de puntos | 4×2 | Hora y fecha (se actualiza sola cada minuto) | Abre las alarmas |
| Batería | 2×2 | Anillo de 24 puntos con el nivel; rojo por debajo del 20 %, `CHG` mientras carga | Refresca los widgets |
| Calendario | 3×3 | El mes actual con el día de hoy marcado en rojo | Abre el calendario en hoy |
| Año en puntos | 4×2 | Un punto por día del año y el % transcurrido | Abre la app |

Todos se pueden redimensionar. En la app se elige el tema (oscuro, claro o Material You en Android 12+) y se pueden añadir los widgets directamente a la pantalla de inicio.

Batería, calendario y año se redibujan cada ~15 minutos con una alarma inexacta (no consume batería apreciable) y al tocar el widget de batería.

## Instalar en el móvil

1. En GitHub, abre **Actions → Dot Widgets APK** y entra en la última ejecución verde.
2. Descarga el artefacto `dot-widgets-apk` y descomprímelo en el móvil.
3. Abre `app-debug.apk` y permite la instalación desde esa fuente.

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
