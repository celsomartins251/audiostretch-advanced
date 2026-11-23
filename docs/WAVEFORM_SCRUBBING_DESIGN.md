# Waveform touch scrubbing

## Objetivo

Adicionar scrubbing na forma de onda: a posição horizontal do dedo controla a posição de leitura; a velocidade e a direção do gesto controlam a velocidade e a direção da reprodução. Quando o dedo fica parado, o motor repete um pequeno trecho ao redor da posição atual para sustentar o som.

## Contrato de interação

- Um toque e arraste na forma de onda entra no modo Scrub e suspende o transporte MediaPlayer sem perder o ponto de retorno.
- O movimento horizontal atualiza continuamente a posição-alvo. A velocidade é estimada pelo deslocamento e intervalo entre eventos, com suavização para evitar saltos.
- O sentido do movimento controla a direção. O módulo de velocidade é limitado para proteger a estabilidade.
- Ao manter o dedo parado, a posição-alvo é mantida e um loop curto de PCM (alvo inicial: 150 ms) é repetido continuamente.
- O loop usa crossfade nas extremidades para reduzir cliques; a janela será ajustada por testes auditivos.
- Ao soltar o dedo, o modo Scrub termina e o cursor permanece na posição atual. A reprodução normal não reinicia sozinha sem uma regra de UX definida.

## Arquitetura

O MediaPlayer permanece como transporte normal. O scrubbing usa um transporte Oboe dedicado e independente; não deve simular o gesto com chamadas repetidas a seekTo().

O callback de áudio nativo deve continuar sem JNI, locks, alocações ou I/O. Decodificação, busca no arquivo, estimativa de velocidade e preparação de janelas PCM ocorrem fora da thread de áudio. O callback apenas lê PCM pré-carregado e aplica interpolação/ganho.

A solução deve suportar movimento para frente e para trás. Um buffer FIFO de reprodução contínua não é suficiente. É necessário um cache PCM endereçável por posição, alimentado pelo decoder em worker, com sincronização preparada fora do callback.

## Critérios de aceitação

1. Arrastar devagar produz reprodução lenta e contínua.
2. Arrastar rápido aumenta proporcionalmente a velocidade sem saltos audíveis excessivos.
3. Arrastar para a esquerda reproduz em sentido reverso.
4. Parar o dedo mantém som contínuo do trecho local, sem silêncio e sem clique perceptível.
5. Retomar o movimento sai do loop de sustentação sem travar a interface.
6. O cursor acompanha a posição de áudio, e o gráfico mantém o auto-scroll já validado.
7. Play/Pause/Stop e troca de arquivo encerram o modo Scrub com segurança.
8. O caminho MediaPlayer normal permanece intacto.
9. Validar no dispositivo real com arquivos curtos e longos, mono/estéreo e taxas diferentes; CI de compilação não substitui teste auditivo.

## Estratégia de entrega

Implementar em etapas: (a) contrato de eventos de toque e estados do transporte; (b) fonte PCM com acesso por posição e cache; (c) leitura variável bidirecional e loop com crossfade no motor Oboe; (d) sincronização do cursor e integração da UI; (e) testes e APK. Não habilitar o recurso por padrão até os testes de dispositivo confirmarem ausência de underruns e cliques.