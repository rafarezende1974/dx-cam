# DX Cam v0.4.0-visual — roteiro de teste (layout novo)

Instale por cima? Não: **desinstale a versão anterior antes** (o APK debug
muda de assinatura a cada build) e baixe o artefato logado no GitHub pelo celular.

A interface mudou de lugar, o motor é o mesmo da v0.3.1 (que você aprovou).
Confira:

1. **Topo:** parâmetros à esquerda (LENTE, FPS, OBTUR., ÍRIS f1,8, FOCO),
   timecode grande no centro (00:00:00:00 parado), ISO, WB, TOM e o selo
   do modo (4K · 16:9, ou HDR 10-bit) à direita.
2. **Toque num parâmetro** abre o painel de ajuste sobre o visor (slider
   âmbar). Toque de novo fecha. **Toque longo** em ISO/OBTUR./WB/FOCO trava
   o valor manual (nome ganha "· TRAV" e fica âmbar).
3. **FPS:** toque curto alterna o fps da mesma resolução (4K: 24 ↔ 30;
   1080: 24 → 30 → 60). Toque longo no FPS ou toque no **selo 4K** abre o
   painel de modo: resolução/codec, bitrate ECO/PADRÃO/ALTO com Mbps,
   destino INTERNO/SSD e projeto (tudo da Etapa 3, agora dentro do painel).
4. **Timecode gravando:** fica vermelho e conta com quadros (HH:MM:SS:FF).
5. **Base:** à esquerda, o lugar do histograma (Etapa 4); no centro, o
   **tempo de gravação restante** estimado pelo espaço livre e bitrate,
   barra de uso, GB livres e bateria; à direita, o lugar dos medidores de
   áudio (Etapa 4).
6. **Régua lateral:** GUIAS liga a grade de terços; ± abre o EV; REC grande
   no meio (miolo vira quadrado gravando); FOTO, LUT e NÍVEL avisam a etapa
   em que chegam.
7. **Abas** na extrema direita: Câmera (ativa), Mídia e Configurações
   (Etapa 7).
8. **Toque no visor:** moldura de foco aparece onde tocou e some sozinha.
9. Grave um clipe curto e confira que salva igual a antes (interno ou SSD,
   nome com projeto, divisão — comportamento da Etapa 3 não mudou).
10. Feche e reabra o app: valores, destino, bitrate e grade voltam como estavam.

Se algo sair do lugar (texto cortado, painel cobrindo controle), manda o
print que eu ajusto na hora.
