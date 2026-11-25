# DX Cam — Teste da Etapa 2 (núcleo de câmera) — v0.2.1-core

> v0.2.1 corrige a prévia deitada da v0.2.0 (faltava a rotação da matriz conforme o sentido da paisagem), some com as barras do sistema (tela cheia) e troca o status para "Câmera pronta" quando o primeiro quadro chega. **Confira a prévia nos dois sentidos da paisagem** (virando o aparelho 180°).

Build pelo GitHub Actions (artefato `dx-cam-diag-apk`). Como a assinatura de debug muda a cada build, **desinstale a versão anterior antes de instalar**.

A tela abre direto na câmera, em paisagem. O que testar, na ordem:

1. **Visor e valores reais** — a faixa de baixo (ISO, OBTURADOR, BRANCO, FOCO, EV, ZOOM) mostra os valores que a câmera está usando de verdade: aponte para a janela e para o escuro e veja ISO e obturador mudarem sozinhos (estão em AUTO).
2. **ISO manual** — toque em ISO, desligue o AUTO e arraste: a imagem responde e a faixa mostra o valor em âmbar com "MAN". Volte ao AUTO pelo mesmo botão.
3. **Obturador por ângulo** — toque em OBTURADOR, desligue o AUTO: padrão 180°. Troque o modo de vídeo no topo (toque no botão do modo) de 24p para 30p e veja o valor efetivo mudar sozinho (ângulo recalcula). No modo VELOCIDADE, o valor é fixo: trave 1/24 e troque para 60p — o app tem que **avisar na faixa âmbar** que ajustou para caber no quadro.
4. **Branco manual** — toque em BRANCO, desligue o AUTO e teste os presets (Tungstênio deixa a cena azulada sob luz quente; Luz do dia é o neutro). O slider TINT mexe no verde/magenta.
5. **Foco** — toque na prévia: o foco vai para o ponto tocado (modo TOQUE). No painel de FOCO: AF CONTÍNUO volta ao automático; MANUAL + slider anda o foco de 0,10 m até infinito — confira a distância na faixa.
6. **Trava** — com um controle em manual, **toque e segure** a célula dele na faixa: aparece "TRAV". Valor travado não muda com troca de modo (só com o aviso da regra 3). Segure de novo para destravar.
7. **Zoom** — pinça na prévia ou painel de ZOOM: 0,6× (ultra-wide), 1×, 2×, 5×, 10×; a faixa mostra a distância focal equivalente (14–240 mm).
8. **EV** — com ISO/obturador em AUTO, EV +1,0 clareia e −1,0 escurece (faixa do S24: ±2).
9. **Gravar** — botão ●: grava no modo do topo, com os controles como estiverem (manuais continuam valendo durante a gravação). Pare com ■. O arquivo vai para **Movies/DXCam** (`DXCam_data_hora.mp4`) — confira na galeria e, se puder, no Premiere: modo HDR tem que sair BT.2020/HDR.
10. **Persistência** — feche o app (force a parada) e reabra: modo, valores manuais e travas têm que voltar como estavam.

Anote o que quebrar com print da tela. Não precisa de relatório desta vez — o retorno é visual, no uso.
