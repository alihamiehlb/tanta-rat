'use strict';

// ──────────────────────────────────────────────
// State
// ──────────────────────────────────────────────
const state = {
    token:          localStorage.getItem('gs_token') || '',
    socket:         null,
    deviceId:       null,       // currently selected device
    devices:        new Map(),  // deviceId -> device object
    // Map
    map:            null,
    mapMarker:      null,
    mapTrail:       null,
    trailPoints:    [],
    // Data
    blockedApps:    [],
    blockedSites:   [],
    installedApps:  [],
    blockLogApps:   [],
    blockLogSites:  [],
    // Stream
    frameCount:     0,
    lastFrameTime:  Date.now(),
    deviceW:        1080,
    deviceH:        1920,
    // Gesture tracking
    dragging:       false,
    dragStart:      null,
};

// ──────────────────────────────────────────────
// DOM references (queried once after DOMContentLoaded)
// ──────────────────────────────────────────────
let $;

function initDom() {
    $ = {
        loginScreen:    document.getElementById('login-screen'),
        dashboard:      document.getElementById('main-dashboard'),
        loginForm:      document.getElementById('login-form'),
        tokenInput:     document.getElementById('auth-token'),
        btnLogout:      document.getElementById('btn-logout'),
        deviceList:     document.getElementById('device-list'),
        tabBtns:        document.querySelectorAll('.tab-btn'),
        tabContents:    document.querySelectorAll('.tab-content'),
        connStatus:     document.getElementById('connection-status'),
        fpsCounter:     document.getElementById('fps-counter'),
        latency:        document.getElementById('latency-indicator'),
        canvas:         document.getElementById('screen-canvas'),
        canvasPlaceholder: document.getElementById('canvas-placeholder'),
        // Location
        infoLatlng:     document.getElementById('info-latlng'),
        infoAccuracy:   document.getElementById('info-accuracy'),
        infoSpeed:      document.getElementById('info-speed'),
        infoLastUpdate: document.getElementById('info-last-update'),
        btnReqLocation: document.getElementById('btn-request-location'),
        // App blocker
        appSearch:      document.getElementById('app-search'),
        btnRefreshApps: document.getElementById('btn-refresh-apps'),
        installedList:  document.getElementById('installed-apps-list'),
        blockedAppsList:document.getElementById('blocked-apps-list'),
        appBlockLog:    document.getElementById('app-block-log'),
        // Site blocker
        domainInput:    document.getElementById('domain-input'),
        btnAddDomain:   document.getElementById('btn-add-domain'),
        blockedSitesList:document.getElementById('blocked-sites-list'),
        siteBlockLog:   document.getElementById('site-block-log'),
        presetBtns:     document.querySelectorAll('.preset-btn'),
        // Actions
        btnPlaySound:   document.getElementById('action-play-sound'),
        btnReqLoc2:     document.getElementById('action-request-location'),
        btnLockScreen:  document.getElementById('action-lock-screen'),
        // Quality
        qualityBtns:    document.querySelectorAll('.quality-btn'),
        // Nav
        navBack:        document.getElementById('nav-back'),
        navHome:        document.getElementById('nav-home'),
        navRecents:     document.getElementById('nav-recents'),
    };

    $.ctx = $.canvas.getContext('2d', { alpha: false });
}

// ──────────────────────────────────────────────
// Init
// ──────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    initDom();
    setupTabs();
    setupCanvas();
    setupNavButtons();
    setupBlockerUI();
    setupActions();
    setupQualityBtns();

    // Auto-login if token saved
    if (state.token) {
        $.tokenInput.value = state.token;
        connect(state.token);
    }

    $.loginForm.addEventListener('submit', (e) => {
        e.preventDefault();
        const token = $.tokenInput.value.trim();
        if (!token) return;
        connect(token);
    });

    $.btnLogout.addEventListener('click', disconnect);

    // FPS counter
    setInterval(() => {
        $.fpsCounter.textContent = `${state.frameCount} FPS`;
        state.frameCount = 0;
    }, 1000);
});

