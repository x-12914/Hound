"use strict";

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
const state = {
  token: localStorage.getItem("hound_token") || null,
  role: localStorage.getItem("hound_role") || null,
  email: localStorage.getItem("hound_email") || null,
  alerts: new Map(),     // id -> alert detail object
  markers: new Map(),    // id -> L.marker (latest position)
  trails: new Map(),     // id -> L.polyline
  selected: null,
  filter: "active",
  ws: null,
};

const $ = (sel) => document.querySelector(sel);

// ---------------------------------------------------------------------------
// API helpers
// ---------------------------------------------------------------------------
async function api(path, opts = {}) {
  const headers = opts.headers || {};
  if (state.token) headers["Authorization"] = "Bearer " + state.token;
  const res = await fetch(path, { ...opts, headers });
  if (res.status === 401) { logout(); throw new Error("unauthorized"); }
  return res;
}

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------
async function login(email, password) {
  const res = await fetch("/api/auth/login-json", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) throw new Error("Invalid credentials");
  const data = await res.json();
  state.token = data.access_token;
  state.role = data.role;
  state.email = email;
  localStorage.setItem("hound_token", data.access_token);
  localStorage.setItem("hound_role", data.role);
  localStorage.setItem("hound_email", email);
}

function logout() {
  state.token = state.role = state.email = null;
  localStorage.clear();
  if (state.ws) { try { state.ws.close(); } catch (e) {} }
  location.reload();
}

// ---------------------------------------------------------------------------
// Map
// ---------------------------------------------------------------------------
let map;
function initMap() {
  map = L.map("map", { zoomControl: true }).setView([20, 0], 2);
  // Full-colour street basemap (CARTO Voyager) — a real map, not the dark tiles.
  L.tileLayer("https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png", {
    attribution: "&copy; OpenStreetMap &copy; CARTO",
    maxZoom: 20,
  }).addTo(map);
}

function alertColor(a) {
  return a.status === "active" ? "#ff3b4e"
    : a.status === "acknowledged" ? "#f5a623"
    : a.status === "resolved" ? "#2ecc71" : "#5a6678";
}

function latestPoint(a) {
  if (!a.locations || a.locations.length === 0) return null;
  return a.locations[a.locations.length - 1];
}

function renderMapForAlert(a) {
  const pts = (a.locations || []).map((l) => [l.lat, l.lng]);
  if (pts.length === 0) return;
  const color = alertColor(a);

  // trail
  let trail = state.trails.get(a.id);
  if (trail) { trail.setLatLngs(pts); trail.setStyle({ color }); }
  else {
    trail = L.polyline(pts, { color, weight: 3, opacity: 0.7 }).addTo(map);
    state.trails.set(a.id, trail);
  }

  // latest marker
  const last = pts[pts.length - 1];
  let marker = state.markers.get(a.id);
  const icon = L.divIcon({
    className: "",
    html: `<div style="width:18px;height:18px;border-radius:50%;background:${color};
            border:3px solid #0b0e14;box-shadow:0 0 12px ${color}"></div>`,
    iconSize: [18, 18], iconAnchor: [9, 9],
  });
  if (marker) { marker.setLatLng(last); marker.setIcon(icon); }
  else {
    marker = L.marker(last, { icon }).addTo(map);
    marker.on("click", () => selectAlert(a.id));
    state.markers.set(a.id, marker);
  }
  marker.bindTooltip(`${a.owner_email || "device"} · ${a.status}`, { direction: "top" });
}

function focusAlert(a) {
  const p = latestPoint(a);
  if (p) map.setView([p.lat, p.lng], 16, { animate: true });
}

// ---------------------------------------------------------------------------
// Rendering: list + detail
// ---------------------------------------------------------------------------
function timeAgo(iso) {
  const s = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60) return s + "s ago";
  if (s < 3600) return Math.floor(s / 60) + "m ago";
  if (s < 86400) return Math.floor(s / 3600) + "h ago";
  return Math.floor(s / 86400) + "d ago";
}

