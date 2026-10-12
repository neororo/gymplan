'use strict';

pdfjsLib.GlobalWorkerOptions.workerSrc = window.PDF_WORKER_SRC || 'vendor/pdf.worker.min.js';
const { PDFDocument, StandardFonts, rgb, degrees, pushGraphicsState, popGraphicsState, concatTransformationMatrix } = PDFLib;

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const SVGNS = 'http://www.w3.org/2000/svg';

const COLORS = ['#ffd400', '#2bd46b', '#3fa9ff', '#ff4d6d', '#111111'];
const MIN_SCALE = 0.25, MAX_SCALE = 5;

/* ── Document model ──
   sources: loaded files ({ bytes, pdf (pdf.js), pages[] (pdf.js page cache) })
   pages:   ordered page list ({ src, idx, rot (extra rotation), annots[] })
   Annotation coordinates are stored in PDF user space (points), so they
   survive zoom and rotation and can be written back with pdf-lib as-is. */
const state = {
  name: '',
  sources: [],
  pages: [],
  scale: 1,
  tool: 'select',
  color: COLORS[0],
  textSize: 14,
  pendingImage: null,
  history: [],
  query: '',
  current: 0,
  dirty: false,
  recentId: null,
};

let observer = null;
let thumbCache = new Map();
let pickerMode = 'open';

/* ── Utilities ── */
function toast(msg, ms = 2400) {
  const t = document.createElement('div');
  t.className = 'toast';
  t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), ms);
}

async function busy(fn) {
  const b = document.createElement('div');
  b.className = 'busy';
  b.innerHTML = '<div class="spinner"></div>';
  document.body.appendChild(b);
  try { return await fn(); }
  catch (err) { console.error(err); toast('Error: ' + (err.message || err)); }
  finally { b.remove(); }
}

function hexToRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return rgb(((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255);
}

function baseName(name) { return name.replace(/\.[^.]+$/, ''); }

// Inside the claude.ai viewer, files are offered through the `downloads`
// capability; everywhere else a regular <a download> link is used.
const embedded = typeof window.claude?.use === 'function';
let downloadsApi;
async function download(bytes, filename) {
  if (embedded) {
    if (downloadsApi === undefined) downloadsApi = await window.claude.use('downloads');
    if (!downloadsApi) { toast('La descarga no está disponible en esta vista'); return false; }
    try {
      await downloadsApi.save({ filename, data: new Blob([bytes], { type: 'application/pdf' }) });
      return true;
    } catch (err) {
      if (err?.code !== 'declined') toast('No se pudo guardar el archivo: ' + (err?.message || err?.code || err));
      return false;
    }
  }
  const url = URL.createObjectURL(new Blob([bytes], { type: 'application/pdf' }));
  const a = document.createElement('a');
  a.href = url; a.download = filename;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
  return true;
}

function confirmModal(message, okLabel = 'Aceptar') {
  return new Promise(resolve => {
    const bg = document.createElement('div');
    bg.className = 'modal-bg';
    bg.innerHTML = `<div class="modal" role="dialog"><p></p><div class="modal-actions">
      <button class="btn" data-k="no">Cancelar</button>
      <button class="btn primary" data-k="yes"></button></div></div>`;
    bg.querySelector('p').textContent = message;
    bg.querySelector('[data-k="yes"]').textContent = okLabel;
    document.body.appendChild(bg);
    bg.addEventListener('click', e => {
      const k = e.target.closest('[data-k]')?.dataset.k;
      if (k || e.target === bg) { bg.remove(); resolve(k === 'yes'); }
    });
  });
}

function showView(id) {
  for (const v of ['home', 'viewer', 'organizer']) $('#' + v).hidden = v !== id;
}

function readFile(file) {
  return file.arrayBuffer().then(b => new Uint8Array(b));
}

/* ── Recent files (IndexedDB) ── */
const db = (() => {
  let p;
  const open = () => p || (p = new Promise((res, rej) => {
    const r = indexedDB.open('pdflow', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('recent', { keyPath: 'id' });
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  }));
  const tx = async (mode, fn) => {
    const d = await open();
    return new Promise((res, rej) => {
      const t = d.transaction('recent', mode);
      const req = fn(t.objectStore('recent'));
      t.oncomplete = () => res(req && req.result);
      t.onerror = () => rej(t.error);
    });
  };
  return {
    all: () => tx('readonly', s => s.getAll()).catch(() => []),
    put: v => tx('readwrite', s => s.put(v)).catch(() => {}),
    del: id => tx('readwrite', s => s.delete(id)).catch(() => {}),
    get: id => tx('readonly', s => s.get(id)).catch(() => null),
  };
})();

async function saveRecent(name, bytes) {
  const id = name + ':' + bytes.length;
  await db.put({ id, name, size: bytes.length, date: Date.now(), bytes });
  const all = (await db.all()).sort((a, b) => b.date - a.date);
  for (const r of all.slice(8)) await db.del(r.id);
  return id;
}

async function renderRecents() {
  const box = $('#recents');
  const all = (await db.all()).sort((a, b) => b.date - a.date);
  box.innerHTML = '';
  if (!all.length) {
    box.innerHTML = '<div class="empty-note">Los archivos que abras aparecerán aquí.</div>';
    return;
  }
  for (const r of all) {
    const b = document.createElement('button');
    b.className = 'recent';
    const kb = r.size > 1048576 ? (r.size / 1048576).toFixed(1) + ' MB' : Math.ceil(r.size / 1024) + ' KB';
    b.innerHTML = `<svg class="i" style="color:var(--accent)"><use href="#i-file"/></svg>
      <div class="grow"><div></div><div class="meta">${new Date(r.date).toLocaleString()} · ${kb}</div></div>
      <span class="icon-btn" title="Quitar de recientes"><svg class="i"><use href="#i-x"/></svg></span>`;
    b.querySelector('.grow div').textContent = r.name;
    b.addEventListener('click', async e => {
      if (e.target.closest('.icon-btn')) { await db.del(r.id); renderRecents(); return; }
      busy(async () => { await loadDocument([{ name: r.name, bytes: r.bytes }]); state.recentId = r.id; openViewer(); });
    });
    box.appendChild(b);
  }
}

/* ── Loading ── */
async function addSource(bytes) {
  const pdf = await pdfjsLib.getDocument({ data: bytes.slice() }).promise;
  const src = { bytes, pdf, pages: [] };
  for (let i = 1; i <= pdf.numPages; i++) src.pages.push(await pdf.getPage(i));
  state.sources.push(src);
  return state.sources.length - 1;
}

async function appendPdf(bytes) {
  const s = await addSource(bytes);
  for (let i = 0; i < state.sources[s].pages.length; i++) {
    state.pages.push({ src: s, idx: i, rot: 0, annots: [] });
  }
}

async function imagesToPdf(files) {
  const doc = await PDFDocument.create();
  for (const f of files) {
    const bytes = await readFile(f);
    const img = /png$/i.test(f.type) || /\.png$/i.test(f.name) ? await doc.embedPng(bytes) : await doc.embedJpg(bytes);
    // Fit to A4 (portrait or landscape following the image)
    const land = img.width > img.height;
    const [pw, ph] = land ? [842, 595] : [595, 842];
    const k = Math.min((pw - 40) / img.width, (ph - 40) / img.height, 1);
    const w = img.width * k, h = img.height * k;
    const page = doc.addPage([pw, ph]);
    page.drawImage(img, { x: (pw - w) / 2, y: (ph - h) / 2, width: w, height: h });
  }
  return doc.save();
}

function resetDoc() {
  state.sources = [];
  state.pages = [];
  state.history = [];
  state.query = '';
  state.current = 0;
  state.dirty = false;
  state.recentId = null;
  thumbCache = new Map();
  textCache.clear();
}

async function loadDocument(items) {
  resetDoc();
  for (const it of items) await appendPdf(it.bytes);
  state.name = items.length === 1 ? baseName(items[0].name) : 'Combinado';
}

async function handleFiles(fileList, mode) {
  const files = [...fileList];
  if (!files.length) return;
  const isImg = f => /^image\//.test(f.type) || /\.(png|jpe?g)$/i.test(f.name);
  await busy(async () => {
    if (mode === 'org-add') {
      for (const f of files) {
        await appendPdf(isImg(f) ? await imagesToPdf([f]) : await readFile(f));
      }
      state.dirty = true;
      renderOrganizer();
      return;
    }
    const imgs = files.filter(isImg);
    const pdfs = files.filter(f => !isImg(f));
    const items = [];
    for (const f of pdfs) items.push({ name: f.name, bytes: await readFile(f) });
    if (imgs.length) items.push({ name: imgs.length === 1 ? imgs[0].name : 'Imágenes', bytes: await imagesToPdf(imgs) });
    await loadDocument(items);
    if (items.length === 1 && pdfs.length === 1) state.recentId = await saveRecent(pdfs[0].name, items[0].bytes);
    if (items.length > 1 || imgs.length) state.dirty = true;

    if (mode === 'organize' || mode === 'extract') {
      openOrganizer(mode === 'extract');
    } else {
      openViewer();
      if (mode === 'sign') setTool('sign');
      if (mode === 'edit') setTool('edit');
    }
  });
}

async function openSample() {
  await busy(async () => {
    const doc = await PDFDocument.create();
    const bold = await doc.embedFont(StandardFonts.HelveticaBold);
    const reg = await doc.embedFont(StandardFonts.Helvetica);
    const ink = rgb(0.1, 0.1, 0.12), grey = rgb(0.42, 0.42, 0.46);
    const pages = [
      ['Contrato de arriendo', ['Entre las partes que suscriben se acuerda el arriendo del inmueble', 'ubicado en Av. Providencia 1234, depto. 56, Santiago.', '', 'Primera. El plazo del contrato es de 12 meses a contar del', '1 de noviembre de 2026, renovable por periodos iguales.', '', 'Segunda. La renta mensual es de $650.000, pagadera dentro de', 'los primeros cinco días de cada mes.', '', 'Tercera. El arrendatario entrega una garantía equivalente', 'a un mes de renta.']],
      ['Anexo A: inventario', ['Cocina equipada con horno y encimera.', 'Dos dormitorios con closet.', 'Un estacionamiento y una bodega.', '', 'El arrendatario declara recibir el inmueble en buen estado.']],
      ['Firmas', ['', 'Arrendador: ______________________', '', '', 'Arrendatario: ______________________', '', '', 'Santiago, 12 de octubre de 2026']],
    ];
    pages.forEach(([title, lines], i) => {
      const p = doc.addPage([595, 842]);
      p.drawText('PDF de ejemplo', { x: 60, y: 800, size: 9, font: reg, color: grey });
      p.drawText(title, { x: 60, y: 750, size: 24, font: bold, color: ink });
      lines.forEach((l, j) => p.drawText(l, { x: 60, y: 700 - j * 22, size: 12, font: reg, color: ink }));
      p.drawText(`Página ${i + 1} de ${pages.length}`, { x: 260, y: 40, size: 9, font: reg, color: grey });
    });
    await loadDocument([{ name: 'Contrato de ejemplo.pdf', bytes: await doc.save() }]);
    openViewer();
  });
}

function pick(mode) {
  pickerMode = mode;
  const inp = $('#fileInput');
  inp.multiple = mode === 'merge' || mode === 'images' || mode === 'org-add';
  inp.accept = mode === 'images' ? 'image/png,image/jpeg' : 'application/pdf,.pdf,image/png,image/jpeg';
  inp.value = '';
  inp.click();
}

/* ── Geometry helpers ── */
function pdfPage(p) { return state.sources[p.src].pages[p.idx]; }
function totalRot(p) { return (pdfPage(p).rotate + p.rot) % 360; }
function viewportOf(p, scale = state.scale) {
  return pdfPage(p).getViewport({ scale, rotation: totalRot(p) });
}

/* ── Viewer ── */
function openViewer() {
  showView('viewer');
  $('#docName').textContent = state.name + '.pdf';
  $('#pageCount').textContent = state.pages.length;
  if (window.innerWidth <= 720) $('#sidebar').hidden = true;
  fitWidth(false);
  buildPages();
  buildThumbs();
  setCurrent(Math.min(state.current, state.pages.length - 1));
  setTool(state.tool === 'sign' ? 'select' : state.tool);
}

function fitWidth(rebuild = true) {
  if (!state.pages.length) return;
  const container = $('#pages');
  const avail = Math.max(200, (container.clientWidth || window.innerWidth) - 32);
  const w = viewportOf(state.pages[0], 1).width;
  state.scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, Math.min(avail / w, 1.6)));
  if (rebuild) buildPages();
}

