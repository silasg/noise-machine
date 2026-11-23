"use strict";

// Explicit local-app opt-in; ordinary browser clients keep the full visuals.
if (new URLSearchParams(window.location.search).get("lightweight") === "1") {
  document.documentElement.classList.add("lightweight");
}

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

let state = null;
let selectedDays = new Set();
const renderedSections = {};
let lastEventAt = 0;
let stateRevision = 0;
let refreshPending = null;
let commandRefreshTimer = null;
let interactingControl = null;
let evtSrc = null;
let scheduleNextTimer = null;
let visibleTab = "pad";
const voiceVolumes = new Map();

function queueVoiceVolume(id, volume) {
  let pending = voiceVolumes.get(id);
  if (!pending) {
    pending = { value: null, timer: null, request: null, lastSent: 0, cancelled: false };
    voiceVolumes.set(id, pending);
  }
  if (pending.cancelled) return;
  pending.value = volume;
  if (!pending.timer && !pending.request) {
    pending.timer = setTimeout(() => flushVoiceVolume(id), Math.max(0, 100 - (Date.now() - pending.lastSent)));
  }
}

function flushVoiceVolume(id) {
  const pending = voiceVolumes.get(id);
  if (!pending) return;
  clearTimeout(pending.timer);
  pending.timer = null;
  if (pending.cancelled || pending.request || pending.value === null) return;
  const volume = pending.value;
  pending.value = null;
  pending.lastSent = Date.now();
  pending.request = api("/api/voice/volume", { id, volume }).catch(console.warn).then(() => {
    pending.request = null;
    if (!pending.cancelled && pending.value !== null) queueVoiceVolume(id, pending.value);
  });
}

function cancelVoiceVolume(id) {
  const pending = voiceVolumes.get(id);
  if (!pending) return Promise.resolve();
  pending.cancelled = true;
  clearTimeout(pending.timer);
  pending.value = null;
  // Serialize deactivation after any already-sent volume command.
  return Promise.resolve(pending.request).then(() => {
    if (voiceVolumes.get(id) === pending) voiceVolumes.delete(id);
  });
}

// ---------- networking ----------

