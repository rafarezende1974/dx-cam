# DX Cam Diag — como rodar a Fase 0 no Galaxy S24

Tempo total: uns 10 minutos. Faça com o celular **fora da capinha grossa** e, se possível, com o **SSD que você usa em campo conectado por USB-C** na hora do teste de escrita.

## Instalação

1. Baixe o artefato `dx-cam-diag-apk` do GitHub Actions e instale o `app-debug.apk`.
   - Se já houver um DX Cam Diag instalado de um build anterior, **desinstale antes** (cada build debug tem assinatura nova).
   - No celular, precisa estar logado no GitHub para baixar o artefato.
2. Abra o **DX Cam Diag** e aceite as permissões de câmera e microfone.

## Passo a passo

**Rode um teste por vez e espere o status voltar antes de tocar no próximo botão.** Se um teste travar, a v0.1.1 encerra sozinha em até ~55 s e registra no relatório em qual etapa travou — nesse caso, feche e reabra o app e rode só aquele teste de novo.

1. **Diagnóstico automático** — ao abrir, o app já lê o aparelho e monta o relatório na tela. Confira se aparecem as câmeras traseiras com ISO, obturador e RAW.
2. **Gravar teste 4K 24p H.265 (30 s)** — aponte para uma cena com luz variada (uma janela ajuda) e toque no botão. Não feche o app. Na metade, o app trava os controles no manual (ISO 800, foco a 2 m) — a imagem pode mudar de brilho, é esperado. Ao fim, a análise do arquivo entra no relatório.
3. **Gravar teste 1080p 60p H.264 (30 s)** — mesma cena, também com a metade manual.
4. **Gravar teste 4K 24p H.265 10-BIT HLG (30 s)** — mesma cena. É o teste que decide como a v1 grava em 10 bits.
5. **Foto RAW de teste** — já aprovado na v0.1.1; só rode de novo se quiser.
6. **Teste de escrita 128 MB** — interno já aprovado; rode com o **SSD plugado** para fechar esse item.
7. **Salvar relatório** e depois **Compartilhar relatório (WhatsApp)** — envie o arquivo `.txt` para a conversa do DX Cam.

## Depois, no computador (conferência do 10-bit e do RAW)

Os arquivos de teste ficam em `Android/data/br.com.dxlabs.dxcam/files/` (Movies = vídeos, Pictures = fotos). Se quiser conferir antes de enviar:

- Abra o `teste_4k24_hevc.mp4` no **Premiere**: Propriedades do arquivo devem mostrar HEVC; confira a profundidade de cor.
- Abra o `teste_foto.dng` no **Lightroom**: confira ISO e exposição nos metadados.

## O que a Fase 0 decide

Com o relatório, fechamos: lista final de resoluções/fps/codecs da v1, se o perfil Neutro (log-like) entra, se o falso cor cabe no S24 gravando 4K e quais controles manuais cada lente realmente entrega. Se ISO/obturador/branco/foco manual falharem no S24, o escopo da v1 é revisto **antes** de qualquer código de interface.