function setZoom(scale) {
  const container = $('#pages');
  const cur = state.current;
  state.scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale));
  buildPages();
  scrollToPage(cur, false);
  void container;
}

function buildPages() {
  const container = $('#pages');
  if (observer) observer.disconnect();
  container.innerHTML = '';
  $('#zoomLbl').textContent = Math.round(state.scale * 100) + '%';

  observer = new IntersectionObserver(entries => {
    for (const e of entries) if (e.isIntersecting) renderPage(+e.target.dataset.n);
  }, { root: container, rootMargin: '600px 0px' });

  state.pages.forEach((p, n) => {
    const vp = viewportOf(p);
    const div = document.createElement('div');
    div.className = 'page';
    div.dataset.n = n;
    div.style.width = vp.width + 'px';
    div.style.height = vp.height + 'px';
    div.innerHTML = `<div class="num">${n + 1}</div>`;
    const svg = document.createElementNS(SVGNS, 'svg');
    svg.classList.add('overlay');
    svg.setAttribute('viewBox', `0 0 ${vp.width} ${vp.height}`);
    div.appendChild(svg);
    container.appendChild(div);
    attachPointer(svg, n);
    drawAnnots(n);
    observer.observe(div);
  });
}

async function renderPage(n) {
  const div = $(`.page[data-n="${n}"]`);
  if (!div || div.dataset.rendered) return;
  div.dataset.rendered = '1';
  const p = state.pages[n];
  const page = pdfPage(p);
  const vp = viewportOf(p);
  const dpr = Math.min(window.devicePixelRatio || 1, 2);

  const canvas = document.createElement('canvas');
  canvas.width = Math.floor(vp.width * dpr);
  canvas.height = Math.floor(vp.height * dpr);
  div.prepend(canvas);
  let ready;
  div._ready = new Promise(r => { ready = r; });
  await page.render({
    canvasContext: canvas.getContext('2d'),
    viewport: vp,
    transform: dpr !== 1 ? [dpr, 0, 0, dpr, 0, 0] : null,
  }).promise.finally(ready);

  const tl = document.createElement('div');
  tl.className = 'textLayer';
  tl.style.setProperty('--scale-factor', vp.scale);
  canvas.after(tl);
  const textContent = await page.getTextContent();
  await pdfjsLib.renderTextLayer({ textContentSource: textContent, container: tl, viewport: vp }).promise;
  markHits(tl);
}

function markHits(tl) {
  const q = state.query.toLowerCase();
  for (const s of $$('span', tl)) {
    s.classList.toggle('hit', !!q && s.textContent.toLowerCase().includes(q));
  }
}

function scrollToPage(n, smooth = true) {
  const div = $(`.page[data-n="${n}"]`);
  if (!div) return;
  $('#pages').scrollTo({ top: div.offsetTop - 12, behavior: smooth ? 'smooth' : 'auto' });
  setCurrent(n);
}

function setCurrent(n) {
  if (n < 0 || n >= state.pages.length) return;
  state.current = n;
  $('#pageInput').value = n + 1;
  for (const t of $$('.thumb')) t.classList.toggle('cur', +t.dataset.n === n);
}

function trackCurrentPage() {
  const container = $('#pages');
  const mid = container.scrollTop + container.clientHeight / 3;
  let best = 0;
  for (const d of $$('.page', container)) {
    if (d.offsetTop <= mid) best = +d.dataset.n; else break;
  }
  if (best !== state.current) {
    setCurrent(best);
    const t = $(`.thumb[data-n="${best}"]`);
    if (t && !$('#sidebar').hidden) t.scrollIntoView({ block: 'nearest' });
  }
}

/* ── Thumbnails ── */
async function thumbnail(p) {
  const key = `${p.src}:${p.idx}:${p.rot}`;
  if (thumbCache.has(key)) return thumbCache.get(key);
  const promise = (async () => {
    const base = viewportOf(p, 1);
    const vp = viewportOf(p, 260 / Math.max(base.width, base.height));
    const c = document.createElement('canvas');
    c.width = Math.ceil(vp.width); c.height = Math.ceil(vp.height);
    await pdfPage(p).render({ canvasContext: c.getContext('2d'), viewport: vp }).promise;
    return c.toDataURL('image/jpeg', 0.8);
  })();
  thumbCache.set(key, promise);
  return promise;
}

function buildThumbs() {
  const box = $('#thumbs');
  box.innerHTML = '';
  const io = new IntersectionObserver(async entries => {
    for (const e of entries) {
      if (!e.isIntersecting) continue;
      io.unobserve(e.target);
      const n = +e.target.dataset.n;
      const img = new Image();
      img.src = await thumbnail(state.pages[n]);
      img.alt = 'Página ' + (n + 1);
      e.target.querySelector('.ph').replaceWith(img);
    }
  }, { root: $('.side-body'), rootMargin: '300px' });
  state.pages.forEach((p, n) => {
    const t = document.createElement('button');
    t.className = 'thumb' + (n === state.current ? ' cur' : '');
    t.dataset.n = n;
    t.innerHTML = `<div class="ph"></div><div>${n + 1}</div>`;
    t.addEventListener('click', () => {
      scrollToPage(n);
      if (window.innerWidth <= 720) $('#sidebar').hidden = true;
    });
    box.appendChild(t);
    io.observe(t);
  });
}

