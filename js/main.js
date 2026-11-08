import * as THREE from 'three';
import { RGBELoader } from 'three/addons/loaders/RGBELoader.js';
import { Studio, MATERIALS, PRIMITIVES } from './studio.js';
import { Tracking } from './track.js';

const $ = (id) => document.getElementById(id);
const canvas = $('gl');
const bgVideo = $('bg-video');
const studio = new Studio(canvas);
const tracking = new Tracking(studio, bgVideo);

function toast(msg, ms = 3200) {
  const t = $('toast'); t.textContent = msg; t.hidden = false;
  clearTimeout(toast.t); toast.t = setTimeout(() => (t.hidden = true), ms);
}
function status(msg) { $('status').textContent = msg || ''; }

// ---------- abas ----------
document.querySelectorAll('#tabs button').forEach((b) => {
  b.onclick = () => {
    document.querySelectorAll('#tabs button').forEach((x) => x.classList.toggle('active', x === b));
    document.querySelectorAll('.pane').forEach((p) => (p.hidden = p.dataset.pane !== b.dataset.tab));
  };
});
const segment = (id, cb) => {
  const root = $(id);
  root.querySelectorAll('button').forEach((b) => (b.onclick = () => {
    root.querySelectorAll('button').forEach((x) => x.classList.toggle('active', x === b));
    cb(b.dataset);
  }));
};

// ---------- criar ----------
for (const [k, p] of Object.entries(PRIMITIVES)) {
  const b = document.createElement('button');
  b.className = 'tile';
  b.innerHTML = `<span>${p.icon}</span>${p.label}`;
  b.onclick = () => { studio.add(k); };
  $('prims').appendChild(b);
}
$('import-glb').onchange = async (e) => {
  const f = e.target.files[0]; e.target.value = '';
  if (!f) return;
  try { status('Carregando modelo…'); await studio.importGLB(f); } catch (err) { toast('Modelo inválido: ' + err.message); }
  status(stateLabel());
};
$('duplicate').onclick = () => studio.duplicate();
$('delete').onclick = () => studio.remove();
segment('gizmo-mode', (d) => {
  studio.setGizmoMode(d.mode);
  $('create-hint').textContent = d.mode === 'drop'
    ? 'Toque no chão para levar o objeto selecionado até lá.'
    : 'Toque num objeto para selecionar. Arraste as setas do gizmo para editar.';
});

// ---------- material ----------
for (const [k, m] of Object.entries(MATERIALS)) {
  const b = document.createElement('button');
  b.className = 'tile mat';
  b.innerHTML = `<span class="swatch ${k}" style="--c:${m.color}"></span>${m.label}`;
  b.onclick = () => {
    const mat = studio.selectedMaterial();
    if (!mat) return toast('Selecione um objeto primeiro.');
    studio.selected.traverse((o) => { if (o.isMesh) studio.applyMaterial(o.material, k); });
    syncMaterialUI();
  };
  $('mats').appendChild(b);
}
const matSliders = { 'm-rough': 'roughness', 'm-metal': 'metalness', 'm-trans': 'transmission', 'm-coat': 'clearcoat', 'm-emit': 'emissiveIntensity' };
function syncMaterialUI() {
  const m = studio.selectedMaterial();
  $('mat-hint').hidden = !!m;
  document.querySelectorAll('[data-pane=material] .sliders input').forEach((i) => (i.disabled = !m));
  if (!m) return;
  $('m-color').value = '#' + m.color.getHexString();
  for (const [id, k] of Object.entries(matSliders)) $(id).value = m[k] ?? 0;
}
$('m-color').oninput = (e) => studio.selected?.traverse((o) => o.isMesh && o.material.color.set(e.target.value));
for (const [id, k] of Object.entries(matSliders)) {
  $(id).oninput = (e) => studio.selected?.traverse((o) => {
    if (!o.isMesh) return;
    o.material[k] = parseFloat(e.target.value);
    if (k === 'emissiveIntensity' && o.material.emissive.getHex() === 0) o.material.emissive.copy(o.material.color);
    if (k === 'transmission') o.material.thickness = 0.5;
  });
}
studio.onSelect = (o) => { syncMaterialUI(); status(stateLabel()); };
syncMaterialUI();

