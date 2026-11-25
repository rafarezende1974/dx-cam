# DX Cam v2.0.0-link — roteiro de teste (v2 Etapa 1: vínculo remoto)

**Desinstale a versão anterior antes de instalar** (assinatura muda a
cada build) — nos **dois** aparelhos: o S24 (câmera) e o Tab S9+
(controle), com o mesmo APK.

Pré-condição: os dois aparelhos na **mesma rede Wi-Fi** (a de casa
serve para o teste; em campo, o hotspot de um deles ou o roteador
dedicado). Não usa internet nem nuvem.

## No S24 (câmera)
1. Abra o DX Cam → aba **Configurações** → seção **VÍNCULO REMOTO**.
2. Toque **ARMAR ESTE APARELHO COMO CÂMERA**. Anote o que aparece:
   **IP** (ex.: 192.168.0.12), porta 47777 e o **código** de 4 dígitos
   (o código muda a cada vez que arma).

## No Tab S9+ (controle)
3. Abra o DX Cam → aba **Configurações** → **CONTROLAR OUTRO APARELHO**.
4. Digite o **IP** e o **código** do S24 → **CONECTAR**.
5. Em segundos, a **prévia do S24 aparece no tablet** (imagem de
   monitor, 640×360 — é prévia de enquadramento, não o sinal final) e a
   linha de estado mostra modo, ISO, obturador, bateria e destino do S24,
   atualizando a cada segundo.

## Comandos
6. Toque **● REC** no tablet: o S24 **começa a gravar** (confira no
   S24: timecode vermelho rodando). O botão do tablet vira **■ PARAR**
   e o estado mostra GRAVANDO. Toque de novo para parar — o clipe fica
   salvo no S24, no destino dele.
7. Toque **FOTO** no tablet: o S24 fotografa (JPEG/DNG conforme o
   formato configurado nele).
8. Ande com o tablet para longe do roteador até cair: os dois lados
   avisam a desconexão, e o S24 **continua funcionando sozinho**
   (se estava gravando, a gravação não cai).

## Segurança e limites desta etapa
9. Código errado no tablet: a câmera recusa ("codigo recusado").
10. Nesta etapa o controle é: prévia + REC + foto + estado. ISO,
    obturador, foco e modo **pelo tablet** são a próxima etapa do
    vínculo — por enquanto eles se ajustam no próprio S24.
11. Desarmar: no S24, Configurações → **DESARMAR ESTA CÂMERA**.
