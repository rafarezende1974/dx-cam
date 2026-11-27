# DX Cam v2.4.3-diagnostico — a conexão cai no comando: agora ela diz por quê

**Desinstale a versão anterior antes de instalar** — em todos os
aparelhos (câmeras e tablet), mesmo APK novo nos dois lados.

## O que o último teste mostrou
O tablet fica em "aguardando resposta" e a câmera registra que o
controle **desconectou**: ou seja, a conexão **morre no momento do
comando**. Esta versão registra o motivo exato da morte nas duas
pontas, move os envios do tablet para fora da tela (fila própria) e
ganha um botão **PING** que testa o transporte sem tocar na gravação.

## Reteste (nesta ordem)
1. Conecte a câmera na multicam (prévia no ar).
2. Toque **PING** (ao lado de NOVA SESSÃO) e leia a linha de
   resposta do tablet:
   - **"CAM 1 respondeu: ACK PING"** → o transporte está são; o
     problema mora no REC/FOTO em si. Siga ao passo 3.
   - **Ficou aguardando / o cartão caiu** → anote o texto que ficou
     **no cartão da CAM 1** (ele traz o motivo entre parênteses).
3. Toque **● REC TODAS** e observe a queda:
   - No **cartão da CAM 1** (tablet): o texto final dele.
   - Na **câmera** (Configurações → Vínculo Remoto): a linha
     "Último comando do controle: Controle caiu: ..." — o trecho
     depois dos dois pontos é o motivo técnico da queda
     (EOF, exceção ou "pacote invalido" com tipo/tamanho).
4. Me passe esses dois textos (cartão + linha da câmera). Com o
   motivo exato, a correção é direta — sem mais uma rodada de chute.
