const API_BASE = '/api';

// State
const state = {
    authToken: localStorage.getItem('guardianToken') || '',
    socket: null,
    selectedDeviceId: null,
    devices: new Map(),
    map: null,
    locationMarker: null,
    locationTrail: null,
    trailLatLngs: [],
    isDrawing: false,
    swipeStart: null,
    frameCount: 0,
    lastFpsUpdate: Date.now(),
    blockedApps: [],
    blockedSites: [],
    installedApps: [],
    blockLog: { apps: [], sites: [] },
    deviceWidth: 1080,
    deviceHeight: 1920
};

// DOM Elements
const els = {
    loginScreen: document.getElementById('login-screen'),
    mainDashboard: document.getElementById('main-dashboard'),
    loginForm: document.getElementById('login-form'),
    authTokenInput: document.getElementById('auth-token'),
    deviceList: document.getElementById('device-list'),
    tabBtns: document.querySelectorAll('.tab-btn'),
    tabContents: document.querySelectorAll('.tab-content'),
    mirrorStatus: document.getElementById('mirror-status'),
    fpsCounter: document.getElementById('fps-counter'),
    canvas: document.getElementById('screen-canvas'),
    ctx: null,
    qualitySelector: document.getElementById('quality-selector')
};

els.ctx = els.canvas.getContext('2d', { alpha: false });

// Initialize
function init() {
    if (state.authToken) {
        connectSocket(state.authToken);
    }

    els.loginForm.addEventListener('submit', (e) => {
        e.preventDefault();
        const token = els.authTokenInput.value.trim();
        if (token) {
            localStorage.setItem('guardianToken', token);
            state.authToken = token;
            connectSocket(token);
        }
    });

    // Tabs
    els.tabBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            const tabId = btn.dataset.tab;
            els.tabBtns.forEach(b => b.classList.remove('active'));
            els.tabContents.forEach(c => c.classList.remove('active'));
            
            btn.classList.add('active');
            document.getElementById(`tab-${tabId}`).classList.add('active');

            if (tabId === 'location') initMap();
        });
    });

    setupCanvasInteractions();
    setupNavigationButtons();
    setupBlockers();
    setupActions();

    // FPS Loop
    setInterval(() => {
        els.fpsCounter.innerText = `${state.frameCount} FPS`;
        state.frameCount = 0;
    }, 1000);
}

function connectSocket(token) {
    state.socket = io('/dashboard', {
        auth: { token },
        query: { token }
    });

    state.socket.on('connect', () => {
        els.loginScreen.classList.remove('active');
        els.mainDashboard.classList.add('active');
    });

    state.socket.on('connect_error', (err) => {
        alert('Connection error. Please check your token.');
        localStorage.removeItem('guardianToken');
        els.loginScreen.classList.add('active');
        els.mainDashboard.classList.remove('active');
    });

    state.socket.on('devices_list', (devices) => {
        state.devices.clear();
        devices.forEach(d => state.devices.set(d.deviceId, d));
        renderDeviceList();
    });

    state.socket.on('device_connected', (device) => {
        state.devices.set(device.deviceId, device);
        renderDeviceList();
    });

    state.socket.on('device_disconnected', (data) => {
        const deviceId = data.deviceId || data;
        const device = state.devices.get(deviceId);
        if (device) {
            device.online = false;
            renderDeviceList();
        }
    });

    state.socket.on('frame', async (data) => {
        if (!state.selectedDeviceId) return;
        
        try {
            // data may arrive as {deviceId, buffer, ...} or raw binary
            const frameBuffer = data.buffer || data;
            const blob = new Blob([frameBuffer], { type: 'image/webp' });
            const imageBitmap = await createImageBitmap(blob);
            
            els.canvas.width = imageBitmap.width;
            els.canvas.height = imageBitmap.height;
            state.deviceWidth = imageBitmap.width;
            state.deviceHeight = imageBitmap.height;

            els.ctx.drawImage(imageBitmap, 0, 0);
            imageBitmap.close();
            state.frameCount++;
            
            if (data.timestamp) {
                const latency = Date.now() - data.timestamp;
                const el = document.getElementById('latency-indicator');
                if (el) el.innerText = `${latency}ms`;
            }
        } catch (e) {
            console.error('Frame decode error:', e);
        }
    });

    state.socket.on('location_update', (data) => {
        if (data.deviceId !== state.selectedDeviceId) return;
        updateLocation(data.location || data);
    });

    state.socket.on('installed_apps', (data) => {
        if (data.deviceId !== state.selectedDeviceId) return;
        state.installedApps = data.apps;
        renderInstalledApps();
    });
    
    state.socket.on('block_event', (data) => {
        if (data.deviceId !== state.selectedDeviceId) return;
        if (data.type === 'app') {
            state.blockLog.apps.unshift(data);
            if (state.blockLog.apps.length > 50) state.blockLog.apps.pop();
            renderAppBlockLog();
        } else if (data.type === 'site') {
            state.blockLog.sites.unshift(data);
            if (state.blockLog.sites.length > 50) state.blockLog.sites.pop();
            renderSiteBlockLog();
        }
    });
}

