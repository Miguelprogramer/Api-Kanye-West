#!/usr/bin/env bash
# Recompila o tracker (Rust → WebAssembly) e gera pkg/.
# Requisitos: rustup target add wasm32-unknown-unknown && cargo install wasm-bindgen-cli --version 0.2.130
set -euo pipefail
cd "$(dirname "$0")/engine"
cargo build --release --target wasm32-unknown-unknown -p cgi-wasm
wasm-bindgen --target web --out-dir ../pkg --no-typescript target/wasm32-unknown-unknown/release/cgi_wasm.wasm