// ──────────────────────────────────────────────
// Socket connection
// ──────────────────────────────────────────────
function connect(token) {
    if (state.socket) state.socket.disconnect();

    state.token = token;
    localStorage.setItem('gs_token', token);

    state.socket = io('/dashboard', {
        auth:  { token },
        query: { token },
        transports: ['websocket'],
    });

    state.socket.on('connect', () => {
        showDashboard();
        console.log('Connected to GuardianShield server');
    });

    state.socket.on('connect_error', (err) => {
        console.error('Auth error:', err.message);
        localStorage.removeItem('gs_token');
        state.token = '';
        showLogin();
        alert('Connection failed — check your AUTH_TOKEN.');
    });

    state.socket.on('disconnect', () => {
        updateConnectionStatus(false);
    });

    // Device list
    state.socket.on('devices_list', (list) => {
        state.devices.clear();
        list.forEach(d => state.devices.set(d.deviceId, d));
        renderDeviceList();
    });

    state.socket.on('device_connected', (d) => {
        state.devices.set(d.deviceId, d);
        renderDeviceList();
    });

    state.socket.on('device_disconnected', (data) => {
        const id = typeof data === 'string' ? data : data.deviceId;
        const d = state.devices.get(id);
        if (d) { d.online = false; renderDeviceList(); }
        if (id === state.deviceId) updateConnectionStatus(false);
    });

    // Screen frame
    state.socket.on('frame', async (data) => {
        if (!state.deviceId) return;
        try {
            const buf = data.buffer || data;
            const blob = new Blob([buf], { type: 'image/webp' });
            const bmp = await createImageBitmap(blob);

            $.canvas.width  = bmp.width;
            $.canvas.height = bmp.height;
            state.deviceW   = bmp.width;
            state.deviceH   = bmp.height;

            $.ctx.drawImage(bmp, 0, 0);
            bmp.close();
            state.frameCount++;
            $.canvasPlaceholder.style.display = 'none';

            if (data.timestamp) {
                $.latency.textContent = `${Date.now() - data.timestamp}ms`;
            }
        } catch (e) {
            console.warn('Frame decode error', e);
        }
    });

    // Location
    state.socket.on('location_update', (data) => {
        if (data.deviceId !== state.deviceId) return;
        updateMap(data.location || data);
    });

    // Installed apps
    state.socket.on('installed_apps_update', (data) => {
        if (data.deviceId !== state.deviceId) return;
        state.installedApps = data.apps || [];
        renderInstalledApps();
    });

    // Block events
    state.socket.on('block_event', (data) => {
        if (data.deviceId !== state.deviceId) return;
        if (data.type === 'app') {
            state.blockLogApps.unshift(data);
            if (state.blockLogApps.length > 50) state.blockLogApps.pop();
            renderAppBlockLog();
        } else {
            state.blockLogSites.unshift(data);
            if (state.blockLogSites.length > 50) state.blockLogSites.pop();
            renderSiteBlockLog();
        }
    });
}

function disconnect() {
    if (state.socket) { state.socket.disconnect(); state.socket = null; }
    localStorage.removeItem('gs_token');
    state.token = '';
    state.deviceId = null;
    state.devices.clear();
    showLogin();
}

// ──────────────────────────────────────────────
// Screen show/hide
// ──────────────────────────────────────────────
function showDashboard() {
    $.loginScreen.classList.add('hidden');
    $.dashboard.classList.remove('hidden');
}
function showLogin() {
    $.loginScreen.classList.remove('hidden');
    $.dashboard.classList.add('hidden');
}