function renderDeviceList() {
    els.deviceList.innerHTML = '';
    if (state.devices.size === 0) {
        els.deviceList.innerHTML = '<li class="no-devices">No devices connected</li>';
        return;
    }

    state.devices.forEach((device, id) => {
        const li = document.createElement('li');
        if (id === state.selectedDeviceId) li.classList.add('selected');
        
        const lastSeen = device.lastSeen ? new Date(device.lastSeen).toLocaleString() : 'N/A';
        const displayName = device.label || device.model || 'Unknown Device';
        const shortId = id.length > 8 ? id.substring(0, 8) : id;
        
        li.innerHTML = `
            <div class="status-dot ${device.online ? 'online' : ''}"></div>
            <div class="device-info">
                <span class="device-name">${displayName}</span>
                <span class="device-last-seen">ID: ${shortId} • ${lastSeen}</span>
            </div>
        `;
        
        li.addEventListener('click', () => selectDevice(id));
        els.deviceList.appendChild(li);
    });
}

function selectDevice(deviceId) {
    state.selectedDeviceId = deviceId;
    renderDeviceList();
    els.mirrorStatus.innerText = `Connected - Streaming`;
    
    state.socket.emit('watch_device', deviceId);
    
    els.ctx.clearRect(0, 0, els.canvas.width, els.canvas.height);
    state.trailLatLngs = [];
    if (state.locationTrail) state.locationTrail.setLatLngs([]);
    
    fetchDeviceData(deviceId);
}

async function fetchDeviceData(deviceId) {
    try {
        // Fetch blocklists
        const res = await fetch(`${API_BASE}/blocklist/${deviceId}`, {
            headers: { 'Authorization': `Bearer ${state.authToken}` }
        });
        if (res.ok) {
            const data = await res.json();
            state.blockedApps = data.apps || [];
            state.blockedSites = data.sites || [];
            renderBlockedApps();
            renderBlockedSites();
        }

        // Request installed apps from device via socket
        state.socket.emit('request_installed_apps', deviceId);
    } catch (e) {
        console.error('Error fetching device data:', e);
    }
}

