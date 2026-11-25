# DX Cam

App Android da DX Labs estilo Rabbit Cam / Blackmagic Camera: transforma o Galaxy S24 em câmera B de cinema para eventos, com controle manual completo, monitoramento profissional e LUT — sem nuvem e sem assinatura.

- **Pacote:** `br.com.dxlabs.dxcam`
- **Aparelho principal:** Galaxy S24 · **secundário:** Galaxy Tab S9+
- **Plano da v1:** documento "Plano DX Cam v1" (documentos do DX Cam, fora deste repo)

## Estado atual: v1 em construção — Etapa 3 (gravação completa), v0.3.1-rec

> v0.3.1 corrige o build vermelho da v0.3.0: `MediaRecorder.setOnInfoListener(listener, handler)` não existe na API pública visível ao compilador — fica a versão de um argumento (os eventos de divisão são raros e chegam na thread principal).

> **Fase 0 FECHADA em 25/11/2026** (3 relatórios do S24; resultados e decisões travadas no adendo do plano). Etapa 2 (v0.2.1-core, confirmada por ele no S24): visor em tela cheia, faixa manual com valores reais, painel de ajuste fino, travas, foco por toque, zoom por pinça e estado persistente. **Etapa 3 (v0.3.0-rec):** destinos interno (`Movies/DXCam`) ou SSD (pasta via SAF com permissão persistente, botão SSD no topo; toque longo troca a pasta), bitrate em 3 níveis (ECO/PADRÃO/ALTO, Mbps efetivo sempre no botão do modo), nomeação `DXCam[_projeto]_AAAAMMDD_HHMMSS[_parteN].mp4` (projeto via toque longo no botão do modo) e divisão automática em ~3,8 GB sem perder quadros. Próximas etapas: 4) monitoramento, 5) LUT, 6) foto, 7) mídia/configurações, 8) teste de campo. Guias: `TESTE-ETAPA-2.md` e `TESTE-ETAPA-3.md`.

Nenhuma tela da v1 é codada antes da Fase 0. Este app (`DX Cam Diag`) roda no S24 real e responde, com números, o que o aparelho entrega:

1. Capacidades Camera2 de cada lente (ISO, obturador, foco manual, branco em Kelvin, RAW)
2. Combinações reais de vídeo e perfis de faixa dinâmica 10-bit
3. Encoder HEVC Main10 de verdade + análise do arquivo gravado
4. Foto RAW (DNG) de teste para abrir no Lightroom
5. Custo/estabilidade em gravações curtas de teste (4K 24p H.265 e 1080p 60p H.264)
6. Velocidade de escrita no armazenamento interno e no SSD externo (USB-C)
7. Fontes de áudio, ganho e taxas suportadas
8. Bateria e temperatura antes/depois de cada gravação

O relatório gerado pelo app fecha a lista de resoluções, codecs e controles da v1. Instruções de teste: **TESTE-FASE-0.md**.

## Build

Build via GitHub Actions, no padrão DX Labs (build local não é usado):

1. Criar o arquivo `.github/workflows/build-apk.yml` pela interface web do GitHub colando o conteúdo de `BUILD-WORKFLOW.yml.txt` (o push via API não tem permissão de Workflows).
2. Push na `main` (ou Run workflow manual) gera o artefato `dx-cam-diag-apk` com o `app-debug.apk`.
3. APK debug muda de assinatura a cada build: **desinstalar a versão anterior antes de instalar a nova**.

## Estrutura

- `app/src/main/java/br/com/dxlabs/dxcam/Probes.kt` — sondas de leitura (Camera2, encoders, áudio, armazenamento)
- `app/src/main/java/br/com/dxlabs/dxcam/CameraTester.kt` — testes que abrem a câmera (gravação, RAW, escrita)
- `app/src/main/java/br/com/dxlabs/dxcam/MainActivity.kt` — tela única do diagnóstico e o relatório

> **v2.1.0-controle (25/11/2026) — v2 Etapa 2 (controles manuais pelo tablet):** o vínculo ganha comandos `SET` (iso/isoAuto, angle/shutterAuto, kelvin/wbAuto, focusM/focusAF, ev, zoom) aplicados na câmera pelos mesmos caminhos da interface local (`state` + `engine.applyState()`), e a linha STATE passa a carregar também os ajustes (não só a leitura), para o tablet mostrar o valor devolvido pela câmera. A tela de controle ganha seis steppers (−/valor/+, AUTO onde cabe): ISO pelos degraus reais, obturador por ângulo, branco em Kelvin ±100, foco por distância (0,10 m–∞ ou AF), EV ±0,5 e zoom 0,6–10×. Ajustes valem ao vivo, inclusive gravando. Modo/fps/destino seguem no aparelho-câmera nesta etapa. Guia: `TESTE-V2-ETAPA-2.md`.

> **v2.0.0-link (25/11/2026) — v2 Etapa 1 (vínculo remoto):** o DX Cam ganha os dois papéis na rede local (permissão INTERNET nova; sem nuvem). **Câmera:** Configurações → "armar" abre servidor (porta 47777) com código de pareamento de 4 dígitos; **Controle:** tela própria com IP+código, prévia ao vivo (JPEG 640×360 gerado do quadro de análise no motor, ~7 fps, envio sem bloquear a câmera — servidor guarda só o quadro mais novo), comandos REC_START/REC_STOP/PHOTO e linha STATE a cada 1s (modo, ISO, obturador, bateria, destino). Protocolo por pacotes `[tipo][tamanho][conteúdo]` em `RemoteLink.kt`. Controles manuais pelo tablet ficam para a próxima etapa do vínculo. Guia: `TESTE-V2-ETAPA-1.md`.

