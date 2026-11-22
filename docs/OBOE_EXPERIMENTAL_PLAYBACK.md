# Reprodução PCM experimental com Oboe

## Estado

A classe `OboePlaybackController` é uma primeira integração isolada entre
`PcmDecoder` e `AudioNative`. Ela foi adicionada à branch
`feature/oboe-native-engine-foundation`.

**Ainda não está conectada à interface nem substitui o MediaPlayer.**
O transporte principal do aplicativo permanece inalterado.

## Fluxo

1. O worker abre a saída Oboe e consulta a frequência real do stream.
2. `PcmDecoder` decodifica o arquivo e converte o PCM para Float32 estéreo
   na frequência de saída.
3. O produtor envia os blocos para o buffer circular nativo.
4. Se o buffer estiver cheio, o worker aguarda brevemente e tenta novamente.
   O callback Oboe nunca espera pelo produtor.
5. Ao final, o worker aguarda o esvaziamento do buffer e fecha o stream.

## Segurança e limitações

- Decodificação e backpressure ocorrem fora da thread de áudio.
- O cancelamento é cooperativo; o fechamento do stream ocorre no worker.
- A classe aceita uma execução por vez.
- Não implementa seek, pausa, retomada, loop ou processamento DSP.
- Ainda não há sincronização de posição com a interface.
- A reprodução real precisa ser testada em dispositivo Android; CI confirma
  compilação, não qualidade sonora nem compatibilidade de hardware.

## Próxima validação

Antes de conectar controles à interface, compilar e revisar esta integração.
Depois será criado um modo de teste explícito, sem alterar o caminho normal
de reprodução pelo MediaPlayer.