// Canvas Interaction
function setupCanvasInteractions() {
    els.canvas.addEventListener('contextmenu', e => e.preventDefault());

    function getDeviceCoords(e) {
        const rect = els.canvas.getBoundingClientRect();
        const scaleX = state.deviceWidth / rect.width;
        const scaleY = state.deviceHeight / rect.height;
        return {
            x: (e.clientX - rect.left) * scaleX,
            y: (e.clientY - rect.top) * scaleY
        };
    }

    els.canvas.addEventListener('mousedown', (e) => {
        if (!state.selectedDeviceId) return;
        const coords = getDeviceCoords(e);
        state.isDrawing = true;
        state.swipeStart = { x: coords.x, y: coords.y, time: Date.now() };
    });

    els.canvas.addEventListener('mouseup', (e) => {
        if (!state.isDrawing || !state.selectedDeviceId) return;
        state.isDrawing = false;
        const coords = getDeviceCoords(e);
        const start = state.swipeStart;
        const duration = Date.now() - start.time;
        
        const dist = Math.hypot(coords.x - start.x, coords.y - start.y);
        
        if (dist < 10) {
            // Tap
            state.socket.emit('touch', {
                deviceId: state.selectedDeviceId,
                action: 'tap',
                x: start.x,
                y: start.y
            });
        } else {
            // Swipe
            state.socket.emit('touch', {
                deviceId: state.selectedDeviceId,
                action: 'swipe',
                x1: start.x,
                y1: start.y,
                x2: coords.x,
                y2: coords.y,
                duration: duration
            });
        }
    });

    els.canvas.addEventListener('mouseleave', () => { state.isDrawing = false; });

    els.canvas.addEventListener('keydown', (e) => {
        if (!state.selectedDeviceId) return;
        e.preventDefault();
        state.socket.emit('touch', {
            deviceId: state.selectedDeviceId,
            action: 'type',
            text: e.key
        });
    });
}

function setupNavigationButtons() {
    const navs = ['back', 'home', 'recents'];
    navs.forEach(nav => {
        document.getElementById(`nav-${nav}`).addEventListener('click', () => {
            if (state.selectedDeviceId) {
                state.socket.emit('touch', { deviceId: state.selectedDeviceId, action: nav });
            }
        });
    });
}

// Map
function initMap() {
    if (state.map) return;
    
    state.map = L.map('map').setView([0, 0], 2);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '© OpenStreetMap contributors'
    }).addTo(state.map);

    state.locationMarker = L.circleMarker([0, 0], {
        radius: 8,
        fillColor: "#4f8cff",
        color: "#fff",
        weight: 2,
        opacity: 1,
        fillOpacity: 0.8
    }).addTo(state.map);

    state.locationTrail = L.polyline([], { color: '#4f8cff', weight: 3 }).addTo(state.map);
}

