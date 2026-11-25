# DX Cam v2.2.0-toque — roteiro de teste (v2 Etapa 3: foco por toque no tablet)

**Desinstale a versão anterior antes de instalar** — nos **dois**
aparelhos (S24 câmera e Tab S9+ controle), mesmo APK.

## Preparação
1. Mesma rede Wi-Fi; S24 armado como câmera; tablet conectado como
   controle, prévia no ar (roteiro da Etapa 1 do vínculo).

## O teste
2. No tablet, **toque na prévia** sobre um objeto/pessoa em um canto
   da imagem (não no centro): a câmera foca **naquele ponto** — a
   retícula de foco aparece também na tela do S24, no mesmo lugar.
3. Toque em outro ponto, mais perto/longe: o foco muda para lá.
4. A linha FOCO do tablet passa a mostrar **"Por toque (na previa)"**.
5. Toque nas faixas pretas acima/abaixo da prévia (fora da imagem):
   o tablet avisa "toque fora da imagem" e nada muda na câmera.
6. No tablet, linha FOCO → botão AUTO: volta ao AF contínuo.
7. Se a câmera estiver em **foco manual** (distância fixa), o toque é
   recusado e o S24 mostra o aviso — o manual tem prioridade, igual ao
   toque na própria tela dele.

## Correção que foi junto
A v2.1.0 tinha referências erradas a dois tipos internos
(ShutterKind/FocusMode) que **podem ter quebrado o build dela**. Se o
build da v2.1.0 ficou vermelho, era isso — está corrigido nesta.
