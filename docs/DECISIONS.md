# Decisões técnicas iniciais

- Repositório independente: audiostretch-advanced.
- Plataforma: Android nativo com interface local em WebView.
- Kotlin controla ciclo de vida, SAF e APIs Android.
- HTML/CSS/JavaScript compõem a interface.
- C++/JNI é a fronteira planejada do DSP; Oboe/AAudio será integrado após a fundação.
- Transporte inicial usa MediaPlayer como base funcional, não como motor DSP profissional.
- Processamento em tempo real não poderá depender de UI, rede ou operações de disco.
- Unidade temporal canônica do DSP: frames/amostras.
- Recursos futuros permanecem no roadmap, sem simular implementação.