// ---------- luz ----------
segment('env-presets', (d) => { studio.setEnvironment(d.env); syncSunUI(); });
function syncSunUI() {
  $('l-sun').value = studio.sun.intensity;
  $('l-azim').value = studio.sunAngles.azim;
  $('l-elev').value = studio.sunAngles.elev;
}
$('l-exposure').oninput = (e) => (studio.renderer.toneMappingExposure = +e.target.value);
$('l-env').oninput = (e) => studio.setEnvIntensity(+e.target.value);
$('l-sun').oninput = (e) => studio.setSun({ intensity: +e.target.value });
$('l-azim').oninput = (e) => studio.setSun({ azim: +e.target.value });
$('l-elev').oninput = (e) => studio.setSun({ elev: +e.target.value });
$('l-soft').oninput = (e) => studio.setSun({ soft: +e.target.value });
$('import-hdr').onchange = async (e) => {
  const f = e.target.files[0]; e.target.value = '';
  if (!f) return;
  const url = URL.createObjectURL(f);
  try {
    const tex = await new RGBELoader().loadAsync(url);
    tex.mapping = THREE.EquirectangularReflectionMapping;
    studio.setEnvironment('hdri', tex);
    toast('HDRI aplicado: a iluminação e os reflexos agora vêm da sua imagem.');
  } catch { toast('Não foi possível ler este .hdr'); }
  URL.revokeObjectURL(url);
};

// ---------- render ----------
segment('quality', (d) => studio.setQuality(d.q));
$('r-ao').onchange = (e) => studio.setAO(e.target.checked);
$('r-bloom').onchange = (e) => studio.setBloom(e.target.checked);
$('r-grain').oninput = (e) => studio.setGrain(+e.target.value);
studio.setGrain(0.02);
$('r-still').onclick = async () => {
  const blob = tracking.solve ? await compositeStill() : await studio.snapshot();
  download(blob, 'forja-cgi.png');
};
function download(blob, name) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob); a.download = name; a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 4000);
}

// ---------- tracking ----------
let analyzed = false;
async function onVideo(file) {
  if (!file) return;
  try {
    const info = await tracking.loadVideo(file);
    $('t-run').disabled = false;
    toast(`Vídeo: ${info.width}×${info.height}, ${info.duration.toFixed(1)} s. Toque em “Analisar câmera”.`);
    if (info.duration > 30) toast('Vídeos longos demoram para analisar. Recomendado: até 15 s.', 5000);
  } catch (err) { toast(err.message); }
}
$('video-file').onchange = (e) => { onVideo(e.target.files[0]); e.target.value = ''; };
$('video-cam').onchange = (e) => { onVideo(e.target.files[0]); e.target.value = ''; };

$('t-run').onclick = async () => {
  const [maxSide, want] = $('t-quality').value.split(',').map(Number);
  const fps = +$('t-fps').value;
  $('t-run').disabled = true;
  $('t-progress').hidden = false;
  $('t-result').hidden = true;
  const bar = $('t-progress').firstElementChild;
  const t0 = performance.now();
  try {
    const r = await tracking.analyze({ maxSide, want, fps, shot: $('t-shot').value, lens: $('t-lens').checked }, (p) => {
      if (p.stage === 'track') {
        bar.style.width = (p.done / p.total) * 90 + '%';
        status(`Rastreando quadro ${p.done}/${p.total} · ${p.active} pontos`);
      } else { bar.style.width = '95%'; status('Resolvendo a câmera 3D…'); }
    });
    bar.style.width = '100%';
    const quality = r.error < 0.6 ? 'excelente' : r.error < 1.2 ? 'boa' : r.error < 2.5 ? 'razoável' : 'fraca';
    $('t-result').innerHTML = `<b>Câmera resolvida</b> em ${((performance.now() - t0) / 1000).toFixed(1)} s<br>
      ${r.solvedFrames}/${r.frames} quadros · ${r.points} pontos 3D<br>
      Erro médio: <b>${r.error.toFixed(2)} px</b> (${quality}) · lente ≈ ${r.hfov.toFixed(0)}°`;
    $('t-result').hidden = false;
    enterTracked();
  } catch (err) {
    studio.paused = false;
    bgVideo.hidden = !analyzed;
    toast(err.message, 6000);
    status(stateLabel());
  }
  $('t-run').disabled = false;
};

