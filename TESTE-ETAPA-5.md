# DX Cam v0.6.0-lut — roteiro de teste (Etapa 5: LUT .cube)

**Desinstale a versão anterior antes de instalar** (assinatura muda a cada build).

A LUT é aplicada **só na prévia** (via OpenGL): o arquivo gravado sai
**limpo, sem LUT**, como definido no plano — a LUT é referência de look
para você enxergar o resultado enquanto grava.

1. **Importar:** toque no botão **LUT** da régua lateral → **IMPORTAR
   .CUBE** → escolha um .cube que você usa no Premiere (vale de qualquer
   pasta/Drive do celular). Ele é copiado para dentro do app e já entra ativo.
2. **Efeito na prévia:** a imagem do visor muda na hora para o look da LUT.
   O botão LUT da régua fica destacado.
3. **Intensidade:** no painel LUT, arraste a intensidade (0–100%): em 50%
   o look fica pela metade, em 0% some sem desligar.
4. **Enquadramento:** compare o enquadramento com a LUT ligada e desligada
   (chip LUT: LIGADA/DESLIGADA no painel): precisa ser **o mesmo** — nada
   de imagem esticada ou cortada diferente. Se estiver diferente, me conte
   (e um print ajuda) que eu calibro o zoom da prévia GL.
5. **Gravar com LUT:** grave um clipe com a LUT ligada e abra o arquivo:
   ele tem que estar **sem o look** (cores normais do modo), enquanto a
   prévia mostrava o look. Esse é o teste mais importante da etapa.
6. **Várias LUTs:** importe uma segunda: as duas aparecem como botões no
   painel e alternam na hora. **REMOVER A ATIVA** apaga a selecionada.
7. **Persistência:** feche e reabra o app com a LUT ligada: a prévia já
   abre com o look aplicado.
8. **Zebra/peaking/histograma com LUT:** continuam funcionando (a análise
   é da imagem sem LUT — os valores de exposição não mudam com o look).
9. **Trava de segurança:** com a gravação em andamento, tentar trocar de
   LUT ou desligar mostra o aviso de que só muda com a gravação parada —
   a prévia nunca pode cair no meio de um clipe.
