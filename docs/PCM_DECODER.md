# Decodificador PCM experimental

O módulo `PcmDecoder` decodifica uma URI usando MediaExtractor/MediaCodec e entrega blocos Float32 intercalados em estéreo para um consumidor fornecido pelo chamador. A taxa de saída é explícita e deve ser obtida do stream Oboe já aberto.

- PCM suportado: 8-bit unsigned, 16-bit signed, 24-bit packed, 32-bit signed e Float32.
- Mono é duplicado para L/R; estéreo mantém os dois canais; mais de dois canais são reduzidos a mono por média e duplicados, pois o layout multicanal não é garantido em todos os decoders.
- Reamostragem linear com retenção do frame anterior para continuidade entre buffers.
- Cancelamento cooperativo por callback; recursos MediaCodec/MediaExtractor são liberados em finally.
- O módulo ainda não está ligado ao MainActivity, ao AudioBridge ou ao MediaPlayer. O consumidor e o controle de backpressure precisam ser fornecidos numa etapa posterior.
- A validação de qualidade, sincronização, seek e reprodução em aparelho real permanece pendente.
