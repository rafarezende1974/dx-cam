# DX Cam v2.4.0-multicam — roteiro de teste (v2 Etapa 5: multicam)

**Desinstale a versão anterior antes de instalar** — em todos os
aparelhos. Para o teste completo são **3 aparelhos**: 2 câmeras
(S24 + um segundo celular com o DX Cam) e o tablet de controle.
Com 2 aparelhos (1 câmera + tablet) já dá para validar a tela.

## Preparação
1. Todos na mesma rede Wi-Fi.
2. Em **cada câmera**: Configurações → **ARMAR ESTE APARELHO COMO
   CÂMERA** (cada uma mostra o **seu** IP e o **seu** código — são
   diferentes por aparelho e a cada armada).
3. No tablet: Configurações → **MULTICAM (até 4 câmeras)**.

## Conexões
4. Em **CAM 1**, digite o IP + código da câmera 1 → **CONECTAR**:
   a prévia dela aparece no cartão, com a linha de estado (modo,
   ISO, bateria, espera/gravando). Repita em **CAM 2** com a outra.
   Os IPs ficam lembrados para a próxima vez; os códigos, não
   (mudam a cada armada, por segurança).
5. Feche a tela da multicam e reabra: as conexões **continuam vivas**
   (fechar a tela não derruba nada).

## Sessão (nome unificado)
6. Toque **NOVA SESSÃO**: nasce uma etiqueta tipo `MC_154500`,
   aplicada como **projeto** em todas as câmeras conectadas.
7. Grave e confira os arquivos nas câmeras: todos saem
   `DXCam_MC_154500_<data>_<hora>.mp4` — mesma sessão em todas,
   prontos para agrupar na edição. Câmera que conectar depois
   recebe a sessão sozinha.

## Disparo conjunto
8. **● REC TODAS**: todas as câmeras conectadas começam a gravar;
   os cartões mostram GRAVANDO. **■ PARAR TODAS** encerra em todas.
   **FOTO TODAS** fotografa em todas.
9. Honestidade da sincronia: o disparo sai para todas de uma vez,
   mas o desvio real é o da rede local (fração de segundo entre a
   primeira e a última). Para sincronia fina na edição, os arquivos
   mestres de cada câmera seguem sendo a referência — uma palma no
   início do take alinha tudo no Premiere.

## Limites desta etapa
10. Ajustes finos (ISO, foco etc.) por câmera continuam na tela de
    controle individual (um aparelho por vez) ou na própria câmera.
    A multicam é o painel de conjunto: prévias, estado, sessão e
    disparo. O corte ao vivo entre elas é o DX Cap (v3).