async function api(path, body) {
  const opts = { method: body ? "POST" : "GET" };
  if (body) {
    opts.headers = { "Content-Type": "application/json" };
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(path, opts);
  if (!res.ok) throw new Error(`${path}: ${res.status}`);
  const contentType = res.headers.get("content-type") || "";
  const result = await (contentType.includes("application/json") ? res.json() : res.text());
  if (path !== "/api/state") {
    clearTimeout(commandRefreshTimer);
    commandRefreshTimer = setTimeout(async () => {
      // A poll started before the command may contain pre-command state.
      if (refreshPending) await refreshPending.catch(() => {});
      refresh().catch(console.warn);
    }, 0);
  }
  return result;
}

function connectEvents() {
  if (evtSrc) try { evtSrc.close(); } catch (_) {}
  try {
    evtSrc = new EventSource("/api/events");
    evtSrc.onmessage = (e) => {
      try {
        const snapshot = JSON.parse(e.data);
        if (!snapshot || typeof snapshot !== "object") return;
        state = snapshot;
        stateRevision++;
        lastEventAt = Date.now();
        render();
      } catch (_) {}
    };
    evtSrc.onerror = () => {
      // Keep EventSource's reconnect, but poll until snapshots resume.
      lastEventAt = 0;
    };
  } catch (_) {}
}

async function refresh() {
  // Share in-flight polls; don't let an older HTTP response replace SSE state.
  if (refreshPending) return refreshPending;
  const revision = stateRevision;
  refreshPending = (async () => {
    try {
      const snapshot = await api("/api/state");
      if (revision === stateRevision) {
        state = snapshot;
        render();
      }
    } finally {
      refreshPending = null;
    }
  })();
  return refreshPending;
}

// ---------- rendering ----------

function render() {
  if (!state) return;
  renderStatus();
  const active = activeMap();
  voiceVolumes.forEach((pending, id) => {
    if (!active.has(id)) cancelVoiceVolume(id);
  });
  if (visibleTab === "pad") {
    renderSection("pad", [state.catalog, state.activeVoices], ["#pad-grid"], renderPad);
    renderSection("master", state.masterVolume, ["#master-volume"], renderMaster);
  }
  if (visibleTab === "scenes") renderSection("scenes", [state.scenes, state.catalog], ["#scene-list"], renderScenes);
  if (visibleTab === "schedule") {
    renderSection("scene-options", (state.scenes || []).map(sc => sc.name),
      ["#sched-scene"], renderSceneOptions);
    const schedule = (state.schedule || []).map(entry => {
      const copy = Object.assign({}, entry);
      delete copy.nextTriggerMs;
      return copy;
    });
    renderSection("schedule", schedule, ["#schedule-list"], renderSchedule);
  }
  if (visibleTab === "library") renderSection("library", state.catalog, ["#library-list"], renderLibrary);
  if (visibleTab === "settings") renderSection("settings", [state.settings, state.systemVolumeTarget],
    ["#pane-settings"], renderSettings);
}

function renderSection(name, value, selectors, draw) {
  const key = JSON.stringify(value);
  if (renderedSections[name] === key) return;
  const busy = selectors.some(selector => {
    const root = $(selector);
    const focused = document.activeElement;
    return root && ((focused && /^(INPUT|SELECT|TEXTAREA)$/.test(focused.tagName) && root.contains(focused)) ||
      (interactingControl && root.contains(interactingControl)));
  });
  if (busy) return; // Retry with the latest state when focus/drag ends.
  draw();
  renderedSections[name] = key;
}

function activeMap() {
  const m = new Map();
  for (const v of (state.activeVoices || [])) m.set(v.id, v.volume);
  return m;
}

function renderStatus() {
  const dot = $("#status-dot");
  const active = (state.activeVoices || []).length > 0;
  dot.classList.toggle("live", active && !state.paused);
  dot.classList.toggle("paused", state.paused);
  $("#btn-pause").textContent = state.paused ? "Resume" : "Pause";

  const upcoming = (state.schedule || [])
    .filter(e => e.enabled && e.nextTriggerMs)
    .sort((a, b) => a.nextTriggerMs - b.nextTriggerMs)[0];
  if (upcoming) {
    const dt = new Date(upcoming.nextTriggerMs);
    const sceneTxt = upcoming.action === "STOP" ? "stop" : `→ ${upcoming.sceneName || "?"}`;
    $("#next-fire").textContent = `next: ${dt.toLocaleString([], {weekday:"short", hour:"2-digit", minute:"2-digit"})} ${sceneTxt}`;
  } else {
    $("#next-fire").textContent = "";
  }
}

function renderPad() {
  const grid = $("#pad-grid");
  const active = activeMap();
  const cats = {};
  for (const s of (state.catalog || [])) {
    if (!cats[s.category]) cats[s.category] = [];
    cats[s.category].push(s);
  }
  grid.innerHTML = "";
  for (const cat of Object.keys(cats)) {
    const heading = document.createElement("div");
    heading.style.gridColumn = "1 / -1";
    heading.style.color = "var(--muted)";
    heading.style.fontSize = "12px";
    heading.style.textTransform = "uppercase";
    heading.style.letterSpacing = "1px";
    heading.style.marginTop = "8px";
    heading.textContent = cat;
    grid.appendChild(heading);
    for (const s of cats[cat]) {
      grid.appendChild(makePad(s, active));
    }
  }
}

function renderMaster() {
  const mv = state.masterVolume == null ? 0.6 : state.masterVolume;
  $("#master-volume").value = Math.round(mv * 100);
  $("#master-readout").textContent = `${Math.round(mv * 100)}%`;
}

function makePad(sound, active) {
  const isOn = active.has(sound.id);
  const vol = active.has(sound.id) ? active.get(sound.id) : 1;

  const pad = document.createElement("div");
  pad.className = "pad" + (isOn ? " active" : "") + (sound.available ? "" : " unavailable");
  pad.dataset.id = sound.id;

  const label = document.createElement("div");
  label.className = "label";
  label.textContent = sound.label;
  pad.appendChild(label);

  const meta = document.createElement("div");
  meta.className = "meta";
  meta.innerHTML = `<span>${sound.kind === "PROCEDURAL" ? "synth" : sound.builtIn ? "asset" : "user"}</span>` +
                   `<span>${isOn ? Math.round(vol*100) + "%" : ""}</span>`;
  pad.appendChild(meta);

  const volRow = document.createElement("div");
  volRow.className = "vol-row";
  const slider = document.createElement("input");
  slider.type = "range";
  slider.min = 0; slider.max = 200; slider.step = 1;
  slider.value = Math.round(vol * 100);
  slider.style.display = isOn ? "block" : "none";
  slider.addEventListener("click", e => e.stopPropagation());
  slider.addEventListener("input", () => {
    const v = parseInt(slider.value, 10) / 100;
    queueVoiceVolume(sound.id, v);
  });
  slider.addEventListener("change", () => flushVoiceVolume(sound.id));
  slider.addEventListener("blur", () => flushVoiceVolume(sound.id));
  volRow.appendChild(slider);
  pad.appendChild(volRow);

  if (!sound.available) {
    const note = document.createElement("div");
    note.className = "meta";
    note.style.color = "var(--warn)";
    note.textContent = "missing audio file";
    pad.appendChild(note);
  }

  pad.addEventListener("click", async () => {
    if (!sound.available) return;
    try {
      if (activeMap().has(sound.id)) {
        await cancelVoiceVolume(sound.id);
        await api("/api/voice/deactivate", { id: sound.id });
      }
      else await api("/api/voice/activate", { id: sound.id });
    } catch (e) { console.warn(e); }
  });

  return pad;
}

function renderScenes() {
  const list = $("#scene-list");
  list.innerHTML = "";
  for (const sc of (state.scenes || [])) {
    const card = document.createElement("div");
    card.className = "scene-card";
    const voices = sc.voices.map(v => `${labelFor(v.id)} ${Math.round(v.volume*100)}%`).join(", ");
    card.innerHTML = `<h4>${escapeHtml(sc.name)}</h4>
      <div class="voices">${voices || "(empty)"}</div>
      <div class="scene-actions">
        <button data-act="apply">Apply</button>
        <button data-act="delete" class="ghost">Delete</button>
      </div>`;
    card.querySelector('[data-act="apply"]').onclick = () =>
      api("/api/scene/apply", { name: sc.name }).catch(console.warn);
    card.querySelector('[data-act="delete"]').onclick = () => {
      if (confirm(`Delete scene "${sc.name}"?`))
        api("/api/scene/delete", { name: sc.name }).catch(console.warn);
    };
    list.appendChild(card);
  }
}

function renderSceneOptions() {
  // Update options only when scene names change, not on every snapshot.
  const sceneSel = $("#sched-scene");
  const cur = sceneSel.value;
  sceneSel.innerHTML = "";
  for (const sc of (state.scenes || [])) {
    const o = document.createElement("option");
    o.value = sc.name; o.textContent = sc.name;
    sceneSel.appendChild(o);
  }
  if (cur) sceneSel.value = cur;
}

function renderSchedule() {
  const list = $("#schedule-list");
  list.innerHTML = "";
  const rows = (state.schedule || []).slice().sort((a, b) =>
    a.hour * 60 + a.minute - (b.hour * 60 + b.minute));
  for (const e of rows) {
    const div = document.createElement("div");
    div.className = "schedule-row" + (e.enabled ? "" : " disabled");
    const days = e.daysOfWeek.length ? e.daysOfWeek.map(dowLabel).join(" ") : "every day";
    const dur = e.durationMinutes ? ` · auto-stop after ${e.durationMinutes}m` : "";
    const sceneTxt = e.action === "STOP" ? "stop all" : `→ ${e.sceneName || "(unset)"}`;
    div.innerHTML = `
      <div class="info">
        <span class="time">${pad2(e.hour)}:${pad2(e.minute)}</span>
        <span class="sub">${days} · ${sceneTxt}${dur}</span>
      </div>
      <div class="row gap">
        <button data-act="edit" class="ghost">Edit</button>
        <button data-act="toggle" class="ghost">${e.enabled ? "Disable" : "Enable"}</button>
        <button data-act="delete" class="danger">Delete</button>
      </div>`;
    div.querySelector('[data-act="edit"]').onclick = () => loadScheduleEntry(e);
    div.querySelector('[data-act="toggle"]').onclick = () => {
      api("/api/schedule/upsert", Object.assign({}, e, { enabled: !e.enabled })).catch(console.warn);
    };
    div.querySelector('[data-act="delete"]').onclick = () => {
      if (confirm("Delete this schedule entry?"))
        api("/api/schedule/delete", { id: e.id }).catch(console.warn);
    };
    list.appendChild(div);
  }
}

function renderLibrary() {
  const list = $("#library-list");
  list.innerHTML = "";
  const customs = (state.catalog || []).filter(s => !s.builtIn);
  if (customs.length === 0) {
    list.innerHTML = `<p class="muted">No custom uploads yet. Drop in any audio file (mp3, ogg, wav, m4a, flac, opus) to add it as a tile.</p>`;
    return;
  }
  for (const s of customs) {
    const card = document.createElement("div");
    card.className = "library-card";
    card.innerHTML = `<div>${escapeHtml(s.label)}</div>`;
    const del = document.createElement("button");
    del.className = "danger";
    del.textContent = "Delete";
    del.onclick = () => {
      if (confirm(`Delete ${s.label}?`)) api("/api/sound/delete", { id: s.id }).catch(console.warn);
    };
    card.appendChild(del);
    list.appendChild(card);
  }
}

function renderSettings() {
  const s = state.settings || {};
  $("#set-port").value = s.port == null ? 8378 : s.port;
  $("#set-dnd").checked = !!s.enableDnd;
  $("#set-alarms-through-dnd").checked = !!s.allowAlarmsThroughDnd;
  $("#set-autostart").checked = !!s.autoStartOnBoot;
  $("#set-keep-screen").checked = !!s.keepScreenOn;
  const sysv = state.systemVolumeTarget == null ? 0.6 : state.systemVolumeTarget;
  $("#set-sysvol").value = Math.round(sysv * 100);
  $("#sysvol-readout").textContent = `${Math.round(sysv * 100)}%`;

  const warns = $("#permission-warnings");
  warns.innerHTML = "";
  if (s.enableDnd && !s.notificationPolicyAccess) {
    const w = document.createElement("div");
    w.className = "warning";
    w.textContent = "Notification policy access not granted. DND won't take effect until you grant it in Android settings.";
    warns.appendChild(w);
  }
  if (!s.canScheduleExactAlarms) {
    const w = document.createElement("div");
    w.className = "warning";
    w.textContent = "Exact alarms not allowed. Schedules may fire late. Grant 'Alarms & reminders' permission in app info.";
    warns.appendChild(w);
  }
}

// ---------- helpers ----------

function labelFor(id) {
  const s = (state.catalog || []).find(x => x.id === id);
  return s ? s.label : id;
}
function pad2(n) { return n < 10 ? "0" + n : String(n); }
function dowLabel(d) {
  return ["", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"][d] || String(d);
}
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[c]));
}

