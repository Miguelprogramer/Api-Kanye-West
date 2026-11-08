# Reels Coach

App web para celular (PWA) que ajuda a gravar e editar Reels.

1. **Formato**: escolha entre Falando para a câmera, Tutorial/mãos, Lista/Top 3, Antes e depois, POV/cenas.
2. **Gravação**: câmera em 9:16 com guias de enquadramento (silhueta, linha dos olhos, área da ação, terços), espaço do gancho, posição da legenda e áreas cobertas pela interface do Reels. Contagem regressiva e dicas por formato.
3. **Editor**: cortar início/fim, texto de gancho, legendas por tempo, estilos de texto, zonas seguras e tutorial passo a passo. Exporta o vídeo final (MP4 quando o navegador suporta, senão WebM) com gancho e legendas gravados.

Tudo roda no navegador, sem servidor nem custo. A câmera exige HTTPS (ex.: GitHub Pages ou Vercel) ou `localhost`.

Para testar localmente: `python3 -m http.server` e abra `http://localhost:8000`.
