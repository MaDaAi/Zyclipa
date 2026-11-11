// Zyclipra remote dashboard. Talks only to the desktop app that served this page.
// All calls send the X-Zyclipra header (CSRF protection) and rely on an HttpOnly session cookie.
// Clip pictures and videos load from /api/clip/thumb and /api/clip/media, which need the same sign-in.

interface Status {
  version: string; recording: boolean; recordingState: string; elapsedMs: number;
  replay: boolean; replaySeconds: number; replayAvailable: number; ffmpeg: boolean;
  micProcessing: boolean; audioControl: boolean; clips: number; storageBytes: number;
  memoryMb: number; cpuLoad: number;
}
interface ClipInfo {
  id: string; title: string; source: string; kind: string; createdAt: number; durationSec: number; sizeBytes: number;
  width: number; height: number; fps: number; favorite: boolean; video: boolean; audioTracks: number; description: string;
}
interface Channel { id: string; name: string; volume: number; muted: boolean; }
interface Sound { id: string; name: string; category: string; playing: boolean; }

const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

async function api<T>(path: string, body?: object): Promise<T> {
  const res = await fetch("/api/" + path, {
    method: body ? "POST" : "GET",
    headers: { "X-Zyclipra": "1", "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
    credentials: "same-origin",
  });
  if (res.status === 401) { showLogin(); throw new Error("signed out"); }
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || res.statusText);
  return data as T;
}

function text(el: HTMLElement, t: string): void { el.textContent = t; }

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, content?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (content !== undefined) e.textContent = content;
  return e;
}

function fmtDur(sec: number): string {
  const s = Math.round(sec);
  return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
}

function fmtBytes(b: number): string {
  if (b < 1024 * 1024) return (b / 1024).toFixed(0) + " KB";
  if (b < 1024 * 1024 * 1024) return (b / 1024 / 1024).toFixed(1) + " MB";
  return (b / 1024 / 1024 / 1024).toFixed(2) + " GB";
}

function ago(ms: number): string {
  const m = Math.round((Date.now() - ms) / 60000);
  if (m < 1) return "just now";
  if (m < 60) return m + (m === 1 ? " minute ago" : " minutes ago");
  const h = Math.round(m / 60);
  if (h < 24) return h + (h === 1 ? " hour ago" : " hours ago");
  const d = Math.round(h / 24);
  return d + (d === 1 ? " day ago" : " days ago");
}

function dayLabel(ms: number): string {
  const d = new Date(ms), today = new Date();
  const start = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const diff = Math.round((start(today) - start(d)) / 86400000);
  if (diff === 0) return "Today";
  if (diff === 1) return "Yesterday";
  return d.toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric", year: d.getFullYear() === today.getFullYear() ? undefined : "numeric" });
}

function toast(msg: string, error = false): void {
  const t = $("toast");
  text(t, msg);
  t.className = "toast show" + (error ? " error" : "");
  window.setTimeout(() => (t.className = "toast"), 3000);
}

function showLogin(): void {
  $("login").hidden = false;
  $("clips-view").hidden = true;
  $("control-view").hidden = true;
  $("nav").hidden = true;
  $("logout").hidden = true;
  stopPolling();
}

function showApp(): void {
  $("login").hidden = true;
  $("nav").hidden = false;
  $("logout").hidden = false;
  showView(view);
  start();
}

async function login(ev: Event): Promise<void> {
  ev.preventDefault();
  const code = ($("code") as HTMLInputElement).value;
  try {
    await api("login", { code });
    showApp();
  } catch (e) {
    text($("login-error"), (e as Error).message);
  }
}

// ---------------- views ----------------

let view: "clips" | "control" = "clips";

function showView(v: "clips" | "control"): void {
  view = v;
  $("clips-view").hidden = v !== "clips";
  $("control-view").hidden = v !== "control";
  for (const [id, on] of [["tab-clips", v === "clips"], ["tab-control", v === "control"]] as const) {
    $(id).className = on ? "tab on" : "tab";
    $(id).setAttribute("aria-pressed", String(on));
  }
  if (v === "clips") loadClips().catch(() => undefined);
}

let timer: number | undefined;
let levelTimer: number | undefined;