// ──────────────────────────────────────────────
// Device management
// ──────────────────────────────────────────────
function renderDeviceList() {
    $.deviceList.innerHTML = '';

    if (state.devices.size === 0) {
        $.deviceList.innerHTML = `
            <li class="no-devices">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" width="20" height="20">
                    <rect x="5" y="2" width="14" height="20" rx="2"/>
                    <line x1="12" y1="18" x2="12" y2="18.01" stroke-width="2"/>
                </svg>
                No devices connected
            </li>`;
        return;
    }

    state.devices.forEach((device, id) => {
        const li = document.createElement('li');
        li.dataset.id = id;
        if (id === state.deviceId) li.classList.add('selected');

        const name    = device.label || device.model || 'Unknown Device';
        const shortId = id.substring(0, 8);
        const lastSeen = device.lastSeen
            ? new Date(device.lastSeen).toLocaleTimeString()
            : 'Never';

        li.innerHTML = `
            <div class="status-dot ${device.online ? 'online' : ''}"></div>
            <div class="device-info">
                <span class="device-name">${escHtml(name)}</span>
                <span class="device-last-seen">${shortId} · ${lastSeen}</span>
            </div>`;

        li.addEventListener('click', () => selectDevice(id));
        $.deviceList.appendChild(li);
    });
}

function selectDevice(id) {
    state.deviceId = id;
    renderDeviceList();

    const device = state.devices.get(id);
    updateConnectionStatus(device?.online ?? false, device?.label || device?.model || id);

    // Subscribe to this device's screen
    if (state.socket) state.socket.emit('watch_device', id);

    // Clear old frame
    $.ctx.clearRect(0, 0, $.canvas.width, $.canvas.height);
    $.canvasPlaceholder.style.display = 'flex';

    // Reset location trail
    state.trailPoints = [];
    if (state.mapTrail) state.mapTrail.setLatLngs([]);

    // Fetch blocklists and installed apps
    fetchBlocklists(id);
}

function updateConnectionStatus(online, label = '') {
    if (online) {
        $.connStatus.className = 'status-badge online';
        $.connStatus.innerHTML = `<span class="status-dot online"></span>${escHtml(label)}`;
    } else {
        $.connStatus.className = 'status-badge offline';
        $.connStatus.innerHTML = `<span class="status-dot"></span>No Device`;
    }
}

// ──────────────────────────────────────────────
// API helpers
// ──────────────────────────────────────────────
async function apiFetch(path, method = 'GET', body = null) {
    const opts = {
        method,
        headers: {
            'Authorization': `Bearer ${state.token}`,
            ...(body ? { 'Content-Type': 'application/json' } : {}),
        },
        ...(body ? { body: JSON.stringify(body) } : {}),
    };
    const res = await fetch(`/api${path}`, opts);
    if (!res.ok) throw new Error(`${method} ${path} → ${res.status}`);
    return res.json();
}

async function fetchBlocklists(id) {
    try {
        const data = await apiFetch(`/blocklist/${id}`);
        state.blockedApps  = data.apps  || [];
        state.blockedSites = data.sites || [];
        renderBlockedApps();
        renderBlockedSites();
        renderInstalledApps();
    } catch (e) {
        console.warn('Blocklist fetch error', e);
    }

    // Ask device for installed apps
    if (state.socket) state.socket.emit('request_installed_apps', id);
}

async function saveBlocklists() {
    if (!state.deviceId) return;
    try {
        await apiFetch(`/blocklist/${state.deviceId}`, 'PUT', {
            apps:  state.blockedApps,
            sites: state.blockedSites,
        });
    } catch (e) {
        console.warn('Blocklist save error', e);
    }
}

async function sendCommand(action, params = {}) {
    if (!state.deviceId) { alert('Select a device first.'); return; }
    try {
        await apiFetch(`/command/${state.deviceId}`, 'POST', { action, params });
    } catch (e) {
        console.warn('Command error', e);
    }
}

// ──────────────────────────────────────────────
// Tabs
// ──────────────────────────────────────────────
function setupTabs() {
    $.tabBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            $.tabBtns.forEach(b => b.classList.remove('active'));
            $.tabContents.forEach(c => c.classList.remove('active'));
            btn.classList.add('active');
            const tab = document.getElementById(`tab-${btn.dataset.tab}`);
            if (tab) tab.classList.add('active');
            if (btn.dataset.tab === 'location') initMap();
        });
    });
}