/* ── Annotations: drawing on overlay ── */
// Families used for replaced text. The bundled fonts are metric-compatible
// with the common Office/PDF fonts, so edited lines keep their width.
const FONT_FAMILIES = {
  sans:    { file: 'LiberationSans',  css: 'Arial, Helvetica, sans-serif',      std: 0 },
  serif:   { file: 'LiberationSerif', css: '"Times New Roman", Times, serif',   std: 1 },
  mono:    { file: 'LiberationMono',  css: '"Courier New", Courier, monospace', std: 2 },
  calibri: { file: 'Carlito',         css: 'Calibri, sans-serif',               std: 0 },
  cambria: { file: 'Caladea',         css: 'Cambria, serif',                    std: 1 },
};
const STD_FONTS = [
  ['Helvetica', 'Helvetica-Bold', 'Helvetica-Oblique', 'Helvetica-BoldOblique'],
  ['Times-Roman', 'Times-Bold', 'Times-Italic', 'Times-BoldItalic'],
  ['Courier', 'Courier-Bold', 'Courier-Oblique', 'Courier-BoldOblique'],
];
const STYLE_SUFFIX = ['Regular', 'Bold', 'Italic', 'BoldItalic'];
const DEFAULT_FONT = { family: 'sans', bold: false, italic: false };
const styleIndex = f => (f.bold ? 1 : 0) + (f.italic ? 2 : 0);
const fontFile = (f = DEFAULT_FONT) => `fonts/${FONT_FAMILIES[f.family].file}-${STYLE_SUFFIX[styleIndex(f)]}.ttf`;
const stdFontName = (f = DEFAULT_FONT) => STD_FONTS[FONT_FAMILIES[f.family].std][styleIndex(f)];
const familyCss = (f = DEFAULT_FONT) => `"PF ${FONT_FAMILIES[f.family].file}", ${FONT_FAMILIES[f.family].css}`;
const fontCss = (f = DEFAULT_FONT, px) => `${f.italic ? 'italic ' : ''}${f.bold ? 700 : 400} ${px}px ${familyCss(f)}`;
const sameFont = (a, b) => a.family === b.family && !!a.bold === !!b.bold && !!a.italic === !!b.italic;

const fontBytes = new Map();
function loadFontBytes(f) {
  const url = fontFile(f);
  if (!fontBytes.has(url)) {
    fontBytes.set(url, fetch(url).then(r => (r.ok ? r.arrayBuffer() : null)).catch(() => null));
  }
  return fontBytes.get(url);
}

const faces = new Map();
function ensureFace(f = DEFAULT_FONT) {
  const url = fontFile(f);
  if (!faces.has(url)) {
    faces.set(url, loadFontBytes(f).then(async buf => {
      if (!buf) return;
      const face = new FontFace(`PF ${FONT_FAMILIES[f.family].file}`, buf.slice(0), {
        weight: f.bold ? '700' : '400', style: f.italic ? 'italic' : 'normal',
      });
      await face.load();
      document.fonts.add(face);
    }).catch(() => {}));
  }
  return faces.get(url);
}

const measureCtx = document.createElement('canvas').getContext('2d');
function textWidthPt(text, size, font) {
  measureCtx.font = fontCss(font, size);
  return Math.max(0, ...text.split('\n').map(l => measureCtx.measureText(l).width));
}

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
const rgbToHex = c => {
  const m = c.match(/\d+(\.\d+)?/g);
  return m ? '#' + m.slice(0, 3).map(v => Math.round(+v).toString(16).padStart(2, '0')).join('') : '#000000';
};

function svgEl(tag, attrs) {
  const el = document.createElementNS(SVGNS, tag);
  for (const k in attrs) el.setAttribute(k, attrs[k]);
  return el;
}

function textNode(a, ax, ay, s) {
  const f = a.font || DEFAULT_FONT;
  const el = svgEl('text', {
    x: ax, y: ay, fill: a.color, 'font-size': a.size * s,
    'font-family': familyCss(f), 'font-weight': f.bold ? 700 : 400,
    'font-style': f.italic ? 'italic' : 'normal', style: 'white-space:pre',
  });
  a.text.split('\n').forEach((line, i) => {
    const ts = svgEl('tspan', { x: ax, dy: i ? a.size * 1.15 * s : 0 });
    ts.textContent = line || ' ';
    el.appendChild(ts);
  });
  return el;
}

function rectBox(vp, x1, y1, x2, y2) {
  const [ax, ay] = vp.convertToViewportPoint(x1, y1), [bx, by] = vp.convertToViewportPoint(x2, y2);
  return { x: Math.min(ax, bx), y: Math.min(ay, by), width: Math.abs(bx - ax), height: Math.abs(by - ay) };
}

function annotElement(a, vp, R) {
  const s = vp.scale;
  if (a.type === 'highlight' || a.type === 'rect' || a.type === 'whiteout') {
    const box = rectBox(vp, a.x1, a.y1, a.x2, a.y2);
    if (a.type === 'highlight') return svgEl('rect', { ...box, fill: a.color, 'fill-opacity': 0.4, style: 'mix-blend-mode:multiply' });
    if (a.type === 'whiteout') return svgEl('rect', { ...box, fill: a.color });
    return svgEl('rect', { ...box, fill: 'none', stroke: a.color, 'stroke-width': a.width * s });
  }
  if (a.type === 'ink') {
    const pts = a.points.map(([x, y]) => vp.convertToViewportPoint(x, y).map(v => v.toFixed(1)).join(',')).join(' ');
    return svgEl('polyline', { points: pts, fill: 'none', stroke: a.color, 'stroke-width': a.width * s, 'stroke-linecap': 'round', 'stroke-linejoin': 'round' });
  }
  // text, edits & images are anchored at their local origin (baseline-left /
  // bottom-left) and keep the orientation they had when created (a.r0).
  const [ax, ay] = vp.convertToViewportPoint(a.x, a.y);
  const turn = ((R - a.r0) % 360 + 360) % 360;
  let el;
  if (a.type === 'edit') {
    // replaced line: cover the original, then write each formatted run on top
    el = svgEl('g', {});
    el.appendChild(svgEl('rect', { ...rectBox(vp, a.x1, a.y1, Math.max(a.x2, a.x + (a.tw || 0) + 1), a.y2), fill: a.bg }));
    if (!a._editing) {
      const g = svgEl('g', turn ? { transform: `rotate(${turn} ${ax} ${ay})` } : {});
      let cx = 0;
      for (const r of a.runs) {
        const rx = ax + cx * s, ry = ay - r.dy * s;
        const t = svgEl('text', {
          x: rx, y: ry, fill: r.color, 'font-size': r.size * s, 'font-family': familyCss(r.font),
          'font-weight': r.font.bold ? 700 : 400, 'font-style': r.font.italic ? 'italic' : 'normal', style: 'white-space:pre',
        });
        if (r.hs !== 1) t.setAttribute('transform', `translate(${rx} 0) scale(${r.hs} 1) translate(${-rx} 0)`);
        t.textContent = r.text;
        g.appendChild(t);
        cx += r.w;
      }
      el.appendChild(g);
    }
    return el;
  }
  if (a.type === 'text') {
    el = a._editing ? svgEl('g', {}) : textNode(a, ax, ay, s);
  } else {
    el = svgEl('image', { x: ax, y: ay - a.h * s, width: a.w * s, height: a.h * s, href: a.data, preserveAspectRatio: 'none' });
  }
  if (turn) el.setAttribute('transform', `rotate(${turn} ${ax} ${ay})`);
  return el;
}

function drawAnnots(n) {
  const div = $(`.page[data-n="${n}"]`);
  if (!div) return;
  const svg = $('svg.overlay', div);
  const p = state.pages[n];
  const vp = viewportOf(p);
  const R = totalRot(p);
  svg.innerHTML = '';
  p.annots.forEach((a, i) => {
    const el = annotElement(a, vp, R);
    el.dataset.i = i;
    svg.appendChild(el);
    // resize handle on the bottom-right corner of upright images
    if (state.tool === 'move' && a.type === 'image' && R === a.r0) {
      const [ax, ay] = vp.convertToViewportPoint(a.x, a.y);
      const h = svgEl('rect', { x: ax + a.w * vp.scale - 7, y: ay - 7, width: 14, height: 14, rx: 3, class: 'handle' });
      h.dataset.i = i;
      h.dataset.handle = '1';
      svg.appendChild(h);
    }
  });
}

function redrawAll() { state.pages.forEach((_, n) => drawAnnots(n)); }

function addAnnot(n, a) {
  state.pages[n].annots.push(a);
  state.history.push({ type: 'add', page: state.pages[n], annot: a });
  state.dirty = true;
  drawAnnots(n);
  updateUndo();
}

function removeAnnot(n, i) {
  const p = state.pages[n];
  const [a] = p.annots.splice(i, 1);
  state.history.push({ type: 'remove', page: p, annot: a, index: i });
  state.dirty = true;
  drawAnnots(n);
  updateUndo();
}

const snapshot = a => JSON.parse(JSON.stringify(a));

function pushModify(page, annot, before) {
  state.history.push({ type: 'modify', page, annot, before });
  state.dirty = true;
  updateUndo();
}

function translateAnnot(a, dx, dy) {
  if ('x1' in a) { a.x1 += dx; a.x2 += dx; a.y1 += dy; a.y2 += dy; }
  if ('x' in a) { a.x += dx; a.y += dy; }
  if (a.points) a.points = a.points.map(([x, y]) => [x + dx, y + dy]);
}

function undo() {
  const h = state.history.pop();
  if (!h) return;
  if (h.type === 'add') h.page.annots.splice(h.page.annots.indexOf(h.annot), 1);
  else if (h.type === 'remove') h.page.annots.splice(h.index, 0, h.annot);
  else if (h.type === 'modify') {
    for (const k of Object.keys(h.annot)) delete h.annot[k];
    Object.assign(h.annot, h.before);
  }
  const n = state.pages.indexOf(h.page);
  if (n >= 0) drawAnnots(n);
  updateUndo();
}

