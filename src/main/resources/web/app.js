"use strict";
// Zyclipra remote dashboard. Talks only to the desktop app that served this page.
// All calls send the X-Zyclipra header (CSRF protection) and rely on an HttpOnly session cookie –
// nothing about the sign-in is kept in localStorage. Clip pictures and videos load from
// /api/clip/thumb and /api/clip/media, which need the same sign-in. Google tokens never reach this page.
const $ = (id) => document.getElementById(id);
async function api(path, body) {
    const res = await fetch("/api/" + path, {
        method: body ? "POST" : "GET",
        headers: { "X-Zyclipra": "1", "Content-Type": "application/json" },
        body: body ? JSON.stringify(body) : undefined,
        credentials: "same-origin",
    });
    if (res.status === 401 && path !== "login") {
        showLogin();
        throw new Error("Please sign in.");
    }
    const data = await res.json();
    if (!res.ok)
        throw new Error(data.error || res.statusText);
    return data;
}
function text(el, t) { el.textContent = t; }
function el(tag, cls, content) {
    const e = document.createElement(tag);
    if (cls)
        e.className = cls;
    if (content !== undefined)
        e.textContent = content;
    return e;
}
function fmtDur(sec) {
    const s = Math.round(sec);
    return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
}
function fmtBytes(b) {
    if (b < 1024 * 1024)
        return (b / 1024).toFixed(0) + " KB";
    if (b < 1024 * 1024 * 1024)
        return (b / 1024 / 1024).toFixed(1) + " MB";
    return (b / 1024 / 1024 / 1024).toFixed(2) + " GB";
}
function ago(ms) {
    const m = Math.round((Date.now() - ms) / 60000);
    if (m < 1)
        return "just now";
    if (m < 60)
        return m + (m === 1 ? " minute ago" : " minutes ago");
    const h = Math.round(m / 60);
    if (h < 24)
        return h + (h === 1 ? " hour ago" : " hours ago");
    const d = Math.round(h / 24);
    return d + (d === 1 ? " day ago" : " days ago");
}
function dayLabel(ms) {
    const d = new Date(ms), today = new Date();
    const start = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
    const diff = Math.round((start(today) - start(d)) / 86400000);
    if (diff === 0)
        return "Today";
    if (diff === 1)
        return "Yesterday";
    return d.toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric", year: d.getFullYear() === today.getFullYear() ? undefined : "numeric" });
}
function toast(msg, error = false) {
    const t = $("toast");
    text(t, msg);
    t.className = "toast show" + (error ? " error" : "");
    window.setTimeout(() => (t.className = "toast"), 3500);
}
const SYNC_LABEL = {
    local: "Local only", queued: "Queued for Google Drive", uploading: "Uploading to Google Drive…", synced: "On Google Drive",
    failed: "Upload failed", unavailable: "Cloud file unavailable", cloudOnly: "Only on Google Drive",
};
// ---------------- sign-in ----------------
function showLogin() {
    $("login").hidden = false;
    for (const v of VIEWS)
        $(v + "-view").hidden = true;
    $("nav").hidden = true;
    $("logout").hidden = true;
    stopPolling();
}
function showApp() {
    $("login").hidden = true;
    $("nav").hidden = false;
    $("logout").hidden = false;
    $("password").value = "";
    showView(view);
    start();
}
async function login(ev) {
    ev.preventDefault();
    try {
        await api("login", { username: $("username").value.trim(), password: $("password").value });
        text($("login-error"), "");
        showApp();
    }
    catch (e) {
        text($("login-error"), e.message);
    }
}
// ---------------- views ----------------
const VIEWS = ["clips", "control", "cloud", "account"];
let view = "clips";
function showView(v) {
    view = v;
    for (const x of VIEWS) {
        $(x + "-view").hidden = x !== v;
        $("tab-" + x).className = x === v ? "tab on" : "tab";
        $("tab-" + x).setAttribute("aria-pressed", String(x === v));
    }
    if (v === "clips")
        loadClips().catch(() => undefined);
    if (v === "control")
        refreshStatus().catch(() => undefined);
    if (v === "cloud")
        loadCloud().catch(e => toast(e.message, true));
    if (v === "account")
        loadAccount().catch(e => toast(e.message, true));
}
let timer;
let levelTimer;
function stopPolling() {
    if (timer)
        window.clearInterval(timer);
    if (levelTimer)
        window.clearInterval(levelTimer);
    timer = levelTimer = undefined;
}
// ---------------- clips ----------------
let allClips = [];
let shown = [];
let openIndex = -1;
let knownIds = new Set();
const freshIds = new Set();
async function loadClips() {
    const clips = await api("clips?limit=all");
    if (knownIds.size > 0)
        for (const c of clips)
            if (!knownIds.has(c.id))
                freshIds.add(c.id);
    const sig = (l) => JSON.stringify(l.map(c => [c.id, c.title, c.favorite, c.sizeBytes, c.sync]));
    const changed = sig(clips) !== sig(allClips);
    allClips = clips;
    if (!changed && $("clip-groups").childElementCount > 0)
        return;
    const game = $("game");
    const current = game.value;
    const games = Array.from(new Set(clips.map(c => c.source).filter(s => s))).sort((a, b) => a.localeCompare(b));
    game.replaceChildren(new Option("All games", ""), ...games.map(g => new Option(g, g)));
    game.value = games.includes(current) ? current : "";
    renderClips();
    if ($("player").open && shown[openIndex])
        paintPlayerSync(shown[openIndex]);
}
function filtered() {
    const q = $("search").value.trim().toLowerCase();
    const game = $("game").value;
    const kind = $("kind").value;
    const sort = $("sort").value;
    const list = allClips.filter(c => (!q || c.title.toLowerCase().includes(q) || c.source.toLowerCase().includes(q) || c.description.toLowerCase().includes(q))
        && (!game || c.source === game)
        && (kind === "" || (kind === "video" && c.video) || (kind === "screenshot" && !c.video) || (kind === "favorite" && c.favorite)
            || (kind === "local" && c.sync !== "synced") || (kind === "synced" && c.sync === "synced")));
    const by = {
        new: (a, b) => b.createdAt - a.createdAt,
        old: (a, b) => a.createdAt - b.createdAt,
        long: (a, b) => b.durationSec - a.durationSec,
        big: (a, b) => b.sizeBytes - a.sizeBytes,
    };
    return list.sort(by[sort] || by.new);
}
function renderClips() {
    shown = filtered();
    const total = allClips.reduce((s, c) => s + c.sizeBytes, 0);
    const synced = allClips.filter(c => c.sync === "synced").length;
    text($("clip-count"), "(" + allClips.length + ")");
    text($("clip-stats"), allClips.length === 0 ? "" : `${fmtBytes(total)} of clips on your PC · ${synced} on Google Drive` + (shown.length !== allClips.length ? ` · showing ${shown.length}` : ""));
    const box = $("clip-groups");
    box.replaceChildren();
    if (shown.length === 0) {
        box.appendChild(el("p", "empty muted", allClips.length === 0 ? "No clips yet. Press Clip it (with instant replay on) or record, and it shows up here." : "No clips match."));
        return;
    }
    const sort = $("sort").value;
    const byTime = sort === "new" || sort === "old";
    let grid = null;
    let lastDay = "";
    shown.forEach((c, i) => {
        const day = byTime ? dayLabel(c.createdAt) : "";
        if (!grid || day !== lastDay) {
            if (byTime) {
                const games = Array.from(new Set(shown.filter(x => dayLabel(x.createdAt) === day).map(x => x.source))).join(" & ");
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
function syncBadge(state) {
    const b = el("span", "sync sync-" + state, state === "synced" ? "☁ ✓" : state === "uploading" ? "☁ ↑" : state === "queued" ? "☁ …" : state === "failed" ? "☁ !" : "☁ ✕");
    b.title = SYNC_LABEL[state] || state;
    return b;
}
function card(c, index) {
    const b = el("button", "clip-card");
    b.setAttribute("aria-label", "Open " + c.title + " (" + (SYNC_LABEL[c.sync] || "") + ")");
    const thumb = el("div", "thumb");
    const img = el("img");
    img.alt = "";
    img.loading = "lazy";
    img.src = "/api/clip/thumb?id=" + encodeURIComponent(c.id);
    img.addEventListener("error", () => { img.remove(); thumb.classList.add("no-thumb"); });
    thumb.appendChild(img);
    if (c.video)
        thumb.appendChild(el("span", "pill", fmtDur(c.durationSec)));
    if (c.favorite)
        thumb.appendChild(el("span", "star", "★"));
    if (freshIds.has(c.id))
        thumb.appendChild(el("span", "new", "NEW"));
    if (c.sync !== "local")
        thumb.appendChild(syncBadge(c.sync));
    const body = el("div", "clip-body");
    body.append(el("span", "game caps", c.source || "Clip"), el("span", "title", c.title), el("span", "meta", ago(c.createdAt) + " · " + fmtBytes(c.sizeBytes)));
    b.append(thumb, body);
    b.addEventListener("click", () => openPlayer(index));
    return b;
}
function openPlayer(index) {
    const c = shown[index];
    if (!c)
        return;
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
        v.addEventListener("error", () => media.replaceChildren(el("p", "muted", "This browser can't play this file here – use Download to watch it.")));
        media.replaceChildren(v);
    }
    else {
        const i = el("img");
        i.alt = c.title;
        i.src = src;
        media.replaceChildren(i);
    }
    text($("player-game"), c.source || "Clip");
    text($("player-title"), c.title);
    const parts = [new Date(c.createdAt).toLocaleString()];
    if (c.video)
        parts.push(fmtDur(c.durationSec));
    if (c.height)
        parts.push(c.height + "p" + (c.fps ? " " + Math.round(c.fps) + " FPS" : ""));
    parts.push(fmtBytes(c.sizeBytes));
    if (c.video && c.audioTracks > 1)
        parts.push(c.audioTracks + " sound tracks (the browser plays the first; Download has them all)");
    text($("player-meta"), parts.join(" · "));
    text($("player-desc"), c.description || "");
    $("player-download").href = src + "&download=1";
    $("player-prev").disabled = index <= 0;
    $("player-next").disabled = index >= shown.length - 1;
    paintPlayerSync(c);
    const d = $("player");
    if (!d.open)
        d.showModal();
}
function paintPlayerSync(c) {
    const s = $("player-sync");
    text(s, "Google Drive: " + (SYNC_LABEL[c.sync] || c.sync));
    s.className = "small sync-text-" + c.sync;
    const b = $("player-upload");
    b.hidden = c.sync === "synced" || c.sync === "uploading";
    text(b, c.sync === "queued" ? "Cancel upload" : c.sync === "failed" || c.sync === "unavailable" ? "Retry upload" : "Upload to Google Drive");
}
async function playerSyncAction() {
    const c = shown[openIndex];
    if (!c)
        return;
    try {
        const path = c.sync === "queued" ? "cloud/cancel" : c.sync === "failed" || c.sync === "unavailable" ? "cloud/retry" : "cloud/upload";
        const r = await api(path, { id: c.id });
        toast(r.ok || "Done");
        await loadClips();
    }
    catch (e) {
        toast(e.message, true);
    }
}
function stopMedia() {
    const v = $("player-media").querySelector("video");
    if (v) {
        v.pause();
        v.removeAttribute("src");
        v.load();
    }
}
// ---------------- capture + control ----------------
let lastStatus;
/** The capture bar on the Clips page: clip the last moments, record, or take a screenshot from your phone. */
function paintCapture(s) {
    const st = $("cap-state");
    if (s.recording) {
        text(st, "● Recording " + fmtDur(s.elapsedMs / 1000));
        st.className = "cap-state rec";
    }
    else if (s.replay) {
        text(st, "Instant replay on · " + s.replayAvailable + " s ready");
        st.className = "cap-state live";
    }
    else {
        text(st, "Instant replay is off");
        st.className = "cap-state";
    }
    const clip = $("cap-clip");
    text(clip, s.replay ? "⚡ Clip it (last " + Math.min(s.replayAvailable || s.replaySeconds, s.replaySeconds) + " s)" : "Turn on instant replay");
    clip.disabled = !s.ffmpeg;
    const rec = $("cap-rec");
    text(rec, s.recording ? "■ Stop recording" : "● Record");
    rec.disabled = !s.ffmpeg;
}
/** After saving, look for the new clip a few times (the desktop app needs a moment to write it). */
function watchForNewClip() {
    knownIds = new Set(allClips.map(c => c.id));
    for (const ms of [1000, 3000, 6000, 10000, 15000])
        window.setTimeout(() => loadClips().catch(() => undefined), ms);
}
/** Runs an action on the PC and shows exactly what the desktop app answered. */
async function action(path, busy, then) {
    if (busy)
        busy.disabled = true;
    try {
        const r = await api(path, {});
        toast(r.ok || "Done");
        if (then)
            then();
    }
    catch (e) {
        toast(e.message, true);
    }
    finally {
        if (busy)
            busy.disabled = false;
        refreshStatus().catch(() => undefined);
    }
}
function clipIt() {
    if (!lastStatus)
        return;
    const b = $("cap-clip");
    if (!lastStatus.replay) {
        action("replay/toggle", b);
        return;
    }
    action("replay/save", b, watchForNewClip);
}
async function refreshStatus() {
    const s = await api("status");
    lastStatus = s;
    paintCapture(s);
    const rec = $("rec-state");
    text(rec, s.recording ? "● REC " + fmtDur(s.elapsedMs / 1000) : s.recordingState === "IDLE" ? "Not recording" : s.recordingState.toLowerCase() + "…");
    rec.className = s.recording ? "big rec" : "big";
    text($("rec-btn"), s.recording ? "Stop recording" : "Start recording");
    $("rec-btn").disabled = !s.ffmpeg;
    text($("replay-state"), s.replay ? s.replayAvailable + " / " + s.replaySeconds + " s buffered" : "Off");
    text($("replay-btn"), s.replay ? "Turn replay off" : "Turn replay on");
    $("save-btn").disabled = !s.replay;
    text($("sys"), `Zyclipra ${s.version} · ${s.clips} clips (${fmtBytes(s.storageBytes)}) · app memory ${s.memoryMb} MB · CPU ${s.cpuLoad}% · `
        + (s.audioControl ? "audio control on" : "audio control unavailable") + (s.micProcessing ? " · mic processing on" : ""));
}
async function refreshLevels() {
    const lv = await api("levels");
    for (const [id, peak] of Object.entries(lv)) {
        const bar = document.getElementById("lv-" + id);
        if (bar) {
            const db = 20 * Math.log10(Math.max(peak, 1e-6));
            bar.style.width = Math.max(0, Math.min(100, ((db + 60) / 60) * 100)) + "%";
        }
    }
}
async function loadMixer() {
    const chans = await api("mixer");
    const box = $("mixer");
    box.replaceChildren();
    for (const c of chans) {
        const row = el("div", "chan");
        const name = el("span", "chan-name", c.name);
        const slider = document.createElement("input");
        slider.type = "range";
        slider.min = "0";
        slider.max = "100";
        slider.value = String(Math.round(c.volume * 100));
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
async function loadSounds() {
    const sounds = await api("sounds");
    const box = $("sounds");
    box.replaceChildren();
    if (sounds.length === 0) {
        box.appendChild(el("p", "muted", "Import sounds in the desktop app."));
        return;
    }
    for (const s of sounds) {
        const b = el("button", s.playing ? "pad playing" : "pad", s.name);
        b.title = s.category;
        b.addEventListener("click", () => api("sounds/play", { id: s.id }).then(() => window.setTimeout(loadSounds, 200)).catch(e => toast(e.message, true)));
        box.appendChild(b);
    }
}
// ---------------- cloud & sync ----------------
const PREFS = ["autoUpload", "uploadClips", "uploadRecordings", "uploadScreenshots"];
async function loadCloud() {
    const c = await api("cloud");
    const avatar = $("cloud-avatar");
    avatar.hidden = !c.hasAvatar;
    if (c.hasAvatar && !avatar.src)
        avatar.src = "/api/cloud/avatar";
    if (!c.available) {
        text($("cloud-account"), "Google Drive isn't available in this version.");
        return;
    }
    text($("cloud-title"), c.connected ? (c.name || "Google Drive") : "Google Drive");
    text($("cloud-account"), c.connected ? "● Connected as " + c.email : c.needsReauth
        ? "Google asked Zyclipra to sign in again. Open Zyclipra on your PC › Settings › Cloud & Sync › Reconnect."
        : "Not connected. Connect your Google account in the Zyclipra app on your PC (Settings › Cloud & Sync).");
    $("cloud-account").className = c.connected ? "ok-text" : c.needsReauth ? "warn-text" : "muted";
    const folder = $("cloud-folder");
    folder.hidden = !c.connected;
    folder.href = c.folderUrl;
    text($("cloud-note"), "Clips on Google Drive go to My Drive › " + c.folder + " › Clips, Recordings and Screenshots. "
        + (c.keepLocal ? "Clips also stay on your PC." : "After a verified upload, clips are moved off your PC (set in the app)."));
    for (const p of PREFS) {
        const box = $("pref-" + p);
        box.checked = c[p];
        box.disabled = p !== "autoUpload" && !c.autoUpload;
    }
    const n = (k) => c.counts[k] || 0;
    text($("cloud-summary"), `${n("synced")} synced · ${n("queued") + n("uploading")} waiting · ${n("failed")} failed`
        + (n("unavailable") ? ` · ${n("unavailable")} missing from Drive` : "")
        + " · last upload " + (c.lastSyncAt ? ago(c.lastSyncAt) : "never") + (c.problem ? " · " + c.problem : ""));
    $("retry-all").disabled = n("failed") + n("unavailable") === 0;
    const q = $("cloud-queue");
    q.replaceChildren();
    if (c.queue.length === 0) {
        q.appendChild(el("p", "muted small", "Nothing waiting."));
        return;
    }
    for (const i of c.queue) {
        const row = el("div", "q-row");
        const info = el("div", "grow");
        info.append(el("strong", "", i.title), el("span", "muted small q-state sync-text-" + i.state, (SYNC_LABEL[i.state] || i.state) + (i.state === "uploading" ? " " + fmtBytes(i.done) + " / " + fmtBytes(i.size) : "") + (i.error ? " – " + i.error : "")));
        row.appendChild(info);
        if (i.state === "uploading") {
            const bar = el("progress");
            bar.max = Math.max(1, i.size);
            bar.value = i.done;
            row.appendChild(bar);
        }
        if (i.state === "failed" || i.state === "unavailable") {
            const b = el("button", "", "Retry");
            b.addEventListener("click", () => api("cloud/retry", { id: i.id }).then(r => { toast(r.ok || ""); loadCloud(); }).catch(e => toast(e.message, true)));
            row.appendChild(b);
        }
        if (i.state === "queued" || i.state === "uploading" || i.state === "failed") {
            const b = el("button", "ghost", "Cancel");
            b.addEventListener("click", () => api("cloud/cancel", { id: i.id }).then(r => { toast(r.ok || ""); loadCloud(); }).catch(e => toast(e.message, true)));
            row.appendChild(b);
        }
        q.appendChild(row);
    }
}
// ---------------- account ----------------
async function loadAccount() {
    const a = await api("account");
    text($("acc-user"), "Signed in as " + a.username);
    const h = a.hosting;
    const where = h.mode === "public" ? "the internet through your own HTTPS reverse proxy (" + h.publicUrl + ")"
        : ["this PC", h.lan ? "your home network" : "", h.vpn ? "your Tailscale network" : ""].filter(x => x).join(", ");
    text($("acc-hosting"), "Hosting: reachable from " + where + ". Encrypted (HTTPS). Change this in the Zyclipra app › Settings › Hosting.");
    api("cloud").then(c => text($("acc-cloud"), "Google Drive: " + (c.connected ? "connected as " + c.email : c.needsReauth ? "needs reconnecting" : "not connected")))
        .catch(() => undefined);
    $("pw-new").minLength = a.minPasswordLength;
    $("pw-new").placeholder = "at least " + a.minPasswordLength + " characters";
    $("user-new").value = a.username;
    const box = $("sessions");
    box.replaceChildren();
    for (const s of a.sessions) {
        const row = el("div", "q-row");
        const info = el("div", "grow");
        info.append(el("strong", "", s.address + (s.current ? " (this browser)" : "")), el("span", "muted small q-state", "signed in " + ago(s.created) + " · last used " + ago(s.lastSeen) + (s.agent ? " · " + s.agent.slice(0, 80) : "")));
        row.appendChild(info);
        if (!s.current) {
            const b = el("button", "", "Sign out");
            b.addEventListener("click", () => api("account/revoke", { id: s.id }).then(r => { toast(r.ok || ""); loadAccount(); }).catch(e => toast(e.message, true)));
            row.appendChild(b);
        }
        box.appendChild(row);
    }
}
async function changePassword(ev) {
    ev.preventDefault();
    const f = (id) => $(id);
    try {
        const r = await api("account/password", { current: f("pw-current").value, password: f("pw-new").value, confirm: f("pw-confirm").value });
        toast(r.ok || "Password changed");
        for (const id of ["pw-current", "pw-new", "pw-confirm"])
            f(id).value = "";
        loadAccount();
    }
    catch (e) {
        toast(e.message, true);
    }
}
async function changeUsername(ev) {
    ev.preventDefault();
    const f = (id) => $(id);
    try {
        const r = await api("account/username", { current: f("user-pw").value, username: f("user-new").value.trim() });
        toast(r.ok || "Username changed");
        f("user-pw").value = "";
        loadAccount();
    }
    catch (e) {
        toast(e.message, true);
    }
}
// ---------------- start ----------------
function start() {
    refreshStatus().catch(() => undefined);
    loadMixer().catch(() => undefined);
    loadClips().catch(() => undefined);
    loadSounds().catch(() => undefined);
    stopPolling();
    let ticks = 0;
    timer = window.setInterval(() => {
        if (document.hidden)
            return;
        ticks++;
        refreshStatus().catch(() => undefined);
        if (view === "control")
            loadSounds().catch(() => undefined);
        else if (view === "cloud")
            loadCloud().catch(() => undefined);
        else if (view === "clips" && ticks % 5 === 0)
            loadClips().catch(() => undefined); // new clips and sync states within ~10 s
    }, 2000);
    levelTimer = window.setInterval(() => { if (!document.hidden && view === "control")
        refreshLevels().catch(() => undefined); }, 250);
}
document.addEventListener("DOMContentLoaded", () => {
    $("login-form").addEventListener("submit", login);
    for (const v of VIEWS)
        $("tab-" + v).addEventListener("click", () => showView(v));
    for (const id of ["search", "game", "kind", "sort"])
        $(id).addEventListener("input", renderClips);
    const d = $("player");
    d.addEventListener("close", stopMedia);
    d.addEventListener("click", e => { if (e.target === d)
        d.close(); });
    $("player-close").addEventListener("click", () => d.close());
    $("player-prev").addEventListener("click", () => openPlayer(openIndex - 1));
    $("player-next").addEventListener("click", () => openPlayer(openIndex + 1));
    $("player-upload").addEventListener("click", playerSyncAction);
    d.addEventListener("keydown", e => {
        if (e.key === "ArrowLeft" && !(e.target instanceof HTMLVideoElement))
            openPlayer(openIndex - 1);
        if (e.key === "ArrowRight" && !(e.target instanceof HTMLVideoElement))
            openPlayer(openIndex + 1);
    });
    $("cap-clip").addEventListener("click", clipIt);
    $("cap-rec").addEventListener("click", () => { const was = lastStatus?.recording; action("recording/toggle", $("cap-rec"), was ? watchForNewClip : undefined); });
    $("cap-shot").addEventListener("click", () => action("screenshot", $("cap-shot"), watchForNewClip));
    $("rec-btn").addEventListener("click", () => action("recording/toggle", $("rec-btn")));
    $("replay-btn").addEventListener("click", () => action("replay/toggle", $("replay-btn")));
    $("save-btn").addEventListener("click", () => action("replay/save", $("save-btn"), watchForNewClip));
    $("shot-btn").addEventListener("click", () => action("screenshot", $("shot-btn"), watchForNewClip));
    $("stop-sounds").addEventListener("click", () => action("sounds/stop", null));
    for (const p of PREFS)
        $("pref-" + p).addEventListener("change", () => {
            api("cloud/settings", { [p]: $("pref-" + p).checked }).then(() => { toast("Saved"); loadCloud(); }).catch(e => { toast(e.message, true); loadCloud(); });
        });
    $("retry-all").addEventListener("click", () => api("cloud/retry", {}).then(r => { toast(r.ok || ""); loadCloud(); }).catch(e => toast(e.message, true)));
    $("pw-form").addEventListener("submit", changePassword);
    $("user-form").addEventListener("submit", changeUsername);
    $("logout").addEventListener("click", () => api("logout", {}).finally(showLogin));
    api("setup").then(s => {
        if (!s.configured)
            text($("login-hint"), "The dashboard isn't set up yet. In Zyclipra on your PC, open Settings › Hosting and create a username and password.");
    }).catch(() => undefined);
    api("status").then(showApp).catch(() => undefined);
});
