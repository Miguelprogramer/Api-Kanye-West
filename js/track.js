// Tracking de câmera: extrai os quadros do vídeo, envia ao tracker do effectCraft (WebAssembly, num
// worker), converte a solução (structure from motion) para a cena three.js e encaixa a câmera 3D
// no vídeo real, quadro a quadro.
import * as THREE from 'three';

// ---------- álgebra da solução (convenção do effectCraft: câmera x→direita, y→baixo, z→frente;
// X_cam = R (X − C), R em eixo-ângulo) ----------
function rodrigues([x, y, z]) {
  const th = Math.hypot(x, y, z);
  const m = new THREE.Matrix3();
  if (th < 1e-12) return m.identity();
  const [a, b, c] = [x / th, y / th, z / th];
  const s = Math.sin(th), co = Math.cos(th), t = 1 - co;
  return m.set(
    co + a * a * t, a * b * t - c * s, a * c * t + b * s,
    b * a * t + c * s, co + b * b * t, b * c * t - a * s,
    c * a * t - b * s, c * b * t + a * s, co + c * c * t);
}
const v3 = (a) => new THREE.Vector3(a[0], a[1], a[2]);

/** Plano do chão por RANSAC entre planos aproximadamente perpendiculares ao “para cima” das câmeras. */
function fitGround(points, cams) {
  const up = new THREE.Vector3();
  cams.forEach((c) => up.add(new THREE.Vector3(0, -1, 0).applyMatrix3(c.Rt)));
  up.normalize();
  const P = points.map((p) => v3(p.pos));
  if (P.length < 3) return null;
  // escala da cena: distância mediana câmera → pontos
  const d = P.map((p) => p.distanceTo(cams[0].C)).sort((a, b) => a - b);
  const thr = d[Math.floor(d.length / 2)] * 0.015;
  let best = null;
  const rnd = (n) => Math.floor(Math.random() * n);
  for (let it = 0; it < 600; it++) {
    const a = P[rnd(P.length)], b = P[rnd(P.length)], c = P[rnd(P.length)];
    const n = new THREE.Vector3().subVectors(b, a).cross(new THREE.Vector3().subVectors(c, a));
    if (n.lengthSq() < 1e-18) continue;
    n.normalize();
    if (n.dot(up) < 0) n.negate();
    if (n.dot(up) < Math.cos(THREE.MathUtils.degToRad(35))) continue;
    const off = -n.dot(a);
    // o chão fica abaixo das câmeras
    if (cams.some((cm) => n.dot(cm.C) + off < 0)) continue;
    let score = 0;
    for (const p of P) if (Math.abs(n.dot(p) + off) < thr) score++;
    // prefere planos mais baixos em empate (mesas, prateleiras ficam acima)
    if (!best || score > best.score) best = { n, off, score };
  }
  if (!best || best.score < 6) {
    // sem plano claro: chão abaixo do ponto mais baixo
    const off = -Math.min(...P.map((p) => up.dot(p)));
    return { n: up, off, inliers: [] };
  }
  // refina com os inliers (mínimos quadrados via centróide + normal mantida)
  const inl = P.filter((p) => Math.abs(best.n.dot(p) + best.off) < thr);
  const cen = inl.reduce((s, p) => s.add(p), new THREE.Vector3()).multiplyScalar(1 / inl.length);
  return { n: best.n, off: -best.n.dot(cen), inliers: inl, centroid: cen };
}

export class Tracking {
  constructor(studio, video) {
    this.studio = studio;
    this.video = video;
    this.solve = null;
    this.adjust = { groundY: 0, rot: 0, height: 1.5 };
    this.points = null;
    this.worker = null;
  }

  async loadVideo(file) {
    const v = this.video;
    if (this.url) URL.revokeObjectURL(this.url);
    this.url = URL.createObjectURL(file);
    v.src = this.url;
    v.muted = true; v.playsInline = true;
    await new Promise((res, rej) => { v.onloadedmetadata = res; v.onerror = () => rej(new Error('Não foi possível abrir este vídeo.')); });
    // alguns webm vêm com duração infinita
    if (!isFinite(v.duration) || v.duration < 0.05) {
      await new Promise((r) => {
        const check = () => { if (isFinite(v.duration) && v.duration > 0.05) { v.removeEventListener('durationchange', check); r(); } };
        v.addEventListener('durationchange', check);
        setTimeout(r, 4000);
        v.currentTime = 1e9;
      });
      v.currentTime = 0;
      await new Promise((r) => { v.onseeked = r; setTimeout(r, 1000); });
    }
    this.duration = v.duration;
    this.size = [v.videoWidth, v.videoHeight];
    return { duration: v.duration, width: v.videoWidth, height: v.videoHeight };
  }