// ──────────────────────────────────────────────
// Canvas / Remote control
// ──────────────────────────────────────────────
function setupCanvas() {
    const cv = $.canvas;

    cv.addEventListener('contextmenu', e => e.preventDefault());
    cv.setAttribute('tabindex', '0');

    function getCoords(e) {
        const r = cv.getBoundingClientRect();
        return {
            x: Math.round((e.clientX - r.left) * (state.deviceW / r.width)),
            y: Math.round((e.clientY - r.top)  * (state.deviceH / r.height)),
        };
    }

    cv.addEventListener('mousedown', (e) => {
        if (!state.deviceId) return;
        e.preventDefault();
        cv.focus();
        state.dragging  = true;
        state.dragStart = { ...getCoords(e), t: Date.now() };
    });

    cv.addEventListener('mouseup', (e) => {
        if (!state.dragging || !state.deviceId) return;
        state.dragging = false;
        const end  = getCoords(e);
        const dx   = end.x - state.dragStart.x;
        const dy   = end.y - state.dragStart.y;
        const dist = Math.hypot(dx, dy);
        const dur  = Date.now() - state.dragStart.t;

        if (dist < 8) {
            emit('touch', { action: 'tap', x: state.dragStart.x, y: state.dragStart.y });
        } else {
            emit('touch', { action: 'swipe', x1: state.dragStart.x, y1: state.dragStart.y, x2: end.x, y2: end.y, duration: dur });
        }
    });

    cv.addEventListener('mouseleave', () => { state.dragging = false; });

    cv.addEventListener('keydown', (e) => {
        if (!state.deviceId) return;
        e.preventDefault();
        if (e.key === 'Backspace') {
            emit('touch', { action: 'key', keyCode: 67 }); // KEYCODE_DEL
        } else if (e.key === 'Enter') {
            emit('touch', { action: 'key', keyCode: 66 }); // KEYCODE_ENTER
        } else if (e.key.length === 1) {
            emit('touch', { action: 'type', text: e.key });
        }
    });

    // Touch support (mobile dashboard)
    let touchStart = null;
    cv.addEventListener('touchstart', (e) => {
        e.preventDefault();
        const t = e.touches[0];
        const coords = getCoords(t);
        touchStart = { ...coords, t: Date.now() };
    }, { passive: false });

    cv.addEventListener('touchend', (e) => {
        e.preventDefault();
        if (!touchStart || !state.deviceId) return;
        const t = e.changedTouches[0];
        const end  = getCoords(t);
        const dist = Math.hypot(end.x - touchStart.x, end.y - touchStart.y);
        if (dist < 8) {
            emit('touch', { action: 'tap', x: touchStart.x, y: touchStart.y });
        } else {
            emit('touch', { action: 'swipe', x1: touchStart.x, y1: touchStart.y, x2: end.x, y2: end.y, duration: Date.now() - touchStart.t });
        }
        touchStart = null;
    }, { passive: false });
}

function emit(event, data) {
    if (!state.socket || !state.deviceId) return;
    state.socket.emit(event, { deviceId: state.deviceId, ...data });
}

// ──────────────────────────────────────────────
// Navigation buttons
// ──────────────────────────────────────────────
function setupNavButtons() {
    $.navBack.addEventListener('click',    () => emit('touch', { action: 'back' }));
    $.navHome.addEventListener('click',    () => emit('touch', { action: 'home' }));
    $.navRecents.addEventListener('click', () => emit('touch', { action: 'recents' }));
}

