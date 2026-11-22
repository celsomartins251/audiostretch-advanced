# Arquitetura

## Camadas
- Android/Kotlin: ciclo de vida, SAF, permissões, persistência e orquestração.
- WebView local: interface HTML/CSS/JS sem dependência de CDN.
- Transporte ativo: Android MediaPlayer para formatos suportados pelo decoder do dispositivo.
- C++/JNI: fronteira nativa com Oboe 1.11.0 integrada via Prefab/CMake.
- Oboe: abertura experimental de stream de saída de baixa latência; callback atual produz silêncio e ainda não recebe PCM.
- DSP futuro: pipeline PCM, processamento temporal e pitch shift em tempo real, isolado da UI, rede e I/O.

## Tempo
A unidade temporal canônica do motor DSP será posição em frames de áudio. Conversões para milissegundos ocorrerão somente nas fronteiras de APIs que exijam essa unidade.

## Segurança
A WebView carrega assets locais. Acesso genérico a arquivos e conteúdo está desabilitado; a seleção ocorre pelo Storage Access Framework. A ponte JavaScript expõe operações específicas de transporte.

## Offline e persistência
A interface não depende de CDN. Room, gerenciamento de projetos e waveform calculada ainda serão implementados. O acesso ao áudio usa URI concedida pelo seletor do Android.

## Migração do transporte
O MediaPlayer permanece ativo e não é substituído nesta etapa. A integração PCM será feita em paralelo e só poderá assumir a reprodução após validação funcional e comparação de estabilidade.