function updateLocation(data) {
    if (!state.map) return;
    const { lat, lng, accuracy, speed, timestamp } = data;
    const latlng = [lat, lng];
    
    state.locationMarker.setLatLng(latlng);
    state.trailLatLngs.push(latlng);
    state.locationTrail.setLatLngs(state.trailLatLngs);
    state.map.setView(latlng, 16);

    document.getElementById('info-latlng').innerText = `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
    document.getElementById('info-accuracy').innerText = accuracy ? Math.round(accuracy) : 'N/A';
    document.getElementById('info-speed').innerText = speed ? speed.toFixed(1) : 'N/A';
    document.getElementById('info-last-update').innerText = new Date(timestamp).toLocaleTimeString();
}

document.getElementById('btn-request-location').addEventListener('click', async () => {
    if (!state.selectedDeviceId) return;
    await fetch(`${API_BASE}/command/${state.selectedDeviceId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${state.authToken}` },
        body: JSON.stringify({ action: 'request_location' })
    });
});

// App Blocker
document.getElementById('btn-refresh-apps').addEventListener('click', async () => {
    if (!state.selectedDeviceId) return;
    await fetch(`${API_BASE}/command/${state.selectedDeviceId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${state.authToken}` },
        body: JSON.stringify({ action: 'get_apps' })
    });
});

function renderInstalledApps() {
    const list = document.getElementById('installed-apps-list');
    list.innerHTML = '';
    const filter = document.getElementById('app-search').value.toLowerCase();
    
    state.installedApps.forEach(app => {
        if (filter && !app.name.toLowerCase().includes(filter) && !app.packageName.toLowerCase().includes(filter)) return;
        
        const isBlocked = state.blockedApps.includes(app.packageName);
        const li = document.createElement('li');
        li.innerHTML = `
            <div class="app-info">
                <span>${app.name}</span>
                <span class="app-pkg">${app.packageName}</span>
            </div>
            <label class="switch">
                <input type="checkbox" ${isBlocked ? 'checked' : ''} onchange="toggleAppBlock('${app.packageName}', this.checked)">
                <span class="slider"></span>
            </label>
        `;
        list.appendChild(li);
    });
}

document.getElementById('app-search').addEventListener('input', renderInstalledApps);

window.toggleAppBlock = async function(pkg, block) {
    if (!state.selectedDeviceId) return;
    
    if (block && !state.blockedApps.includes(pkg)) state.blockedApps.push(pkg);
    if (!block) state.blockedApps = state.blockedApps.filter(p => p !== pkg);
    
    await fetch(`${API_BASE}/blocklist/${state.selectedDeviceId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${state.authToken}` },
        body: JSON.stringify({ apps: state.blockedApps, sites: state.blockedSites })
    });
    
    renderBlockedApps();
};

function renderBlockedApps() {
    const list = document.getElementById('blocked-apps-list');
    list.innerHTML = '';
    state.blockedApps.forEach(pkg => {
        const li = document.createElement('li');
        li.innerHTML = `<span>${pkg}</span><button onclick="toggleAppBlock('${pkg}', false)">Unblock</button>`;
        list.appendChild(li);
    });
    if (state.installedApps.length > 0) renderInstalledApps();
}

function renderAppBlockLog() {
    const list = document.getElementById('app-block-log');
    list.innerHTML = '';
    state.blockLog.apps.forEach(log => {
        const li = document.createElement('li');
        li.innerHTML = `<span>${log.packageName} blocked</span><span class="app-pkg">${new Date(log.timestamp).toLocaleTimeString()}</span>`;
        list.appendChild(li);
    });
}

// Site Blocker
function setupBlockers() {
    document.getElementById('btn-add-domain').addEventListener('click', () => {
        const input = document.getElementById('domain-input');
        const domain = input.value.trim().toLowerCase();
        if (domain && !state.blockedSites.includes(domain)) {
            state.blockedSites.push(domain);
            updateSiteBlocklist();
            input.value = '';
        }
    });

    document.querySelectorAll('.preset-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const preset = btn.dataset.preset;
            let domains = [];
            if (preset === 'social') domains = ['facebook.com', 'instagram.com', 'tiktok.com', 'twitter.com', 'snapchat.com'];
            if (preset === 'adult') domains = ['pornhub.com', 'xvideos.com'];
            if (preset === 'gaming') domains = ['roblox.com', 'steampowered.com'];
            
            domains.forEach(d => { if (!state.blockedSites.includes(d)) state.blockedSites.push(d); });
            updateSiteBlocklist();
        });
    });
}

window.removeDomain = function(domain) {
    state.blockedSites = state.blockedSites.filter(d => d !== domain);
    updateSiteBlocklist();
};

async function updateSiteBlocklist() {
    if (!state.selectedDeviceId) return;
    await fetch(`${API_BASE}/blocklist/${state.selectedDeviceId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${state.authToken}` },
        body: JSON.stringify({ apps: state.blockedApps, sites: state.blockedSites })
    });
    renderBlockedSites();
}

function renderBlockedSites() {
    const list = document.getElementById('blocked-sites-list');
    list.innerHTML = '';
    state.blockedSites.forEach(domain => {
        const li = document.createElement('li');
        li.innerHTML = `<span>${domain}</span><button onclick="removeDomain('${domain}')">Remove</button>`;
        list.appendChild(li);
    });
}

function renderSiteBlockLog() {
    const list = document.getElementById('site-block-log');
    list.innerHTML = '';
    state.blockLog.sites.forEach(log => {
        const li = document.createElement('li');
        li.innerHTML = `<span>${log.url} blocked</span><span class="app-pkg">${new Date(log.timestamp).toLocaleTimeString()}</span>`;
        list.appendChild(li);
    });
}

// Actions
function setupActions() {
    const actions = {
        'action-play-sound': 'play_sound',
        'action-request-location': 'request_location',
        'action-lock-screen': 'lock_screen'
    };

    for (const [id, action] of Object.entries(actions)) {
        document.getElementById(id).addEventListener('click', async () => {
            if (!state.selectedDeviceId) return alert('Select a device first');
            await fetch(`${API_BASE}/command/${state.selectedDeviceId}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${state.authToken}` },
                body: JSON.stringify({ action })
            });
        });
    }
}

// Run
init();
