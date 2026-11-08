// Cena 3D: renderizador PBR, iluminação por HDRI, sombras suaves, pós-processamento e objetos.
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { TransformControls } from 'three/addons/controls/TransformControls.js';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
import { RGBELoader } from 'three/addons/loaders/RGBELoader.js';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { Sky } from 'three/addons/objects/Sky.js';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { GTAOPass } from 'three/addons/postprocessing/GTAOPass.js';
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';
import { ShaderPass } from 'three/addons/postprocessing/ShaderPass.js';

export const MATERIALS = {
  plastico: { label: 'Plástico', color: '#e8e8e8', roughness: 0.35, metalness: 0, clearcoat: 0.3 },
  cromo:    { label: 'Cromo', color: '#ffffff', roughness: 0.04, metalness: 1 },
  ouro:     { label: 'Ouro', color: '#ffc861', roughness: 0.18, metalness: 1 },
  cobre:    { label: 'Cobre', color: '#f0a07a', roughness: 0.3, metalness: 1 },
  aluminio: { label: 'Alumínio', color: '#d6d9de', roughness: 0.45, metalness: 1 },
  vidro:    { label: 'Vidro', color: '#ffffff', roughness: 0.02, metalness: 0, transmission: 1, ior: 1.5, thickness: 0.6 },
  ceramica: { label: 'Cerâmica', color: '#f4efe6', roughness: 0.12, metalness: 0, clearcoat: 1, clearcoatRoughness: 0.05 },
  borracha: { label: 'Borracha', color: '#202124', roughness: 0.9, metalness: 0 },
  carro:    { label: 'Pintura', color: '#c0102a', roughness: 0.4, metalness: 0.6, clearcoat: 1, clearcoatRoughness: 0.03 },
  veludo:   { label: 'Tecido', color: '#5a3fb0', roughness: 1, metalness: 0, sheen: 1, sheenRoughness: 0.5, sheenColor: '#b9a6ff' },
  neon:     { label: 'Neon', color: '#111111', roughness: 0.5, metalness: 0, emissive: '#30e0ff', emissiveIntensity: 4 }
};

export const PRIMITIVES = {
  cubo:     { label: 'Cubo', icon: '■', make: () => new THREE.BoxGeometry(0.4, 0.4, 0.4, 1, 1, 1), y: 0.2 },
  esfera:   { label: 'Esfera', icon: '●', make: () => new THREE.SphereGeometry(0.22, 96, 64), y: 0.22 },
  cilindro: { label: 'Cilindro', icon: '▮', make: () => new THREE.CylinderGeometry(0.16, 0.16, 0.45, 96), y: 0.225 },
  cone:     { label: 'Cone', icon: '▲', make: () => new THREE.ConeGeometry(0.2, 0.45, 96), y: 0.225 },
  toro:     { label: 'Toro', icon: '◎', make: () => new THREE.TorusGeometry(0.16, 0.06, 64, 160), y: 0.22 },
  no:       { label: 'Nó', icon: '∞', make: () => new THREE.TorusKnotGeometry(0.13, 0.045, 300, 48), y: 0.22 },
  capsula:  { label: 'Cápsula', icon: '⬮', make: () => new THREE.CapsuleGeometry(0.12, 0.25, 16, 48), y: 0.245 },
  placa:    { label: 'Placa', icon: '▬', make: () => new THREE.BoxGeometry(0.6, 0.03, 0.4), y: 0.015 }
};

const GRAIN_SHADER = {
  uniforms: { tDiffuse: { value: null }, amount: { value: 0.02 }, time: { value: 0 } },
  vertexShader: 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }',
  fragmentShader: `uniform sampler2D tDiffuse; uniform float amount; uniform float time; varying vec2 vUv;
    float h(vec2 p){ return fract(sin(dot(p, vec2(12.9898,78.233)) + time) * 43758.5453); }
    void main(){ vec4 c = texture2D(tDiffuse, vUv);
      float n = h(vUv * 1000.0) - 0.5;
      vec2 d = vUv - 0.5; float vig = 1.0 - dot(d, d) * 0.35;
      gl_FragColor = vec4((c.rgb + n * amount) * vig, c.a); }`
};

