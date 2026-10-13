# PDFlow

Lector y editor de PDF tipo Acrobat que corre 100% en el navegador (PWA, funciona sin conexión). Los archivos nunca salen del dispositivo.

Abrir: `pdf/index.html` (servido por HTTP, p. ej. GitHub Pages o `python3 -m http.server`).

## Funciones
- **Lector**: vista continua, miniaturas, zoom (botones, Ctrl+rueda, ajustar al ancho), ir a página, selección y copia de texto.
- **Buscar** texto en todo el documento (Ctrl+F) con resultados y resaltado.
- **Editar PDF**: toca cualquier línea de texto para cambiarla o borrarla. Cada tramo conserva su formato (fuente, negrita, cursiva, tamaño y color) y Ctrl+B / Ctrl+I cambian el estilo de lo seleccionado; inserta imágenes (mover y redimensionar), tapa contenido y agrega páginas en blanco.
- **Texto nuevo**: selector de fuente (Helvetica, Times, Courier, Arial, Calibri, Cambria), negrita y tamaño; se ajusta solo a la fuente principal del documento. Los montos alineados a la derecha siguen alineados al editarlos.
- **Anotar**: resaltar, dibujo libre, texto, rectángulos; 5 colores; mover, borrar y deshacer (Ctrl+Z).
- **Firmar**: dibuja tu firma una vez (se recuerda) y colócala en cualquier página.
- **Organizar**: reordenar (arrastrar o flechas), girar, eliminar, insertar otro PDF, extraer páginas a un PDF nuevo.
- **Combinar** varios PDF e **imágenes a PDF** (JPG/PNG).
- **Descargar, compartir e imprimir** con todas las anotaciones incrustadas en el PDF.
- **Recientes** guardados localmente (IndexedDB).

## Tecnología
HTML/CSS/JS sin build. [pdf.js](https://mozilla.github.io/pdf.js/) 3.11.174 para renderizar, [pdf-lib](https://pdf-lib.js.org/) 1.17.1 y @pdf-lib/fontkit 1.1.1 para escribir el PDF e incrustar fuentes (en `vendor/`).

## Limitaciones
- Editar texto reemplaza visualmente la línea: el texto original queda tapado, no eliminado del archivo.
- Cada celda de una tabla se edita por separado (se detectan bordes y columnas); se editan líneas horizontales; el texto girado dentro de la página no se puede editar.
- Si el PDF usa las fuentes estándar (Courier, Helvetica, Times), el texto editado usa exactamente esas mismas fuentes. Si no, usa fuentes libres equivalentes incluidas en `fonts/` (Calibri→Carlito, Cambria→Caladea, Arial→Liberation Sans, Times→Liberation Serif, Courier→Liberation Mono); otras fuentes se aproximan a la más parecida.