function updateUndo() { $('#undoBtn').disabled = !state.history.length; }

function localPoint(svg, e) {
  const r = svg.getBoundingClientRect();
  return [e.clientX - r.left, e.clientY - r.top];
}

/* ── Existing text: lines, fonts and colours read from the page ── */
const textCache = new Map();

function fontObject(page, fontId) {
  try { return page.commonObjs.has(fontId) ? page.commonObjs.get(fontId) : null; } catch { return null; }
}

// Maps a PDF font (e.g. "ABCDEF+Calibri-BoldItalic") to the closest bundled family and style.
function detectFont(page, fontId, style) {
  const obj = fontObject(page, fontId);
  const n = (obj?.name || '').replace(/^[A-Z]{6}\+/, '').toLowerCase();
  const fam = style?.fontFamily || '';
  let family = 'sans';
  if (/calibri|carlito/.test(n)) family = 'calibri';
  else if (/cambria|caladea/.test(n)) family = 'cambria';
  else if (/courier|mono|consol|menlo|cousine/.test(n) || fam === 'monospace') family = 'mono';
  else if (/times|georgia|garamond|roman|minion|palatino|antiqua|baskerville|bookman|century|tinos|(?<!sans-?)serif/.test(n) || (!n && fam === 'serif')) family = 'serif';
  return {
    family,
    bold: /bold|black|heavy|semibold|demi|extrab/.test(n) || !!obj?.bold || !!obj?.black,
    italic: /italic|oblique|-it$|kursiv/.test(n) || !!obj?.italic,
  };
}

// Groups pdf.js text runs into lines. A line keeps its runs separately, so
// a bold word or a coloured phrase inside a sentence keeps its format.
async function textItems(p) {
  const key = `${p.src}:${p.idx}`;
  if (textCache.has(key)) return textCache.get(key);
  const tc = await pdfPage(p).getTextContent();
  const runs = [];
  for (const it of tc.items) {
    if (!it.str || !it.transform) continue;
    const [a, b, c, d, e, f] = it.transform;
    if (Math.abs(b) > 0.01 || Math.abs(c) > 0.01 || a <= 0) continue; // horizontal text only
    const size = Math.abs(d) || a;
    const prev = runs[runs.length - 1];
    const near = prev && Math.abs(prev.y - f) < size * 0.3 && e >= prev.x2 - size * 0.3 && e - prev.x2 < size * 0.8;
    if (!it.str.trim()) {
      if (near && !prev.str.endsWith(' ')) { prev.str += ' '; prev.x2 = Math.max(prev.x2, e + it.width); }
      continue;
    }
    if (near && prev.fontId === it.fontName && Math.abs(prev.size - size) < 0.5) {
      if (e - prev.x2 > size * 0.15 && !prev.str.endsWith(' ') && !it.str.startsWith(' ')) prev.str += ' ';
      prev.str += it.str;
      prev.x2 = Math.max(prev.x2, e + it.width);
      continue;
    }
    runs.push({ str: it.str, x: e, y: f, x2: e + it.width, size, fontId: it.fontName, style: tc.styles[it.fontName] });
  }
  const lines = [];
  for (const r of runs) {
    const l = lines[lines.length - 1];
    const last = l && l.runs[l.runs.length - 1];
    const big = last ? Math.max(last.size, r.size) : 0;
    if (last && Math.abs(last.y - r.y) < big * 0.35 && r.x >= last.x2 - big * 0.3 && r.x - last.x2 < big * 1.2) {
      if (r.x - last.x2 > r.size * 0.15 && !last.str.endsWith(' ') && !r.str.startsWith(' ')) { last.str += ' '; last.x2 = r.x; }
      l.runs.push(r);
    } else {
      lines.push({ runs: [r] });
    }
  }
  for (const l of lines) {
    l.x1 = l.runs[0].x;
    l.y = l.runs[0].y;
    l.x2 = Math.max(...l.runs.map(r => r.x2));
    l.y1 = Math.min(...l.runs.map(r => r.y - r.size * 0.25));
    l.y2 = Math.max(...l.runs.map(r => r.y + r.size * 0.92));
  }
  textCache.set(key, lines);
  return lines;
}

function findLine(lines, px, py) {
  return lines.find(l => px >= l.x1 - 2 && px <= l.x2 + 2 && py >= l.y1 - 1 && py <= l.y2 + 1);
}

// Background = most common colour in the box, text = the one furthest from it.
function sampleColors(n, box) {
  const fallback = { fg: '#000000', bg: '#ffffff' };
  const c = $(`.page[data-n="${n}"] canvas`);
  if (!c) return fallback;
  const k = c.width / parseFloat(c.parentElement.style.width);
  const x = Math.max(0, Math.floor(box.x * k)), y = Math.max(0, Math.floor(box.y * k));
  const w = Math.min(c.width - x, Math.ceil(box.width * k)), h = Math.min(c.height - y, Math.ceil(box.height * k));
  if (w < 1 || h < 1) return fallback;
  let data;
  try { data = c.getContext('2d').getImageData(x, y, w, h).data; } catch { return fallback; }
  const buckets = new Map();
  for (let i = 0; i < data.length; i += 4) {
    const key = ((data[i] >> 4) << 8) | ((data[i + 1] >> 4) << 4) | (data[i + 2] >> 4);
    const b = buckets.get(key) || [0, 0, 0, 0];
    b[0] += data[i]; b[1] += data[i + 1]; b[2] += data[i + 2]; b[3]++;
    buckets.set(key, b);
  }
  let best = null;
  for (const b of buckets.values()) if (!best || b[3] > best[3]) best = b;
  const bg = best.slice(0, 3).map(v => Math.round(v / best[3]));
  let fg = [0, 0, 0], far = 0;
  for (let i = 0; i < data.length; i += 4) {
    const dist = Math.abs(data[i] - bg[0]) + Math.abs(data[i + 1] - bg[1]) + Math.abs(data[i + 2] - bg[2]);
    if (dist > far) { far = dist; fg = [data[i], data[i + 1], data[i + 2]]; }
  }
  const hex = v => '#' + v.map(q => q.toString(16).padStart(2, '0')).join('');
  return { bg: hex(bg), fg: far > 90 ? hex(fg) : '#000000' };
}

function showHover(svg, n, e) {
  let hb = $('.hover-box', svg);
  const p = state.pages[n];
  const lines = textCache.get(`${p.src}:${p.idx}`);
  if (!lines) { textItems(p); return; }
  const vp = viewportOf(p);
  const [vx, vy] = localPoint(svg, e);
  const l = e.target.closest('[data-i]') ? null : findLine(lines, ...vp.convertToPdfPoint(vx, vy));
  if (!l) { if (hb) hb.remove(); return; }
  if (!hb) { hb = svgEl('rect', { class: 'hover-box', rx: 2 }); svg.appendChild(hb); }
  const box = rectBox(vp, l.x1, l.y1, l.x2, l.y2);
  for (const k in box) hb.setAttribute(k, box[k] + (k === 'x' || k === 'y' ? -2 : 4));
}

