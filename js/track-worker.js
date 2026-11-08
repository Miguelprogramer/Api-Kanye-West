// Worker do tracking: roda o tracker de câmera do effectCraft (Rust → WebAssembly) fora da thread da interface.
import init, { CameraTracker } from '../pkg/cgi_wasm.js';

let tracker = null;
const ready = init();

self.onmessage = async (e) => {
  const m = e.data;
  try {
    await ready;
    if (m.type === 'start') {
      tracker?.free();
      tracker = new CameraTracker(m.width, m.height, m.fps, m.maxSide, m.want);
      self.postMessage({ type: 'started' });
    } else if (m.type === 'frame') {
      tracker.push_rgba(new Uint8Array(m.buffer));
      self.postMessage({ type: 'tracked', frame: tracker.frames(), active: tracker.active() });
    } else if (m.type === 'solve') {
      const json = tracker.solve(JSON.stringify(m.settings));
      self.postMessage({ type: 'solved', solve: JSON.parse(json) });
    }
  } catch (err) {
    self.postMessage({ type: 'error', message: String(err?.message || err) });
  }
};
