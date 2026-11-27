# DX Cam v2.4.2-feedback — REC TODAS e FOTO TODAS: agora com resposta visível

**Desinstale a versão anterior antes de instalar** — em todos os
aparelhos (câmeras e tablet), mesmo APK novo nos dois lados.

## O que estava errado na v2.4.1
As respostas existiam, mas eram escritas na linha de status da tela
da câmera — que fica **coberta** pelas telas de Configurações e da
Multicam justamente durante esse teste. Resultado: "não dá info
nenhuma". Corrigido: cada tela agora tem a **sua própria** linha de
resposta, sempre visível.

## Onde ler as respostas agora
- **No tablet, tela Multicam:** linha em destaque logo abaixo do
  botão NOVA SESSÃO. Ao tocar REC TODAS ela mostra "enviado para N
  camera(s): aguardando resposta..." e depois **"CAM 1 respondeu:
  ACK REC_START"** (ou **ERR + motivo**).
- **Na câmera, aba Configurações → seção Vínculo Remoto:** a linha
  "Último comando do controle: 23:05:12 — REC_START → ACK gravando"
  mostra o que chegou nela e o que ela respondeu.

## Reteste
1. Conecte as câmeras na multicam (prévias no ar).
2. Toque **● REC TODAS** e leia a linha de resposta do tablet.
3. Numa das câmeras, abra Configurações e leia a linha do último
   comando na seção do vínculo.
4. Repita com **FOTO TODAS**.
5. Me passe os textos das duas linhas (tablet + uma câmera). Se a
   linha do tablet ficar só no "aguardando resposta..." e a câmera
   não registrar comando nenhum, o problema é o comando não chegar —
   e aí eu ataco o transporte, já sabendo onde ele quebra.