/* ── Pointer handling ── */
function attachPointer(svg, n) {
  let drag = null;

  svg.addEventListener('pointerdown', e => {
    if (e.button !== 0) return;
    const p = state.pages[n];
    const vp = viewportOf(p);
    const [vx, vy] = localPoint(svg, e);
    const toPdf = (x, y) => vp.convertToPdfPoint(x, y);
    const tool = state.tool;
    const hit = e.target.closest('[data-i]');
    const hitAnnot = hit ? p.annots[+hit.dataset.i] : null;

    if (tool === 'erase') {
      if (hit) removeAnnot(n, +hit.dataset.i);
      return;
    }
    if (tool === 'move') {
      if (!hitAnnot) return;
      if (hit.dataset.handle) {
        const [ax, ay] = vp.convertToViewportPoint(hitAnnot.x, hitAnnot.y);
        drag = { kind: 'resize', annot: hitAnnot, before: snapshot(hitAnnot), tl: [ax, ay - hitAnnot.h * vp.scale], vp };
      } else {
        drag = { kind: 'move', annot: hitAnnot, before: snapshot(hitAnnot), last: toPdf(vx, vy), vp };
      }
    } else if (tool === 'text' || tool === 'edit') {
      e.preventDefault();
      if (hitAnnot && (hitAnnot.type === 'text' || hitAnnot.type === 'edit')) reopenText(n, hitAnnot);
      else if (tool === 'text') openTextInput(n, vx, vy);
      else editLineAt(n, vx, vy, [e.clientX, e.clientY]);
      return;
    } else if (tool === 'sign') {
      e.preventDefault();
      placeSignature(n, vx, vy);
      return;
    } else if (tool === 'image') {
      e.preventDefault();
      placeImage(n, vx, vy);
      return;
    } else if (tool === 'highlight' || tool === 'rect' || tool === 'whiteout') {
      const temp = svgEl('rect', tool === 'highlight' ? { fill: state.color, 'fill-opacity': 0.4 }
        : tool === 'whiteout' ? { fill: '#ffffff', stroke: '#999', 'stroke-dasharray': '4 3' }
        : { fill: 'none', stroke: state.color, 'stroke-width': 2 * vp.scale });
      svg.appendChild(temp);
      drag = { kind: tool, x0: vx, y0: vy, x1: vx, y1: vy, temp, vp };
    } else if (tool === 'draw') {
      const temp = svgEl('polyline', { fill: 'none', stroke: state.color, 'stroke-width': 2 * vp.scale, 'stroke-linecap': 'round', 'stroke-linejoin': 'round' });
      svg.appendChild(temp);
      drag = { kind: 'draw', pts: [[vx, vy]], temp, vp };
    } else return;
    e.preventDefault();
    svg.setPointerCapture(e.pointerId);
  });

  svg.addEventListener('pointermove', e => {
    if (!drag) {
      if (state.tool === 'edit' && e.pointerType !== 'touch') showHover(svg, n, e);
      return;
    }
    const [vx, vy] = localPoint(svg, e);
    if (drag.kind === 'draw') {
      drag.pts.push([vx, vy]);
      drag.temp.setAttribute('points', drag.pts.map(q => q.join(',')).join(' '));
    } else if (drag.kind === 'move') {
      const cur = drag.vp.convertToPdfPoint(vx, vy);
      translateAnnot(drag.annot, cur[0] - drag.last[0], cur[1] - drag.last[1]);
      drag.last = cur;
      drawAnnots(n);
    } else if (drag.kind === 'resize') {
      const a = drag.annot, s = drag.vp.scale;
      const ratio = drag.before.h / drag.before.w;
      a.w = Math.max(12, (vx - drag.tl[0]) / s);
      a.h = a.w * ratio;
      [a.x, a.y] = drag.vp.convertToPdfPoint(drag.tl[0], drag.tl[1] + a.h * s);
      drawAnnots(n);
    } else {
      drag.x1 = vx; drag.y1 = vy;
      drag.temp.setAttribute('x', Math.min(drag.x0, vx));
      drag.temp.setAttribute('y', Math.min(drag.y0, vy));
      drag.temp.setAttribute('width', Math.abs(vx - drag.x0));
      drag.temp.setAttribute('height', Math.abs(vy - drag.y0));
    }
  });

  svg.addEventListener('pointerleave', () => { if (!drag) $('.hover-box', svg)?.remove(); });

  const end = () => {
    if (!drag) return;
    const d = drag; drag = null;
    if (d.temp) d.temp.remove();
    const toPdf = (x, y) => d.vp.convertToPdfPoint(x, y);
    if (d.kind === 'move' || d.kind === 'resize') {
      if (JSON.stringify(d.before) !== JSON.stringify(d.annot)) pushModify(state.pages[n], d.annot, d.before);
    } else if (d.kind === 'draw') {
      if (d.pts.length < 2) return;
      addAnnot(n, { type: 'ink', color: state.color, width: 2, points: d.pts.map(([x, y]) => toPdf(x, y)) });
    } else {
      if (Math.abs(d.x1 - d.x0) < 3 || Math.abs(d.y1 - d.y0) < 3) return;
      const [x1, y1] = toPdf(d.x0, d.y0), [x2, y2] = toPdf(d.x1, d.y1);
      const color = d.kind === 'whiteout' ? '#ffffff' : state.color;
      addAnnot(n, { type: d.kind, color, width: 2, x1, y1, x2, y2 });
    }
  };
  svg.addEventListener('pointerup', end);
  svg.addEventListener('pointercancel', end);
}

/* ── Text editing ── */
// Floating editor; calls done(text) on commit or done(null) on cancel.
function openEditor(n, { left, top, text = '', size, font = DEFAULT_FONT, color = '#000000', bg = '', minWidth = 0, singleLine = false }, done) {
  const div = $(`.page[data-n="${n}"]`);
  const s = viewportOf(state.pages[n]).scale;
  const ta = document.createElement('textarea');
  ta.className = 'text-input';
  ta.wrap = 'off';
  ta.spellcheck = false;
  ta.value = text;
  Object.assign(ta.style, { left: left + 'px', top: top + 'px', font: fontCss(font, size * s), color });
  if (bg) ta.style.background = bg;
  div.appendChild(ta);
  const fit = () => {
    ta.rows = Math.max(1, ta.value.split('\n').length);
    ta.style.width = '0px';
    ta.style.width = Math.max(minWidth, ta.scrollWidth + 14) + 'px';
  };
  fit();
  ta.focus();
  ta.select();
  setTimeout(() => ta.focus(), 0);
  let closed = false;
  const close = save => {
    if (closed) return;
    closed = true;
    const value = ta.value.replace(/\s+$/, '');
    ta.remove();
    done(save ? value : null);
  };
  ta.addEventListener('input', fit);
  ta.addEventListener('keydown', e => {
    if (e.key === 'Enter' && (singleLine || !e.shiftKey)) { e.preventDefault(); close(true); }
    if (e.key === 'Escape') close(false);
  });
  ta.addEventListener('blur', () => close(true));
}

// Editor box position that lines its text up with a baseline at (bx, by).
const editorAt = (bx, by, size, s) => ({ left: bx - 5.5, top: by - 3.5 - size * 0.92 * s });

function openTextInput(n, vx, vy) {
  const p = state.pages[n];
  const vp = viewportOf(p);
  const size = state.textSize;
  const color = state.color === '#ffd400' ? '#111111' : state.color;
  openEditor(n, { left: vx, top: vy, size, color }, text => {
    if (!text) return;
    const [x, y] = vp.convertToPdfPoint(vx + 5.5, vy + 3.5 + size * 0.92 * vp.scale);
    addAnnot(n, { type: 'text', text, size, font: DEFAULT_FONT, color, x, y, r0: totalRot(p) });
  });
}

function reopenText(n, a) {
  const p = state.pages[n];
  const before = snapshot(a);
  if (a.type === 'edit') {
    a._editing = true;
    drawAnnots(n);
    openRichEditor(n, a, null, runs => {
      delete a._editing;
      if (runs) { a.runs = runs; layoutRuns(a); pushModify(p, a, before); }
      drawAnnots(n);
    });
    return;
  }
  const vp = viewportOf(p);
  const [ax, ay] = vp.convertToViewportPoint(a.x, a.y);
  a._editing = true;
  drawAnnots(n);
  openEditor(n, { ...editorAt(ax, ay, a.size, vp.scale), text: a.text, size: a.size, font: a.font, color: a.color }, text => {
    delete a._editing;
    if (text === '') { removeAnnot(n, p.annots.indexOf(a)); return; }
    if (text !== null && text !== a.text) { a.text = text; pushModify(p, a, before); }
    drawAnnots(n);
  });
}

// Width of each run (including its horizontal scale) and of the whole line.
function layoutRuns(a) {
  for (const r of a.runs) r.w = textWidthPt(r.text, r.size, r.font) * r.hs;
  a.tw = a.runs.reduce((t, r) => t + r.w, 0);
}

// Contenteditable line editor: one span per run, so typing inside a run keeps
// its font, size and colour. Ctrl+B / Ctrl+I also work and are read back.
function openRichEditor(n, a, clientPt, done) {
  const div = $(`.page[data-n="${n}"]`);
  const vp = viewportOf(state.pages[n]);
  const s = vp.scale;
  const [ax, ay] = vp.convertToViewportPoint(a.x, a.y);
  const big = Math.max(...a.runs.map(r => r.size));
  const ed = document.createElement('div');
  ed.className = 'text-input rich';
  ed.contentEditable = 'true';
  ed.spellcheck = false;
  Object.assign(ed.style, {
    left: ax - 5.5 + 'px', top: ay - 3.5 - big * 0.92 * s + 'px', fontSize: big * s + 'px',
    background: a.bg, minWidth: (a.x2 - a.x1) * s + 14 + 'px',
  });
  a.runs.forEach((r, i) => {
    const sp = document.createElement('span');
    sp.dataset.run = i;
    sp.textContent = r.text;
    Object.assign(sp.style, {
      fontFamily: familyCss(r.font), fontWeight: r.font.bold ? 700 : 400, fontStyle: r.font.italic ? 'italic' : 'normal',
      fontSize: r.size * s + 'px', color: r.color,
    });
    ed.appendChild(sp);
  });
  div.appendChild(ed);
  ed.focus();
  const sel = getSelection();
  const at = clientPt && document.caretRangeFromPoint?.(clientPt[0], clientPt[1]);
  if (at && ed.contains(at.startContainer)) { sel.removeAllRanges(); sel.addRange(at); }
  else { const r = document.createRange(); r.selectNodeContents(ed); r.collapse(false); sel.removeAllRanges(); sel.addRange(r); }

  const read = () => {
    const out = [];
    const walker = document.createTreeWalker(ed, NodeFilter.SHOW_TEXT);
    let node;
    while ((node = walker.nextNode())) {
      const text = node.nodeValue.replace(/[\n\r]/g, ' ').replace(/ /g, ' ');
      if (!text) continue;
      const el = node.parentElement;
      const runEl = el.closest('[data-run]');
      const base = a.runs[runEl ? +runEl.dataset.run : 0];
      const cs = getComputedStyle(el);
      const font = { family: base.font.family, bold: parseInt(cs.fontWeight, 10) >= 600, italic: cs.fontStyle !== 'normal' };
      const color = rgbToHex(cs.color);
      const last = out[out.length - 1];
      if (last && sameFont(last.font, font) && last.color === color && last.size === base.size && last.dy === base.dy && last.hs === base.hs) last.text += text;
      else out.push({ ...base, font, color, text });
    }
    if (out.length) out[out.length - 1].text = out[out.length - 1].text.replace(/\s+$/, '');
    return out.filter(r => r.text);
  };
  const key = runs => JSON.stringify(runs.map(r => [r.text, r.font, r.color, r.size]));
  let closed = false;
  const close = async save => {
    if (closed) return;
    closed = true;
    const runs = save ? read() : null;
    ed.remove();
    if (!runs || key(runs) === key(a.runs.map(r => ({ ...r, text: r.text.replace(/\s+$/, '') })).filter(r => r.text))) { done(null); return; }
    await Promise.all(runs.map(r => ensureFace(r.font)));
    done(runs);
  };
  ed.addEventListener('keydown', e => {
    if (e.key === 'Enter') { e.preventDefault(); close(true); }
    if (e.key === 'Escape') close(false);
  });
  ed.addEventListener('paste', e => {
    e.preventDefault();
    document.execCommand('insertText', false, (e.clipboardData.getData('text/plain') || '').replace(/\s*\n\s*/g, ' '));
  });
  ed.addEventListener('blur', () => close(true));
}

