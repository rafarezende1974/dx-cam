# DX Cam v0.9.0-rastro — roteiro de teste (Etapa 8: Rastro e Time Lapse)

**Desinstale a versão anterior antes de instalar** (assinatura muda a cada build).

Antes de tudo: a Etapa 7 (abas Mídia/Configurações) continua valendo —
se algo dela falhar, me conte junto.

## Por que o rastro é "por empilhamento"
O sensor do S24 expõe no máximo **1/10 s por quadro** (medido na Fase 0) —
não existe obturador de segundos numa única foto. Então o DX Cam soma
muitos quadros de 1/10 até juntar o tempo escolhido: é a mesma técnica
dos modos de "longa exposição" dos apps de câmera.

## Foto com rastro
1. **Segure o botão FOTO** para abrir o painel da foto. No fim dele está
   a seção **RASTRO**: duração (1s a 30s) e mistura (LUZ / MOVIMENTO).
2. **LUZ (trilhas):** aponte para uma rua à noite ou acenda um isqueiro/
   celular e desenhe no ar durante a captura: o rastro da luz tem que
   ficar desenhado na foto. Toque **INICIAR RASTRO**, movimente a luz
   e espere acabar (ou toque em FOTO para parar e salvar).
3. Confira a foto na aba Mídia: nome termina em `_rastro.jpg`, em
   Pictures/DXCam (ou no SSD, se for o destino).
4. **MOVIMENTO (borrão):** aponte para água corrente, ventilador ou
   pessoas andando com 5–10s: o que se move fica borrado/sedoso e o que
   está parado fica nítido. **Tripé ajuda muito** nos dois modos.
5. O progresso aparece no aviso no topo (porcentagem e quadros somados).
6. Durante o rastro, REC e foto normal ficam bloqueados; ao terminar, a
   câmera volta sozinha.

## Time lapse (vídeo)
7. Abra o **painel do modo** (selo do modo ou toque longo no FPS): no
   fim, a seção **TIME LAPSE**. Escolha **2s** para o primeiro teste.
8. Grave por **30 segundos reais** apontando para algo que se move
   (rua, nuvens). A prévia fica em ritmo de quadro — é assim mesmo.
9. Abra o clipe na aba Mídia: ele deve ter **~0,5 segundo** de duração
   (30s ÷ 2s = 15 quadros, a 30 fps) e o movimento bem acelerado.
   **Se o clipe sair com 30 segundos normais, me avise** — significa
   que o gravador do S24 ignorou a taxa de captura e eu mudo a técnica.
10. Desligue o time lapse no painel e grave um clipe normal para
    confirmar que tudo voltou ao normal.

## Vídeo com rastro (dica que já existia)
11. Para borrão de movimento em vídeo comum, sem time lapse: ponha o
    obturador em **ângulo 360°** (painel do OBTUR.) — cada quadro expõe
    o tempo inteiro dele e o movimento ganha rastro natural.