function stopPolling(): void {
  if (timer) window.clearInterval(timer);
  if (levelTimer) window.clearInterval(levelTimer);
  timer = levelTimer = undefined;
}

// ---------------- clips ----------------

let allClips: ClipInfo[] = [];
let shown: ClipInfo[] = [];
let openIndex = -1;

async function loadClips(): Promise<void> {
  const clips = await api<ClipInfo[]>("clips?limit=all");
  const changed = JSON.stringify(clips.map(c => [c.id, c.title, c.favorite, c.sizeBytes])) !== JSON.stringify(allClips.map(c => [c.id, c.title, c.favorite, c.sizeBytes]));
  allClips = clips;
  if (!changed && $("clip-groups").childElementCount > 0) return;
  const game = $("game") as HTMLSelectElement;
  const current = game.value;
  const games = Array.from(new Set(clips.map(c => c.source).filter(s => s))).sort((a, b) => a.localeCompare(b));
  game.replaceChildren(new Option("All games", ""), ...games.map(g => new Option(g, g)));
  game.value = games.includes(current) ? current : "";
  renderClips();
}

function filtered(): ClipInfo[] {
  const q = ($("search") as HTMLInputElement).value.trim().toLowerCase();
  const game = ($("game") as HTMLSelectElement).value;
  const kind = ($("kind") as HTMLSelectElement).value;
  const sort = ($("sort") as HTMLSelectElement).value;
  const list = allClips.filter(c =>
    (!q || c.title.toLowerCase().includes(q) || c.source.toLowerCase().includes(q) || c.description.toLowerCase().includes(q))
    && (!game || c.source === game)
    && (kind === "" || (kind === "video" && c.video) || (kind === "screenshot" && !c.video) || (kind === "favorite" && c.favorite)));
  const by: Record<string, (a: ClipInfo, b: ClipInfo) => number> = {
    new: (a, b) => b.createdAt - a.createdAt,
    old: (a, b) => a.createdAt - b.createdAt,
    long: (a, b) => b.durationSec - a.durationSec,
    big: (a, b) => b.sizeBytes - a.sizeBytes,
  };
  return list.sort(by[sort] || by.new);
}

function renderClips(): void {
  shown = filtered();
  const total = allClips.reduce((s, c) => s + c.sizeBytes, 0);
  text($("clip-count"), "(" + allClips.length + ")");
  text($("clip-stats"), allClips.length === 0 ? "" : `${fmtBytes(total)} of clips` + (shown.length !== allClips.length ? ` · showing ${shown.length}` : ""));
  const box = $("clip-groups");
  box.replaceChildren();
  if (shown.length === 0) {
    box.appendChild(el("p", "empty muted", allClips.length === 0 ? "No clips yet. Record or save an instant replay in Zyclipra and it shows up here." : "No clips match."));
    return;
  }
  const byTime = ($("sort") as HTMLSelectElement).value === "new" || ($("sort") as HTMLSelectElement).value === "old";
  let grid: HTMLElement | null = null;
  let lastDay = "";
  shown.forEach((c, i) => {
    const day = byTime ? dayLabel(c.createdAt) : "";
    if (!grid || day !== lastDay) {
      if (byTime) {
        const sameDay = shown.filter(x => dayLabel(x.createdAt) === day);
        const games = Array.from(new Set(sameDay.map(x => x.source))).join(" & ");
        const h = el("h3", "day");
        h.append(el("span", "", day), el("span", "muted", " " + games));
        box.appendChild(h);
      }
      grid = el("div", "clip-grid");
      box.appendChild(grid);
      lastDay = day;
    }
    grid.appendChild(card(c, i));
  });
}

function card(c: ClipInfo, index: number): HTMLElement {
  const b = el("button", "clip-card");
  b.setAttribute("aria-label", "Open " + c.title);
  const thumb = el("div", "thumb");
  const img = el("img");
  img.alt = "";
  img.loading = "lazy";
  img.src = "/api/clip/thumb?id=" + encodeURIComponent(c.id);
  img.addEventListener("error", () => { img.remove(); thumb.classList.add("no-thumb"); });
  thumb.appendChild(img);
  if (c.video) thumb.appendChild(el("span", "pill", fmtDur(c.durationSec)));
  if (c.favorite) thumb.appendChild(el("span", "star", "★"));
  const body = el("div", "clip-body");
  body.append(el("span", "game caps", c.source || "Clip"), el("span", "title", c.title),
    el("span", "meta", ago(c.createdAt) + " · " + fmtBytes(c.sizeBytes)));
  b.append(thumb, body);
  b.addEventListener("click", () => openPlayer(index));
  return b;
}

