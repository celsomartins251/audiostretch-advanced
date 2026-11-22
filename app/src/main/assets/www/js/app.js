(() => {
  const el = (id) => document.getElementById(id);
  let duration = 0;
  let seeking = false;
  const api = () => window.AudioBridge || null;
  const time = (ms) => {
    const sec = Math.max(0, Math.floor(ms / 1000));
    return String(Math.floor(sec / 60)).padStart(2, "0") + ":" + String(sec % 60).padStart(2, "0");
  };
  el("importBtn").addEventListener("click", () => api()?.importAudio());
  el("playBtn").addEventListener("click", () => api()?.play());
  el("pauseBtn").addEventListener("click", () => api()?.pause());
  el("stopBtn").addEventListener("click", () => api()?.stop());
  el("volume").addEventListener("input", e => api()?.setVolume(Number(e.target.value)));
  el("seek").addEventListener("input", e => {
    seeking = true;
    el("currentTime").textContent = time(duration * Number(e.target.value) / 1000);
  });
  el("seek").addEventListener("change", e => {
    if (duration) api()?.seekTo(Math.round(duration * Number(e.target.value) / 1000));
    seeking = false;
  });
  window.onNativeAudioLoaded = (length, name) => {
    duration = Number(length) || 0;
    el("fileName").textContent = String(name || "Áudio importado");
    el("totalTime").textContent = time(duration);
    el("currentTime").textContent = "00:00";
    el("seek").value = "0";
    el("status").textContent = "ÁUDIO CARREGADO";
    el("error").textContent = "";
  };
  window.onNativeAudioError = message => { el("error").textContent = String(message); };
  window.onNativePosition = position => {
    if (!seeking && duration) el("seek").value = String(Math.min(1000, Number(position) / duration * 1000));
  };
  window.setInterval(() => {
    const native = api();
    if (!native || !native.hasAudio()) return;
    const position = native.getPosition();
    if (!seeking) {
      el("currentTime").textContent = time(position);
      el("seek").value = duration ? String(Math.min(1000, position / duration * 1000)) : "0";
    }
    el("status").textContent = native.isPlaying() ? "REPRODUZINDO" : "PRONTO";
  }, 250);
})();