function loadScheduleEntry(e) {
  $("#sched-id").value = e.id;
  $("#sched-time").value = `${pad2(e.hour)}:${pad2(e.minute)}`;
  $("#sched-action").value = e.action;
  $("#sched-scene").value = e.sceneName || "";
  $("#sched-duration").value = e.durationMinutes || "";
  $("#sched-enabled").checked = !!e.enabled;
  selectedDays = new Set(e.daysOfWeek || []);
  refreshDowButtons();
  switchTab("schedule");
}

function refreshDowButtons() {
  $$('.dow button[data-dow]').forEach(b => {
    const d = parseInt(b.dataset.dow, 10);
    b.classList.toggle("on", selectedDays.has(d));
  });
}

function clearScheduleForm() {
  $("#sched-id").value = "";
  $("#sched-time").value = "22:00";
  $("#sched-action").value = "ACTIVATE_SCENE";
  $("#sched-duration").value = "";
  $("#sched-enabled").checked = true;
  selectedDays = new Set();
  refreshDowButtons();
}

function switchTab(name) {
  visibleTab = name;
  $$(".tab").forEach(t => t.classList.toggle("active", t.dataset.tab === name));
  $$(".pane").forEach(p => p.classList.toggle("active", p.id === `pane-${name}`));
  render();
}