function openPlayer(index: number): void {
  const c = shown[index];
  if (!c) return;
  openIndex = index;
  const media = $("player-media");
  stopMedia();
  const src = "/api/clip/media?id=" + encodeURIComponent(c.id);
  if (c.video) {
    const v = el("video");
    v.controls = true;
    v.autoplay = true;
    v.preload = "metadata";
    v.src = src;
    v.addEventListener("error", () => {
      media.replaceChildren(el("p", "muted", "This browser can't play this file" + (c.kind === "screenshot" ? "." : " here – use Download to watch it.")));
    });
    media.replaceChildren(v);
  } else {
    const i = el("img");
    i.alt = c.title;
    i.src = src;
    media.replaceChildren(i);
  }
  text($("player-game"), c.source || "Clip");
  text($("player-title"), c.title);
  const parts = [new Date(c.createdAt).toLocaleString()];
  if (c.video) parts.push(fmtDur(c.durationSec));
  if (c.height) parts.push(c.height + "p" + (c.fps ? " " + Math.round(c.fps) + " FPS" : ""));
  parts.push(fmtBytes(c.sizeBytes));
  if (c.video && c.audioTracks > 1) parts.push(c.audioTracks + " sound tracks");
  text($("player-meta"), parts.join(" · "));
  text($("player-desc"), c.description || "");
  ($("player-download") as HTMLAnchorElement).href = src + "&download=1";
  ($("player-prev") as HTMLButtonElement).disabled = index <= 0;
  ($("player-next") as HTMLButtonElement).disabled = index >= shown.length - 1;
  const d = $("player") as HTMLDialogElement;
  if (!d.open) d.showModal();
}

function stopMedia(): void {
  const v = $("player-media").querySelector("video");
  if (v) { v.pause(); v.removeAttribute("src"); v.load(); }
}

// ---------------- control ----------------

async function refreshStatus(): Promise<void> {
  const s = await api<Status>("status");
  const rec = $("rec-state");
  text(rec, s.recording ? "● REC " + fmtDur(s.elapsedMs / 1000) : s.recordingState === "IDLE" ? "Not recording" : s.recordingState.toLowerCase() + "…");
  rec.className = s.recording ? "big rec" : "big";
  text($("rec-btn"), s.recording ? "Stop recording" : "Start recording");
  ($("rec-btn") as HTMLButtonElement).disabled = !s.ffmpeg;
  text($("replay-state"), s.replay ? s.replayAvailable + " / " + s.replaySeconds + " s buffered" : "Off");
  text($("replay-btn"), s.replay ? "Turn replay off" : "Turn replay on");
  ($("save-btn") as HTMLButtonElement).disabled = !s.replay;
  text($("sys"), `Zyclipra ${s.version} · ${s.clips} clips (${fmtBytes(s.storageBytes)}) · app memory ${s.memoryMb} MB · CPU ${s.cpuLoad}% · `
    + (s.audioControl ? "audio control on" : "audio control unavailable") + (s.micProcessing ? " · mic processing on" : ""));
}

async function refreshLevels(): Promise<void> {
  const lv = await api<Record<string, number>>("levels");
  for (const [id, peak] of Object.entries(lv)) {
    const bar = document.getElementById("lv-" + id);
    if (bar) {
      const db = 20 * Math.log10(Math.max(peak, 1e-6));
      bar.style.width = Math.max(0, Math.min(100, ((db + 60) / 60) * 100)) + "%";
    }
  }
}