  /** Analisa o vídeo: tracking 2D quadro a quadro e depois a solução da câmera. */
  async analyze({ maxSide, want, fps, shot, lens }, onProgress) {
    const v = this.video;
    const [W, H] = this.size;
    const k = Math.min(1, maxSide / Math.max(W, H));
    const tw = Math.max(2, Math.round(W * k)), th = Math.max(2, Math.round(H * k));
    const can = document.createElement('canvas');
    can.width = tw; can.height = th;
    const ctx = can.getContext('2d', { willReadFrequently: true });

    this.worker?.terminate();
    const worker = this.worker = new Worker(new URL('./track-worker.js', import.meta.url), { type: 'module' });
    const call = (msg, transfer) => new Promise((res, rej) => {
      worker.onmessage = (e) => (e.data.type === 'error' ? rej(new Error(e.data.message)) : res(e.data));
      worker.postMessage(msg, transfer || []);
    });

    await call({ type: 'start', width: tw, height: th, fps, maxSide: Math.max(tw, th), want });
    const n = isFinite(this.duration) && this.duration > 0.05 ? Math.max(2, Math.ceil(this.duration * fps)) : 3600;
    this.fps = fps;
    this.times = [];
    // o vídeo precisa estar “visível” para o navegador entregar quadros
    const wasHidden = v.hidden; v.hidden = false;
    this.studio.paused = true; // libera a CPU/GPU para a análise
    v.pause();
    v.currentTime = 0;
    await new Promise((r) => { v.onseeked = r; setTimeout(r, 1000); });
    const grab = async (i) => {
      ctx.drawImage(v, 0, 0, tw, th);
      const img = ctx.getImageData(0, 0, tw, th);
      const r = await call({ type: 'frame', buffer: img.data.buffer }, [img.data.buffer]);
      onProgress?.({ stage: 'track', done: i + 1, total: n, active: r.active });
    };
    if ('requestVideoFrameCallback' in HTMLVideoElement.prototype) {
      // Toca o vídeo e pausa em cada amostra: funciona mesmo em arquivos sem índice de busca.
      const nextFrame = (target) => new Promise((res) => {
        const cb = (_now, meta) => {
          if (meta.mediaTime >= target || v.ended) { v.pause(); res(meta.mediaTime); } else v.requestVideoFrameCallback(cb);
        };
        v.requestVideoFrameCallback(cb);
        v.play().catch(() => res(v.currentTime));
        v.onended = () => res(v.currentTime);
      });
      v.playbackRate = 0.5;
      for (let i = 0; i < n; i++) {
        const prev = this.times[this.times.length - 1] ?? -1;
        if (v.ended) break;
        const t = await nextFrame(Math.max(i / fps, prev + 0.5 / fps));
        if (t <= prev) break; // fim do vídeo
        this.times.push(t);
        await grab(i);
      }
      v.pause();
      v.playbackRate = 1;
      v.onended = null;
    } else {
      for (let i = 0; i < n; i++) {
        v.currentTime = Math.min(this.duration - 1e-3, (i + 0.5) / fps);
        await new Promise((r) => { v.onseeked = r; });
        this.times.push(v.currentTime);
        await grab(i);
      }
    }
    this.frameCount = this.times.length;
    v.hidden = wasHidden;
    this.studio.paused = false;
    onProgress?.({ stage: 'solve' });
    const { solve } = await call({ type: 'solve', settings: { shot, lensDistortion: !!lens } });
    worker.terminate(); this.worker = null;
    if (!solve.frames?.some((f) => f.solved)) throw new Error('Não foi possível resolver a câmera. Grave andando (não só girando), com mais textura no ambiente e boa luz.');
    this.solve = solve;
    this.prepare();
    return {
      solvedFrames: solve.frames.filter((f) => f.solved).length,
      frames: solve.frames.length,
      points: solve.points.length,
      error: solve.averageError,
      method: solve.methodUsed,
      hfov: 2 * Math.atan(solve.size[0] / 2 / this.focal(0)) * 180 / Math.PI
    };
  }

  focal(i) { return this.solve.frames[i]?.focal || this.solve.frames.find((f) => f.solved).focal; }