async function editLineAt(n, vx, vy, clientPt) {
  const p = state.pages[n];
  const vp = viewportOf(p);
  const div = $(`.page[data-n="${n}"]`);
  await div?._ready; // fonts are known once the page has been drawn
  const lines = await textItems(p);
  const l = findLine(lines, ...vp.convertToPdfPoint(vx, vy));
  if (!l) { toast('Toca un texto del documento para editarlo'); return; }
  $('.hover-box', div)?.remove();
  const page = pdfPage(p);
  const { bg } = sampleColors(n, rectBox(vp, l.x1, l.y1, l.x2, l.y2));
  const runs = l.runs.map(r => ({
    text: r.str, size: r.size, dy: r.y - l.y, w0: r.x2 - r.x,
    font: detectFont(page, r.fontId, r.style),
    color: sampleColors(n, rectBox(vp, r.x, r.y - r.size * 0.25, r.x2, r.y + r.size * 0.92)).fg,
  }));
  await Promise.all(runs.map(r => ensureFace(r.font)));
  for (const r of runs) {
    // horizontal scale that makes the bundled font match the original run width
    const m = textWidthPt(r.text, r.size, r.font);
    r.hs = m > 0 ? clamp(r.w0 / m, 0.7, 1.4) : 1;
    if (Math.abs(r.hs - 1) < 0.03) r.hs = 1;
    delete r.w0;
  }
  const a = { type: 'edit', x: l.x1, y: l.y, x1: l.x1 - 1, y1: l.y1, x2: l.x2 + 1, y2: l.y2, bg, runs, r0: 0 };
  layoutRuns(a);
  openRichEditor(n, a, clientPt, newRuns => {
    if (!newRuns) return;
    a.runs = newRuns;
    layoutRuns(a);
    addAnnot(n, a);
  });
}

/* ── Images ── */
function pickImage() {
  return new Promise(resolve => {
    const inp = document.createElement('input');
    inp.type = 'file';
    inp.accept = 'image/*';
    inp.addEventListener('change', async () => {
      const f = inp.files[0];
      if (!f) return resolve(null);
      const url = await new Promise(r => { const fr = new FileReader(); fr.onload = () => r(fr.result); fr.readAsDataURL(f); });
      const img = new Image();
      img.onload = () => {
        let data = url;
        // pdf-lib embeds PNG and JPEG only; convert anything else to PNG
        if (!/^data:image\/(png|jpe?g);/.test(url)) {
          const c = document.createElement('canvas');
          c.width = img.naturalWidth; c.height = img.naturalHeight;
          c.getContext('2d').drawImage(img, 0, 0);
          data = c.toDataURL('image/png');
        }
        resolve({ data, width: img.naturalWidth, height: img.naturalHeight });
      };
      img.onerror = () => { toast('No se pudo leer la imagen'); resolve(null); };
      img.src = url;
    });
    inp.click();
  });
}

async function placeImage(n, vx, vy) {
  if (!state.pendingImage) {
    state.pendingImage = await pickImage();
    if (state.pendingImage) toast('Toca la página donde quieras colocar la imagen');
    return;
  }
  const img = state.pendingImage;
  state.pendingImage = null;
  const p = state.pages[n];
  const vp = viewportOf(p);
  const pageW = viewportOf(p, 1).width;
  const w = Math.min(img.width * 0.75, pageW * 0.5), h = w * img.height / img.width;
  const [x, y] = vp.convertToPdfPoint(vx - (w * vp.scale) / 2, vy + (h * vp.scale) / 2);
  addAnnot(n, { type: 'image', data: img.data, w, h, x, y, r0: totalRot(p) });
  setTool('move');
  toast('Arrastra la imagen para moverla o su esquina para cambiar el tamaño');
}

/* ── Signature ── */
function getSignature() {
  try { return JSON.parse(localStorage.getItem('pdflow.signature')); } catch { return null; }
}

function signatureModal() {
  return new Promise(resolve => {
    const bg = document.createElement('div');
    bg.className = 'modal-bg';
    bg.innerHTML = `<div class="modal">
      <h2>Crea tu firma</h2>
      <p>Dibuja tu firma con el dedo, el lápiz o el mouse.</p>
      <canvas class="sig-pad"></canvas>
      <div class="modal-actions">
        <button class="btn" data-k="clear">Borrar</button>
        <span class="spacer"></span>
        <button class="btn" data-k="cancel">Cancelar</button>
        <button class="btn primary" data-k="ok">Usar firma</button>
      </div></div>`;
    document.body.appendChild(bg);
    const c = $('canvas', bg);
    const dpr = window.devicePixelRatio || 1;
    c.width = c.clientWidth * dpr; c.height = c.clientHeight * dpr;
    const ctx = c.getContext('2d');
    ctx.scale(dpr, dpr);
    ctx.lineWidth = 2.5; ctx.lineCap = 'round'; ctx.lineJoin = 'round'; ctx.strokeStyle = '#0b1f66';
    let drawing = false, dirty = false;
    const bounds = { x0: Infinity, y0: Infinity, x1: -Infinity, y1: -Infinity };
    const pos = e => { const r = c.getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; };
    const grow = (x, y) => { bounds.x0 = Math.min(bounds.x0, x); bounds.y0 = Math.min(bounds.y0, y); bounds.x1 = Math.max(bounds.x1, x); bounds.y1 = Math.max(bounds.y1, y); };
    c.addEventListener('pointerdown', e => { drawing = true; c.setPointerCapture(e.pointerId); const [x, y] = pos(e); ctx.beginPath(); ctx.moveTo(x, y); grow(x, y); });
    c.addEventListener('pointermove', e => { if (!drawing) return; const [x, y] = pos(e); ctx.lineTo(x, y); ctx.stroke(); grow(x, y); dirty = true; });
    c.addEventListener('pointerup', () => { drawing = false; });
    const close = v => { bg.remove(); resolve(v); };
    bg.addEventListener('click', e => {
      const k = e.target.closest('[data-k]')?.dataset.k;
      if (k === 'clear') { ctx.clearRect(0, 0, c.width, c.height); dirty = false; Object.assign(bounds, { x0: Infinity, y0: Infinity, x1: -Infinity, y1: -Infinity }); }
      if (k === 'cancel' || e.target === bg) close(null);
      if (k === 'ok') {
        if (!dirty) { toast('Dibuja tu firma primero'); return; }
        const pad = 6;
        const sx = Math.max(0, (bounds.x0 - pad) * dpr), sy = Math.max(0, (bounds.y0 - pad) * dpr);
        const sw = Math.min(c.width - sx, (bounds.x1 - bounds.x0 + pad * 2) * dpr);
        const sh = Math.min(c.height - sy, (bounds.y1 - bounds.y0 + pad * 2) * dpr);
        const out = document.createElement('canvas');
        out.width = sw; out.height = sh;
        out.getContext('2d').drawImage(c, sx, sy, sw, sh, 0, 0, sw, sh);
        const sig = { data: out.toDataURL('image/png'), ratio: sh / sw };
        try { localStorage.setItem('pdflow.signature', JSON.stringify(sig)); } catch {}
        close(sig);
      }
    });
  });
}

async function placeSignature(n, vx, vy) {
  let sig = getSignature();
  if (!sig) sig = await signatureModal();
  if (!sig) return;
  const p = state.pages[n];
  const vp = viewportOf(p);
  const w = 150, h = w * sig.ratio;
  // click = centre of the signature; anchor = its bottom-left corner
  const [x, y] = vp.convertToPdfPoint(vx - (w * vp.scale) / 2, vy + (h * vp.scale) / 2);
  addAnnot(n, { type: 'image', data: sig.data, w, h, x, y, r0: totalRot(p) });
}

