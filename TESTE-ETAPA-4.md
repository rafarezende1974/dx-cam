# DX Cam v0.5.0-monitor — roteiro de teste (Etapa 4: monitoramento)

**Desinstale a versão anterior antes de instalar** (assinatura muda a cada build).

O monitoramento analisa a prévia de forma leve (~8 fps, fora da tela de
gravação). Se a sessão encher na gravação, ele pausa a parte de imagem e
avisa — a gravação nunca é sacrificada.

1. **Histograma:** a caixinha "Rec.709" agora mostra o histograma real,
   mexendo com a luz da cena (aponte para uma janela e para um canto escuro).
2. **Medidores de áudio:** fale perto do celular **sem gravar** — os dois
   medidores se mexem (o microfone é mono, os dois canais mostram o mesmo
   nível) e a marquinha branca segura o pico. **Gravando**, eles continuam
   se mexendo, agora alimentados pelo próprio gravador.
3. **Zebra:** aponte para algo bem claro (janela, lâmpada): as áreas
   estouradas ganham hachura vermelha.
4. **Painel de monitoramento:** toque no histograma ou nos medidores.
   Ligue/desligue ZEBRA, PEAKING, HISTOGRAMA e MEDIDORES e mexa no limiar
   da zebra (70–100 IRE; padrão 95). Tudo fica salvo ao reabrir o app.
5. **Peaking:** com o PEAKING ligado, foque num objeto com bordas (teclado,
   lombada de livro): as bordas focadas ganham marquinhas verdes. É uma
   grade aproximada (48×27), não pixel a pixel — me diga se ajuda ou atrapalha.
6. **Nível:** botão NÍVEL na régua lateral: aparece a linha do horizonte no
   centro com o ângulo em graus. **Confira contra uma superfície que você
   sabe que está nivelada** (mesa, moldura): se a linha inclinar para o lado
   errado, me avise que eu inverto o sinal.
7. **Gravação 4K com tudo ligado:** grave 1–2 min em 4K com zebra,
   peaking e histograma ligados. O clipe precisa sair íntegro, sem travar.
   Se aparecer o aviso "Monitoramento de imagem pausado nesta gravação",
   é a proteção funcionando — me conte que apareceu.
8. **Gravação longa (se tiver tempo):** o teste de divisão da Etapa 3
   (~10 min em 4K PADRÃO) com o monitoramento ligado fecha de vez o item
   de estabilidade que vem desde a Fase 0.
