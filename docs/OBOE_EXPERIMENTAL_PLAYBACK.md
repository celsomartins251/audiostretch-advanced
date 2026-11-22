# Reprodução PCM experimental com Oboe

## Estado atual

A classe `OboePlaybackController` integra `PcmDecoder` e `AudioNative` em um caminho experimental de reprodução. O teste está disponível na interface do APK Android por meio do painel **Teste Oboe**.

O transporte principal do aplicativo continua sendo o Android `MediaPlayer`. O Oboe não o substitui e não é iniciado automaticamente.

## Como testar

1. Abra o aplicativo Android e importe um arquivo de áudio.
2. Toque em **Testar motor Oboe** para iniciar a reprodução pelo caminho nativo.
3. Use **Parar teste** para interromper a execução experimental.
4. Para voltar ao caminho habitual, use o controle normal de reprodução do MediaPlayer.

Ao iniciar o teste, o MediaPlayer é pausado. O teste Oboe começa do início do arquivo. Ao terminar ou interromper, o MediaPlayer não é retomado automaticamente.

## Fluxo técnico

1. O worker abre a saída Oboe e consulta a frequência real do stream.
2. `PcmDecoder` decodifica a URI e converte o PCM para Float32 estéreo na frequência de saída.
3. O produtor envia blocos para o buffer circular nativo, respeitando backpressure.
4. O callback Oboe consome o buffer sem bloqueios, JNI ou alocações; em underrun, completa o bloco com silêncio.
5. Ao final, o worker aguarda o esvaziamento do buffer e fecha o stream.

## Limitações conhecidas

- Uma execução experimental por vez.
- Não implementa seek, pausa, retomada, loop ou processamento DSP no caminho Oboe.
- Não há sincronização da posição com a interface.
- A reprodução real precisa ser validada em dispositivos Android e com diferentes arquivos.
- CI confirma compilação, mas não comprova qualidade sonora, ausência de interrupções ou compatibilidade com todo hardware.

## Critério para avançar

Manter o MediaPlayer como transporte principal até concluir testes reais de reprodução, estabilidade, formatos, taxa de amostragem e comportamento de interrupção. A migração do transporte só deve ser considerada depois da validação de seek, pause/resume, loop, sincronização e processamento de áudio.