function renderList() {
  const list = $("#alert-list");
  let alerts = [...state.alerts.values()];
  if (state.filter === "active") alerts = alerts.filter((a) => a.status === "active");
  alerts.sort((x, y) => new Date(y.triggered_at) - new Date(x.triggered_at));

  if (alerts.length === 0) {
    list.innerHTML = `<div class="empty">No ${state.filter === "active" ? "active " : ""}alerts.</div>`;
    return;
  }
  list.innerHTML = alerts.map((a) => {
    const p = latestPoint(a);
    const coords = p ? `${p.lat.toFixed(4)}, ${p.lng.toFixed(4)}` : "no location";
    const sel = a.id === state.selected ? "selected" : "";
    const act = a.status === "active" ? "active" : "";
    return `<div class="alert-item ${sel} ${act}" data-id="${a.id}">
      <div class="row1">
        <span class="who">${escapeHtml(a.owner_email || "Unknown")}</span>
        <span class="time">${timeAgo(a.triggered_at)}</span>
      </div>
      <div class="meta">
        <span class="badge ${a.status}">${a.status}</span>
        <span>${escapeHtml(a.device_name || "device")}</span>
      </div>
      <div class="meta"><span>📍 ${coords}</span></div>
    </div>`;
  }).join("");

  list.querySelectorAll(".alert-item").forEach((el) => {
    el.addEventListener("click", () => selectAlert(parseInt(el.dataset.id)));
  });
}

function selectAlert(id) {
  state.selected = id;
  renderList();
  const a = state.alerts.get(id);
  if (a) { focusAlert(a); renderDetail(a); }
}

async function renderDetail(a) {
  const d = $("#detail");
  d.classList.remove("hidden");
  const p = latestPoint(a);
  const triggered = new Date(a.triggered_at).toLocaleString();
  const canTriage = true;

  d.innerHTML = `
    <button class="close" id="detail-close">✕</button>
    <h3>${escapeHtml(a.owner_email || "Unknown")}</h3>
    <div class="sub">${escapeHtml(a.device_name || "device")} · alert #${a.id}</div>
    <div class="kv"><span>Status</span><span class="badge ${a.status}">${a.status}</span></div>
    <div class="kv"><span>Triggered</span><span>${triggered}</span></div>
    <div class="kv"><span>Location pts</span><span>${(a.locations || []).length}</span></div>
    ${p ? `<div class="kv"><span>Last fix</span><span>${p.lat.toFixed(5)}, ${p.lng.toFixed(5)}</span></div>` : ""}
    ${p && p.accuracy_m != null ? `<div class="kv"><span>Accuracy</span><span>±${Math.round(p.accuracy_m)} m</span></div>` : ""}
    ${canTriage ? `<div class="actions">
      ${a.status === "active" ? `<button class="ack" id="btn-ack">Acknowledge</button>` : ""}
      ${a.status !== "resolved" && a.status !== "cancelled" ? `<button class="resolve" id="btn-resolve">Resolve</button>` : ""}
    </div>` : ""}
    <div class="section-title">Audio clips (${(a.audio_clips || []).length})</div>
    <div id="audio-container">${(a.audio_clips || []).length ? "" : '<div class="sub">No audio yet.</div>'}</div>
    ${p ? `<a class="sub" href="https://www.google.com/maps?q=${p.lat},${p.lng}" target="_blank">Open in Google Maps ↗</a>` : ""}
  `;

  $("#detail-close").onclick = () => d.classList.add("hidden");
  const ack = $("#btn-ack"); if (ack) ack.onclick = () => setStatus(a.id, "acknowledged");
  const rev = $("#btn-resolve"); if (rev) rev.onclick = () => setStatus(a.id, "resolved");

  // Load audio clips as authenticated blobs.
  const container = $("#audio-container");
  for (const clip of a.audio_clips || []) {
    try {
      const res = await api(`/api/alerts/${a.id}/audio/${clip.id}/file`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const audio = document.createElement("audio");
      audio.controls = true;
      audio.src = url;
      container.appendChild(audio);
    } catch (e) { /* ignore */ }
  }
}

async function setStatus(id, status) {
  await api(`/api/alerts/${id}/status`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ status }),
  });
  // The WS broadcast will update local state; optimistically update too.
  const a = state.alerts.get(id);
  if (a) { a.status = status; renderList(); renderDetail(a); renderMapForAlert(a); }
}

