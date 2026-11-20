# A6400 Sync

App Android que se conecta na Sony a6400 por **Wi-Fi (PTP/IP, o mesmo protocolo do Imaging Edge Mobile)** e, a cada foto tirada:

1. **Baixa** a foto na hora. **Vídeos são ignorados.**
2. **Depura** cada foto: marca como desfocada/tremida, subexposta, estourada ou duplicada de rajada.
3. **Edita** as aprovadas: balanço de branco, níveis automáticos e saturação.
4. **Organiza** em `Pictures/A6400/<data>/{Originais, Selecionadas, Descartadas}`. Nenhuma foto é apagada.

## Como usar
1. Câmera: `Menu > Rede > Ctrl c/ Smartphone > Ligado`. Formato: JPEG (o RAW já é salvo em Originais, mas ainda não é editado).
2. Conecte o celular no Wi-Fi `DIRECT-xxxx:ILCE-6400`.
3. Abra o app e toque em **Iniciar sincronização**.

## Compilar
Abra a pasta no Android Studio e rode (Android 10+).

## Ajustes
- `Triage.blurThreshold`: sensibilidade do detector de foco.
- `Triage.duplicateDistance`: o quanto uma foto precisa ser parecida com outra pra contar como duplicada.
- `AutoEdit.saturation`: intensidade da cor.

## Versões
A versão aparece no topo do app (ex.: "Versão 1.12 (build 12)"). Cada build do GitHub gera um número novo; instale sempre o maior.

- **Conexão manual corrigida:** detecta a câmera testando se ela responde, sem depender do IP. Ao voltar das Configurações de Wi-Fi, conecta sozinho. Lista na Atividade o IP e o gateway de cada Wi-Fi.
- **Protocolo Sony refeito** seguindo o libgphoto2 (SDIO + leitura da 0xD215 antes de baixar). Testes com câmera simulada.
- **Conexão no Xiaomi:** permissões, Localização, janela de rede e plano B pelo Wi-Fi manual.
- **Diagnóstico** de portas e erro detalhado.
- **Novo visual:** tema escuro, painel, prévia antes/depois, ajustes e estilos.
- **Primeira versão:** transferência por Wi-Fi, triagem, edição e organização.
- **Modo cabo USB (PC Remoto):** conexão pelo cabo, com prioridade sobre o Wi-Fi. O app abre sozinho quando a câmera é plugada.