function enterTracked() {
  analyzed = true;
  studio.tracked = true;
  studio.orbit.enabled = false;
  studio.floor.visible = false;
  studio.sky.visible = false;
  studio.catcher.visible = $('t-catcher').checked;
  studio.scene.background = null;
  studio.renderer.setClearColor(0x000000, 0);
  studio.setAspect(tracking.size[0] / tracking.size[1]);
  bgVideo.hidden = false;
  placeVideo();
  $('t-adjust').hidden = false;
  $('scrub').hidden = false;
  $('frame').max = tracking.frameCount - 1;
  $('r-video').disabled = false;
  tracking.applyTime(tracking.times[0]);
  seekFrame(0);
  // move os objetos existentes para a frente da câmera do 1º quadro
  studio.objects.forEach((o) => {
    const p = studio.spawnPoint();
    const box = new THREE.Box3().setFromObject(o);
    o.position.x += p.x - (box.min.x + box.max.x) / 2;
    o.position.z += p.z - (box.min.z + box.max.z) / 2;
    o.position.y += p.y - box.min.y;
  });
  status(stateLabel());
  toast('Pronto! Adicione objetos na aba Criar: eles ficam presos ao chão real. Use ▶ para ver o resultado.', 5000);
}

function exitTracked() {
  bgVideo.pause();
  analyzed = false;
  studio.tracked = false;
  tracking.dispose();
  studio.orbit.enabled = true;
  studio.floor.visible = true;
  studio.catcher.visible = false;
  studio.setAspect(null);
  bgVideo.hidden = true;
  $('t-adjust').hidden = true;
  $('t-result').hidden = true;
  $('t-progress').hidden = true;
  $('scrub').hidden = true;
  $('r-video').disabled = true;
  studio.camera.fov = 45;
  studio.camera.position.set(1.1, 0.9, 1.4);
  studio.orbit.target.set(0, 0.2, 0);
  studio.camera.updateProjectionMatrix();
  studio.setEnvironment(studio.envName === 'hdri' ? 'studio' : studio.envName);
  status(stateLabel());
}
$('t-exit').onclick = exitTracked;

function placeVideo() {
  const r = canvas.getBoundingClientRect();
  const host = canvas.parentElement.getBoundingClientRect();
  Object.assign(bgVideo.style, { left: r.left - host.left + 'px', top: r.top - host.top + 'px', width: r.width + 'px', height: r.height + 'px' });
}
window.addEventListener('resize', () => setTimeout(placeVideo, 50));

$('t-points').onchange = (e) => tracking.setShowPoints(e.target.checked);
$('t-catcher').onchange = (e) => (studio.catcher.visible = e.target.checked);
const adj = (id, key) => ($(id).oninput = (e) => {
  tracking.adjust[key] = +e.target.value;
  tracking.updateMapping();
  tracking.applyTime(bgVideo.currentTime);
});
adj('t-ground-y', 'groundY'); adj('t-ground-rot', 'rot'); adj('t-scale', 'height');

// sincroniza a câmera 3D com o vídeo
studio.beforeRender = () => {
  if (!analyzed) return;
  if (!bgVideo.paused) {
    tracking.applyTime(bgVideo.currentTime);
    $('frame').value = tracking.currentFrame;
    $('frame-label').textContent = tracking.currentFrame;
  }
  studio.sun.target.position.set(studio.camera.position.x, 0, studio.camera.position.z);
  studio.setSun({});
};

