require('dotenv').config();
const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const path = require('path');
const { blocklistStore, blockLogStore } = require('./store');

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: '*',
    methods: ['GET', 'POST', 'PUT']
  },
  maxHttpBufferSize: 1e8 // Allow larger frames
});

const PORT = process.env.PORT || 3000;
const AUTH_TOKEN = process.env.AUTH_TOKEN || 'your-secret-token-here';

// Middleware
app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

// Authentication middleware for API routes
const apiAuth = (req, res, next) => {
  const token = req.headers.authorization?.split(' ')[1] || req.query.token;
  if (token !== AUTH_TOKEN) {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  next();
};

// In-memory state
// deviceId -> { socketId, model, osVersion, online, lastSeen, lastLocation, installedApps }
const devices = new Map();
// dashboardSocketId -> deviceId
const dashboardWatchers = new Map();

// REST API Endpoints
app.post('/api/register', apiAuth, (req, res) => {
  const { deviceId, model, osVersion, appVersion } = req.body;
  if (!deviceId) return res.status(400).json({ error: 'deviceId required' });
  
  if (!devices.has(deviceId)) {
    devices.set(deviceId, {
      socketId: null,
      model: model || 'Unknown',
      osVersion: osVersion || 'Unknown',
      appVersion: appVersion || 'Unknown',
      online: false,
      lastSeen: Date.now(),
      lastLocation: null,
      installedApps: []
    });
  } else {
    const device = devices.get(deviceId);
    devices.set(deviceId, { ...device, model, osVersion, appVersion });
  }
  
  res.json({ success: true, message: 'Device registered' });
});

app.get('/api/devices', apiAuth, (req, res) => {
  const deviceList = Array.from(devices.entries()).map(([deviceId, data]) => ({
    deviceId,
    ...data
  }));
  res.json(deviceList);
});

app.post('/api/command/:deviceId', apiAuth, (req, res) => {
  const { deviceId } = req.params;
  const { action, params } = req.body;
  
  const device = devices.get(deviceId);
  if (!device || !device.socketId || !device.online) {
    return res.status(404).json({ error: 'Device not found or offline' });
  }
  
  io.of('/device').to(device.socketId).emit('command', { action, params });
  res.json({ success: true, message: 'Command sent' });
});

app.get('/api/blocklist/:deviceId', apiAuth, (req, res) => {
  const { deviceId } = req.params;
  const blocklist = blocklistStore.get(deviceId) || { apps: [], sites: [] };
  res.json(blocklist);
});

app.put('/api/blocklist/:deviceId', apiAuth, (req, res) => {
  const { deviceId } = req.params;
  const { apps, sites } = req.body;
  
  const updatedBlocklist = {
    apps: Array.isArray(apps) ? apps : [],
    sites: Array.isArray(sites) ? sites : []
  };
  
  blocklistStore.set(deviceId, updatedBlocklist);
  
  const device = devices.get(deviceId);
  if (device && device.socketId && device.online) {
    io.of('/device').to(device.socketId).emit('blocklist_updated', updatedBlocklist);
  }
  
  res.json({ success: true, blocklist: updatedBlocklist });
});

app.get('/api/installed-apps/:deviceId', apiAuth, (req, res) => {
  const { deviceId } = req.params;
  const device = devices.get(deviceId);
  if (!device) return res.status(404).json({ error: 'Device not found' });
  
  res.json({ apps: device.installedApps || [] });
});

// Socket.IO Auth middleware
const socketAuth = (socket, next) => {
  const token = socket.handshake.auth.token || socket.handshake.query.token;
  if (token !== AUTH_TOKEN) {
    return next(new Error('Authentication error'));
  }
  next();
};

// Device Namespace
const deviceNamespace = io.of('/device');
deviceNamespace.use(socketAuth);

deviceNamespace.on('connection', (socket) => {
  const deviceId = socket.handshake.query.deviceId;
  const deviceLabel = socket.handshake.query.label || '';
  if (!deviceId) {
    console.error(`Device connected without deviceId (Socket ${socket.id})`);
    return socket.disconnect();
  }

  console.log(`Device connected: ${deviceId} — ${deviceLabel} (Socket ${socket.id})`);

  let device = devices.get(deviceId) || {
    model: 'Unknown',
    osVersion: 'Unknown',
    lastLocation: null,
    installedApps: []
  };

  device = {
    ...device,
    socketId: socket.id,
    model: socket.handshake.query.model || device.model,
    osVersion: socket.handshake.query.osVersion || device.osVersion,
    label: deviceLabel || device.label || '',
    online: true,
    lastSeen: Date.now()
  };
  devices.set(deviceId, device);

  dashboardNamespace.emit('device_connected', { deviceId, ...device });

  socket.on('frame', (data) => {
    // data should contain { buffer, width, height, timestamp } or be a raw buffer.
    // Spec: receive binary screen frame data + deviceId, relay to all dashboards watching
    let frameData = data;
    if (Buffer.isBuffer(data)) {
        frameData = { buffer: data, timestamp: Date.now() };
    }
    
    // Find all dashboard clients watching this device
    for (const [dashSocketId, watchedDeviceId] of dashboardWatchers.entries()) {
      if (watchedDeviceId === deviceId) {
        dashboardNamespace.to(dashSocketId).emit('frame', {
          deviceId,
          ...frameData
        });
      }
    }
  });

  socket.on('location', (locationData) => {
    // locationData: {lat, lng, accuracy, speed, bearing, altitude, timestamp}
    const currentDevice = devices.get(deviceId);
    if (currentDevice) {
      currentDevice.lastLocation = locationData;
      currentDevice.lastSeen = Date.now();
      devices.set(deviceId, currentDevice);
      dashboardNamespace.emit('location_update', { deviceId, location: locationData });
    }
  });

  socket.on('installed_apps', (data) => {
    // data: {apps: [{packageName, appName, icon}]}
    const currentDevice = devices.get(deviceId);
    if (currentDevice && data.apps) {
      currentDevice.installedApps = data.apps;
      devices.set(deviceId, currentDevice);
      dashboardNamespace.emit('installed_apps_update', { deviceId, apps: data.apps });
    }
  });

  socket.on('block_event', (eventData) => {
    // eventData: {type, target, timestamp}
    const logs = blockLogStore.get(deviceId) || [];
    logs.push(eventData);
    if(logs.length > 1000) logs.shift(); // Keep latest 1000 logs
    blockLogStore.set(deviceId, logs);
    
    dashboardNamespace.emit('block_event', { deviceId, ...eventData });
  });

  socket.on('heartbeat', () => {
    const currentDevice = devices.get(deviceId);
    if (currentDevice) {
      currentDevice.lastSeen = Date.now();
      devices.set(deviceId, currentDevice);
    }
  });

  socket.on('disconnect', () => {
    console.log(`Device disconnected: ${deviceId}`);
    const currentDevice = devices.get(deviceId);
    if (currentDevice && currentDevice.socketId === socket.id) {
      currentDevice.online = false;
      currentDevice.socketId = null;
      devices.set(deviceId, currentDevice);
      dashboardNamespace.emit('device_disconnected', { deviceId });
    }
  });
});

// Dashboard Namespace
const dashboardNamespace = io.of('/dashboard');
dashboardNamespace.use(socketAuth);

dashboardNamespace.on('connection', (socket) => {
  console.log(`Dashboard connected (Socket ${socket.id})`);

  // Send current devices list
  const deviceList = Array.from(devices.entries()).map(([deviceId, data]) => ({
    deviceId,
    ...data
  }));
  socket.emit('devices_list', deviceList);

  socket.on('watch_device', (deviceId) => {
    console.log(`Dashboard ${socket.id} watching device ${deviceId}`);
    dashboardWatchers.set(socket.id, deviceId);
  });

  socket.on('stop_watching', () => {
    console.log(`Dashboard ${socket.id} stopped watching`);
    dashboardWatchers.delete(socket.id);
  });

  socket.on('touch', (data) => {
    // data: {deviceId, action, ...params}
    const { deviceId, action, ...params } = data;
    if (!deviceId) return;

    const device = devices.get(deviceId);
    if (device && device.socketId && device.online) {
      deviceNamespace.to(device.socketId).emit('touch', { action, ...params });
    }
  });

  socket.on('request_installed_apps', (deviceId) => {
    const device = devices.get(deviceId);
    if (device && device.socketId && device.online) {
      deviceNamespace.to(device.socketId).emit('request_installed_apps');
    }
  });

  socket.on('disconnect', () => {
    console.log(`Dashboard disconnected (Socket ${socket.id})`);
    dashboardWatchers.delete(socket.id);
  });
});

server.listen(PORT, () => {
  console.log(`Server running on port ${PORT}`);
});
