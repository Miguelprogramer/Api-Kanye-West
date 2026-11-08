// Formatos de Reels. Coordenadas em % do quadro 9:16 (x, y, largura, altura).
// Zonas comuns: gancho no topo (abaixo do cabeçalho do app) e legenda acima da área de nome/descrição.
window.ZONES = {
  topUI:    { y: 0,  h: 12 },  // cabeçalho do app (Reels, câmera)
  hook:     { y: 14, h: 16 },  // espaço do gancho
  caption:  { y: 62, h: 12 },  // posição da legenda
  bottomUI: { y: 78, h: 22 },  // nome, descrição, áudio
  sideUI:   { x: 84, w: 16, y: 45, h: 40 } // botões curtir/comentar/compartilhar
};

window.FORMATS = [
  {
    id: 'talking',
    emoji: '🗣️',
    name: 'Falando para a câmera',
    desc: 'Você no centro, olhando para a lente. O formato mais usado para dicas e opiniões.',
    duration: '15–45s',
    tips: [
      'Olhos na linha pontilhada, olhando para a lente (não para a tela).',
      'Comece já falando o gancho, sem “oi gente”.',
      'Ombros dentro da silhueta, celular na altura dos olhos.'
    ],
    guides: [
      { type: 'silhouette', x: 22, y: 26, w: 56, h: 74 },
      { type: 'line', y: 39, label: 'linha dos olhos' }
    ]
  },
  {
    id: 'tutorial',
    emoji: '🙌',
    name: 'Tutorial / mãos',
    desc: 'Mostre o passo a passo. As mãos e o objeto ficam no meio do quadro.',
    duration: '20–60s',
    tips: [
      'Deixe o objeto dentro do retângulo central.',
      'Grave de cima ou de frente, com boa luz.',
      'Um passo por corte: depois você junta no editor.'
    ],
    guides: [
      { type: 'box', x: 10, y: 32, w: 80, h: 30, label: 'área da ação' }
    ]
  },
  {
    id: 'top3',
    emoji: '🔢',
    name: 'Lista / Top 3',
    desc: 'Você mais embaixo, com espaço livre em cima para o título e os números.',
    duration: '20–40s',
    tips: [
      'Fique um pouco mais longe: rosto na metade de baixo.',
      'Mostre os dedos ao contar “1, 2, 3”.',
      'O título da lista vai no espaço do gancho.'
    ],
    guides: [
      { type: 'silhouette', x: 26, y: 38, w: 48, h: 62 },
      { type: 'line', y: 50, label: 'linha dos olhos' }
    ]
  },
  {
    id: 'split',
    emoji: '↔️',
    name: 'Antes e depois',
    desc: 'Grave duas partes: o “antes” e o “depois”. Mantenha o mesmo enquadramento.',
    duration: '7–20s',
    tips: [
      'Não mexa o celular entre as duas gravações.',
      'Use o mesmo enquadramento nas duas partes.',
      'Faça a transição num gesto (estalo, mão na lente).'
    ],
    guides: [
      { type: 'box', x: 12, y: 24, w: 76, h: 50, label: 'mantenha aqui' },
      { type: 'line', y: 49, label: 'centro' }
    ]
  },
  {
    id: 'pov',
    emoji: '🎬',
    name: 'POV / cenas',
    desc: 'Cenas do dia a dia com texto por cima. Use a regra dos terços.',
    duration: '7–15s',
    tips: [
      'Coloque o assunto num cruzamento das linhas.',
      'Cenas curtas (1–3s) deixam o vídeo dinâmico.',
      'O texto POV vai no espaço do gancho.'
    ],
    guides: [ { type: 'thirds' } ]
  }
];

window.CAPTION_STYLES = [
  { id: 'classic', name: 'Clássico', font: '800', color: '#ffffff', stroke: '#000000', bg: null },
  { id: 'box',     name: 'Caixa',    font: '800', color: '#000000', stroke: null,      bg: '#ffffff' },
  { id: 'yellow',  name: 'Amarelo',  font: '900', color: '#ffe14d', stroke: '#000000', bg: null },
  { id: 'dark',    name: 'Escuro',   font: '700', color: '#ffffff', stroke: null,      bg: 'rgba(0,0,0,.65)' }
];

window.TUTORIAL = [
  { tab: 'trim', title: 'Corte o começo', text: 'Arraste “Início” até o momento em que você começa a falar. Vídeos que começam direto prendem mais.' },
  { tab: 'hook', title: 'Escreva o gancho', text: 'Digite a frase que aparece no topo nos primeiros segundos. Ela fica no espaço do gancho, longe dos botões do app.' },
  { tab: 'captions', title: 'Adicione legendas', text: 'Muita gente assiste sem som. Toque no vídeo para pausar, escreva o que você falou e toque em “Adicionar”.' },
  { tab: 'style', title: 'Deixe legível', text: 'Escolha um estilo com bom contraste. As áreas escuras mostram onde a interface do Reels cobre o vídeo.' },
  { tab: null, title: 'Exporte', text: 'Toque em “Exportar” para gerar o vídeo final com gancho e legendas. Depois é só postar!' }
];
