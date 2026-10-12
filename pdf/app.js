'use strict';

pdfjsLib.GlobalWorkerOptions.workerSrc = window.PDF_WORKER_SRC || 'vendor/pdf.worker.min.js';
const { PDFDocument, StandardFonts, rgb, degrees } = PDFLib;

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
  await page.render({
    canvasContext: canvas.getContext('2d'),
    viewport: vp,
    transform: dpr !== 1 ? [dpr, 0, 0, dpr, 0, 0] : null,
  }).promise;

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
function svgEl(tag, attrs) {
  const el = document.createElementNS(SVGNS, tag);
  for (const k in attrs) el.setAttribute(k, attrs[k]);
  return el;
}

function annotElement(a, vp, R) {
  const toV = (x, y) => vp.convertToViewportPoint(x, y);
  const s = vp.scale;
  if (a.type === 'highlight' || a.type === 'rect') {
    const [x1, y1] = toV(a.x1, a.y1), [x2, y2] = toV(a.x2, a.y2);
    const box = { x: Math.min(x1, x2), y: Math.min(y1, y2), width: Math.abs(x2 - x1), height: Math.abs(y2 - y1) };
    return a.type === 'highlight'
      ? svgEl('rect', { ...box, fill: a.color, 'fill-opacity': 0.4, style: 'mix-blend-mode:multiply' })
      : svgEl('rect', { ...box, fill: 'none', stroke: a.color, 'stroke-width': a.width * s });
  }
  if (a.type === 'ink') {
    const pts = a.points.map(([x, y]) => toV(x, y).map(v => v.toFixed(1)).join(',')).join(' ');
    return svgEl('polyline', { points: pts, fill: 'none', stroke: a.color, 'stroke-width': a.width * s, 'stroke-linecap': 'round', 'stroke-linejoin': 'round' });
  }
  // text & image are anchored at their local origin (baseline-left / bottom-left)
  // and keep the orientation they had when created (a.r0) relative to the page.
  const [ax, ay] = toV(a.x, a.y);
  const turn = ((R - a.r0) % 360 + 360) % 360;
  let el;
  if (a.type === 'text') {
    el = svgEl('text', { x: ax, y: ay, fill: a.color, 'font-size': a.size * s, 'font-family': 'Helvetica, Arial, sans-serif' });
    a.text.split('\n').forEach((line, i) => {
      const ts = svgEl('tspan', { x: ax, dy: i ? a.size * 1.15 * s : 0 });
      ts.textContent = line || ' ';
      el.appendChild(ts);
    });
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
  });
}

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

function translateAnnot(a, dx, dy) {
  if ('x1' in a) { a.x1 += dx; a.x2 += dx; a.y1 += dy; a.y2 += dy; }
  else if (a.points) a.points = a.points.map(([x, y]) => [x + dx, y + dy]);
  else { a.x += dx; a.y += dy; }
}

function undo() {
  const h = state.history.pop();
  if (!h) return;
  if (h.type === 'add') h.page.annots.splice(h.page.annots.indexOf(h.annot), 1);
  else if (h.type === 'remove') h.page.annots.splice(h.index, 0, h.annot);
  else if (h.type === 'move') translateAnnot(h.annot, -h.dx, -h.dy);
  const n = state.pages.indexOf(h.page);
  if (n >= 0) drawAnnots(n);
  updateUndo();
}

function updateUndo() { $('#undoBtn').disabled = !state.history.length; }

function localPoint(svg, e) {
  const r = svg.getBoundingClientRect();
  return [e.clientX - r.left, e.clientY - r.top];
}

