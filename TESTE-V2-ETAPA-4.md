# DX Cam v2.3.0-lapela — roteiro de teste (v2 Etapa 4: celular-lapela sem fio)

**Desinstale a versão anterior antes de instalar** — em **todos** os
aparelhos (câmera, tablet controle e o celular que será a lapela —
pode ser um terceiro aparelho ou o próprio tablet, um papel por vez).

## Papéis
- **Câmera (S24):** armada como câmera (Configurações → Vínculo Remoto).
- **Lapela (celular de bolso):** Configurações → **MODO LAPELA**.
- **Controle (tablet):** conectado como controle, como nas etapas anteriores.

## O teste
1. Na lapela: digite o **IP e o código da câmera** → **CONECTAR E GRAVAR**.
   A tela mostra o medidor de nível mexendo com a voz, o cronômetro e o
   nome da sessão (ex.: `DXLapela_20261125_153500`).
2. Fale perto da lapela: no **tablet**, a linha de estado passa a
   mostrar **"Lapela ON (−12 dB)"** com o nível dela, atualizado a
   cada segundo.
3. Grave um vídeo pela câmera (REC no tablet ou no S24) falando na
   lapela por 1–2 minutos.
4. Na lapela, toque **PARAR**. Dois arquivos WAV nascem da sessão:
   - **na lapela:** `DXLapela_<data>_<hora>.wav` (o original dela,
     gravado localmente — a segurança principal);
   - **na câmera:** `DXLapela_<data>_<hora>_rx.wav` (o que a câmera
     recebeu pela rede — a segunda segurança).
   Destino segue o de cada aparelho: interno vai para **Music/DXCam**;
   se o destino for SSD, o WAV cai na mesma pasta dos vídeos.
5. Confira os dois WAV no computador: abrem, têm o mesmo conteúdo e
   durações próximas (diferença de até ~1 s é normal: relógios e rede).
6. **Queda de rede no meio:** desligue o Wi-Fi da lapela por 10 s e
   religue. A câmera avisa "lapela desconectou" e salva o `_rx.wav`
   parcial; a lapela salva o WAV local dela. Nada se perde dos dois
   lados — é para isso que existem os dois arquivos.

## Limites honestos desta etapa
7. O áudio da lapela **ainda não entra dentro do MP4** da câmera: ele
   chega como WAV separado, para casar na edição (o nome da sessão é o
   mesmo nos dois lados; um estalo/palma no início alinha tudo no
   Premiere). Misturar ao vivo no arquivo do vídeo é decisão de uma
   etapa futura — o desenho atual privilegia a segurança de ter o
   áudio íntegro em dois lugares.
8. Uma lapela por vez nesta etapa. Várias lapelas são assunto da
   multicam/DX Cap.