/* ── Tools ── */
function setTool(tool) {
  const prev = state.tool;
  state.tool = tool;
  $('#pages').dataset.tool = tool;
  for (const b of $$('[data-tool]')) b.classList.toggle('on', b.dataset.tool === tool);
  if (prev === 'move' || tool === 'move') redrawAll();
  if (tool === 'edit' && prev !== 'edit') {
    state.pages.forEach(p => textItems(p));
    toast('Toca cualquier texto del documento para editarlo');
  }
  if (tool === 'image' && prev !== 'image') {
    state.pendingImage = null;
    pickImage().then(img => {
      if (!img) { if (state.tool === 'image') setTool('select'); return; }
      state.pendingImage = img;
      toast('Toca la página donde quieras colocar la imagen');
    });
  }
  if (tool === 'sign') {
    const sig = getSignature();
    const pick = () => toast('Toca la página donde quieras colocar tu firma');
    if (!sig) signatureModal().then(s => { if (s) pick(); else setTool('select'); });
    else pick();
  }
}

function buildSwatches() {
  const box = $('#swatches');
  box.innerHTML = '';
  for (const c of COLORS) {
    const b = document.createElement('button');
    b.className = 'swatch' + (c === state.color ? ' on' : '');
    b.style.background = c;
    b.title = 'Color';
    b.addEventListener('click', () => { state.color = c; buildSwatches(); });
    box.appendChild(b);
  }
}

/* ── Search ── */
async function search(q) {
  state.query = q.trim();
  const info = $('#searchInfo'), res = $('#results');
  res.innerHTML = '';
  for (const tl of $$('.textLayer')) markHits(tl);
  if (!state.query) { info.textContent = ''; return; }
  const needle = state.query.toLowerCase();
  let count = 0;
  for (let n = 0; n < state.pages.length; n++) {
    const tc = await pdfPage(state.pages[n]).getTextContent();
    const text = tc.items.map(i => i.str + (i.hasEOL ? ' ' : '')).join('');
    const low = text.toLowerCase();
    let at = low.indexOf(needle);
    while (at !== -1) {
      count++;
      const b = document.createElement('button');
      b.className = 'result';
      const pre = text.slice(Math.max(0, at - 30), at), hit = text.slice(at, at + needle.length), post = text.slice(at + needle.length, at + needle.length + 40);
      b.innerHTML = `<span class="pg">Página ${n + 1}</span><span></span><mark></mark><span></span>`;
      const [, s1, m, s2] = b.children;
      s1.textContent = (at > 30 ? '…' : '') + pre; m.textContent = hit; s2.textContent = post + '…';
      const target = n;
      b.addEventListener('click', () => { scrollToPage(target); if (window.innerWidth <= 720) $('#sidebar').hidden = true; });
      res.appendChild(b);
      at = low.indexOf(needle, at + needle.length);
    }
  }
  info.textContent = count ? `${count} resultado${count > 1 ? 's' : ''}` : 'Sin resultados';
}

