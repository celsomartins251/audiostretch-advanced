# Validação

## Verificações previstas
- Build debug com JDK 17 e Android Gradle Plugin 8.7.3.
- Instalação em Android API 26+.
- Importação de áudio via SAF e reprodução dos formatos suportados pelo dispositivo.
- Play, pause, stop, seek e volume.
- Rotação, recriação da Activity e encerramento.
- URI inválida/revogada e falha de decoder.
- Teste em dispositivo físico.

## Limitações desta fundação
- MediaPlayer não garante suporte a todos os codecs nem precisão de transporte em nível de amostra.
- Waveform real, Room, DSP em tempo real, time stretch, pitch shift e análise musical ainda não estão integrados.
- Nenhum teste de build ou dispositivo é declarado concluído apenas pela criação dos arquivos.
