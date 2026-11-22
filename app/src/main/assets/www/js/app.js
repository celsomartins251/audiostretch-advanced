(() => {
  const el = id => document.getElementById(id);
  let duration = 0, seeking = false, peaks = [], zoom = 1, pointA = null, pointB = null;
  let loopEnabled = false, lastLoopSeek = 0, markers = [], markerKey = "";
  const api = () => window.AudioBridge || null;
  const time = ms => {
    const sec = Math.max(0, Math.floor(ms / 1000));
    return String(Math.floor(sec / 60)).padStart(2, "0") + ":" + String(sec % 60).padStart(2, "0");
  };
  const currentPosition = () => Number(api()?.getPosition?.() || 0);
  const draw = () => {
    const canvas = el("waveform"), scroller = el("waveScroll");
    const width = Math.max(1, Math.floor(scroller.clientWidth * zoom)), height = 150;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    canvas.style.width = width + "px"; canvas.style.height = height + "px";
    canvas.width = Math.floor(width * dpr); canvas.height = Math.floor(height * dpr);
    const ctx = canvas.getContext("2d"); ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, width, height);
    ctx.strokeStyle = "#ffffff12"; ctx.lineWidth = 1;
    for (let x = 0; x < width; x += 40) { ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, height); ctx.stroke(); }
    if (!peaks.length || !duration) return;
    const xFor = ms => Math.max(0, Math.min(width, ms / duration * width));
    if (pointA !== null && pointB !== null) {
      const left = xFor(Math.min(pointA, pointB)), right = xFor(Math.max(pointA, pointB));
      ctx.fillStyle = "#d6a83a25"; ctx.fillRect(left, 0, Math.max(1, right - left), height);
      ctx.strokeStyle = "#d6a83a"; ctx.lineWidth = 1;
      [left, right].forEach(x => { ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, height); ctx.stroke(); });
    }
    ctx.strokeStyle = "#d6a83a"; ctx.lineWidth = Math.max(1, 1.2 * zoom);
    ctx.beginPath();
    const center = height / 2, scale = height * .43;
    peaks.forEach((p, i) => {
      const x = (i / (peaks.length - 1)) * width;
      const amp = Math.max(1, p * scale);
      ctx.moveTo(x, center - amp); ctx.lineTo(x, center + amp);
    });
    ctx.stroke();
    const playX = xFor(currentPosition());
    ctx.strokeStyle = "#f4f0e7"; ctx.lineWidth = 1.5;
    ctx.beginPath(); ctx.moveTo(playX, 0); ctx.lineTo(playX, height); ctx.stroke();
  };
  const updateSelection = () => {
    const valid = pointA !== null && pointB !== null && pointB > pointA;
    el("selectionLabel").textContent = valid ? time(pointA) + " – " + time(pointB) : (pointA !== null ? "PONTO A: " + time(pointA) : "SEM SELEÇÃO");
    el("loopBtn").textContent = loopEnabled ? "Desativar loop A–B" : "Ativar loop A–B";
    el("loopBtn").disabled = !valid;
    draw();
  };
  const renderMarkers = () => {
    const host = el("markers"); host.replaceChildren();
    if (!markers.length) { const empty = document.createElement("span"); empty.className = "empty-markers"; empty.textContent = "Nenhum marcador salvo."; host.append(empty); return; }
    markers.forEach((m, i) => {
      const b = document.createElement("button"); b.className = "marker-chip";
      const label = document.createElement("span"); label.textContent = "M" + (i + 1);
      b.append(label, document.createTextNode(time(m)));
      b.addEventListener("click", () => api()?.seekTo(Math.round(m)));
      b.title = "Ir para " + time(m); host.append(b);
    });
  };
  const saveMarkers = () => { try { localStorage.setItem(markerKey, JSON.stringify(markers)); } catch (_) {} };
  el("importBtn").addEventListener("click", () => api()?.importAudio());
  el("playBtn").addEventListener("click", () => api()?.play());
  el("pauseBtn").addEventListener("click", () => api()?.pause());
  el("stopBtn").addEventListener("click", () => api()?.stop());
  el("volume").addEventListener("input", e => api()?.setVolume(Number(e.target.value)));
  el("seek").addEventListener("input", e => { seeking = true; el("currentTime").textContent = time(duration * Number(e.target.value) / 1000); });
  el("seek").addEventListener("change", e => { if (duration) api()?.seekTo(Math.round(duration * Number(e.target.value) / 1000)); seeking = false; });
  el("zoom").addEventListener("input", e => { zoom = Number(e.target.value); el("zoomValue").textContent = zoom + "×"; draw(); });
  el("waveform").addEventListener("click", e => {
    if (!duration) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const x = e.clientX - rect.left + el("waveScroll").scrollLeft;
    api()?.seekTo(Math.round(Math.max(0, Math.min(1, x / e.currentTarget.clientWidth)) * duration));
  });
  el("setA").addEventListener("click", () => { pointA = currentPosition(); pointB = null; loopEnabled = false; updateSelection(); });
  el("setB").addEventListener("click", () => {
    const pos = currentPosition();
    if (pointA === null) { el("error").textContent = "Defina o ponto A antes do ponto B."; return; }
    if (pos <= pointA) { el("error").textContent = "O ponto B precisa estar depois do ponto A."; return; }
    pointB = pos; loopEnabled = false; el("error").textContent = ""; updateSelection();
  });
  el("loopBtn").addEventListener("click", () => { loopEnabled = !loopEnabled; lastLoopSeek = 0; updateSelection(); });
  el("clearSelection").addEventListener("click", () => { pointA = pointB = null; loopEnabled = false; updateSelection(); });
  el("addMarker").addEventListener("click", () => {
    if (!duration) return;
    markers.push(currentPosition()); markers.sort((a, b) => a - b); saveMarkers(); renderMarkers();
  });
  window.onNativeAudioLoaded = (length, name) => {
    duration = Number(length) || 0; peaks = []; pointA = pointB = null; loopEnabled = false;
    el("fileName").textContent = String(name || "Áudio importado");
    el("totalTime").textContent = time(duration); el("currentTime").textContent = "00:00";
    el("seek").value = "0"; el("status").textContent = "ÁUDIO CARREGADO";
    el("error").textContent = ""; el("waveNote").textContent = "Calculando forma de onda…";
    el("waveNote").hidden = false;
    markerKey = "audioStretchMarkers:" + String(name || "audio");
    try { markers = JSON.parse(localStorage.getItem(markerKey) || "[]"); if (!Array.isArray(markers)) markers = []; } catch (_) { markers = []; }
    renderMarkers(); updateSelection();
  };
  window.onNativeWaveform = data => {
    peaks = Array.isArray(data) ? data.map(v => Math.max(0, Math.min(1, Number(v) || 0))) : [];
    el("waveNote").hidden = peaks.length > 0;
    if (!peaks.length) el("waveNote").textContent = "Não foi possível gerar a forma de onda deste arquivo.";
    draw();
  };
  window.onNativeWaveformError = message => { el("waveNote").hidden = false; el("waveNote").textContent = "Waveform indisponível: " + String(message); };
  window.onNativeAudioError = message => { el("error").textContent = String(message); };
  window.onNativePosition = position => { if (!seeking && duration) el("seek").value = String(Math.min(1000, Number(position) / duration * 1000)); draw(); };
  window.addEventListener("resize", draw);
  window.setInterval(() => {
    const native = api(); if (!native || !native.hasAudio()) return;
    const position = native.getPosition();
    if (!seeking) { el("currentTime").textContent = time(position); el("seek").value = duration ? String(Math.min(1000, position / duration * 1000)) : "0"; }
    el("status").textContent = native.isPlaying() ? "REPRODUZINDO" : "PRONTO";
    if (loopEnabled && pointA !== null && pointB !== null && position >= pointB && position - lastLoopSeek > 80) {
      lastLoopSeek = position; native.seekTo(Math.round(pointA));
    }
    draw();
  }, 180);
  updateSelection();
  // Atualizador integrado: consulta o canal beta do GitHub Releases.
  let pendingUpdate = null;
  const updateStatus = message => { el("updateStatus").textContent = message; };
  const checkUpdate = () => {
    pendingUpdate = null;
    el("downloadUpdateBtn").hidden = true;
    el("updateDetails").hidden = true;
    el("updateProgress").hidden = true;
    updateStatus("Consultando o canal beta…");
    if (api()?.checkForUpdates) api().checkForUpdates();
    else updateStatus("Atualizador disponível somente na versão Android do aplicativo.");
  };
  el("updatesBtn").addEventListener("click", () => { document.querySelector(".updates-panel").scrollIntoView({behavior:"smooth",block:"center"}); checkUpdate(); });
  el("checkUpdatesBtn").addEventListener("click", checkUpdate);
  el("downloadUpdateBtn").addEventListener("click", () => {
    if (!pendingUpdate) return;
    el("downloadUpdateBtn").disabled = true;
    updateStatus("Baixando a atualização…");
    el("updateProgress").hidden = false;
    api()?.downloadUpdate(pendingUpdate.url, pendingUpdate.digest || "");
  });
  window.onUpdateAvailable = info => {
    pendingUpdate = info;
    updateStatus("Nova versão beta disponível: " + info.tag);
    el("updateDetails").textContent = (info.title || info.tag) + "\n\n" + (info.notes || "Sem notas de versão.");
    el("updateDetails").hidden = false;
    el("downloadUpdateBtn").hidden = false;
    el("downloadUpdateBtn").disabled = false;
  };
  window.onUpdateStatus = updateStatus;
  window.onUpdateError = message => { updateStatus("Atualização: " + message); el("downloadUpdateBtn").disabled = false; };
  window.onUpdateProgress = percent => {
    if (percent >= 0) el("updateProgressBar").style.width = Math.min(100, percent) + "%";
    updateStatus(percent >= 0 ? "Baixando… " + percent + "%" : "Baixando atualização…");
  };
  window.onUpdateDownloaded = updateStatus;
})();