async function seekFrame(i) {
  const t = tracking.times[i] ?? 0;
  bgVideo.currentTime = t;
  await new Promise((r) => { bgVideo.onseeked = r; setTimeout(r, 600); });
  tracking.applyTime(t);
  $('frame-label').textContent = i;
}
$('frame').oninput = (e) => { bgVideo.pause(); seekFrame(+e.target.value); };
$('play').onclick = () => {
  if (bgVideo.paused) {
    if (bgVideo.ended || bgVideo.currentTime >= bgVideo.duration - 0.05) bgVideo.currentTime = 0;
    bgVideo.play();
  } else bgVideo.pause();
};
bgVideo.addEventListener('play', () => ($('play').textContent = '❚❚'));
bgVideo.addEventListener('pause', () => ($('play').textContent = '▶'));

// ---------- exportação (vídeo real + CGI) ----------
function compositeCanvas() {
  const [W, H] = tracking.size;
  const k = Math.min(1, 1920 / Math.max(W, H));
  const c = document.createElement('canvas');
  c.width = Math.round(W * k / 2) * 2; c.height = Math.round(H * k / 2) * 2;
  return c;
}
function drawComposite(c) {
  const ctx = c.getContext('2d');
  ctx.drawImage(bgVideo, 0, 0, c.width, c.height);
  ctx.drawImage(canvas, 0, 0, c.width, c.height);
}
async function compositeStill() {
  studio.render();
  const c = compositeCanvas();
  drawComposite(c);
  return new Promise((r) => c.toBlob(r, 'image/png'));
}

$('r-video').onclick = async () => {
  if (!tracking.solve) return;
  const c = compositeCanvas();
  const stream = c.captureStream(30);
  const types = ['video/mp4;codecs=avc1', 'video/mp4', 'video/webm;codecs=vp9', 'video/webm'];
  const type = types.find((t) => window.MediaRecorder && MediaRecorder.isTypeSupported(t));
  if (!type) return toast('Este navegador não grava vídeo.');
  const rec = new MediaRecorder(stream, { mimeType: type, videoBitsPerSecond: 12e6 });
  const chunks = [];
  rec.ondataavailable = (e) => e.data.size && chunks.push(e.data);
  const done = new Promise((r) => (rec.onstop = r));
  $('r-video').disabled = true;
  status('Exportando… (o vídeo toca uma vez)');
  bgVideo.currentTime = 0;
  await new Promise((r) => { bgVideo.onseeked = r; setTimeout(r, 800); });
  rec.start(200);
  const hasRVFC = 'requestVideoFrameCallback' in HTMLVideoElement.prototype;
  const step = (now, meta) => {
    tracking.applyTime(meta?.mediaTime ?? bgVideo.currentTime);
    studio.render();
    drawComposite(c);
    status(`Exportando… ${Math.round((bgVideo.currentTime / bgVideo.duration) * 100)}%`);
    if (!bgVideo.ended && !bgVideo.paused) hasRVFC ? bgVideo.requestVideoFrameCallback(step) : requestAnimationFrame(step);
    else rec.stop();
  };
  bgVideo.onended = () => { if (rec.state === 'recording') rec.stop(); };
  await bgVideo.play();
  hasRVFC ? bgVideo.requestVideoFrameCallback(step) : requestAnimationFrame(step);
  await done;
  bgVideo.onended = null;
  download(new Blob(chunks, { type }), 'forja-cgi.' + (type.includes('mp4') ? 'mp4' : 'webm'));
  $('r-video').disabled = false;
  status(stateLabel());
  toast('Vídeo exportado!');
};

function stateLabel() {
  const sel = studio.selected ? ` · selecionado: ${studio.selected.userData.kind || 'objeto'}` : '';
  return (analyzed ? 'Cena rastreada' : 'Estúdio') + ` · ${studio.objects.length} objeto(s)` + sel;
}

// cena inicial
studio.add('esfera', new THREE.Vector3(-0.3, 0, 0));
studio.applyMaterial(studio.selected.material, 'cromo');
studio.add('no', new THREE.Vector3(0.25, 0, -0.1));
studio.applyMaterial(studio.selected.material, 'ouro');
studio.add('cubo', new THREE.Vector3(0.05, 0, 0.35));
studio.applyMaterial(studio.selected.material, 'vidro');
studio.select(null);
status(stateLabel());

window.__forja = { studio, tracking }; // depuração

if ('serviceWorker' in navigator && location.protocol === 'https:') navigator.serviceWorker.register('sw.js').catch(() => {});
