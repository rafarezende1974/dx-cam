# DX Cam

App Android da DX Labs estilo Rabbit Cam / Blackmagic Camera: transforma o Galaxy S24 em câmera B de cinema para eventos, com controle manual completo, monitoramento profissional e LUT — sem nuvem e sem assinatura.

- **Pacote:** `br.com.dxlabs.dxcam`
- **Aparelho principal:** Galaxy S24 · **secundário:** Galaxy Tab S9+
- **Plano da v1:** documento "Plano DX Cam v1" (documentos do DX Cam, fora deste repo)

## Estado atual: v1 em construção — Etapa 3 (gravação completa), v0.3.0-rec

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
