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