/* ── Export with pdf-lib ── */
async function buildPdf(pageList = state.pages) {
  const out = await PDFDocument.create();
  const libDocs = new Map();
  // Bundled fonts are embedded (subset) through fontkit; the 14 standard PDF
  // fonts are the fallback when fontkit or the font files are unavailable.
  const hasFontkit = typeof fontkit !== 'undefined';
  if (hasFontkit) out.registerFontkit(fontkit);
  const fonts = new Map();
  const getFont = (f = DEFAULT_FONT) => {
    const key = fontFile(f);
    if (!fonts.has(key)) fonts.set(key, (async () => {
      const bytes = hasFontkit ? await loadFontBytes(f) : null;
      let font = null;
      if (bytes) { try { font = await out.embedFont(bytes, { subset: true }); } catch (err) { console.warn(err); } }
      font = font || await out.embedFont(stdFontName(f));
      const chars = new Set(font.getCharacterSet());
      // replace characters the font cannot draw instead of failing the export
      font.safe = t => [...t].map(ch => (ch === '\n' || chars.has(ch.codePointAt(0)) ? ch : '?')).join('');
      return font;
    })());
    return fonts.get(key);
  };
  const images = new Map();

  // Copy each source's pages in one call so shared resources (fonts,
  // images) are written once instead of once per page.
  const copied = new Map();
  for (const p of pageList) {
    if (!libDocs.has(p.src)) libDocs.set(p.src, await PDFDocument.load(state.sources[p.src].bytes, { ignoreEncryption: true }));
  }
  for (const [src, doc] of libDocs) {
    const idxs = [...new Set(pageList.filter(p => p.src === src).map(p => p.idx))];
    const pages = await out.copyPages(doc, idxs);
    idxs.forEach((idx, i) => copied.set(`${src}:${idx}`, pages[i]));
  }

  for (const p of pageList) {
    const key = `${p.src}:${p.idx}`;
    const page = copied.get(key) || (await out.copyPages(libDocs.get(p.src), [p.idx]))[0];
    copied.delete(key);
    out.addPage(page);
    page.setRotation(degrees(totalRot(p)));

    for (const a of p.annots) {
      if (a.type === 'whiteout') {
        page.drawRectangle({ x: Math.min(a.x1, a.x2), y: Math.min(a.y1, a.y2), width: Math.abs(a.x2 - a.x1), height: Math.abs(a.y2 - a.y1), color: hexToRgb(a.color) });
      } else if (a.type === 'edit') {
        let cx = a.x;
        const placed = [];
        for (const r of a.runs) {
          const font = await getFont(r.font);
          const text = font.safe(r.text);
          placed.push({ r, font, text, x: cx });
          cx += font.widthOfTextAtSize(text, r.size) * r.hs;
        }
        page.drawRectangle({ x: a.x1, y: a.y1, width: Math.max(a.x2, cx + 1) - a.x1, height: a.y2 - a.y1, color: hexToRgb(a.bg) });
        for (const { r, font, text, x } of placed) {
          if (r.hs !== 1) page.pushOperators(pushGraphicsState(), concatTransformationMatrix(r.hs, 0, 0, 1, x * (1 - r.hs), 0));
          page.drawText(text, { x, y: a.y + r.dy, size: r.size, font, color: hexToRgb(r.color) });
          if (r.hs !== 1) page.pushOperators(popGraphicsState());
        }
      } else if (a.type === 'highlight' || a.type === 'rect') {
        const x = Math.min(a.x1, a.x2), y = Math.min(a.y1, a.y2);
        const width = Math.abs(a.x2 - a.x1), height = Math.abs(a.y2 - a.y1);
        if (a.type === 'highlight') page.drawRectangle({ x, y, width, height, color: hexToRgb(a.color), opacity: 0.4, blendMode: 'Multiply' });
        else page.drawRectangle({ x, y, width, height, borderColor: hexToRgb(a.color), borderWidth: a.width });
      } else if (a.type === 'ink') {
        // drawSvgPath flips the y axis, so feed it negated y values
        const d = a.points.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(2)} ${(-y).toFixed(2)}`).join(' ');
        page.drawSvgPath(d, { x: 0, y: 0, borderColor: hexToRgb(a.color), borderWidth: a.width, borderLineCap: 1 });
      } else if (a.type === 'text') {
        const font = await getFont(a.font);
        page.drawText(font.safe(a.text), { x: a.x, y: a.y, size: a.size, font, color: hexToRgb(a.color), lineHeight: a.size * 1.15, rotate: degrees(a.r0) });
      } else if (a.type === 'image') {
        if (!images.has(a.data)) images.set(a.data, /^data:image\/jpe?g/.test(a.data) ? await out.embedJpg(a.data) : await out.embedPng(a.data));
        page.drawImage(images.get(a.data), { x: a.x, y: a.y, width: a.w, height: a.h, rotate: degrees(a.r0) });
      }
    }
  }
  out.setProducer('PDFlow');
  return out.save();
}

async function save() {
  await busy(async () => {
    const bytes = await buildPdf();
    if (await download(bytes, state.name + (state.dirty ? '-editado' : '') + '.pdf')) {
      state.dirty = false;
      toast('PDF descargado');
    }
  });
}

async function share() {
  await busy(async () => {
    const bytes = await buildPdf();
    const file = new File([bytes], state.name + '.pdf', { type: 'application/pdf' });
    if (navigator.canShare && navigator.canShare({ files: [file] })) {
      try { await navigator.share({ files: [file], title: state.name }); } catch {}
    } else {
      download(bytes, state.name + '.pdf');
    }
  });
}

async function printDoc() {
  await busy(async () => {
    const bytes = await buildPdf();
    const url = URL.createObjectURL(new Blob([bytes], { type: 'application/pdf' }));
    const frame = document.createElement('iframe');
    frame.style.cssText = 'position:fixed;width:0;height:0;border:0;right:0;bottom:0';
    frame.src = url;
    frame.onload = () => {
      try { frame.contentWindow.focus(); frame.contentWindow.print(); }
      catch { window.open(url, '_blank'); }
      setTimeout(() => { frame.remove(); URL.revokeObjectURL(url); }, 60000);
    };
    document.body.appendChild(frame);
  });
}

/* ── Organizer ── */
const org = { sel: new Set(), extractMode: false, dragFrom: null };

function openOrganizer(extractMode = false) {
  org.sel = new Set();
  org.extractMode = extractMode;
  $('#orgTitle').textContent = extractMode ? 'Extraer páginas' : 'Organizar páginas';
  showView('organizer');
  renderOrganizer();
}

function renderOrganizer() {
  const grid = $('#orgGrid');
  grid.innerHTML = '';
  state.pages.forEach((p, n) => {
    const card = document.createElement('div');
    card.className = 'org-card' + (org.sel.has(p) ? ' sel' : '');
    card.draggable = true;
    card.innerHTML = `<div class="ph"></div><div class="lbl">${n + 1}</div>`;
    thumbnail(p).then(src => { const img = new Image(); img.src = src; $('.ph', card)?.replaceWith(img); });
    card.addEventListener('click', () => {
      org.sel.has(p) ? org.sel.delete(p) : org.sel.add(p);
      card.classList.toggle('sel');
      updateOrgSel();
    });
    card.addEventListener('dragstart', e => { org.dragFrom = n; e.dataTransfer.effectAllowed = 'move'; });
    card.addEventListener('dragover', e => { e.preventDefault(); card.classList.add('drag-over'); });
    card.addEventListener('dragleave', () => card.classList.remove('drag-over'));
    card.addEventListener('drop', e => {
      e.preventDefault();
      card.classList.remove('drag-over');
      if (org.dragFrom == null || org.dragFrom === n) return;
      const [moved] = state.pages.splice(org.dragFrom, 1);
      state.pages.splice(n, 0, moved);
      org.dragFrom = null;
      state.dirty = true;
      renderOrganizer();
    });
    grid.appendChild(card);
  });
  updateOrgSel();
}

function updateOrgSel() {
  $('#orgSel').textContent = `${org.sel.size} de ${state.pages.length} seleccionadas`;
}

function orgSelected() {
  if (!org.sel.size) { toast('Selecciona una o más páginas'); return null; }
  return state.pages.filter(p => org.sel.has(p));
}

function orgRotate(delta) {
  const sel = orgSelected();
  if (!sel) return;
  for (const p of sel) p.rot = (p.rot + delta + 360) % 360;
  state.dirty = true;
  renderOrganizer();
}

function orgMove(dir) {
  const sel = orgSelected();
  if (!sel) return;
  const arr = state.pages;
  const order = dir < 0 ? [...arr.keys()] : [...arr.keys()].reverse();
  for (const i of order) {
    const j = i + dir;
    if (org.sel.has(arr[i]) && j >= 0 && j < arr.length && !org.sel.has(arr[j])) [arr[i], arr[j]] = [arr[j], arr[i]];
  }
  state.dirty = true;
  renderOrganizer();
}

function orgDelete() {
  const sel = orgSelected();
  if (!sel) return;
  if (sel.length === state.pages.length) { toast('El documento debe tener al menos una página'); return; }
  state.pages = state.pages.filter(p => !org.sel.has(p));
  org.sel.clear();
  state.dirty = true;
  renderOrganizer();
}

async function orgBlank() {
  await busy(async () => {
    const sel = state.pages.filter(p => org.sel.has(p));
    const ref = sel[sel.length - 1] || state.pages[state.pages.length - 1];
    const size = ref ? pdfPage(ref).getViewport({ scale: 1, rotation: 0 }) : { width: 595, height: 842 };
    const doc = await PDFDocument.create();
    doc.addPage([size.width, size.height]);
    const s = await addSource(await doc.save());
    const page = { src: s, idx: 0, rot: ref ? totalRot(ref) : 0, annots: [] };
    state.pages.splice(ref ? state.pages.indexOf(ref) + 1 : state.pages.length, 0, page);
    state.dirty = true;
    renderOrganizer();
  });
}

async function orgExtract() {
  const sel = orgSelected();
  if (!sel) return;
  await busy(async () => {
    const bytes = await buildPdf(sel);
    if (await download(bytes, `${state.name}-paginas.pdf`)) toast(`${sel.length} página${sel.length > 1 ? 's' : ''} extraída${sel.length > 1 ? 's' : ''}`);
  });
}

/* ── Wiring ── */
const actions = {
  open: () => pick('open'),
  sign: () => pick('sign'),
  merge: () => pick('merge'),
  organize: () => pick('organize'),
  extract: () => pick('extract'),
  images: () => pick('images'),
  sample: openSample,
  home: async () => {
    if (state.dirty && !(await confirmModal('Tienes cambios sin descargar. ¿Salir de todos modos?', 'Salir'))) return;
    showView('home');
    renderRecents();
  },
  'organize-current': () => openOrganizer(false),
  'toggle-side': () => { $('#sidebar').hidden = !$('#sidebar').hidden; },
  undo,
  'zoom-in': () => setZoom(state.scale * 1.2),
  'zoom-out': () => setZoom(state.scale / 1.2),
  fit: () => { const c = state.current; fitWidth(); scrollToPage(c, false); },
  save,
  share,
  print: printDoc,
  'org-done': () => { openViewer(); },
  'org-add': () => pick('org-add'),
  'org-rotl': () => orgRotate(-90),
  'org-rotr': () => orgRotate(90),
  'org-left': () => orgMove(-1),
  'org-right': () => orgMove(1),
  'org-delete': orgDelete,
  'org-all': () => {
    if (org.sel.size === state.pages.length) org.sel.clear(); else org.sel = new Set(state.pages);
    renderOrganizer();
  },
  'org-extract': orgExtract,
  'org-blank': orgBlank,
  'edit-pdf': () => pick('edit'),
};

document.addEventListener('click', e => {
  const a = e.target.closest('[data-action]');
  if (a && actions[a.dataset.action]) { actions[a.dataset.action](); return; }
  const t = e.target.closest('[data-tool]');
  if (t) setTool(t.dataset.tool);
  const s = e.target.closest('[data-side]');
  if (s) {
    for (const b of $$('[data-side]')) b.classList.toggle('on', b === s);
    $('#thumbs').hidden = s.dataset.side !== 'thumbs';
    $('#searchPane').hidden = s.dataset.side !== 'search';
    if (s.dataset.side === 'search') $('#searchInput').focus();
  }
});

$('#fileInput').addEventListener('change', e => handleFiles(e.target.files, pickerMode));

$('#searchForm').addEventListener('submit', e => { e.preventDefault(); search($('#searchInput').value); });

$('#pageInput').addEventListener('change', e => {
  const n = Math.min(state.pages.length, Math.max(1, parseInt(e.target.value, 10) || 1)) - 1;
  scrollToPage(n);
});

$('#pages').addEventListener('scroll', () => requestAnimationFrame(trackCurrentPage), { passive: true });

// Ctrl/⌘ + wheel zoom
$('#pages').addEventListener('wheel', e => {
  if (!e.ctrlKey && !e.metaKey) return;
  e.preventDefault();
  clearTimeout(e.currentTarget._zt);
  const factor = e.deltaY < 0 ? 1.1 : 1 / 1.1;
  state._pendingScale = (state._pendingScale || state.scale) * factor;
  e.currentTarget._zt = setTimeout(() => { setZoom(state._pendingScale); state._pendingScale = null; }, 120);
}, { passive: false });

document.addEventListener('keydown', e => {
  if ($('#viewer').hidden || e.target.matches('input, textarea')) return;
  const mod = e.ctrlKey || e.metaKey;
  if (mod && e.key.toLowerCase() === 'z') { e.preventDefault(); undo(); }
  else if (mod && e.key.toLowerCase() === 's') { e.preventDefault(); save(); }
  else if (mod && e.key.toLowerCase() === 'f') { e.preventDefault(); $('#sidebar').hidden = false; $('[data-side="search"]').click(); }
  else if (mod && (e.key === '+' || e.key === '=')) { e.preventDefault(); actions['zoom-in'](); }
  else if (mod && e.key === '-') { e.preventDefault(); actions['zoom-out'](); }
  else if (e.key === 'Escape') setTool('select');
});

// Drag & drop on the home screen and on the viewer
const drop = $('#drop');
for (const ev of ['dragenter', 'dragover']) document.addEventListener(ev, e => {
  if (!e.dataTransfer?.types.includes('Files')) return;
  e.preventDefault();
  drop.classList.add('over');
});
document.addEventListener('dragleave', e => { if (!e.relatedTarget) drop.classList.remove('over'); });
document.addEventListener('drop', e => {
  if (!e.dataTransfer?.files.length) return;
  e.preventDefault();
  drop.classList.remove('over');
  handleFiles(e.dataTransfer.files, $('#organizer').hidden ? 'open' : 'org-add');
});

window.addEventListener('beforeunload', e => {
  if (state.dirty && !$('#viewer').hidden) { e.preventDefault(); e.returnValue = ''; }
});

let resizeT;
window.addEventListener('resize', () => {
  clearTimeout(resizeT);
  resizeT = setTimeout(() => { if (!$('#viewer').hidden) for (const t of $$('.text-input')) t.blur(); }, 200);
});

if (!navigator.share || embedded) $('#shareBtn').hidden = true;
// The embedded viewer cannot open the print dialog
if (embedded) $('[data-action="print"]').hidden = true;
ensureFace(DEFAULT_FONT);
buildSwatches();
$('#textSize').addEventListener('change', e => { state.textSize = +e.target.value; });
updateUndo();
renderRecents();

if ('serviceWorker' in navigator && location.protocol !== 'file:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}

// Exposed for automated tests
window.PDFlow = { state, buildPdf, handleFiles, setTool, addAnnot };