  /** Pré-calcula câmeras e o mapeamento “mundo da solução → mundo da cena (metros, Y para cima)”. */
  prepare() {
    const s = this.solve;
    this.cams = s.frames.map((f) => {
      if (!f.solved) return null;
      const R = rodrigues(f.rot);
      return { R, Rt: R.clone().transpose(), C: v3(f.center), f: f.focal };
    });
    const solved = this.cams.filter(Boolean);
    let n, origin;
    if (s.ground) {
      n = v3(s.ground.normal); origin = v3(s.ground.origin);
    } else {
      const g = fitGround(s.points, solved);
      n = g.n;
      // origem: onde o eixo óptico da 1ª câmera toca o chão (ou centróide do chão)
      const c0 = solved[0];
      const fwd = new THREE.Vector3(0, 0, 1).applyMatrix3(c0.Rt);
      const denom = n.dot(fwd);
      const t = denom < -1e-3 ? -(n.dot(c0.C) + g.off) / denom : -1;
      origin = t > 0 ? c0.C.clone().addScaledVector(fwd, t) : (g.centroid || c0.C.clone().addScaledVector(n, -(n.dot(c0.C) + g.off)));
    }
    const c0 = solved[0];
    const right = new THREE.Vector3(1, 0, 0).applyMatrix3(c0.Rt);
    const X = right.addScaledVector(n, -right.dot(n)).normalize();
    const Z = new THREE.Vector3().crossVectors(X, n).normalize();
    this.base = { X, Y: n.clone(), Z, O: origin };
    this.camHeight0 = Math.max(1e-6, n.dot(new THREE.Vector3().subVectors(c0.C, origin)));
    this.updateMapping();
  }

  updateMapping() {
    const { X, Y, Z, O } = this.base;
    const sc = this.adjust.height / this.camHeight0;
    const B = new THREE.Matrix4().makeBasis(X, Y, Z).transpose(); // linhas = eixos → solução para local
    const m = new THREE.Matrix4()
      .makeRotationY(THREE.MathUtils.degToRad(this.adjust.rot))
      .multiply(new THREE.Matrix4().makeTranslation(0, -this.adjust.groundY, 0))
      .multiply(new THREE.Matrix4().makeScale(sc, sc, sc))
      .multiply(B)
      .multiply(new THREE.Matrix4().makeTranslation(-O.x, -O.y, -O.z));
    this.M = m;
    this.Mrot = new THREE.Matrix4().extractRotation(m);
    this.buildPoints();
  }

  buildPoints() {
    const st = this.studio;
    if (this.points) { st.scene.remove(this.points); this.points.geometry.dispose(); }
    const pos = [], col = [];
    for (const p of this.solve.points) {
      const q = v3(p.pos).applyMatrix4(this.M);
      pos.push(q.x, q.y, q.z);
      const good = p.error < 1.5;
      col.push(good ? 0.2 : 1, good ? 1 : 0.6, good ? 0.5 : 0.2);
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
    this.points = new THREE.Points(g, new THREE.PointsMaterial({ size: 5, sizeAttenuation: false, vertexColors: true, depthTest: false, transparent: true, opacity: 0.85 }));
    this.points.renderOrder = 10;
    this.points.visible = this.showPoints !== false;
    st.scene.add(this.points);
  }

  /** Pose da câmera three.js para o quadro i (sem interpolação). */
  pose(i) {
    let c = this.cams[i];
    if (!c) { // quadro não resolvido: usa o resolvido mais próximo
      for (let d = 1; d < this.cams.length && !c; d++) c = this.cams[i - d] || this.cams[i + d];
    }
    const pos = c.C.clone().applyMatrix4(this.M);
    // rotação câmera→mundo: Mrot · Rᵀ · diag(1,−1,−1)
    const Rt4 = new THREE.Matrix4().setFromMatrix3(c.Rt);
    const flip = new THREE.Matrix4().makeScale(1, -1, -1);
    const rot = new THREE.Matrix4().multiplyMatrices(this.Mrot, Rt4).multiply(flip);
    const q = new THREE.Quaternion().setFromRotationMatrix(rot);
    return { pos, q, f: c.f };
  }

  /** Aplica a câmera do tempo t (s) do vídeo, interpolando entre quadros analisados. */
  applyTime(t) {
    if (!this.solve) return;
    const T = this.times, last = this.cams.length - 1;
    let i = 0;
    while (i < last && T[i + 1] <= t) i++;
    const a = i < last ? Math.min(1, Math.max(0, (t - T[i]) / Math.max(1e-6, T[i + 1] - T[i]))) : 0;
    const p0 = this.pose(i), p1 = this.pose(Math.min(i + 1, this.cams.length - 1));
    const cam = this.studio.camera;
    cam.position.lerpVectors(p0.pos, p1.pos, a);
    cam.quaternion.slerpQuaternions(p0.q, p1.q, a);
    const f = p0.f + (p1.f - p0.f) * a;
    cam.fov = 2 * Math.atan(this.solve.size[1] / 2 / f) * 180 / Math.PI;
    cam.updateProjectionMatrix();
    cam.updateMatrixWorld();
    this.currentFrame = i;
  }

  setShowPoints(on) { this.showPoints = on; if (this.points) this.points.visible = on; }

  dispose() {
    this.worker?.terminate();
    if (this.points) this.studio.scene.remove(this.points);
    this.points = null;
    this.solve = null;
  }
}
