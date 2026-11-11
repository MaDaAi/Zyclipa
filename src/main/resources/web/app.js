"use strict";
// Zyclipra dashboard (served by the Zyclipra app on your PC).
// Security: every call sends the X-Zyclipra header (CSRF protection) and relies on an HttpOnly session cookie –
// nothing about the sign-in is kept in localStorage. Commands carry a random X-Command-Id so a double tap or a
// retried request runs only once on the PC. Google tokens never reach this page.
const $ = (id) => document.getElementById(id);
let conn = "connecting";
function setConn(c) {
    if (c === conn)
        return;
    conn = c;
    const label = { connecting: "Connecting…", online: "PC online", interrupted: "Connection interrupted", signin: "Sign-in required" };
    text($("conn-text"), label[c]);
    $("conn").className = "conn conn-" + c;
    for (const b of document.querySelectorAll("[data-live]"))
        b.disabled = c !== "online" || b.dataset.off === "1";
}
async function api(path, body, commandId) {
    let res;
    try {
        const headers = { "X-Zyclipra": "1", "Content-Type": "application/json" };
        if (commandId)
            headers["X-Command-Id"] = commandId;
        res = await fetch("/api/" + path, { method: body ? "POST" : "GET", headers, body: body ? JSON.stringify(body) : undefined, credentials: "same-origin" });
    }
    catch {
        setConn("interrupted");
        throw new Error("Can't reach your PC. Is Zyclipra still running?");
    }
    if (res.status === 401 && path !== "login") {
        showLogin();
        throw new Error("Please sign in.");
    }
    const data = await res.json();
    setConn("online");
    if (!res.ok)
        throw new Error(data.error || res.statusText);
    return data;
}
function newId() {
    const c = window.crypto;
    if (typeof c.randomUUID === "function")
        return c.randomUUID();
    const a = new Uint8Array(16);
    c.getRandomValues(a);
    return Array.from(a, b => b.toString(16).padStart(2, "0")).join("");
}
/** Sends a command to the PC. The promise resolves with what the PC actually did (never an early "OK"). */
function command(action, args = {}) { return api(action, args, newId()); }
/** Runs a command for a button: busy state while it runs, then the PC's own message. */
async function run(action, args, button, after) {
    if (button) {
        button.disabled = true;
        button.classList.add("busy");
    }
    try {
        const r = await command(action, args);
        toast(r.ok || "Done");
        if (after)
            after(r);
    }
    catch (e) {
        toast(e.message, true);
    }
    finally {
        if (button) {
            button.classList.remove("busy");
            button.disabled = conn !== "online";
        }
        refreshStatus().catch(() => undefined);
    }
}
// ---------------- helpers ----------------
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
    const s = Math.max(0, Math.round(sec));
    const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60;
    return (h ? h + ":" + String(m).padStart(2, "0") : String(m)) + ":" + String(s % 60).padStart(2, "0");
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
        return m + " min ago";
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
let toastTimer = 0;
function toast(msg, error = false) {
    const t = $("toast");
    text(t, msg);
    t.className = "toast show" + (error ? " error" : "");
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => (t.className = "toast"), 3500);
}
const SYNC_LABEL = {
    local: "Local only", queued: "Queued for Google Drive", uploading: "Uploading to Google Drive…", synced: "On Google Drive",
    failed: "Upload failed", unavailable: "Cloud file unavailable", cloudOnly: "Only on Google Drive",
};
/**
 * A 0–300 % gain slider with marks at 0/100/200/300 and an orange fill above 100 %.
 * onChange runs while dragging (throttled by the caller); onCommit when the finger/mouse lets go.
 */