// ---------------------------------------------------------------------------
// Real-time
// ---------------------------------------------------------------------------
function connectWs() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  const ws = new WebSocket(`${proto}://${location.host}/ws?token=${encodeURIComponent(state.token)}`);
  state.ws = ws;
  ws.onopen = () => setWsStatus(true);
  ws.onclose = () => { setWsStatus(false); setTimeout(connectWs, 3000); };
  ws.onerror = () => { try { ws.close(); } catch (e) {} };
  ws.onmessage = (ev) => handleEvent(JSON.parse(ev.data));
}

function setWsStatus(online) {
  $("#ws-status").className = "ws-dot " + (online ? "online" : "offline");
  $("#ws-label").textContent = online ? "live" : "reconnecting…";
}

function handleEvent(ev) {
  if (ev.type === "alert.new") {
    const a = ev.alert;
    a.locations = a.location ? [a.location] : [];
    a.audio_clips = [];
    state.alerts.set(a.id, a);
    renderMapForAlert(a);
    renderList();
    notifyNewAlert(a);
  } else if (ev.type === "alert.location") {
    const a = state.alerts.get(ev.alert_id);
    if (a) {
      a.locations = a.locations || [];
      a.locations.push(ev.location);
      renderMapForAlert(a);
      if (state.selected === a.id) { renderDetail(a); }
      renderList();
    }
  } else if (ev.type === "alert.audio") {
    const a = state.alerts.get(ev.alert_id);
    if (a) {
      a.audio_clips = a.audio_clips || [];
      a.audio_clips.push(ev.audio);
      if (state.selected === a.id) renderDetail(a);
    }
  } else if (ev.type === "alert.status") {
    const a = state.alerts.get(ev.alert_id);
    if (a) { a.status = ev.status; renderMapForAlert(a); renderList(); if (state.selected === a.id) renderDetail(a); }
  }
}

function notifyNewAlert(a) {
  playSiren();
  if ("Notification" in window && Notification.permission === "granted") {
    new Notification("🚨 EMERGENCY ALERT", { body: `${a.owner_email || "Device"} triggered an alert` });
  }
  selectAlert(a.id);
}

// Simple WebAudio siren so we don't ship an audio file.
function playSiren() {
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const now = ctx.currentTime;
    for (let i = 0; i < 3; i++) {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = "sine";
      osc.frequency.setValueAtTime(880, now + i * 0.45);
      osc.frequency.setValueAtTime(660, now + i * 0.45 + 0.22);
      gain.gain.setValueAtTime(0.001, now + i * 0.45);
      gain.gain.exponentialRampToValueAtTime(0.3, now + i * 0.45 + 0.05);
      gain.gain.exponentialRampToValueAtTime(0.001, now + i * 0.45 + 0.4);
      osc.connect(gain); gain.connect(ctx.destination);
      osc.start(now + i * 0.45); osc.stop(now + i * 0.45 + 0.42);
    }
  } catch (e) { /* autoplay may be blocked until user interacts */ }
}

// ---------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------
async function loadAlerts() {
  const res = await api("/api/alerts");
  const alerts = await res.json();
  state.alerts.clear();
  for (const a of alerts) {
    a.locations = a.locations || [];
    a.audio_clips = a.audio_clips || [];
    state.alerts.set(a.id, a);
    renderMapForAlert(a);
  }
  renderList();
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

async function start() {
  $("#app").classList.remove("hidden");
  $("#login").classList.add("hidden");
  $("#who").textContent = `${state.email} · ${state.role}`;
  initMap();
  if ("Notification" in window && Notification.permission === "default") {
    Notification.requestPermission();
  }
  await loadAlerts();
  connectWs();
}

document.addEventListener("DOMContentLoaded", () => {
  $("#login-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    $("#login-error").textContent = "";
    try {
      await login($("#email").value.trim(), $("#password").value);
      await start();
    } catch (err) {
      $("#login-error").textContent = "Invalid email or password.";
    }
  });
  $("#logout").addEventListener("click", logout);
  document.querySelectorAll(".filter").forEach((b) => {
    b.addEventListener("click", () => {
      document.querySelectorAll(".filter").forEach((x) => x.classList.remove("active"));
      b.classList.add("active");
      state.filter = b.dataset.filter;
      renderList();
    });
  });

  if (state.token) { start().catch(() => logout()); }
});
