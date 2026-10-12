# PDFlow

Lector y editor de PDF tipo Acrobat que corre 100% en el navegador (PWA, funciona sin conexión). Los archivos nunca salen del dispositivo.

Abrir: `pdf/index.html` (servido por HTTP, p. ej. GitHub Pages o `python3 -m http.server`).

## Funciones
- **Lector**: vista continua, miniaturas, zoom (botones, Ctrl+rueda, ajustar al ancho), ir a página, selección y copia de texto.
- **Buscar** texto en todo el documento (Ctrl+F) con resultados y resaltado.
- **Editar PDF**: toca cualquier línea de texto para cambiarla o borrarla (se detectan fuente, tamaño y colores), inserta imágenes (mover y redimensionar), tapa contenido y agrega páginas en blanco.
- **Anotar**: resaltar, dibujo libre, texto, rectángulos; 5 colores; mover, borrar y deshacer (Ctrl+Z).
- **Firmar**: dibuja tu firma una vez (se recuerda) y colócala en cualquier página.
- **Organizar**: reordenar (arrastrar o flechas), girar, eliminar, insertar otro PDF, extraer páginas a un PDF nuevo.
- **Combinar** varios PDF e **imágenes a PDF** (JPG/PNG).
- **Descargar, compartir e imprimir** con todas las anotaciones incrustadas en el PDF.
- **Recientes** guardados localmente (IndexedDB).

## Tecnología
HTML/CSS/JS sin build. [pdf.js](https://mozilla.github.io/pdf.js/) 3.11.174 para renderizar y [pdf-lib](https://pdf-lib.js.org/) 1.17.1 para escribir el PDF (ambos en `vendor/`).

## Limitaciones
- Editar texto reemplaza visualmente la línea: el texto original queda tapado, no eliminado del archivo.
- Se editan líneas horizontales; el texto girado dentro de la página no se puede editar.
- El texto nuevo usa la fuente estándar más parecida (Helvetica, Times o Courier).