// ──────────────────────────────────────────────
// Map
// ──────────────────────────────────────────────
function initMap() {
    if (state.map) { state.map.invalidateSize(); return; }

    state.map = L.map('map').setView([25, 45], 4);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '© OpenStreetMap contributors',
        maxZoom: 19,
    }).addTo(state.map);

    state.mapMarker = L.circleMarker([0, 0], {
        radius: 9,
        fillColor: '#4f8cff',
        color: '#fff',
        weight: 2,
        fillOpacity: 0.9,
    }).addTo(state.map);

    state.mapTrail = L.polyline([], { color: '#4f8cff', weight: 2, opacity: 0.7 }).addTo(state.map);
}

function updateMap(loc) {
    const { lat, lng, accuracy, speed, timestamp } = loc;
    if (!lat || !lng) return;

    initMap();
    const ll = [lat, lng];
    state.mapMarker.setLatLng(ll);
    state.map.setView(ll, 16);

    state.trailPoints.push(ll);
    state.mapTrail.setLatLngs(state.trailPoints);

    $.infoLatlng.textContent     = `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
    $.infoAccuracy.textContent   = accuracy ? `${Math.round(accuracy)} m` : 'N/A';
    $.infoSpeed.textContent      = speed    ? `${speed.toFixed(1)} m/s`   : 'N/A';
    $.infoLastUpdate.textContent = timestamp ? new Date(timestamp).toLocaleTimeString() : 'N/A';
}

// ──────────────────────────────────────────────
// App Blocker
// ──────────────────────────────────────────────
function renderInstalledApps() {
    $.installedList.innerHTML = '';
    const q = $.appSearch.value.toLowerCase();

    const apps = state.installedApps.filter(a =>
        !q || a.appName?.toLowerCase().includes(q) || a.packageName?.toLowerCase().includes(q)
    );

    if (apps.length === 0) {
        $.installedList.innerHTML = `<li style="color:var(--text-muted);padding:0.5rem;font-size:0.82rem;">
            ${state.installedApps.length === 0 ? 'No apps — click Refresh' : 'No results'}
        </li>`;
        return;
    }

    apps.forEach(app => {
        const blocked = state.blockedApps.includes(app.packageName);
        const li = document.createElement('li');
        li.innerHTML = `
            <div class="app-info">
                <span>${escHtml(app.appName || app.packageName)}</span>
                <span class="app-pkg">${escHtml(app.packageName)}</span>
            </div>
            <label class="switch">
                <input type="checkbox" data-pkg="${escHtml(app.packageName)}" ${blocked ? 'checked' : ''}>
                <span class="slider"></span>
            </label>`;
        li.querySelector('input').addEventListener('change', function () {
            toggleAppBlock(this.dataset.pkg, this.checked);
        });
        $.installedList.appendChild(li);
    });
}

function renderBlockedApps() {
    $.blockedAppsList.innerHTML = '';
    if (state.blockedApps.length === 0) {
        $.blockedAppsList.innerHTML = `<li style="color:var(--text-muted);font-size:0.82rem;padding:0.5rem;">None</li>`;
        return;
    }
    state.blockedApps.forEach(pkg => {
        const li = document.createElement('li');
        li.innerHTML = `<span style="min-width:0;overflow:hidden;text-overflow:ellipsis;">${escHtml(pkg)}</span>
            <button class="remove-btn" data-pkg="${escHtml(pkg)}">Unblock</button>`;
        li.querySelector('.remove-btn').addEventListener('click', function () {
            toggleAppBlock(this.dataset.pkg, false);
        });
        $.blockedAppsList.appendChild(li);
    });
}

function renderAppBlockLog() {
    $.appBlockLog.innerHTML = '';
    state.blockLogApps.slice(0, 30).forEach(log => {
        const li = document.createElement('li');
        li.innerHTML = `<span>${escHtml(log.target || log.packageName)}</span>
            <span>${new Date(log.timestamp).toLocaleTimeString()}</span>`;
        $.appBlockLog.appendChild(li);
    });
}

async function toggleAppBlock(pkg, block) {
    if (block) { if (!state.blockedApps.includes(pkg)) state.blockedApps.push(pkg); }
    else        { state.blockedApps = state.blockedApps.filter(p => p !== pkg); }
    await saveBlocklists();
    renderBlockedApps();
    renderInstalledApps();
}

// ──────────────────────────────────────────────
// Site Blocker
// ──────────────────────────────────────────────
function renderBlockedSites() {
    $.blockedSitesList.innerHTML = '';
    if (state.blockedSites.length === 0) {
        $.blockedSitesList.innerHTML = `<li style="color:var(--text-muted);font-size:0.82rem;padding:0.5rem;">None</li>`;
        return;
    }
    state.blockedSites.forEach(domain => {
        const li = document.createElement('li');
        li.innerHTML = `<span>${escHtml(domain)}</span>
            <button class="remove-btn" data-d="${escHtml(domain)}">Remove</button>`;
        li.querySelector('.remove-btn').addEventListener('click', function () {
            removeDomain(this.dataset.d);
        });
        $.blockedSitesList.appendChild(li);
    });
}

function renderSiteBlockLog() {
    $.siteBlockLog.innerHTML = '';
    state.blockLogSites.slice(0, 30).forEach(log => {
        const li = document.createElement('li');
        li.innerHTML = `<span>${escHtml(log.target || log.url)}</span>
            <span>${new Date(log.timestamp).toLocaleTimeString()}</span>`;
        $.siteBlockLog.appendChild(li);
    });
}

async function addDomain(domain) {
    const d = domain.toLowerCase().trim().replace(/^https?:\/\//,'').split('/')[0];
    if (!d || state.blockedSites.includes(d)) return;
    state.blockedSites.push(d);
    await saveBlocklists();
    renderBlockedSites();
}

async function removeDomain(domain) {
    state.blockedSites = state.blockedSites.filter(d => d !== domain);
    await saveBlocklists();
    renderBlockedSites();
}

function setupBlockerUI() {
    $.appSearch.addEventListener('input', renderInstalledApps);

    $.btnRefreshApps.addEventListener('click', () => {
        if (!state.deviceId) return alert('Select a device first.');
        state.socket.emit('request_installed_apps', state.deviceId);
    });

    $.btnAddDomain.addEventListener('click', () => {
        const v = $.domainInput.value.trim();
        if (v) { addDomain(v); $.domainInput.value = ''; }
    });

    $.domainInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') { const v = $.domainInput.value.trim(); if (v) { addDomain(v); $.domainInput.value = ''; } }
    });

    const PRESETS = {
        social: ['facebook.com','instagram.com','tiktok.com','twitter.com','x.com','snapchat.com','reddit.com'],
        adult:  ['pornhub.com','xvideos.com','xnxx.com','xhamster.com','onlyfans.com'],
        gaming: ['roblox.com','steampowered.com','store.steampowered.com','epicgames.com'],
    };

    $.presetBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            const domains = PRESETS[btn.dataset.preset] || [];
            domains.forEach(d => addDomain(d));
        });
    });
}

// ──────────────────────────────────────────────
// Actions
// ──────────────────────────────────────────────
function setupActions() {
    $.btnPlaySound.addEventListener('click',   () => sendCommand('play_sound'));
    $.btnReqLoc2.addEventListener('click',     () => sendCommand('request_location'));
    $.btnLockScreen.addEventListener('click',  () => sendCommand('lock_screen'));
    $.btnReqLocation.addEventListener('click', () => sendCommand('request_location'));
}

// ──────────────────────────────────────────────
// Quality selector
// ──────────────────────────────────────────────
function setupQualityBtns() {
    $.qualityBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            $.qualityBtns.forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            // Emit quality change to device
            if (state.deviceId) emit('touch', { action: 'set_quality', quality: btn.dataset.q });
        });
    });
}

// ──────────────────────────────────────────────
// Utility
// ──────────────────────────────────────────────
function escHtml(str) {
    return String(str ?? '')
        .replace(/&/g,'&amp;').replace(/</g,'&lt;')
        .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
