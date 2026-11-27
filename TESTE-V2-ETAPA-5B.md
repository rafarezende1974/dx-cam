# DX Cam v2.4.1-comandos — correção/diagnóstico do REC TODAS e FOTO TODAS

**Desinstale a versão anterior antes de instalar** — em todos os
aparelhos (câmeras e tablet), mesmo APK novo nos dois lados.

## O que mudou
A v2.4.0 executava o comando em silêncio: se algo recusava no meio
do caminho, ninguém via. Agora **toda ordem tem resposta**:

- Na tela da **câmera**, aparece **"Controle: REC_START"** (ou PHOTO)
  no instante em que o comando chega nela.
- No **tablet**, aparece **"CAM 1 respondeu: ACK REC_START"** quando
  a câmera aceita — ou **"ERR ..."** com o motivo quando ela recusa
  (câmera abrindo, câmera ocupada, foto durante gravação, erro do
  motor de gravação etc.).
- Se o envio do comando falhar (conexão morta), o cartão da câmera
  cai na hora com o aviso, em vez de fingir que está conectado.

## Reteste (com as câmeras à vista)
1. Conecte as câmeras na multicam como antes (prévias no ar).
2. **Olhando para a tela de uma das câmeras**, toque **● REC TODAS**
   no tablet e observe os dois lados:
   - Na câmera aparece "Controle: REC_START"? No tablet aparece o ACK?
   - Se aparecer **ERR**, anote o texto dele — é o diagnóstico.
   - Se **nada** aparecer na câmera, o comando não está chegando
     nela: me diga também o que o tablet mostrou ao tocar
     ("enviado para N camera(s)" ou "nenhuma camera conectada").
3. Repita com **FOTO TODAS**.
4. Me passe: o que apareceu na câmera, o que apareceu no tablet e,
   se houve ERR, o texto dele.
