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


## Atualizações integradas (canal beta)

O aplicativo consulta as versões beta publicadas em GitHub Releases, apresenta as notas, baixa o APK e solicita ao instalador oficial do Android a confirmação da instalação. O Android exige autorização do usuário para instalar aplicativos desta origem; não existe instalação silenciosa.

### Assinatura persistente obrigatória

Para instalar uma atualização por cima da versão anterior, todos os APKs precisam usar a mesma chave. Configure estes quatro GitHub Actions Secrets no repositório:

- `ANDROID_KEYSTORE_BASE64`: arquivo JKS/keystore codificado em Base64.
- `ANDROID_KEYSTORE_PASSWORD`: senha do keystore.
- `ANDROID_KEY_ALIAS`: alias da chave.
- `ANDROID_KEY_PASSWORD`: senha da chave.

A chave privada deve ser criada e guardada pelo responsável pelo aplicativo. Nunca a coloque no código-fonte ou em arquivos públicos. Sem os quatro secrets, o workflow continua gerando artefatos de teste, mas não publica releases atualizáveis.

**Migração da versão de teste inicial:** os APKs antigos foram assinados com a chave debug temporária do GitHub Actions. Como essa chave não foi persistida, o Android não pode garantir atualização direta desses APKs. Depois de configurar a assinatura persistente, será necessária uma instalação de transição: desinstalar a versão antiga e instalar uma vez o APK assinado com a nova chave. A partir dessa instalação, as versões beta poderão ser atualizadas dentro do aplicativo, preservando os dados do app.

### Publicação

Cada build da branch `main` publica uma versão beta automaticamente quando os quatro secrets de assinatura estiverem configurados. O aplicativo consulta o canal beta e compara o código da versão antes de oferecer o download.


### Verificação da assinatura persistente

Após cadastrar os quatro secrets, um novo push na branch `main` executa a compilação e tenta publicar uma release beta assinada. A confirmação definitiva é a presença da release `beta-N` com o APK `audiostretch-advanced-beta.apk` em GitHub Releases; a simples conclusão do build não confirma a publicação.