> **v0.9.0-rastro (25/11/2026) — Etapa 8 (Rastro e Time Lapse, pedido dele no meio da Etapa 7):** o sensor expõe no máximo 1/10 por quadro (Fase 0), então o rastro é **empilhamento**: sessão dedicada com leitor YUV ≤1920px a 1/10 por quadro, acumulando em memória por **LUZ** (máximo por pixel, com a cor do pixel vencedor — trilhas de luz) ou **MOVIMENTO** (média — borrão/água sedosa), 1–30s, progresso por callback, FOTO para parar e salvar, saída JPEG `_rastro` via YuvImage no destino atual. **Time lapse:** `MediaRecorder.setCaptureRate` + duração de quadro forçada na sessão (buildRequest com override), intervalos 0,5–30s no painel do modo; prévia em ritmo de quadro durante a gravação. Guia: `TESTE-ETAPA-8.md` (item 9 confere se o S24 honrou a taxa de captura: 30s a 2s → clipe de ~0,5s).

> **v0.8.0-midia (25/11/2026) — Etapa 7 (Mídia e Configurações):** as abas da extrema direita viram telas de verdade sobre visor+régua. **Mídia:** grade (3 colunas) dos vídeos de Movies/DXCam e fotos de Pictures/DXCam via MediaStore, ou da pasta do SSD via SAF, com miniaturas (loadThumbnail / frame do MediaMetadataRetriever), toque abre o visualizador (VideoView com MediaController, foto em tela cheia, DNG com aviso + ABRIR FORA via Intent). **Configurações:** destino, bitrate, projeto, formato da foto, padrões de monitoramento (toggles + zebra IRE), LUT (importar/intensidade) e Sobre — tudo reusando os mesmos helpers dos painéis. Troca de aba bloqueada durante a gravação. Guia: `TESTE-ETAPA-7.md`.

> **v0.7.0-foto (25/11/2026) — Etapa 6 (foto):** botão FOTO dispara captura com os controles manuais da prévia (receita RAW da Fase 0): leitores JPEG (no tamanho do RAW) e RAW_SENSOR entram na sessão, mas nunca no pedido repetido; o par imagem + resultado da captura alimenta o `DngCreator`. Formatos RAW+JPEG / só JPEG / só RAW (segurar o FOTO abre o painel), destino segue o do vídeo (Pictures/DXCam ou SSD), mesma nomeação dos vídeos, orientação calculada do sensor e da rotação da tela. Sessão cheia degrada em escada (cai a análise, depois a foto), nunca a prévia. Guia: `TESTE-ETAPA-6.md`.

> **v0.6.0-lut (25/11/2026) — Etapa 5 (LUT .cube):** importação de .cube (parser próprio, 3D 2–64, atlas 2D) copiado para o armazenamento do app; prévia OpenGL ES 2.0 (GLSurfaceView + shader com amostragem trilinear do atlas e intensidade 0–100%) que substitui a TextureView só na exibição — a sessão alimenta o MediaRecorder direto, então **o arquivo grava limpo, sem LUT**. Troca de superfície via `CameraEngine.setPreviewSurface`, recusada durante a gravação. Várias LUTs salvas, alternância instantânea, remoção e persistência completa. Guia: `TESTE-ETAPA-5.md`.

> **v0.5.0-monitor (25/11/2026) — Etapa 4 (monitoramento):** análise leve do plano Y da prévia (ImageReader 640×360, ~8 fps, CPU fora da thread de tela): histograma real de 64 faixas na caixa Rec.709, zebra por células 48×27 com limiar ajustável (70–100 IRE, padrão 95), focus peaking por gradiente (células, aproximado) e medidores de áudio reais — em espera por AudioRecord próprio, gravando pelo `MediaRecorder.getMaxAmplitude()` (microfone mono, dois canais com o mesmo nível). Nível por sensor de rotação (linha + graus, botão NÍVEL). Painel de monitoramento ao tocar no histograma/medidores, tudo persistido. Proteção: se a sessão de gravação não couber com a análise, a sessão reabre sem ela e avisa — a gravação tem prioridade. Guia: `TESTE-ETAPA-4.md`.

> **v0.4.0-visual (25/11/2026):** Etapa 3 confirmada por ele no S24 ("funcionando perfeitamente"). Interface reconstruída no layout aprovado (linha Blackmagic Camera): parâmetros no topo (LENTE, FPS, OBTUR., ÍRIS, FOCO | timecode com quadros no centro | ISO, WB com selo A, TOM, selo do modo), painel de modo no selo/FPS (resolução, bitrate, destino e projeto da Etapa 3), HUD inferior com tempo de gravação restante estimado + bateria (centro) e lugares reservados para histograma e medidores de áudio (Etapa 4), régua lateral com GUIAS (grade de terços, já funcional), EV, REC grande, FOTO/LUT/NÍVEL (avisam suas etapas) e abas Câmera/Mídia/Configurações. Motor sem mudanças. Guia: `TESTE-VISUAL.md`.
