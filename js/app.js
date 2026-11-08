(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);

  const state = {
    format: null,
    stream: null,
    facing: 'user',
    recorder: null,
    chunks: [],
    recStart: 0,
    timerId: null,
    showGuides: true,
    videoURL: null,
    duration: 0,
    trimStart: 0,
    trimEnd: 0,
    hookText: '',
    hookDur: 3,
    captions: [], // { id, start, end, text }
    style: 'classic',
    coachIndex: 0
  };

  // ---------- util ----------
  const fmt = (s) => {
    s = Math.max(0, s || 0);
    const m = Math.floor(s / 60), r = Math.floor(s % 60);
    return `${m}:${String(r).padStart(2, '0')}`;
  };
  const toast = (msg, ms = 2600) => {
    const t = $('toast');
    t.textContent = msg; t.hidden = false;
    clearTimeout(toast._t);
    toast._t = setTimeout(() => { t.hidden = true; }, ms);
  };
  const store = {
    get(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* sem storage */ } }
  };
  const pickMime = () => {
    const opts = ['video/mp4;codecs=avc1,mp4a', 'video/mp4', 'video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm'];
    if (!window.MediaRecorder) return '';
    return opts.find((m) => MediaRecorder.isTypeSupported(m)) || '';
  };

  function show(screen) {
    document.querySelectorAll('.screen').forEach((s) => s.classList.toggle('active', s.id === 'screen-' + screen));
  }

  // ---------- 1. home ----------
  function renderFormats() {
    const list = $('format-list');
    list.innerHTML = '';
    FORMATS.forEach((f) => {
      const b = document.createElement('button');
      b.className = 'format-card';
      b.innerHTML = `
        <span class="f-emoji">${f.emoji}</span>
        <span class="f-body">
          <strong>${f.name}</strong>
          <span>${f.desc}</span>
          <em>Duração ideal: ${f.duration}</em>
        </span>`;
      b.onclick = () => startRecording(f);
      list.appendChild(b);
    });
  }

  // ---------- 2. gravação ----------
  function renderGuides(container, format) {
    const Z = ZONES;
    let html = `
      <div class="z z-ui" style="top:${Z.topUI.y}%;height:${Z.topUI.h}%"></div>
      <div class="z z-hook" style="top:${Z.hook.y}%;height:${Z.hook.h}%"><span>ESPAÇO DO GANCHO</span></div>
      <div class="z z-cap" style="top:${Z.caption.y}%;height:${Z.caption.h}%"><span>LEGENDA</span></div>
      <div class="z z-ui" style="top:${Z.bottomUI.y}%;height:${Z.bottomUI.h}%"><span>nome e descrição</span></div>
      <div class="z z-ui z-side" style="left:${Z.sideUI.x}%;width:${Z.sideUI.w}%;top:${Z.sideUI.y}%;height:${Z.sideUI.h}%"></div>`;
    (format?.guides || []).forEach((g) => {
      if (g.type === 'silhouette') {
        html += `<svg class="g-sil" style="left:${g.x}%;top:${g.y}%;width:${g.w}%;height:${g.h}%" viewBox="0 0 100 235" preserveAspectRatio="xMidYMax meet">
          <path d="M50 12 C66 12 74 26 74 45 C74 66 64 80 50 80 C36 80 26 66 26 45 C26 26 34 12 50 12 Z
                   M40 80 L40 96 M60 80 L60 96 M4 235 C4 130 24 100 50 100 C76 100 96 130 96 235" /></svg>`;
      } else if (g.type === 'box') {
        html += `<div class="g-box" style="left:${g.x}%;top:${g.y}%;width:${g.w}%;height:${g.h}%"><span>${g.label || ''}</span></div>`;
      } else if (g.type === 'line') {
        html += `<div class="g-line" style="top:${g.y}%"><span>${g.label || ''}</span></div>`;
      } else if (g.type === 'thirds') {
        html += `<div class="g-thirds"><i style="left:33.33%"></i><i style="left:66.66%"></i><b style="top:33.33%"></b><b style="top:66.66%"></b></div>`;
      }
    });
    container.innerHTML = html;
  }

  async function openCamera() {
    stopCamera();
    const constraints = {
      audio: { echoCancellation: true, noiseSuppression: true },
      video: { facingMode: state.facing, width: { ideal: 1080 }, height: { ideal: 1920 }, aspectRatio: { ideal: 9 / 16 } }
    };
    try {
      state.stream = await navigator.mediaDevices.getUserMedia(constraints);
    } catch (e) {
      try { state.stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: state.facing }, audio: true }); }
      catch (e2) {
        toast('Não foi possível abrir a câmera. Permita o acesso à câmera e ao microfone (o site precisa estar em HTTPS).', 5000);
        return false;
      }
    }
    const cam = $('cam');
    cam.srcObject = state.stream;
    cam.classList.toggle('mirror', state.facing === 'user');
    return true;
  }

  function stopCamera() {
    if (state.stream) state.stream.getTracks().forEach((t) => t.stop());
    state.stream = null;
  }

  async function startRecording(format) {
    state.format = format;
    $('rec-format-name').textContent = `${format.emoji} ${format.name}`;
    renderGuides($('guides'), format);
    $('guides').hidden = !state.showGuides;
    show('record');
    rotateTips();
    await openCamera();
  }

  function rotateTips() {
    clearInterval(rotateTips._t);
    const tips = state.format.tips;
    let i = 0;
    const el = $('rec-tip');
    el.textContent = '💡 ' + tips[0];
    rotateTips._t = setInterval(() => {
      if (state.recorder) return;
      i = (i + 1) % tips.length;
      el.textContent = '💡 ' + tips[i];
    }, 4500);
  }

  function countdown(n) {
    return new Promise((resolve) => {
      const el = $('countdown');
      el.hidden = false;
      const tick = () => {
        if (n === 0) { el.hidden = true; resolve(); return; }
        el.textContent = n; el.classList.remove('pop'); void el.offsetWidth; el.classList.add('pop');
        n--; setTimeout(tick, 1000);
      };
      tick();
    });
  }

  async function toggleRecord() {
    if (state.recorder) { state.recorder.stop(); return; }
    if (!state.stream && !(await openCamera())) return;
    if (!window.MediaRecorder) { toast('Seu navegador não suporta gravação de vídeo.'); return; }
    await countdown(3);
    const mime = pickMime();
    state.chunks = [];
    const rec = new MediaRecorder(state.stream, mime ? { mimeType: mime, videoBitsPerSecond: 6e6 } : undefined);
    rec.ondataavailable = (e) => e.data.size && state.chunks.push(e.data);
    rec.onstop = () => {
      clearInterval(state.timerId);
      $('rec-btn').classList.remove('on');
      const blob = new Blob(state.chunks, { type: rec.mimeType || 'video/webm' });
      state.recorder = null;
      stopCamera();
      openEditor(blob);
    };
    rec.start(250);
    state.recorder = rec;
    state.recStart = Date.now();
    $('rec-btn').classList.add('on');
    $('rec-tip').textContent = '🔴 Gravando… toque de novo para parar';
    state.timerId = setInterval(() => {
      const s = (Date.now() - state.recStart) / 1000;
      $('rec-timer').textContent = `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(Math.floor(s % 60)).padStart(2, '0')}`;
    }, 250);
  }

  // ---------- 3. editor ----------
  const v = () => $('ed-video');

  function openEditor(blob) {
    if (state.videoURL) URL.revokeObjectURL(state.videoURL);
    state.videoURL = URL.createObjectURL(blob);
    state.captions = [];
    state.hookText = '';
    $('hook-text').value = '';
    $('cap-list').innerHTML = '';
    const video = v();
    video.src = state.videoURL;
    video.classList.toggle('mirror', false);
    show('editor');
    selectTab('trim');
    renderGuidesSafe();

    const ready = () => {
      let d = video.duration;
      // Vídeos gravados em webm às vezes vêm com duração Infinity: força o cálculo.
      if (!isFinite(d)) {
        video.currentTime = 1e9;
        video.addEventListener('timeupdate', function fix() {
          video.removeEventListener('timeupdate', fix);
          video.currentTime = 0;
          setDuration(video.duration);
        });
        return;
      }
      setDuration(d);
    };
    video.addEventListener('loadedmetadata', ready, { once: true });

    if (!store.get('coachDone', false)) startCoach();
  }

  function setDuration(d) {
    state.duration = d || 0;
    state.trimStart = 0;
    state.trimEnd = state.duration;
    ['trim-start', 'trim-end'].forEach((id) => { $(id).max = state.duration; $(id).step = 0.05; });
    $('trim-start').value = 0;
    $('trim-end').value = state.duration;
    updateTrimUI();
    buildThumbs();
  }

  async function buildThumbs() {
    // Miniaturas da timeline a partir de um segundo <video> para não atrapalhar a prévia.
    const track = $('tl-track');
    track.innerHTML = '';
    const n = 8;
    const tmp = document.createElement('video');
    tmp.muted = true; tmp.playsInline = true; tmp.src = state.videoURL;
    await new Promise((r) => { tmp.onloadeddata = r; tmp.onerror = r; });
    const c = document.createElement('canvas');
    c.width = 54; c.height = 96;
    const ctx = c.getContext('2d');
    for (let i = 0; i < n; i++) {
      const img = document.createElement('div');
      img.className = 'tl-thumb';
      track.appendChild(img);
      try {
        tmp.currentTime = (state.duration * (i + 0.5)) / n;
        await new Promise((r) => { tmp.onseeked = r; setTimeout(r, 800); });
        drawCover(ctx, tmp, c.width, c.height);
        img.style.backgroundImage = `url(${c.toDataURL('image/jpeg', 0.6)})`;
      } catch { /* sem miniatura */ }
    }
    tmp.removeAttribute('src');
  }

  function updateTrimUI() {
    const d = state.duration || 1;
    $('trim-start-v').textContent = fmt(state.trimStart) + '.' + Math.floor((state.trimStart % 1) * 10);
    $('trim-end-v').textContent = fmt(state.trimEnd) + '.' + Math.floor((state.trimEnd % 1) * 10);
    const t = $('tl-trim');
    t.style.left = (state.trimStart / d) * 100 + '%';
    t.style.width = ((state.trimEnd - state.trimStart) / d) * 100 + '%';
    renderCapMarks();
    updateTime();
  }

  function updateTime() {
    const video = v();
    const d = state.duration || 1;
    $('ed-time').textContent = `${fmt(video.currentTime - state.trimStart)} / ${fmt(state.trimEnd - state.trimStart)}`;
    $('tl-head').style.left = (video.currentTime / d) * 100 + '%';
    renderOverlays(video.currentTime);
  }

  function activeCaption(t) {
    return state.captions.find((c) => t >= c.start && t < c.end);
  }

  function renderOverlays(t) {
    const hook = $('ed-hook');
    const showHook = state.hookText && t >= state.trimStart && t < state.trimStart + state.hookDur;
    hook.hidden = !showHook;
    if (showHook) hook.textContent = state.hookText;
    const cap = activeCaption(t);
    const capEl = $('ed-caption');
    capEl.hidden = !cap;
    if (cap) capEl.textContent = cap.text;
    const st = CAPTION_STYLES.find((s) => s.id === state.style);
    [hook, capEl].forEach((el) => {
      el.style.color = st.color;
      el.style.background = st.bg || 'transparent';
      el.style.fontWeight = st.font;
      el.style.webkitTextStroke = st.stroke ? `0.06em ${st.stroke}` : '0';
      el.style.paintOrder = 'stroke fill';
    });
  }

  function renderGuidesSafe() {
    const Z = ZONES;
    $('ed-safe').innerHTML = `
      <div class="z z-ui" style="top:${Z.topUI.y}%;height:${Z.topUI.h}%"></div>
      <div class="z z-ui" style="top:${Z.bottomUI.y}%;height:${Z.bottomUI.h}%"></div>
      <div class="z z-ui z-side" style="left:${Z.sideUI.x}%;width:${Z.sideUI.w}%;top:${Z.sideUI.y}%;height:${Z.sideUI.h}%"></div>`;
    $('ed-safe').hidden = !$('show-safe').checked;
  }

  function togglePlay() {
    const video = v();
    if (video.paused) {
      if (video.currentTime < state.trimStart || video.currentTime >= state.trimEnd - 0.05) video.currentTime = state.trimStart;
      video.play();
    } else video.pause();
  }

  function selectTab(tab) {
    document.querySelectorAll('#tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
    ['trim', 'hook', 'captions', 'style'].forEach((p) => { $('panel-' + p).hidden = p !== tab; });
  }

  // legendas
  function addCaption() {
    const text = $('cap-text').value.trim();
    if (!text) { toast('Escreva o texto da legenda.'); return; }
    const start = v().currentTime;
    const dur = parseFloat($('cap-dur').value);
    const end = Math.min(start + dur, state.trimEnd || start + dur);
    // evita sobreposição: encurta a legenda anterior
    state.captions.forEach((c) => { if (c.start < start && c.end > start) c.end = start; });
    state.captions.push({ id: Date.now(), start, end, text });
    state.captions.sort((a, b) => a.start - b.start);
    $('cap-text').value = '';
    renderCapList();
    renderCapMarks();
    v().currentTime = end; // avança para a próxima fala
    toast('Legenda adicionada. Toque ▶ para ouvir a próxima frase.');
  }

  function renderCapList() {
    const ul = $('cap-list');
    ul.innerHTML = '';
    state.captions.forEach((c) => {
      const li = document.createElement('li');
      li.innerHTML = `<button class="cap-go">${fmt(c.start)}</button><span contenteditable="true"></span><button class="cap-del" aria-label="Remover">✕</button>`;
      const span = li.querySelector('span');
      span.textContent = c.text;
      span.oninput = () => { c.text = span.textContent; updateTime(); };
      li.querySelector('.cap-go').onclick = () => { v().currentTime = c.start; };
      li.querySelector('.cap-del').onclick = () => {
        state.captions = state.captions.filter((x) => x !== c);
        renderCapList(); renderCapMarks(); updateTime();
      };
      ul.appendChild(li);
    });
  }

  function renderCapMarks() {
    const d = state.duration || 1;
    $('tl-caps').innerHTML = state.captions.map((c) =>
      `<i style="left:${(c.start / d) * 100}%;width:${((c.end - c.start) / d) * 100}%"></i>`).join('');
  }

  function renderStyles() {
    const wrap = $('style-list');
    wrap.innerHTML = '';
    CAPTION_STYLES.forEach((s) => {
      const b = document.createElement('button');
      b.className = 'style-chip' + (s.id === state.style ? ' active' : '');
      b.textContent = s.name;
      b.style.color = s.color;
      b.style.background = s.bg || '#333';
      b.style.fontWeight = s.font;
      b.onclick = () => { state.style = s.id; renderStyles(); updateTime(); };
      wrap.appendChild(b);
    });
  }

  // tutorial
  function startCoach() { state.coachIndex = 0; showCoach(); }
  function showCoach() {
    const step = TUTORIAL[state.coachIndex];
    document.querySelectorAll('.coach-target').forEach((el) => el.classList.remove('coach-target'));
    if (!step) { $('coach').hidden = true; store.set('coachDone', true); return; }
    if (step.tab) selectTab(step.tab);
    document.querySelectorAll('.coach-target').forEach((el) => el.classList.remove('coach-target'));
    const target = step.tab ? document.querySelector(`#tabs [data-tab="${step.tab}"]`) : $('export-btn');
    target.classList.add('coach-target');
    $('coach-step').textContent = `Passo ${state.coachIndex + 1} de ${TUTORIAL.length}`;
    $('coach-title').textContent = step.title;
    $('coach-text').textContent = step.text;
    $('coach-next').textContent = state.coachIndex === TUTORIAL.length - 1 ? 'Entendi!' : 'Próximo';
    $('coach').hidden = false;
  }

  // ---------- exportação ----------
  function drawCover(ctx, video, W, H) {
    const vw = video.videoWidth, vh = video.videoHeight;
    if (!vw || !vh) return;
    const s = Math.max(W / vw, H / vh);
    const dw = vw * s, dh = vh * s;
    ctx.drawImage(video, (W - dw) / 2, (H - dh) / 2, dw, dh);
  }

  function wrapLines(ctx, text, maxW) {
    const words = text.split(/\s+/);
    const lines = [];
    let line = '';
    words.forEach((w) => {
      const test = line ? line + ' ' + w : w;
      if (ctx.measureText(test).width > maxW && line) { lines.push(line); line = w; } else line = test;
    });
    if (line) lines.push(line);
    return lines;
  }

  function drawText(ctx, text, centerY, size, W) {
    const st = CAPTION_STYLES.find((s) => s.id === state.style);
    ctx.font = `${st.font} ${size}px system-ui, -apple-system, "Segoe UI", Roboto, sans-serif`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    const lines = wrapLines(ctx, text, W * 0.78);
    const lh = size * 1.18;
    const top = centerY - ((lines.length - 1) * lh) / 2;
    lines.forEach((l, i) => {
      const y = top + i * lh;
      if (st.bg) {
        const w = ctx.measureText(l).width + size * 0.6;
        ctx.fillStyle = st.bg;
        roundRect(ctx, W / 2 - w / 2, y - lh / 2, w, lh, size * 0.2);
      }
      if (st.stroke) { ctx.lineWidth = size * 0.14; ctx.strokeStyle = st.stroke; ctx.lineJoin = 'round'; ctx.strokeText(l, W / 2, y); }
      ctx.fillStyle = st.color;
      ctx.fillText(l, W / 2, y);
    });
  }

  function roundRect(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.fill();
  }

  let audioGraph = null;
  function getAudioStream(video) {
    try {
      if (!audioGraph) {
        const AC = window.AudioContext || window.webkitAudioContext;
        const ac = new AC();
        const src = ac.createMediaElementSource(video);
        const dest = ac.createMediaStreamDestination();
        src.connect(dest); src.connect(ac.destination);
        audioGraph = { ac, dest };
      }
      audioGraph.ac.resume();
      return audioGraph.dest.stream;
    } catch { return null; }
  }

  async function exportVideo() {
    if (!state.duration) { toast('Carregando o vídeo…'); return; }
    const video = v();
    video.pause();
    const W = 720, H = 1280;
    const canvas = document.createElement('canvas');
    canvas.width = W; canvas.height = H;
    const ctx = canvas.getContext('2d');
    const stream = canvas.captureStream(30);
    const audio = getAudioStream(video);
    audio?.getAudioTracks().forEach((t) => stream.addTrack(t));
    const mime = pickMime();
    const rec = new MediaRecorder(stream, mime ? { mimeType: mime, videoBitsPerSecond: 5e6 } : undefined);
    const chunks = [];
    rec.ondataavailable = (e) => e.data.size && chunks.push(e.data);

    $('export-overlay').hidden = false;
    $('export-actions').hidden = true;
    $('export-title').textContent = 'Exportando…';
    $('export-msg').textContent = 'O vídeo é reproduzido uma vez para gerar o arquivo. Mantenha esta tela aberta.';
    $('export-bar').style.width = '0%';

    video.currentTime = state.trimStart;
    await new Promise((r) => { video.onseeked = r; setTimeout(r, 1000); });
    video.onseeked = null;

    const total = state.trimEnd - state.trimStart;
    let raf;
    const frame = () => {
      const t = video.currentTime;
      ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H);
      drawCover(ctx, video, W, H);
      if (state.hookText && t < state.trimStart + state.hookDur) {
        drawText(ctx, state.hookText, H * (ZONES.hook.y + ZONES.hook.h / 2) / 100, 64, W);
      }
      const cap = activeCaption(t);
      if (cap) drawText(ctx, cap.text, H * (ZONES.caption.y + ZONES.caption.h / 2) / 100, 52, W);
      $('export-bar').style.width = Math.min(100, ((t - state.trimStart) / total) * 100) + '%';
      if (t >= state.trimEnd || video.ended) { video.pause(); rec.stop(); return; }
      raf = requestAnimationFrame(frame);
    };

    rec.onstop = () => {
      cancelAnimationFrame(raf);
      const type = rec.mimeType || 'video/webm';
      const blob = new Blob(chunks, { type });
      const ext = type.includes('mp4') ? 'mp4' : 'webm';
      const url = URL.createObjectURL(blob);
      const a = $('export-download');
      a.href = url; a.download = `reels.${ext}`;
      $('export-title').textContent = 'Pronto! 🎉';
      $('export-msg').textContent = ext === 'webm'
        ? 'Arquivo .webm gerado. Se o Instagram não aceitar, converta para .mp4 ou use um navegador com suporte a MP4 (Safari/Chrome recente).'
        : 'Vídeo MP4 pronto para postar.';
      $('export-bar').style.width = '100%';
      $('export-actions').hidden = false;
      $('export-share').hidden = !(navigator.canShare && navigator.canShare({ files: [new File([blob], 'reels.' + ext, { type })] }));
      $('export-share').onclick = () => navigator.share({ files: [new File([blob], 'reels.' + ext, { type })] }).catch(() => {});
    };

    rec.start(250);
    await video.play();
    frame();
  }

  // ---------- eventos ----------
  function bind() {
    $('rec-back').onclick = () => { if (state.recorder) return; stopCamera(); show('home'); };
    $('rec-btn').onclick = toggleRecord;
    $('flip-cam').onclick = async () => {
      if (state.recorder) return;
      state.facing = state.facing === 'user' ? 'environment' : 'user';
      await openCamera();
    };
    $('toggle-guides').onclick = () => {
      state.showGuides = !state.showGuides;
      $('guides').hidden = !state.showGuides;
      $('toggle-guides').textContent = 'Guias: ' + (state.showGuides ? 'on' : 'off');
    };

    $('file-input').onchange = (e) => {
      const f = e.target.files[0];
      if (f) openEditor(f);
      e.target.value = '';
    };

    const video = v();
    video.addEventListener('timeupdate', () => {
      if (!video.paused && video.currentTime >= state.trimEnd) video.pause();
      updateTime();
    });
    video.addEventListener('seeked', updateTime);
    video.addEventListener('play', () => { $('play-btn').textContent = '❚❚'; tick(); });
    video.addEventListener('pause', () => { $('play-btn').textContent = '▶'; });
    const tick = () => { if (!video.paused) { updateTime(); requestAnimationFrame(tick); } };
    $('play-btn').onclick = togglePlay;
    $('ed-stage').onclick = togglePlay;

    $('timeline').addEventListener('pointerdown', (e) => {
      const seek = (ev) => {
        const r = $('timeline').getBoundingClientRect();
        const p = Math.min(1, Math.max(0, (ev.clientX - r.left) / r.width));
        video.currentTime = p * state.duration;
      };
      seek(e);
      const move = (ev) => seek(ev);
      window.addEventListener('pointermove', move);
      window.addEventListener('pointerup', () => window.removeEventListener('pointermove', move), { once: true });
    });

    $('ed-back').onclick = () => { video.pause(); show('home'); };
    document.querySelectorAll('#tabs button').forEach((b) => { b.onclick = () => selectTab(b.dataset.tab); });

    $('trim-start').oninput = (e) => {
      state.trimStart = Math.min(parseFloat(e.target.value), state.trimEnd - 0.5);
      e.target.value = state.trimStart; video.currentTime = state.trimStart; updateTrimUI();
    };
    $('trim-end').oninput = (e) => {
      state.trimEnd = Math.max(parseFloat(e.target.value), state.trimStart + 0.5);
      e.target.value = state.trimEnd; video.currentTime = state.trimEnd; updateTrimUI();
    };
    $('set-start').onclick = () => { $('trim-start').value = video.currentTime; $('trim-start').oninput({ target: $('trim-start') }); };
    $('set-end').onclick = () => { $('trim-end').value = video.currentTime; $('trim-end').oninput({ target: $('trim-end') }); };

    $('hook-text').oninput = (e) => { state.hookText = e.target.value.trim(); if (video.currentTime > state.trimStart + state.hookDur) video.currentTime = state.trimStart; updateTime(); };
    $('hook-dur').oninput = (e) => { state.hookDur = parseFloat(e.target.value); $('hook-dur-v').textContent = state.hookDur + 's'; updateTime(); };

    $('cap-add').onclick = addCaption;
    $('cap-text').addEventListener('keydown', (e) => { if (e.key === 'Enter') addCaption(); });
    $('cap-text').addEventListener('focus', () => video.pause());
    $('cap-dur').oninput = (e) => { $('cap-dur-v').textContent = e.target.value + 's'; };

    $('show-safe').onchange = renderGuidesSafe;

    $('coach-next').onclick = () => { state.coachIndex++; showCoach(); };
    $('coach-skip').onclick = () => { state.coachIndex = TUTORIAL.length; showCoach(); };
    $('help-btn').onclick = startCoach;

    $('export-btn').onclick = exportVideo;
    $('export-close').onclick = () => { $('export-overlay').hidden = true; };
  }

  renderFormats();
  renderStyles();
  bind();

  if ('serviceWorker' in navigator && location.protocol === 'https:') {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
})();