export class Studio {
  constructor(canvas) {
    this.canvas = canvas;
    this.objects = [];
    this.selected = null;
    this.onSelect = () => {};
    this.quality = 'high';
    this.tracked = false; // modo composição sobre vídeo

    const r = this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, preserveDrawingBuffer: true, powerPreference: 'high-performance' });
    r.toneMapping = THREE.AgXToneMapping;
    r.toneMappingExposure = 1;
    r.shadowMap.enabled = true;
    r.shadowMap.type = THREE.VSMShadowMap;
    r.outputColorSpace = THREE.SRGBColorSpace;

    this.scene = new THREE.Scene();
    this.camera = new THREE.PerspectiveCamera(45, 1, 0.01, 200);
    this.camera.position.set(1.1, 0.9, 1.4);

    this.pmrem = new THREE.PMREMGenerator(r);
    this.roomEnv = this.pmrem.fromScene(new RoomEnvironment(), 0.04).texture;

    // sol (luz direcional com sombra suave)
    this.sun = new THREE.DirectionalLight(0xffffff, 3);
    this.sun.castShadow = true;
    this.sun.shadow.mapSize.set(2048, 2048);
    this.sun.shadow.radius = 4;
    this.sun.shadow.blurSamples = 16;
    this.sun.shadow.bias = -0.0004;
    this.sun.shadow.normalBias = 0.01;
    const sc = this.sun.shadow.camera; sc.left = sc.bottom = -3; sc.right = sc.top = 3; sc.near = 0.1; sc.far = 30;
    this.scene.add(this.sun, this.sun.target);
    this.sunAngles = { azim: 35, elev: 45 };

    // chão do estúdio e “shadow catcher” para o modo tracking
    this.floor = new THREE.Mesh(new THREE.CircleGeometry(12, 96),
      new THREE.MeshPhysicalMaterial({ color: 0x9a9a9e, roughness: 0.55, metalness: 0 }));
    this.floor.rotation.x = -Math.PI / 2;
    this.floor.receiveShadow = true;
    this.floor.name = 'floor';
    this.scene.add(this.floor);

    this.catcher = new THREE.Mesh(new THREE.PlaneGeometry(40, 40), new THREE.ShadowMaterial({ opacity: 0.55 }));
    this.catcher.rotation.x = -Math.PI / 2;
    this.catcher.receiveShadow = true;
    this.catcher.visible = false;
    this.catcher.name = 'floor';
    this.scene.add(this.catcher);

    this.sky = new Sky();
    this.sky.scale.setScalar(100);
    this.sky.visible = false;
    this.scene.add(this.sky);

    // controles
    this.orbit = new OrbitControls(this.camera, canvas);
    this.orbit.target.set(0, 0.2, 0);
    this.orbit.enableDamping = true;
    this.orbit.maxPolarAngle = Math.PI * 0.495;
    this.gizmo = new TransformControls(this.camera, canvas);
    this.gizmo.setSize(1.15);
    this.gizmo.addEventListener('dragging-changed', (e) => { this.orbit.enabled = !e.value && !this.tracked; });
    this.gizmo.addEventListener('objectChange', () => { this.keepAboveGround(this.selected); this.dirty(); });
    this.scene.add(this.gizmo.getHelper ? this.gizmo.getHelper() : this.gizmo);
    this.dropMode = false;

    // pós-processamento
    this.composer = new EffectComposer(r);
    this.renderPass = new RenderPass(this.scene, this.camera);
    this.aoPass = new GTAOPass(this.scene, this.camera, 1, 1);
    this.aoPass.blendIntensity = 0.85;
    this.bloomPass = new UnrealBloomPass(new THREE.Vector2(1, 1), 0.45, 0.6, 0.9);
    this.bloomPass.enabled = false;
    this.outputPass = new OutputPass();
    this.grainPass = new ShaderPass(GRAIN_SHADER);
    [this.renderPass, this.aoPass, this.bloomPass, this.outputPass, this.grainPass].forEach((p) => this.composer.addPass(p));

    this.setEnvironment('studio');
    this.setSun({});
    this.bindPicking();
    this.resize();
    window.addEventListener('resize', () => this.resize());
    this.clock = new THREE.Clock();
    r.setAnimationLoop(() => this.frame());
  }

  dirty() { /* renderização contínua; gancho para futuro modo “progressivo” */ }

  // ---------- tamanho ----------
  setAspect(aspect) { this.fixedAspect = aspect; this.resize(); }

  resize() {
    const host = this.canvas.parentElement;
    let w = host.clientWidth, h = host.clientHeight;
    if (this.fixedAspect) { // encaixa o quadro do vídeo na área disponível
      if (w / h > this.fixedAspect) w = Math.round(h * this.fixedAspect); else h = Math.round(w / this.fixedAspect);
    }
    this.canvas.style.width = w + 'px';
    this.canvas.style.height = h + 'px';
    const pr = Math.min(window.devicePixelRatio, { draft: 1, high: 2, ultra: 3 }[this.quality]);
    this.renderer.setPixelRatio(pr);
    this.renderer.setSize(w, h, false);
    this.composer.setPixelRatio(pr);
    this.composer.setSize(w, h);
    this.aoPass.setSize(w * pr, h * pr);
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
  }

  setQuality(q) {
    this.quality = q;
    this.sun.shadow.mapSize.setScalar({ draft: 1024, high: 2048, ultra: 4096 }[q]);
    this.sun.shadow.map?.dispose(); this.sun.shadow.map = null;
    this.sun.shadow.blurSamples = { draft: 8, high: 16, ultra: 25 }[q];
    this.aoPass.updateGtaoMaterial({ samples: { draft: 8, high: 16, ultra: 24 }[q] });
    this.resize();
  }

  // ---------- luz ----------
  setEnvironment(name, hdrTexture) {
    this.envName = name;
    this.sky.visible = false;
    let env, bg;
    const u = this.sky.material.uniforms;
    const skyEnv = (elev, azim, turb, ray) => {
      u.turbidity.value = turb; u.rayleigh.value = ray; u.mieCoefficient.value = 0.005; u.mieDirectionalG.value = 0.8;
      const sunPos = new THREE.Vector3().setFromSphericalCoords(1, THREE.MathUtils.degToRad(90 - elev), THREE.MathUtils.degToRad(azim));
      u.sunPosition.value.copy(sunPos);
      const tmp = new THREE.Scene(); const s = this.sky.clone(); s.visible = true; tmp.add(s);
      return this.pmrem.fromScene(tmp).texture;
    };
    if (hdrTexture) { env = this.pmrem.fromEquirectangular(hdrTexture).texture; bg = hdrTexture; }
    else if (name === 'studio') { env = this.roomEnv; bg = new THREE.Color(0x2a2b30); }
    else if (name === 'sunset') { env = skyEnv(4, 250, 10, 3); this.setSun({ elev: 6, azim: 250, color: 0xffb070, intensity: 2.5 }); }
    else if (name === 'day') { env = skyEnv(55, 35, 2, 1); this.setSun({ elev: 55, azim: 35, color: 0xfff4e5, intensity: 4 }); }
    else if (name === 'night') { env = this.roomEnv; bg = new THREE.Color(0x05060a); this.setSun({ intensity: 0.4, color: 0x8aa8ff }); }
    if (name === 'sunset' || name === 'day') { this.sky.visible = !this.tracked; bg = null; }
    this.scene.environment = env;
    this.scene.environmentIntensity = name === 'night' ? 0.15 : (this.envIntensity ?? 1);
    if (!this.tracked) this.scene.background = bg ?? null;
    this.floor.material.color.set(name === 'studio' || name === 'night' ? 0x8f8f94 : 0x6f6a60);
  }

  setEnvIntensity(v) { this.envIntensity = v; this.scene.environmentIntensity = v; }

  setSun({ azim, elev, intensity, color, soft }) {
    if (azim !== undefined) this.sunAngles.azim = azim;
    if (elev !== undefined) this.sunAngles.elev = elev;
    if (intensity !== undefined) this.sun.intensity = intensity;
    if (color !== undefined) this.sun.color.set(color);
    if (soft !== undefined) this.sun.shadow.radius = soft;
    const { azim: a, elev: e } = this.sunAngles;
    const dir = new THREE.Vector3().setFromSphericalCoords(8, THREE.MathUtils.degToRad(90 - e), THREE.MathUtils.degToRad(a));
    this.sun.target.position.copy(this.orbit.target);
    this.sun.position.copy(this.orbit.target).add(dir);
  }

  // ---------- objetos ----------
  makeMaterial(preset) {
    const p = MATERIALS[preset] || MATERIALS.plastico;
    const m = new THREE.MeshPhysicalMaterial();
    this.applyMaterial(m, preset);
    m.userData.preset = preset;
    return m;
  }

  applyMaterial(m, preset) {
    const p = MATERIALS[preset];
    Object.assign(m, { roughness: 0.5, metalness: 0, transmission: 0, ior: 1.5, thickness: 0, clearcoat: 0, clearcoatRoughness: 0.1, sheen: 0, emissiveIntensity: 0 });
    m.color.set(p.color);
    m.emissive.set(p.emissive || '#000000');
    for (const k of ['roughness', 'metalness', 'transmission', 'ior', 'thickness', 'clearcoat', 'clearcoatRoughness', 'sheen', 'sheenRoughness', 'emissiveIntensity']) {
      if (p[k] !== undefined) m[k] = p[k];
    }
    if (p.sheenColor) m.sheenColor.set(p.sheenColor);
    m.userData.preset = preset;
    m.needsUpdate = true;
  }

  add(kind, at) {
    const prim = PRIMITIVES[kind];
    const mesh = new THREE.Mesh(prim.make(), this.makeMaterial(kind === 'placa' ? 'ceramica' : 'plastico'));
    mesh.castShadow = mesh.receiveShadow = true;
    mesh.userData.kind = kind;
    mesh.userData.baseY = prim.y;
    const p = at || this.spawnPoint();
    mesh.position.set(p.x, p.y + prim.y, p.z);
    this.scene.add(mesh);
    this.objects.push(mesh);
    this.select(mesh);
    return mesh;
  }

  spawnPoint() {
    // no chão, à frente da câmera
    const ray = new THREE.Raycaster();
    ray.setFromCamera(new THREE.Vector2(0, -0.15), this.camera);
    const hit = ray.ray.intersectPlane(new THREE.Plane(new THREE.Vector3(0, 1, 0), -this.groundY()), new THREE.Vector3());
    const p = hit && hit.distanceTo(this.camera.position) < 20 ? hit : new THREE.Vector3(0, this.groundY(), 0);
    // afasta de objetos já existentes para não sobrepor
    let tries = 0;
    while (tries++ < 12 && this.objects.some((o) => Math.hypot(o.position.x - p.x, o.position.z - p.z) < 0.35 * this.unit())) {
      p.x += 0.4 * this.unit();
    }
    return p;
  }

  unit() { return this.sceneScale || 1; }
  groundY() { return this.tracked ? this.catcher.position.y : 0; }

  async importGLB(file) {
    const url = URL.createObjectURL(file);
    const gltf = await new GLTFLoader().loadAsync(url);
    URL.revokeObjectURL(url);
    const root = gltf.scene;
    root.traverse((o) => { if (o.isMesh) { o.castShadow = o.receiveShadow = true; } });
    // normaliza para ~40 cm e apoia no chão
    const box = new THREE.Box3().setFromObject(root);
    const size = box.getSize(new THREE.Vector3());
    const s = (0.4 * this.unit()) / Math.max(size.x, size.y, size.z, 1e-6);
    root.scale.setScalar(s);
    const box2 = new THREE.Box3().setFromObject(root);
    const p = this.spawnPoint();
    root.position.set(p.x - (box2.min.x + box2.max.x) / 2, p.y - box2.min.y, p.z - (box2.min.z + box2.max.z) / 2);
    root.userData.kind = 'modelo';
    this.scene.add(root);
    this.objects.push(root);
    this.select(root);
  }

  duplicate() {
    if (!this.selected) return;
    const c = this.selected.clone(true);
    c.traverse((o) => { if (o.isMesh) o.material = o.material.clone(); });
    c.position.x += 0.3 * this.unit();
    this.scene.add(c); this.objects.push(c); this.select(c);
  }

  remove() {
    if (!this.selected) return;
    const o = this.selected;
    this.select(null);
    this.scene.remove(o);
    this.objects = this.objects.filter((x) => x !== o);
  }

  keepAboveGround(o) {
    if (!o) return;
    const box = new THREE.Box3().setFromObject(o);
    const g = this.groundY();
    if (box.min.y < g - 1e-4) o.position.y += g - box.min.y;
  }

  select(o) {
    this.selected = o;
    if (o) this.gizmo.attach(o); else this.gizmo.detach();
    this.onSelect(o);
  }

  selectedMaterial() {
    let m = null;
    this.selected?.traverse((o) => { if (!m && o.isMesh) m = o.material; });
    return m;
  }

  setGizmoMode(mode) {
    this.dropMode = mode === 'drop';
    if (!this.dropMode) this.gizmo.setMode(mode);
    this.gizmo.enabled = !this.dropMode;
    this.gizmo.getHelper && (this.gizmo.getHelper().visible = !this.dropMode);
  }

  bindPicking() {
    const ray = new THREE.Raycaster();
    let down = null;
    this.canvas.addEventListener('pointerdown', (e) => { down = { x: e.clientX, y: e.clientY, t: performance.now() }; });
    this.canvas.addEventListener('pointerup', (e) => {
      if (!down || this.gizmo.dragging) return;
      const moved = Math.hypot(e.clientX - down.x, e.clientY - down.y);
      if (moved > 8 || performance.now() - down.t > 500) return;
      const r = this.canvas.getBoundingClientRect();
      const ndc = new THREE.Vector2(((e.clientX - r.left) / r.width) * 2 - 1, -((e.clientY - r.top) / r.height) * 2 + 1);
      ray.setFromCamera(ndc, this.camera);
      if (this.dropMode && this.selected) {
        const hit = ray.ray.intersectPlane(new THREE.Plane(new THREE.Vector3(0, 1, 0), -this.groundY()), new THREE.Vector3());
        if (hit) {
          const box = new THREE.Box3().setFromObject(this.selected);
          const c = box.getCenter(new THREE.Vector3());
          this.selected.position.x += hit.x - c.x;
          this.selected.position.z += hit.z - c.z;
          this.selected.position.y += hit.y - box.min.y;
        }
        return;
      }
      const hits = ray.intersectObjects(this.objects, true);
      if (hits.length) {
        let o = hits[0].object;
        while (o.parent && !this.objects.includes(o)) o = o.parent;
        this.select(o);
      } else this.select(null);
    });
  }

  // ---------- pós ----------
  setAO(on) { this.aoPass.enabled = on; }
  setBloom(on) { this.bloomPass.enabled = on; }
  setGrain(v) { this.grainPass.uniforms.amount.value = v; this.grainPass.enabled = v > 0; }

  render() {
    this.grainPass.uniforms.time.value = (this.grainPass.uniforms.time.value + 0.37) % 100;
    this.composer.render();
  }

  frame() {
    if (this.paused) return;
    if (!this.tracked) this.orbit.update();
    if (this.beforeRender) this.beforeRender();
    this.render();
  }

  snapshot() {
    this.render();
    return new Promise((res) => this.canvas.toBlob(res, 'image/png'));
  }
}
