# AudioStretch Advanced

Aplicativo Android de análise, reprodução e edição avançada de áudio, desenvolvido por Celso Martins.

## Estado do projeto

Fase 1 — estrutura inicial em implementação. Este repositório é independente dos demais projetos.

## Arquitetura planejada

- Android nativo com Kotlin para ciclo de vida, arquivos, persistência e integração com o sistema.
- Interface HTML5, CSS e JavaScript carregada localmente em Android WebView.
- Motor de áudio nativo em C++ via JNI, com arquitetura preparada para Oboe/AAudio.
- Processamento de áudio em tempo real isolado da interface, rede e operações de I/O.
- GitHub Actions para compilação e validação automatizadas.

## Fase 1

- Inicialização Android e WebView local.
- Importação de áudio por Storage Access Framework.
- Transporte: reproduzir, pausar, parar, volume e busca temporal.
- Waveform, timeline, zoom, seleção e marcadores.
- Estrutura para projetos e operação offline.
- Ponte segura entre JavaScript, Kotlin e C++.
- Workflow de build Android.

## Roadmap

- Time Stretching, Pitch Shifting, LiveScrub e loop A-B.
- Separação de stems e componentes individuais da bateria.
- Reconhecimento de notas, MIDI, harmonia e cifras alinhadas temporalmente.
- Geração e edição de partituras e tablaturas, MusicXML e PDF.
- Mixer multipista com volume, pan, mute, solo, EQ, efeitos e automação.
- Exportação WAV, MP3, MIDI, MusicXML e PDF.
- Recursos de IA local e em nuvem.

## Princípios técnicos

A posição temporal canônica do motor DSP será expressa em frames/amostras. Recursos que ainda não foram implementados ou validados serão identificados como planejados, não como concluídos.

## Repositório

https://github.com/celsomartins251/audiostretch-advanced
