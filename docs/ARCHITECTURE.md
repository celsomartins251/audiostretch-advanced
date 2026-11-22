# Arquitetura

## Camadas
- Android/Kotlin: ciclo de vida, SAF, permissões, persistência e orquestração.
- WebView local: interface HTML/CSS/JS sem dependência de CDN.
- Transporte ativo: Android MediaPlayer para formatos suportados pelo decoder do dispositivo.
- C++/JNI: fronteira nativa com Oboe 1.11.0 integrada via Prefab/CMake.
- Oboe: stream de saída Float32 estéreo em baixa latência; a taxa real é consultada no stream aberto.
- PCM ring buffer: fila SPSC pré-alocada; produtor fora da thread de áudio e consumidor no callback. O callback não usa locks, JNI nem aloca memória; em underrun, completa o bloco com silêncio.
- Controles nativos isolados: pause/resume e limpeza do buffer somente com stream pausado (ou fechado). O proprietário do produtor deve interrompê-lo antes de limpar a fila.
- Contrato PCM: Float32 intercalado estéreo, na taxa de amostragem real do stream. Conversão/downmix/upmix e adaptação de sample rate pertencem ao estágio do decoder.
- DSP futuro: processamento temporal e pitch shift em tempo real, isolado da UI, rede e I/O.

## Tempo
A unidade temporal canônica do motor DSP será posição em frames de áudio. Conversões para milissegundos ocorrerão somente nas fronteiras de APIs que exijam essa unidade.

## Segurança
A WebView carrega assets locais. Acesso genérico a arquivos e conteúdo está desabilitado; a seleção ocorre pelo Storage Access Framework. A ponte JavaScript expõe operações específicas de transporte.

## Offline e persistência
A interface não depende de CDN. Room, gerenciamento de projetos e waveform calculada ainda serão implementados. O acesso ao áudio usa URI concedida pelo seletor do Android.

## Migração do transporte
O MediaPlayer permanece ativo e não é substituído nesta etapa. O decoder Kotlin ainda não alimenta o buffer. A integração só poderá assumir a reprodução após validação de conversão, sincronização, seek, pause, fim de stream e estabilidade em dispositivo real.
