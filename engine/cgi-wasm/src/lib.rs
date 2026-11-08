//! WebAssembly bindings for effectcraft's 3D camera tracker.
//!
//! Usage from JavaScript: create a [`CameraTracker`] with the video size, push every frame as
//! RGBA bytes, then call [`CameraTracker::solve`], which returns the camera solve as JSON
//! (`effectcraft_track::camtrack::CameraSolve`).

use effectcraft_raster::Image;
use effectcraft_track::Frame;
use effectcraft_track::camtrack::{CameraTracks, SolveSettings, TrackAnalyzer, solve};
use wasm_bindgen::prelude::*;

#[wasm_bindgen]
pub struct CameraTracker {
    analyzer: Option<TrackAnalyzer>,
    tracks: Option<CameraTracks>,
    size: [f64; 2],
    frame_duration: f64,
}

#[wasm_bindgen]
impl CameraTracker {
    /// `max_side`: analysis resolution (longest side, pixels); `want`: features to keep tracked.
    #[wasm_bindgen(constructor)]
    pub fn new(width: u32, height: u32, fps: f64, max_side: u32, want: u32) -> CameraTracker {
        let size = [width as f64, height as f64];
        CameraTracker {
            analyzer: Some(TrackAnalyzer::with_budget(size, max_side.max(160), want as usize)),
            tracks: None,
            size,
            frame_duration: 1.0 / fps.max(1.0),
        }
    }

    /// Track one frame (RGBA8, `width * height * 4` bytes).
    pub fn push_rgba(&mut self, rgba: &[u8]) -> Result<(), JsError> {
        let (w, h) = (self.size[0] as u32, self.size[1] as u32);
        if rgba.len() != (w * h * 4) as usize {
            return Err(JsError::new("frame size does not match the tracker size"));
        }
        let a = self.analyzer.as_mut().ok_or_else(|| JsError::new("tracking already finished"))?;
        let img = Image::from_rgba8(w, h, rgba);
        a.push(&Frame::new(&img));
        Ok(())
    }

    pub fn frames(&self) -> u32 {
        self.analyzer.as_ref().map_or_else(|| self.tracks.as_ref().map_or(0, |t| t.frames), |a| a.frames())
    }

    /// Features tracked on the latest frame.
    pub fn active(&self) -> u32 {
        self.analyzer.as_ref().map_or(0, |a| a.active() as u32)
    }

    /// End the tracking pass and return the 2D feature tracks as JSON.
    pub fn finish(&mut self) -> String {
        self.finish_tracks().to_json()
    }

    /// Solve the camera. `settings_json` is a `SolveSettings` (e.g. `{"shot":"fixed"}`); empty
    /// uses the defaults. Returns the `CameraSolve` as JSON.
    pub fn solve(&mut self, settings_json: &str) -> Result<String, JsError> {
        let settings: SolveSettings = if settings_json.trim().is_empty() {
            SolveSettings::default()
        } else {
            serde_json::from_str(settings_json).map_err(|e| JsError::new(&e.to_string()))?
        };
        let tracks = self.finish_tracks().clone();
        let s = solve(&tracks, &settings, None).map_err(|e| JsError::new(&e.0))?;
        Ok(s.to_json())
    }
}

impl CameraTracker {
    fn finish_tracks(&mut self) -> &CameraTracks {
        if let Some(a) = self.analyzer.take() {
            self.tracks = Some(a.finish(0.0, self.frame_duration));
        }
        self.tracks.get_or_insert_with(CameraTracks::default)
    }
}