function attachPointer(svg, n) {
  let drag = null;

  svg.addEventListener('pointerdown', e => {
    if (e.button !== 0) return;
    const p = state.pages[n];
    const vp = viewportOf(p);
    const [vx, vy] = localPoint(svg, e);
    const toPdf = (x, y) => vp.convertToPdfPoint(x, y);
    const tool = state.tool;

    if (tool === 'erase') {
      const t = e.target.closest('[data-i]');
      if (t) removeAnnot(n, +t.dataset.i);
      return;
    }
    if (tool === 'move') {
      const t = e.target.closest('[data-i]');
      if (!t) return;
      drag = { kind: 'move', annot: p.annots[+t.dataset.i], start: toPdf(vx, vy), last: toPdf(vx, vy), vp };
    } else if (tool === 'text') {
      e.preventDefault();
      openTextInput(n, vx, vy);
      return;
    } else if (tool === 'sign') {
      e.preventDefault();
      placeSignature(n, vx, vy);
      return;
    } else if (tool === 'highlight' || tool === 'rect') {
      const temp = svgEl('rect', tool === 'highlight'
        ? { fill: state.color, 'fill-opacity': 0.4 }
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
    if (!drag) return;
    const [vx, vy] = localPoint(svg, e);
    if (drag.kind === 'draw') {
      drag.pts.push([vx, vy]);
      drag.temp.setAttribute('points', drag.pts.map(q => q.join(',')).join(' '));
    } else if (drag.kind === 'move') {
      const cur = drag.vp.convertToPdfPoint(vx, vy);
      translateAnnot(drag.annot, cur[0] - drag.last[0], cur[1] - drag.last[1]);
      drag.last = cur;
      drawAnnots(n);
    } else {
      drag.x1 = vx; drag.y1 = vy;
      drag.temp.setAttribute('x', Math.min(drag.x0, vx));
      drag.temp.setAttribute('y', Math.min(drag.y0, vy));
      drag.temp.setAttribute('width', Math.abs(vx - drag.x0));
      drag.temp.setAttribute('height', Math.abs(vy - drag.y0));
    }
  });

  const end = () => {
    if (!drag) return;
    const d = drag; drag = null;
    if (d.temp) d.temp.remove();
    const toPdf = (x, y) => d.vp.convertToPdfPoint(x, y);
    if (d.kind === 'move') {
      const dx = d.last[0] - d.start[0], dy = d.last[1] - d.start[1];
      if (dx || dy) { state.history.push({ type: 'move', annot: d.annot, page: state.pages[n], dx, dy }); state.dirty = true; updateUndo(); }
    } else if (d.kind === 'draw') {
      if (d.pts.length < 2) return;
      addAnnot(n, { type: 'ink', color: state.color, width: 2, points: d.pts.map(([x, y]) => toPdf(x, y)) });
    } else {
      if (Math.abs(d.x1 - d.x0) < 3 || Math.abs(d.y1 - d.y0) < 3) return;
      const [x1, y1] = toPdf(d.x0, d.y0), [x2, y2] = toPdf(d.x1, d.y1);
      addAnnot(n, { type: d.kind, color: state.color, width: 2, x1, y1, x2, y2 });
    }
  };
  svg.addEventListener('pointerup', end);
  svg.addEventListener('pointercancel', end);
}

function openTextInput(n, vx, vy) {
  const div = $(`.page[data-n="${n}"]`);
  const p = state.pages[n];
  const vp = viewportOf(p);
  const size = 14;
  const ta = document.createElement('textarea');
  ta.className = 'text-input';
  ta.style.left = vx + 'px';
  ta.style.top = vy + 'px';
  ta.style.fontSize = size * vp.scale + 'px';
  ta.style.color = state.color;
  ta.rows = 1;
  div.appendChild(ta);
  ta.focus();
  setTimeout(() => ta.focus(), 0);
  let done = false;
  const commit = save => {
    if (done) return;
    done = true;
    const text = ta.value.replace(/\s+$/, '');
    ta.remove();
    if (!save || !text) return;
    // anchor = baseline of the first line (textarea top + ascent + padding)
    const [x, y] = vp.convertToPdfPoint(vx + 4, vy + 2 + size * 0.85 * vp.scale);
    addAnnot(n, { type: 'text', text, size, color: state.color === '#ffd400' ? '#111111' : state.color, x, y, r0: totalRot(p) });
  };
  ta.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); commit(true); }
    if (e.key === 'Escape') commit(false);
  });
  ta.addEventListener('blur', () => commit(true));
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
  state.tool = tool;
  $('#pages').dataset.tool = tool;
  for (const b of $$('[data-tool]')) b.classList.toggle('on', b.dataset.tool === tool);
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
function winAnsiSafe(s) {
  return s.replace(/[^\n\x20-\x7e\xa0-\xff€]/g, '?');
}

async function buildPdf(pageList = state.pages) {
  const out = await PDFDocument.create();
  const libDocs = new Map();
  let font = null;
  const images = new Map();

  for (const p of pageList) {
    if (!libDocs.has(p.src)) libDocs.set(p.src, await PDFDocument.load(state.sources[p.src].bytes, { ignoreEncryption: true }));
    const [page] = await out.copyPages(libDocs.get(p.src), [p.idx]);
    out.addPage(page);
    page.setRotation(degrees(totalRot(p)));

    for (const a of p.annots) {
      if (a.type === 'highlight' || a.type === 'rect') {
        const x = Math.min(a.x1, a.x2), y = Math.min(a.y1, a.y2);
        const width = Math.abs(a.x2 - a.x1), height = Math.abs(a.y2 - a.y1);
        if (a.type === 'highlight') page.drawRectangle({ x, y, width, height, color: hexToRgb(a.color), opacity: 0.4, blendMode: 'Multiply' });
        else page.drawRectangle({ x, y, width, height, borderColor: hexToRgb(a.color), borderWidth: a.width });
      } else if (a.type === 'ink') {
        // drawSvgPath flips the y axis, so feed it negated y values
        const d = a.points.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(2)} ${(-y).toFixed(2)}`).join(' ');
        page.drawSvgPath(d, { x: 0, y: 0, borderColor: hexToRgb(a.color), borderWidth: a.width, borderLineCap: 1 });
      } else if (a.type === 'text') {
        font = font || await out.embedFont(StandardFonts.Helvetica);
        page.drawText(winAnsiSafe(a.text), { x: a.x, y: a.y, size: a.size, font, color: hexToRgb(a.color), lineHeight: a.size * 1.15, rotate: degrees(a.r0) });
      } else if (a.type === 'image') {
        if (!images.has(a.data)) images.set(a.data, await out.embedPng(a.data));
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
buildSwatches();
updateUndo();
renderRecents();

if ('serviceWorker' in navigator && location.protocol !== 'file:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}

// Exposed for automated tests
window.PDFlow = { state, buildPdf, handleFiles, setTool, addAnnot };