// ---------- wiring ----------

function wire() {
  document.addEventListener("focusout", () => setTimeout(render, 0));
  const beginInteraction = e => { interactingControl = e.target; };
  const endInteraction = () => {
    interactingControl = null;
    setTimeout(render, 0);
  };
  document.addEventListener("touchstart", beginInteraction, { passive: true });
  document.addEventListener("mousedown", beginInteraction);
  document.addEventListener("touchend", endInteraction);
  document.addEventListener("touchcancel", endInteraction);
  document.addEventListener("mouseup", endInteraction);
  window.addEventListener("blur", endInteraction);
  $$(".tab").forEach(t => t.addEventListener("click", () => switchTab(t.dataset.tab)));

  $("#btn-pause").onclick = () =>
    api(state.paused ? "/api/resume" : "/api/pause").catch(console.warn);
  $("#btn-stop").onclick = async () => {
    if (!confirm("Stop all sounds?")) return;
    await Promise.all(Array.from(voiceVolumes.keys()).map(cancelVoiceVolume));
    api("/api/stop-all").catch(console.warn);
  };

  const masterEl = $("#master-volume");
  let masterTimer = null;
  masterEl.addEventListener("input", () => {
    $("#master-readout").textContent = `${masterEl.value}%`;
    clearTimeout(masterTimer);
    masterTimer = setTimeout(() => {
      api("/api/master/volume", { volume: parseInt(masterEl.value, 10) / 100 }).catch(console.warn);
    }, 60);
  });

  $("#btn-save-scene").onclick = async () => {
    const name = $("#scene-name").value.trim();
    if (!name) return alert("Enter a scene name");
    await api("/api/scene/save", { name }).catch(console.warn);
    $("#scene-name").value = "";
  };

  $$('.dow button[data-dow]').forEach(b => {
    b.addEventListener("click", () => {
      const d = parseInt(b.dataset.dow, 10);
      if (selectedDays.has(d)) selectedDays.delete(d); else selectedDays.add(d);
      refreshDowButtons();
    });
  });
  $("#btn-clear-schedule").onclick = clearScheduleForm;
  $("#btn-save-schedule").onclick = async () => {
    const time = $("#sched-time").value || "22:00";
    const [h, m] = time.split(":").map(n => parseInt(n, 10));
    const dur = parseInt($("#sched-duration").value, 10);
    const body = {
      id: $("#sched-id").value || undefined,
      enabled: $("#sched-enabled").checked,
      hour: h, minute: m,
      daysOfWeek: Array.from(selectedDays).sort(),
      action: $("#sched-action").value,
      sceneName: $("#sched-scene").value || null,
      durationMinutes: Number.isFinite(dur) && dur > 0 ? dur : null
    };
    await api("/api/schedule/upsert", body).catch(console.warn);
    clearScheduleForm();
  };

  $("#upload-input").addEventListener("change", async (e) => {
    const f = e.target.files[0];
    if (!f) return;
    const fd = new FormData();
    fd.append("file", f, f.name);
    try {
      await fetch("/api/upload", { method: "POST", body: fd });
      await refresh();
    } catch (err) { alert("Upload failed: " + err); }
    e.target.value = "";
  });

  for (const id of ["#set-dnd", "#set-alarms-through-dnd", "#set-autostart", "#set-keep-screen"]) {
    $(id).addEventListener("change", () => saveSettings());
  }
  $("#set-port").addEventListener("change", () => saveSettings({ port: parseInt($("#set-port").value, 10) }));
  const sv = $("#set-sysvol");
  let svTimer = null;
  sv.addEventListener("input", () => {
    $("#sysvol-readout").textContent = `${sv.value}%`;
    clearTimeout(svTimer);
    svTimer = setTimeout(() => {
      api("/api/system-volume", { fraction: parseInt(sv.value, 10) / 100 }).catch(console.warn);
    }, 100);
  });
}

async function saveSettings(extra) {
  const body = {
    enableDnd: $("#set-dnd").checked,
    allowAlarmsThroughDnd: $("#set-alarms-through-dnd").checked,
    autoStartOnBoot: $("#set-autostart").checked,
    keepScreenOn: $("#set-keep-screen").checked
  };
  Object.assign(body, extra || {});
  try { await api("/api/settings", body); } catch (e) { console.warn(e); }
}

(async function init() {
  wire();
  try { await refresh(); } catch (e) { console.warn(e); }
  connectEvents();
  // Snapshots normally arrive every 1.5s. Poll only if SSE is absent or stale.
  setInterval(() => {
    if (!lastEventAt || Date.now() - lastEventAt > 6500) refresh().catch(() => {});
  }, 5000);
})();
