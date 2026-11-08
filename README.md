# Forja CGI

App de CGI para celular (PWA, roda no navegador): crie objetos 3D, renderize com materiais
realistas e integre ao seu vídeo com tracking de câmera 3D.

## O que faz

- **Criar**: primitivas (cubo, esfera, cilindro, cone, toro, nó, cápsula, placa), importação de `.glb`,
  mover/girar/escalar com gizmo, soltar no chão com um toque, duplicar e apagar.
- **Material** (PBR físico): plástico, cromo, ouro, cobre, alumínio, vidro (transmissão), cerâmica,
  borracha, pintura automotiva (verniz), tecido (sheen) e neon (emissivo); ajuste fino de cor,
  rugosidade, metal, transmissão, verniz e emissão.
- **Luz**: iluminação por HDRI (estúdio, pôr do sol, dia, noite ou seu próprio `.hdr`), sol com sombras
  suaves (VSM), exposição e tone mapping AgX.
- **Tracking**: escolha ou grave um vídeo; o app reconstrói a câmera 3D (structure from motion) e
  encaixa a câmera virtual no vídeo. Detecta o chão automaticamente (RANSAC), põe a cena em escala
  de metros (altura da câmera ajustável) e projeta as sombras dos objetos no chão real.
- **Render**: qualidade Rascunho/Alta/Ultra, oclusão de ambiente (GTAO), bloom, granulação de filme;
  salva imagem PNG e exporta o vídeo final (vídeo real + CGI).

## Tracking: código do effectCraft

O tracker é o **3D Camera Tracker do [effectCraft](https://github.com/storytold/effectcraft)**
(Rust, MIT OR Apache-2.0), compilado para WebAssembly e rodando num Web Worker:
features Shi–Tomasi seguidas com Lucas–Kanade piramidal (com checagem ida e volta), solução por
matriz essencial/homografia em RANSAC, ressecção incremental, triangulação e bundle adjustment
Levenberg–Marquardt, com estimativa da distância focal e, opcionalmente, da distorção da lente.

- `engine/vendor/`: crates do effectCraft copiadas sem alterações (`track`, `raster`, `geom`,
  `color`, `segment`) e as licenças originais.
- `engine/cgi-wasm/`: a ponte JavaScript ↔ Rust (`CameraTracker`).
- `pkg/`: o WebAssembly compilado (já incluído; recompile com `./build-wasm.sh`).

## Rodar

Precisa de HTTPS (ex.: GitHub Pages, Vercel) ou `localhost`:

```
python3 -m http.server 8000
```

Dicas de gravação para um bom tracking: ande devagar (não só gire parado), com o chão e objetos
texturizados em quadro, boa luz e sem zoom. Clipes de 5–15 s.

## Limitações conhecidas

- A análise roda em uma thread do WebAssembly: num celular, um clipe de 10 s em qualidade Normal
  leva alguns minutos.
- Vidro (transmissão) refrata a cena 3D, não o vídeo real atrás dele.
- A exportação de vídeo toca o clipe em tempo real; o formato (MP4 ou WebM) depende do navegador.

## Licenças

three.js (MIT) em `lib/three/`. Código do effectCraft: MIT OR Apache-2.0 (`engine/vendor/LICENSE-*`).