function gainSlider(host, label, onChange, onCommit) {
    host.replaceChildren();
    const top = el("div", "gain-top");
    const name = el("span", "gain-name", label);
    const pct = el("output", "gain-pct", "100%");
    top.append(name, pct);
    const range = el("input");
    range.type = "range";
    range.min = "0";
    range.max = "300";
    range.step = "1";
    range.setAttribute("aria-label", label + " volume, 0 to 300 percent");
    range.setAttribute("list", "gain-ticks");
    const marks = el("div", "gain-marks");
    for (const m of ["0%", "100%", "200%", "300%"])
        marks.appendChild(el("span", "", m));
    host.append(top, range, marks);
    let dragging = false;
    const paint = () => {
        const v = Number(range.value);
        text(pct, v + "%");
        pct.className = "gain-pct" + (v > 100 ? " amp" : "");
        range.style.setProperty("--fill", (v / 3) + "%");
        range.classList.toggle("amp", v > 100);
    };
    range.addEventListener("pointerdown", () => (dragging = true));
    range.addEventListener("input", () => {
        // Rests on 100 % (as recorded) when you pass close to it.
        if (Math.abs(Number(range.value) - 100) <= 3)
            range.value = "100";
        paint();
        onChange(Number(range.value) / 100);
    });
    range.addEventListener("change", () => { dragging = false; onCommit(Number(range.value) / 100); });
    return (g) => { if (!dragging && document.activeElement !== range) {
        range.value = String(Math.round(g * 100));
        paint();
    } };
}
/** At most one call per {@code ms}; the last value always gets through. */
function throttle(fn, ms) {
    let last = 0, timer = 0, pending = 0;
    return (g) => {
        pending = g;
        const now = Date.now();
        if (now - last >= ms) {
            last = now;
            fn(g);
        }
        else {
            window.clearTimeout(timer);
            timer = window.setTimeout(() => { last = Date.now(); fn(pending); }, ms - (now - last));
        }
    };
}
// ---------------- sign-in and views ----------------
const VIEWS = ["home", "clips", "sounds", "settings"];
let view = "home";
function showLogin() {
    $("login").hidden = false;
    for (const v of VIEWS)
        $(v + "-view").hidden = true;
    $("nav").hidden = true;
    $("conn").hidden = true;
    setConn("signin");
    stopPolling();
}
function showApp() {
    $("login").hidden = true;
    $("nav").hidden = false;
    $("conn").hidden = false;
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
function showView(v) {
    view = v;
    for (const x of VIEWS) {
        $(x + "-view").hidden = x !== v;
        $("tab-" + x).className = x === v ? "tab on" : "tab";
        $("tab-" + x).setAttribute("aria-current", x === v ? "page" : "false");
    }
    window.scrollTo(0, 0);
    if (v === "home") {
        refreshStatus().catch(() => undefined);
        loadClips().catch(() => undefined);
    }
    if (v === "clips")
        loadClips().catch(() => undefined);
    if (v === "sounds") {
        loadSounds().catch(e => toast(e.message, true));
        loadOutputs().catch(() => undefined);
    }
    if (v === "settings") {
        loadCloud().catch(e => toast(e.message, true));
        loadMixer().catch(() => undefined);
        loadAccount().catch(() => undefined);
    }
}
let timer;
function stopPolling() {
    if (timer)
        window.clearInterval(timer);
    timer = undefined;
}
// ---------------- dashboard ----------------
let lastStatus;
let statusAt = 0;
async function refreshStatus() {
    const s = await api("status");
    lastStatus = s;
    statusAt = Date.now();
    paintStatus();
}
/** Called often: the recording timer ticks between status updates. */
function paintStatus() {
    const s = lastStatus;
    if (!s)
        return;
    const clip = $("clip-it");
    clip.dataset.off = s.ffmpeg ? "0" : "1";
    clip.disabled = conn !== "online" || !s.ffmpeg || clip.classList.contains("busy");
    text($("clip-it-sub"), !s.ffmpeg ? "FFmpeg is missing on the PC" : s.replay
        ? "Saves the last " + Math.min(s.replayAvailable || 0, s.replaySeconds) + " s (of " + s.replaySeconds + " s)"
        : "Instant replay is off – tap to turn it on");
    clip.classList.toggle("off", !s.replay);
    const rec = $("rec-btn");
    rec.classList.toggle("on", s.recording);
    text($("rec-icon"), s.recording ? "■" : "●");
    text($("rec-text"), s.recording ? "Stop" : s.recordingState === "IDLE" ? "Record" : s.recordingState.toLowerCase() + "…");
    const elapsed = s.recording ? s.elapsedMs + (Date.now() - statusAt) : 0;
    text($("rec-timer"), s.recording ? fmtDur(elapsed / 1000) : "");
    rec.dataset.off = s.ffmpeg ? "0" : "1";
    const sw = $("replay-switch");
    if (!sw.matches(":focus"))
        sw.checked = s.replay;
    text($("replay-state"), s.replay ? "On · " + Math.min(s.replayAvailable, s.replaySeconds) + " of " + s.replaySeconds + " s buffered" : "Off");
    const len = $("replay-length");
    if (document.activeElement !== len)
        len.value = String(s.replaySeconds);
    text($("sys"), `Zyclipra ${s.version} · ${s.clips} clips (${fmtBytes(s.storageBytes)}) · CPU ${s.cpuLoad}%`);
}
function clipIt() {
    const b = $("clip-it");
    if (!lastStatus)
        return;
    if (!lastStatus.replay) {
        run("replay/on", {}, b);
        return;
    }
    run("replay/save", {}, b, watchForNewClip);
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
    renderRecent();
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
function watchForNewClip() {
    knownIds = new Set(allClips.map(c => c.id));
    for (const ms of [800, 2500, 5000, 9000, 15000])
        window.setTimeout(() => loadClips().catch(() => undefined), ms);
}
function renderRecent() {
    const box = $("recent");
    box.replaceChildren();
    const latest = [...allClips].sort((a, b) => b.createdAt - a.createdAt).slice(0, 4);
    if (latest.length === 0) {
        box.appendChild(el("p", "muted small", "No clips yet – press Clip it."));
        return;
    }
    for (const c of latest) {
        const b = card(c, () => { showView("clips"); const i = shown.findIndex(x => x.id === c.id); if (i >= 0)
            openPlayer(i); });
        box.appendChild(b);
    }
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
        new: (a, b) => b.createdAt - a.createdAt, old: (a, b) => a.createdAt - b.createdAt,
        long: (a, b) => b.durationSec - a.durationSec, big: (a, b) => b.sizeBytes - a.sizeBytes,
    };
    return list.sort(by[sort] || by.new);
}
function renderClips() {
    shown = filtered();
    const total = allClips.reduce((s, c) => s + c.sizeBytes, 0);
    const synced = allClips.filter(c => c.sync === "synced").length;
    text($("clip-stats"), allClips.length === 0 ? "" : `${allClips.length} clips · ${fmtBytes(total)} on your PC · ${synced} on Google Drive`
        + (shown.length !== allClips.length ? ` · showing ${shown.length}` : ""));
    const box = $("clip-groups");
    box.replaceChildren();
    if (shown.length === 0) {
        box.appendChild(el("p", "empty muted", allClips.length === 0 ? "No clips yet. Press Clip it (with instant replay on) or record." : "No clips match."));
        return;
    }
    const sort = $("sort").value;
    const byTime = sort === "new" || sort === "old";
    let grid = null;
    let lastDay = "";
    shown.forEach((c, i) => {
        const day = byTime ? dayLabel(c.createdAt) : "";
        if (!grid || day !== lastDay) {
            if (byTime)
                box.appendChild(el("h3", "day", day));
            grid = el("div", "clip-grid");
            box.appendChild(grid);
            lastDay = day;
        }
        grid.appendChild(card(c, () => openPlayer(i)));
    });
}
function syncBadge(state) {
    const b = el("span", "sync sync-" + state, state === "synced" ? "☁ ✓" : state === "uploading" ? "☁ ↑" : state === "queued" ? "☁ …" : state === "failed" ? "☁ !" : "☁ ✕");
    b.title = SYNC_LABEL[state] || state;
    return b;
}
function card(c, open) {
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
    b.addEventListener("click", open);
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
        v.setAttribute("playsinline", "");
        v.addEventListener("error", () => media.replaceChildren(el("p", "muted", "This browser can't play this file here – use Download.")));
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
        parts.push(c.audioTracks + " sound tracks (Download has them all)");
    text($("player-meta"), parts.join(" · "));
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
function playerSyncAction() {
    const c = shown[openIndex];
    if (!c)
        return;
    const action = c.sync === "queued" ? "cloud/cancel" : c.sync === "failed" || c.sync === "unavailable" ? "cloud/retry" : "cloud/upload";
    run(action, { id: c.id }, $("player-upload"), () => loadClips().catch(() => undefined));
}
function stopMedia() {
    const v = $("player-media").querySelector("video");
    if (v) {
        v.pause();
        v.removeAttribute("src");
        v.load();
    }
}
// ---------------- soundboard ----------------
let board;
let page = "All";
let editing = false;
let editId = "";
let setMasterShown = () => undefined;
let setEditGainShown = () => undefined;
async function loadSounds() {
    const b = await api("sounds");
    const sig = (x) => x ? JSON.stringify([x.pages, x.sounds.map(s => [s.id, s.name, s.category, s.color, s.icon])]) : "";
    const rebuild = sig(b) !== sig(board);
    board = b;
    setMasterShown(b.master);
    const lim = $("sb-limit");
    if (document.activeElement !== lim)
        lim.checked = b.limitTo100;
    if (rebuild)
        renderPads();
    else
        paintPads();
    if (editId) {
        const s = b.sounds.find(x => x.id === editId);
        if (s)
            setEditGainShown(s.volume);
    }
    const out = $("sb-output");
    if (document.activeElement !== out && out.options.length > 0)
        out.value = b.output;
}
async function loadOutputs() {
    const list = await api("sounds/outputs");
    const out = $("sb-output");
    out.replaceChildren(new Option("Windows default playback device", ""), ...list.map(d => new Option(d, d)));
    if (board)
        out.value = board.output;
}
function visibleSounds() {
    return board ? board.sounds.filter(s => page === "All" || s.category === page) : [];
}
function renderPads() {
    if (!board)
        return;
    const chips = $("sb-pages");
    chips.replaceChildren();
    if (!["All", ...board.pages].includes(page))
        page = "All";
    for (const p of ["All", ...board.pages]) {
        const n = p === "All" ? board.sounds.length : board.sounds.filter(s => s.category === p).length;
        const c = el("button", "chip" + (p === page ? " on" : ""), p + " (" + n + ")");
        c.setAttribute("role", "tab");
        c.setAttribute("aria-selected", String(p === page));
        c.addEventListener("click", () => { page = p; renderPads(); });
        chips.appendChild(c);
    }
    text($("sb-hint"), editing ? "Edit mode: tap a sound to change its volume, colour, icon, page or place. On a computer you can also drag sounds around." : "");
    const box = $("pads");
    box.replaceChildren();
    const list = visibleSounds();
    if (list.length === 0) {
        box.appendChild(el("p", "empty muted", board.sounds.length === 0 ? "No sounds yet. Add MP3 or WAV files in the Zyclipra app (Soundboard › Add sounds)." : "No sounds on this page."));
        return;
    }
    for (const s of list)
        box.appendChild(pad(s));
    paintPads();
}
function pad(s) {
    const b = el("button", "sound");
    b.dataset.id = s.id;
    if (s.color)
        b.style.setProperty("--pad", s.color);
    const icon = el("span", "sound-icon", s.icon || "♪");
    const name = el("span", "sound-name", s.name);
    const meta = el("span", "sound-meta", (s.volume !== 1 ? Math.round(s.volume * 100) + "% · " : "") + fmtDur(s.duration));
    const bar = el("span", "sound-progress");
    const stop = el("span", "sound-stop", "■");
    stop.setAttribute("role", "button");
    stop.setAttribute("aria-label", "Stop " + s.name);
    b.append(icon, name, meta, bar, stop);
    b.setAttribute("aria-label", "Play " + s.name + " on the PC");
    b.addEventListener("click", ev => {
        if (editing) {
            openEditor(s.id);
            return;
        }
        if (ev.target.classList.contains("sound-stop")) {
            soundCommand("sounds/stop", s.id, b);
            return;
        }
        soundCommand("sounds/play", s.id, b);
    });
    b.draggable = true;
    b.addEventListener("dragstart", e => { if (!editing) {
        e.preventDefault();
        return;
    } e.dataTransfer?.setData("text/plain", s.id); });
    b.addEventListener("dragover", e => { if (editing)
        e.preventDefault(); });
    b.addEventListener("drop", e => {
        e.preventDefault();
        const from = e.dataTransfer?.getData("text/plain");
        if (!from || from === s.id || !board)
            return;
        const to = board.sounds.findIndex(x => x.id === s.id);
        run("sounds/move", { id: from, index: to }, null, () => loadSounds().catch(() => undefined));
    });
    return b;
}
/** Instant feedback on tap, then the PC's answer: the button lights up only when the PC confirms it's playing. */
async function soundCommand(action, id, b) {
    b.classList.add("pressed");
    window.setTimeout(() => b.classList.remove("pressed"), 180);
    try {
        await command(action, { id });
        if (board) {
            const s = board.sounds.find(x => x.id === id);
            if (s) {
                s.playing = action === "sounds/play";
                s.progress = 0;
            }
        }
        paintPads();
        window.setTimeout(() => loadSounds().catch(() => undefined), 250);
    }
    catch (e) {
        toast(e.message, true);
        b.classList.add("failed");
        window.setTimeout(() => b.classList.remove("failed"), 900);
    }
}
function paintPads() {
    if (!board)
        return;
    for (const b of document.querySelectorAll("#pads .sound")) {
        const s = board.sounds.find(x => x.id === b.dataset.id);
        if (!s)
            continue;
        b.classList.toggle("playing", s.playing);
        b.querySelector(".sound-progress").style.width = (s.playing ? Math.max(2, s.progress * 100) : 0) + "%";
    }
}
function openEditor(id) {
    const s = board?.sounds.find(x => x.id === id);
    if (!s || !board)
        return;
    editId = id;
    text($("se-title"), (s.icon ? s.icon + " " : "") + s.name);
    const send = throttle(g => command("sounds/volume", { id, gain: g }).catch(e => toast(e.message, true)), 150);
    setEditGainShown = gainSlider($("se-gain"), "Volume", send, g => command("sounds/volume", { id, gain: g }).then(r => toast(r.ok || "Saved")).catch(e => toast(e.message, true)));
    setEditGainShown(s.volume);
    const sel = $("se-page");
    sel.replaceChildren(...board.pages.map(p => new Option(p, p)));
    sel.value = s.category;
    $("se-color").value = s.color || "#5B6CF9";
    $("se-icon").value = s.icon;
    $("sound-edit").showModal();
}
function moveEdited(delta) {
    if (!board || !editId)
        return;
    const vis = visibleSounds();
    const i = vis.findIndex(x => x.id === editId);
    const target = vis[i + delta];
    if (!target)
        return;
    const to = board.sounds.findIndex(x => x.id === target.id);
    run("sounds/move", { id: editId, index: to }, null, () => loadSounds().catch(() => undefined));
}
// ---------------- settings: cloud, mixer, account ----------------
const PREFS = ["autoUpload", "uploadClips", "uploadRecordings", "uploadScreenshots"];
async function loadCloud() {
    const c = await api("cloud");
    const avatar = $("cloud-avatar");
    avatar.hidden = !c.hasAvatar;
    if (c.hasAvatar && !avatar.src)
        avatar.src = "/api/cloud/avatar";
    text($("cloud-title"), c.connected ? (c.name || "Google Drive") : "Google Drive");
    text($("cloud-account"), c.connected ? "● Connected as " + c.email : !c.configured ? "This copy of Zyclipra was built without Google sign-in."
        : (c.needsReauth ? "Google asked Zyclipra to sign in again. " : "Not connected. ") + "The Google window opens in the browser on your PC.");
    $("cloud-account").className = c.connected ? "ok-text" : c.needsReauth ? "warn-text" : "muted";
    $("cloud-connect").hidden = c.connected || !c.configured;
    text($("cloud-connect"), c.needsReauth ? "Sign in with Google again" : "Sign in with Google");
    $("cloud-disconnect").hidden = !c.connected;
    const folder = $("cloud-folder");
    folder.hidden = !c.connected;
    folder.href = c.folderUrl;
    text($("cloud-note"), "Backups go to My Drive › " + c.folder + " › Clips, Recordings, Screenshots. " + (c.keepLocal ? "Clips also stay on your PC." : "Uploaded clips are moved off your PC (set in the app)."));
    for (const p of PREFS) {
        const box = $("pref-" + p);
        box.checked = c[p];
        box.disabled = p !== "autoUpload" && !c.autoUpload;
    }
    const n = (k) => c.counts[k] || 0;
    text($("cloud-summary"), `${n("synced")} synced · ${n("queued") + n("uploading")} waiting · ${n("failed")} failed`
        + (n("unavailable") ? ` · ${n("unavailable")} missing from Drive` : "") + " · last upload " + (c.lastSyncAt ? ago(c.lastSyncAt) : "never") + (c.problem ? " · " + c.problem : ""));
    $("retry-all").disabled = n("failed") + n("unavailable") === 0;
    const q = $("cloud-queue");
    q.replaceChildren();
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
            b.addEventListener("click", () => run("cloud/retry", { id: i.id }, b, () => loadCloud().catch(() => undefined)));
            row.appendChild(b);
        }
        if (i.state === "queued" || i.state === "uploading" || i.state === "failed") {
            const b = el("button", "ghost", "Cancel");
            b.addEventListener("click", () => run("cloud/cancel", { id: i.id }, b, () => loadCloud().catch(() => undefined)));
            row.appendChild(b);
        }
        q.appendChild(row);
    }
}
async function loadMixer() {
    const chans = await api("mixer");
    const box = $("mixer");
    box.replaceChildren();
    for (const c of chans) {
        const row = el("div", "chan");
        const name = el("span", "chan-name", c.name);
        const slider = el("input");
        slider.type = "range";
        slider.min = "0";
        slider.max = "100";
        slider.value = String(Math.round(c.volume * 100));
        slider.setAttribute("aria-label", c.name + " volume");
        const mute = el("button", c.muted ? "mute on" : "mute", c.muted ? "Muted" : "Mute");
        const send = () => command("mixer", { channel: c.id, volume: Number(slider.value) / 100, muted: c.muted }).catch(e => toast(e.message, true));
        slider.addEventListener("change", send);
        mute.addEventListener("click", () => { c.muted = !c.muted; mute.className = c.muted ? "mute on" : "mute"; text(mute, c.muted ? "Muted" : "Mute"); send(); });
        row.append(name, slider, mute);
        box.appendChild(row);
    }
}
async function loadAccount() {
    const a = await api("account");
    text($("acc-user"), "Signed in as " + a.username);
    const h = a.hosting;
    const where = h.mode === "public" ? "the internet through your HTTPS reverse proxy (" + h.publicUrl + ")"
        : ["this PC", h.lan ? "your home network" : "", h.vpn ? "your Tailscale network" : ""].filter(x => x).join(", ");
    text($("acc-hosting"), "Reachable from " + where + ". Encrypted (HTTPS). Change this in Zyclipra › Settings › Hosting.");
    $("pw-new").minLength = a.minPasswordLength;
    $("pw-new").placeholder = "at least " + a.minPasswordLength + " characters";
    $("user-new").value = a.username;
    const box = $("sessions");
    box.replaceChildren();
    for (const s of a.sessions) {
        const row = el("div", "q-row");
        const info = el("div", "grow");
        info.append(el("strong", "", s.address + (s.current ? " (this browser)" : "")), el("span", "muted small q-state", "signed in " + ago(s.created) + " · last used " + ago(s.lastSeen) + (s.agent ? " · " + s.agent.slice(0, 60) : "")));
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
    loadClips().catch(() => undefined);
    stopPolling();
    let ticks = 0;
    timer = window.setInterval(() => {
        paintStatus();
        if (document.hidden)
            return;
        ticks++;
        if (ticks % 4 === 0)
            refreshStatus().catch(() => undefined); // every 2 s
        if (view === "sounds" && ticks % 2 === 0)
            loadSounds().catch(() => undefined); // every 1 s: playing state, progress, volumes
        if (view === "settings" && ticks % 6 === 0)
            loadCloud().catch(() => undefined);
        if ((view === "clips" || view === "home") && ticks % 20 === 0)
            loadClips().catch(() => undefined);
    }, 500);
}
document.addEventListener("DOMContentLoaded", () => {
    $("login-form").addEventListener("submit", login);
    // Updates pause while the page is in the background (saves phone battery); catch up as soon as it's visible.
    document.addEventListener("visibilitychange", () => {
        if (document.hidden || $("nav").hidden)
            return;
        refreshStatus().catch(() => undefined);
        if (view === "sounds")
            loadSounds().catch(() => undefined);
        if (view === "settings")
            loadCloud().catch(() => undefined);
        if (view === "clips" || view === "home")
            loadClips().catch(() => undefined);
    });
    for (const v of VIEWS)
        $("tab-" + v).addEventListener("click", () => showView(v));
    $("see-clips").addEventListener("click", () => showView("clips"));
    for (const id of ["search", "game", "kind", "sort"])
        $(id).addEventListener("input", renderClips);
    // dashboard
    $("clip-it").dataset.live = "1";
    $("rec-btn").dataset.live = "1";
    $("shot-btn").dataset.live = "1";
    $("clip-it").addEventListener("click", clipIt);
    $("rec-btn").addEventListener("click", () => {
        const was = lastStatus?.recording;
        run(was ? "recording/stop" : "recording/start", {}, $("rec-btn"), was ? watchForNewClip : undefined);
    });
    $("shot-btn").addEventListener("click", () => run("screenshot", {}, $("shot-btn"), watchForNewClip));
    $("replay-switch").addEventListener("change", () => {
        const on = $("replay-switch").checked;
        run(on ? "replay/on" : "replay/off", {}, null);
    });
    $("replay-length").addEventListener("change", () => run("replay/length", { seconds: Number($("replay-length").value) }, null));
    // player
    const d = $("player");
    d.addEventListener("close", stopMedia);
    d.addEventListener("click", e => { if (e.target === d)
        d.close(); });
    $("player-close").addEventListener("click", () => d.close());
    $("player-prev").addEventListener("click", () => openPlayer(openIndex - 1));
    $("player-next").addEventListener("click", () => openPlayer(openIndex + 1));
    $("player-upload").addEventListener("click", playerSyncAction);
    // soundboard
    const sendMaster = throttle(g => command("sounds/master", { gain: g }).catch(e => toast(e.message, true)), 150);
    setMasterShown = gainSlider($("master-gain"), "Soundboard volume", sendMaster, g => command("sounds/master", { gain: g }).then(r => toast(r.ok || "Saved")).catch(e => toast(e.message, true)));
    $("stop-all").addEventListener("click", () => run("sounds/stopAll", {}, $("stop-all"), () => loadSounds().catch(() => undefined)));
    $("sb-limit").addEventListener("change", () => run("sounds/limit", { on: $("sb-limit").checked }, null, () => loadSounds().catch(() => undefined)));
    $("sb-edit").addEventListener("click", () => {
        editing = !editing;
        text($("sb-edit"), editing ? "Done" : "Edit");
        $("sb-edit").setAttribute("aria-pressed", String(editing));
        $("pads").classList.toggle("editing", editing);
        renderPads();
    });
    $("sb-output").addEventListener("change", () => run("sounds/output", { device: $("sb-output").value }, null));
    const se = $("sound-edit");
    se.addEventListener("close", () => { editId = ""; });
    se.addEventListener("click", e => { if (e.target === se)
        se.close(); });
    $("se-close").addEventListener("click", () => se.close());
    $("se-page").addEventListener("change", () => run("sounds/page", { id: editId, page: $("se-page").value }, null, () => loadSounds().catch(() => undefined)));
    // Colour and icon go through the PC too (it checks and saves them).
    $("se-color").addEventListener("change", () => run("sounds/look", { id: editId, color: $("se-color").value }, null, () => loadSounds().catch(() => undefined)));
    $("se-nocolor").addEventListener("click", () => run("sounds/look", { id: editId, color: "" }, null, () => loadSounds().catch(() => undefined)));
    $("se-icon").addEventListener("change", () => run("sounds/look", { id: editId, icon: $("se-icon").value }, null, () => loadSounds().catch(() => undefined)));
    $("se-left").addEventListener("click", () => moveEdited(-1));
    $("se-right").addEventListener("click", () => moveEdited(1));
    // settings
    for (const p of PREFS)
        $("pref-" + p).addEventListener("change", () => {
            run("cloud/settings", { [p]: $("pref-" + p).checked }, null, () => loadCloud().catch(() => undefined));
        });
    $("cloud-connect").addEventListener("click", () => run("cloud/connect", {}, $("cloud-connect"), () => {
        for (const ms of [5000, 15000, 30000, 60000])
            window.setTimeout(() => loadCloud().catch(() => undefined), ms);
    }));
    $("cloud-disconnect").addEventListener("click", () => {
        if (window.confirm("Disconnect Google Drive? Zyclipra stops uploading. Nothing is deleted on Google Drive or your PC."))
            run("cloud/disconnect", {}, $("cloud-disconnect"), () => loadCloud().catch(() => undefined));
    });
    $("retry-all").addEventListener("click", () => run("cloud/retry", {}, $("retry-all"), () => loadCloud().catch(() => undefined)));
    $("pw-form").addEventListener("submit", changePassword);
    $("user-form").addEventListener("submit", changeUsername);
    $("logout").addEventListener("click", () => api("logout", {}).finally(showLogin));
    // shared tick marks for gain sliders
    const ticks = el("datalist");
    ticks.id = "gain-ticks";
    for (const v of [0, 100, 200, 300])
        ticks.appendChild(new Option("", String(v)));
    document.body.appendChild(ticks);
    api("setup").then(s => {
        if (!s.configured)
            text($("login-hint"), "The dashboard isn't set up yet. In Zyclipra on your PC, open Settings › Hosting and create a username and password.");
    }).catch(() => undefined);
    api("status").then(showApp).catch(() => undefined);
});