async function loadMixer(): Promise<void> {
  const chans = await api<Channel[]>("mixer");
  const box = $("mixer");
  box.replaceChildren();
  for (const c of chans) {
    const row = el("div", "chan");
    const name = el("span", "chan-name", c.name);
    const slider = document.createElement("input");
    slider.type = "range"; slider.min = "0"; slider.max = "100"; slider.value = String(Math.round(c.volume * 100));
    slider.setAttribute("aria-label", c.name + " volume");
    const mute = el("button", c.muted ? "mute on" : "mute", c.muted ? "Muted" : "Mute");
    const meter = el("div", "meter");
    const fill = el("div");
    fill.id = "lv-" + c.id;
    meter.appendChild(fill);
    const send = () => api("mixer", { channel: c.id, volume: Number(slider.value) / 100, muted: c.muted }).catch(e => toast(e.message, true));
    slider.addEventListener("change", send);
    mute.addEventListener("click", () => {
      c.muted = !c.muted;
      mute.className = c.muted ? "mute on" : "mute";
      text(mute, c.muted ? "Muted" : "Mute");
      send();
    });
    row.append(name, meter, slider, mute);
    box.appendChild(row);
  }
}

async function loadSounds(): Promise<void> {
  const sounds = await api<Sound[]>("sounds");
  const box = $("sounds");
  box.replaceChildren();
  if (sounds.length === 0) { box.appendChild(el("p", "muted", "Import sounds in the desktop app.")); return; }
  for (const s of sounds) {
    const b = el("button", s.playing ? "pad playing" : "pad", s.name);
    b.title = s.category;
    b.addEventListener("click", () => api("sounds/play", { id: s.id }).then(() => window.setTimeout(loadSounds, 200)).catch(e => toast(e.message, true)));
    box.appendChild(b);
  }
}

function action(path: string, ok: string): void {
  api(path, {}).then(() => { toast(ok); window.setTimeout(refreshStatus, 900); window.setTimeout(loadClips, 2500); }).catch(e => toast(e.message, true));
}

function start(): void {
  refreshStatus().catch(() => undefined);
  loadMixer().catch(() => undefined);
  loadClips().catch(() => undefined);
  loadSounds().catch(() => undefined);
  stopPolling();
  let ticks = 0;
  timer = window.setInterval(() => {
    if (document.hidden) return;
    ticks++;
    if (view === "control") { refreshStatus().catch(() => undefined); loadSounds().catch(() => undefined); }
    else if (ticks % 8 === 0) loadClips().catch(() => undefined); // new clips appear within ~15 s
  }, 2000);
  levelTimer = window.setInterval(() => { if (!document.hidden && view === "control") refreshLevels().catch(() => undefined); }, 250);
}

document.addEventListener("DOMContentLoaded", () => {
  $("login-form").addEventListener("submit", login);
  $("tab-clips").addEventListener("click", () => showView("clips"));
  $("tab-control").addEventListener("click", () => { showView("control"); refreshStatus().catch(() => undefined); });
  for (const id of ["search", "game", "kind", "sort"]) $(id).addEventListener("input", renderClips);
  const d = $("player") as HTMLDialogElement;
  d.addEventListener("close", stopMedia);
  d.addEventListener("click", e => { if (e.target === d) d.close(); });
  $("player-close").addEventListener("click", () => d.close());
  $("player-prev").addEventListener("click", () => openPlayer(openIndex - 1));
  $("player-next").addEventListener("click", () => openPlayer(openIndex + 1));
  d.addEventListener("keydown", e => {
    if (e.key === "ArrowLeft" && !(e.target instanceof HTMLVideoElement)) openPlayer(openIndex - 1);
    if (e.key === "ArrowRight" && !(e.target instanceof HTMLVideoElement)) openPlayer(openIndex + 1);
  });
  $("rec-btn").addEventListener("click", () => action("recording/toggle", "Recording toggled"));
  $("replay-btn").addEventListener("click", () => action("replay/toggle", "Instant replay toggled"));
  $("save-btn").addEventListener("click", () => action("replay/save", "Saving replay…"));
  $("shot-btn").addEventListener("click", () => action("screenshot", "Screenshot taken"));
  $("stop-sounds").addEventListener("click", () => action("sounds/stop", "Sounds stopped"));
  $("logout").addEventListener("click", () => api("logout", {}).finally(showLogin));
  api<Status>("status").then(showApp).catch(() => undefined);
});
