const { app, BrowserWindow, ipcMain, dialog, clipboard, safeStorage, session, desktopCapturer } = require('electron');
const path = require('node:path');
const os = require('node:os');
const fs = require('node:fs');
const crypto = require('node:crypto');
const dgram = require('node:dgram');
const net = require('node:net');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const { classify } = require('./model.cjs');

const execFileAsync = promisify(execFile);
const DISCOVERY_PORT = 41331;
const URL = 'https://aura-day-tracker.vercel.app';
const PARTITION = 'persist:aura-account';
let win;
let udp;
let tcpServer;
let beaconTimer;
let pollTimer;
let account = null;
let peers = new Map();
let mediaEvents = [];
let lastOwnMedia = '';
const ownMediaClassifications = new Map();
const seenPointIds = new Set();
let ignoredClipboard = '';
let lastDesktopClipboard = '';
let feedback = [];
let settings = { clipboard: false, mediaDetection: false };
let screenCaptureArmed = false;
let screenCaptureActive = false;
let ocrWorkerPromise = null;
const deviceId = loadDeviceId();

function userFile(name) { return path.join(app.getPath('userData'), name); }
function loadDeviceId() {
  try {
    const p = userFile('device-id');
    const id = fs.readFileSync(p, 'utf8').trim();
    if (id) return id;
  } catch {}
  const id = crypto.randomUUID();
  app.whenReady().then(() => fs.mkdirSync(app.getPath('userData'), { recursive: true }));
  try { fs.writeFileSync(userFile('device-id'), id, { mode: 0o600 }); } catch {}
  return id;
}
function rawKey() { return Buffer.from(account.lanSyncKey, 'utf8'); }
function readClipboardText(limit = 6000) {
  try {
    const value = clipboard.readText();
    if (typeof value === 'string') return value.slice(0, limit);
    if (Buffer.isBuffer(value)) return value.toString('utf8', 0, limit);
  } catch {}
  return '';
}
function aesKey() {
  const hex = account.lanSyncKey;
  if (!/^[0-9a-f]{64}$/i.test(hex)) throw Error('Aura local sync key is not a valid 256-bit key.');
  return crypto.createHash('sha256').update(Buffer.from(hex, 'hex')).digest();
}
function sign(body) { return crypto.createHmac('sha256', rawKey()).update(body).digest('hex'); }
function verify(body, signature) {
  const expected = Buffer.from(sign(body), 'hex');
  let got;
  try { got = Buffer.from(signature, 'hex'); } catch { return false; }
  return expected.length === got.length && crypto.timingSafeEqual(expected, got);
}
function encryptMedia(value, sentAt) {
  const key = aesKey();
  const iv = crypto.randomBytes(12);
  const aad = Buffer.from(`${account.accountId}|${deviceId}|${sentAt}`);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(aad);
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(value)), cipher.final(), cipher.getAuthTag()]);
  return { mediaIv: iv.toString('base64'), mediaData: ciphertext.toString('base64') };
}
function decryptMedia(message) {
  const key = aesKey();
  const iv = Buffer.from(message.mediaIv, 'base64');
  const all = Buffer.from(message.mediaData, 'base64');
  if (iv.length !== 12 || all.length < 17 || all.length > 4096) throw Error('invalid media packet');
  const aad = Buffer.from(`${message.accountId}|${message.deviceId}|${message.sentAt}`);
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
  decipher.setAAD(aad);
  decipher.setAuthTag(all.subarray(all.length - 16));
  const clear = Buffer.concat([decipher.update(all.subarray(0, -16)), decipher.final()]).toString('utf8');
  return JSON.parse(clear);
}
function emitState() {
  if (!win || win.isDestroyed()) return;
  win.webContents.send('state', {
    connected: !!account,
    accountName: account?.name || '',
    accountId: account?.accountId || '',
    devices: [...peers.values()].filter(p => Date.now() - p.seenAt < 16000),
    media: mediaEvents.slice(0, 30),
    settings,
    screenCaptureActive,
    points: account?.stats?.total ?? null,
    streak: account?.stats?.streak ?? null,
    focus: hasActiveFocus(),
    linuxHint: process.platform === 'linux' ? 'MPRIS/playerctl on Linux' : ''
  });
}
function localRuntimePath(...parts) {
  const root = app.isPackaged
    ? path.join(process.resourcesPath, 'app.asar.unpacked', 'node_modules')
    : path.join(__dirname, '..', 'node_modules');
  return path.join(root, ...parts);
}
async function getOcrWorker() {
  if (!ocrWorkerPromise) {
    const { createWorker } = require('tesseract.js');
    const cachePath = path.join(app.getPath('userData'), 'ocr-cache');
    fs.mkdirSync(cachePath, { recursive: true, mode: 0o700 });
    ocrWorkerPromise = createWorker('eng', 1, {
      workerPath: localRuntimePath('tesseract.js', 'src', 'worker-script', 'node', 'index.js'),
      langPath: localRuntimePath('@tesseract.js-data', 'eng', '4.0.0_best_int'),
      cachePath,
      gzip: true,
      logger: () => {}
    }).catch(error => { ocrWorkerPromise = null; throw error; });
  }
  return ocrWorkerPromise;
}
function pickCaptureSource(sources) {
  if (!sources.length) return Promise.resolve(null);
  if (sources.length === 1) return Promise.resolve(sources[0]);
  const buttons = [...sources.slice(0, 8).map(source => source.name.slice(0, 70)), 'Cancel'];
  return dialog.showMessageBox(win, {
    type: 'info',
    title: 'Choose a media window',
    message: 'Aura will scan only the window you choose.',
    detail: 'Screen frames are processed on this PC and discarded. Audio is not captured.',
    buttons,
    cancelId: buttons.length - 1,
    defaultId: buttons.length - 1,
    noLink: true
  }).then(({ response }) => response < Math.min(sources.length, 8) ? sources[response] : null);
}
function configureScreenCapture() {
  session.defaultSession.setPermissionRequestHandler((webContents, permission, callback) => {
    if (permission === 'display-capture') {
      callback(Boolean(screenCaptureArmed && win && webContents === win.webContents));
      return;
    }
    callback(false);
  });
  session.defaultSession.setDisplayMediaRequestHandler(async (request, callback) => {
    if (!screenCaptureArmed || !settings.mediaDetection || !request.videoRequested || request.audioRequested || !request.userGesture || !win || request.frame?.url?.startsWith('file:') !== true) {
      callback(null);
      return;
    }
    screenCaptureArmed = false;
    try {
      const sources = await desktopCapturer.getSources({
        types: ['window'],
        thumbnailSize: { width: 0, height: 0 },
        fetchWindowIcons: false
      });
      const source = await pickCaptureSource(sources);
      if (!source) { callback(null); return; }
      screenCaptureActive = true;
      emitState();
      callback({ video: source });
    } catch {
      screenCaptureActive = false;
      emitState();
      callback(null);
    }
  });
}
async function inspectCapturedFrame(dataUrl) {
  if (!screenCaptureActive || !settings.mediaDetection || typeof dataUrl !== 'string') throw Error('Start visual scanning first.');
  const match = dataUrl.match(/^data:image\/(?:jpeg|png);base64,([a-z\d+/=]+)$/i);
  if (!match || match[1].length > 1200000) throw Error('The captured frame was too large to inspect.');
  const image = Buffer.from(match[1], 'base64');
  if (!image.length || image.length > 900000) throw Error('The captured frame was too large to inspect.');
  const worker = await getOcrWorker();
  const { data } = await worker.recognize(image);
  const visibleText = String(data?.text || '').replace(/\s+/g, ' ').trim().slice(0, 1200);
  if (visibleText.length < 5) return { text: '', result: { category: 'uncertain', confidence: 0.5, label: 'No readable media text', evidence: 0 } };
  const result = classify(`${lastOwnMedia} ${visibleText}`, feedback);
  const preview = visibleText.slice(0, 120);
  mediaEvents.unshift({ source: 'Selected window · local OCR', app: 'Visual content', title: preview, category: result.category, label: result.label, confidence: result.confidence, at: Date.now(), own: true });
  if (settings.autoPause && hasActiveFocus() && result.category === 'entertainment' && result.confidence >= 0.76) await pauseOwnMedia();
  mediaEvents = mediaEvents.slice(0, 50);
  emitState();
  return { text: preview, result };
}
function auraDayKey(date = new Date()) {
  const day = new Date(date);
  if (day.getHours() < 2) day.setDate(day.getDate() - 1);
  return `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`;
}
function streakFromDays(days) {
  const set = new Set(days || []);
  const today = new Date();
  const currentDate = new Date(today.getFullYear(), today.getMonth(), today.getDate(), 2);
  if (today.getHours() < 2) currentDate.setDate(currentDate.getDate() - 1);
  const current = auraDayKey(currentDate);
  const first = new Date(currentDate);
  if (!set.has(current)) first.setDate(first.getDate() - 1);
  if (!set.has(auraDayKey(first))) return 0;
  let count = 0;
  for (;;) {
    if (!set.has(auraDayKey(first))) return count;
    count++; first.setDate(first.getDate() - 1);
  }
}
function hasActiveFocus() { return [...peers.values()].some(p => p.focusActive && Date.now() - p.seenAt < 16000); }
function broadcast(payload) {
  if (!account || !udp) return;
  const body = JSON.stringify({ ...payload, accountId: account.accountId, deviceId, sentAt: Date.now() });
  const envelope = Buffer.from(JSON.stringify({ payload: body, signature: sign(body) }));
  if (envelope.length > 32768) return;
  for (const address of broadcastAddresses()) {
    try { udp.send(envelope, DISCOVERY_PORT, address); } catch {}
  }
}
function broadcastAddresses() {
  const out = new Set(['255.255.255.255']);
  for (const rows of Object.values(os.networkInterfaces())) for (const n of rows || []) {
    if (!n.internal && n.family === 'IPv4' && n.address && n.netmask) {
      const ip = n.address.split('.').map(Number), mask = n.netmask.split('.').map(Number);
      out.add(ip.map((v, i) => ((v & mask[i]) | (~mask[i] & 255))).join('.'));
    }
  }
  return [...out];
}
function validMessage(msg, signature) {
  if (!account || msg.accountId !== account.accountId || msg.deviceId === deviceId || !verify(JSON.stringify(msg), signature)) return false;
  return Date.now() - Number(msg.sentAt) >= 0 && Date.now() - Number(msg.sentAt) <= 10000;
}
function startLan() {
  if (!account || udp) return;
  tcpServer = net.createServer(handleTcp).listen(0, '0.0.0.0');
  udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  udp.on('message', (bytes, remote) => {
    try {
      const envelope = JSON.parse(bytes.toString('utf8'));
      const msg = JSON.parse(envelope.payload);
      if (!validMessage(msg, envelope.signature)) return;
      if (msg.kind === 'hello') {
        const previous = peers.get(msg.deviceId) || {};
        const focusActive = typeof msg.focusActive === 'boolean' ? msg.focusActive : !!previous.focusActive;
        peers.set(msg.deviceId, { ...previous, id: msg.deviceId, name: msg.deviceName || previous.name || 'Aura device', ip: remote.address, port: Number(msg.port), seenAt: Date.now(), focusActive, focusSubject: msg.focusSubject || previous.focusSubject || '' });
        emitState(); return;
      }
      if (msg.kind === 'point') {
        const id = String(msg.eventId || ''); const day = String(msg.day || ''); const points = Number(msg.points);
        if (!id || id.length > 100 || !/^\d{4}-\d{2}-\d{2}$/.test(day) || !Number.isInteger(points) || points < 1 || points > 10 || seenPointIds.has(id)) return;
        seenPointIds.add(id);
        if (seenPointIds.size > 4000) seenPointIds.delete(seenPointIds.values().next().value);
        account.stats ||= {}; account.stats.total = Number(account.stats.total || 0) + points;
        account.stats.completed = Number(account.stats.completed || 0) + 1;
        if (day === auraDayKey()) account.stats.today = Number(account.stats.today || 0) + 1;
        account.days = [...new Set([...(account.days || []), day])].sort().reverse();
        account.stats.streak = streakFromDays(account.days); account.seenPointIds = [...seenPointIds]; saveAccount(); emitState(); return;
      }
      if (msg.kind === 'focus') {
        const p = peers.get(msg.deviceId) || { id: msg.deviceId, name: 'Aura device', ip: remote.address, seenAt: Date.now() };
        p.seenAt = Date.now(); p.focusActive = !!msg.active; p.focusSubject = msg.subject || '';
        peers.set(msg.deviceId, p); emitState();
        if (p.focusActive && settings.autoPause && [...ownMediaClassifications.values()].some(result => result.category === 'entertainment' && result.confidence >= 0.76)) pauseOwnMedia();
        return;
      }
      if (msg.kind === 'clipboard' && settings.clipboard && typeof msg.text === 'string' && msg.text.length <= 6000) {
        ignoredClipboard = msg.text; clipboard.writeText(msg.text); emitState(); return;
      }
      if (msg.kind === 'media') {
        if (!settings.mediaDetection) return;
        const data = decryptMedia(msg);
        const peer = peers.get(msg.deviceId);
        const result = classify(`${data.app || ''} ${data.title || ''} ${data.artist || ''}`, feedback);
        mediaEvents.unshift({ source: peer?.name || 'Aura device', app: String(data.app || 'Media app').slice(0, 100), packageName: String(data.packageName || ''), title: String(data.title || '').slice(0, 180), category: result.category, label: result.label, confidence: result.confidence, at: Date.now() });
        if (settings.autoPause && hasActiveFocus() && result.category === 'entertainment' && result.confidence >= 0.76 && data.packageName) {
          broadcast({ kind: 'media_pause', targetDeviceId: msg.deviceId, packageName: String(data.packageName).slice(0, 180) });
        }
        mediaEvents = mediaEvents.slice(0, 50); emitState();
      }
    } catch {}
  });
  udp.on('error', () => {});
  udp.bind(DISCOVERY_PORT, '0.0.0.0', () => { try { udp.setBroadcast(true); } catch {} });
  beaconTimer = setInterval(() => broadcast({ kind: 'hello', deviceName: `Aura Sync · ${os.hostname().slice(0, 28)}`, port: tcpServer?.address()?.port || 0 }), 2000);
  pollTimer = setInterval(pollMedia, 8000);
  pollMedia();
}
function stopLan() {
  clearInterval(beaconTimer); clearInterval(pollTimer); beaconTimer = pollTimer = null;
  try { udp?.close(); } catch {} udp = null;
  try { tcpServer?.close(); } catch {} tcpServer = null;
  peers.clear();
}
function handleTcp(socket) {
  const chunks = []; let size = 0;
  socket.on('data', chunk => {
    if (size + chunk.length > 55 * 1024 * 1024) return socket.destroy();
    chunks.push(chunk); size += chunk.length;
  });
  socket.on('end', () => {
    const all = Buffer.concat(chunks, size);
    if (all.length < 6) return;
    const headerLength = all.readUInt32BE(0);
    if (headerLength < 2 || headerLength > 4096 || all.length < headerLength + 20) return;
    const headerBytes = all.subarray(4, headerLength + 4);
    let header;
    try { header = JSON.parse(headerBytes.toString('utf8')); } catch { return; }
    receiveShare(socket, header, headerBytes, all.subarray(headerLength + 4)).catch(() => {});
  });
}
async function receiveShare(socket, header, headerBytes, sealed) {
  if (!account || header.accountId !== account.accountId || header.deviceId === deviceId || sealed.length < 16) return;
  const iv = Buffer.from(header.iv, 'base64');
  const key = aesKey();
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
  decipher.setAAD(headerBytes);
  decipher.setAuthTag(sealed.subarray(-16));
  const clear = Buffer.concat([decipher.update(sealed.subarray(0, -16)), decipher.final()]);
  if (header.text === true || header.mime === 'text/plain') {
    const text = clear.toString('utf8').slice(0, 100000);
    ignoredClipboard = text; clipboard.writeText(text);
    mediaEvents.unshift({ source: header.name || 'Aura device', app: 'Shared text', category: 'share', label: text.slice(0, 180), confidence: 1, at: Date.now() }); emitState(); return;
  }
  const save = await dialog.showSaveDialog(win, { defaultPath: path.basename(header.name || 'Aura file') });
  if (!save.canceled && save.filePath) { fs.writeFileSync(save.filePath, clear); mediaEvents.unshift({ source: header.name || 'Aura device', app: 'File transfer', category: 'share', label: path.basename(save.filePath), confidence: 1, at: Date.now() }); emitState(); }
}
function sendEncrypted(peer, { name, mime, data, text }) {
  if (!peer || !account) throw Error('Choose a connected Aura device first.');
  const iv = crypto.randomBytes(12);
  const key = aesKey();
  const header = { accountId: account.accountId, deviceId, name, mime, iv: iv.toString('base64') };
  if (text) header.text = true;
  const headerBytes = Buffer.from(JSON.stringify(header));
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(headerBytes);
  const encrypted = Buffer.concat([cipher.update(data), cipher.final(), cipher.getAuthTag()]);
  const prefix = Buffer.alloc(4); prefix.writeUInt32BE(headerBytes.length);
  return new Promise((resolve, reject) => {
    const socket = net.connect(peer.port, peer.ip, () => { socket.write(Buffer.concat([prefix, headerBytes, encrypted])); socket.end(); });
    socket.on('error', reject); socket.on('close', () => resolve(true));
  });
}
function saveAccount() {
  if (!account) return;
  account.seenPointIds = [...seenPointIds];
  fs.mkdirSync(app.getPath('userData'), { recursive: true });
  const protectedValue = safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(JSON.stringify(account)).toString('base64') : null;
  fs.writeFileSync(userFile('account.json'), JSON.stringify({ encrypted: protectedValue, account: protectedValue ? undefined : { accountId: account.accountId, name: account.name, lanSyncKey: account.lanSyncKey } }), { mode: 0o600 });
  try { feedback = JSON.parse(fs.readFileSync(userFile('feedback.json'), 'utf8')); } catch {}
  try { settings = { ...settings, ...JSON.parse(fs.readFileSync(userFile('settings.json'), 'utf8')) }; } catch {}
}
function loadAccount() {
  try {
    const saved = JSON.parse(fs.readFileSync(userFile('account.json'), 'utf8'));
    if (saved.encrypted && safeStorage.isEncryptionAvailable()) account = JSON.parse(safeStorage.decryptString(Buffer.from(saved.encrypted, 'base64')));
    else account = saved.account || null;
    for (const id of account?.seenPointIds || []) seenPointIds.add(id);
  } catch {}
  try { settings = { ...settings, ...JSON.parse(fs.readFileSync(userFile('settings.json'), 'utf8')) }; } catch {}
  try { feedback = JSON.parse(fs.readFileSync(userFile('feedback.json'), 'utf8')); } catch {}
}
async function refreshAccount() {
  const ses = session.fromPartition(PARTITION);
  const response = await ses.fetch(`${URL}/api/me`, { credentials: 'include' });
  if (!response.ok) throw Error(response.status === 401 ? 'Sign in to Aura in the account window, then check again.' : `Aura account request failed (${response.status}).`);
  const data = await response.json();
  const key = data.profile?.lanSyncKey;
  const id = data.user?.id;
  if (!id || !key || key.length < 32) throw Error('Your Aura account has no local ecosystem key yet. Open Aura on Android once while signed in, then retry.');
  account = { accountId: id, lanSyncKey: key, name: data.user.name || data.user.email || 'Aura account', stats: data.stats || {} };
  account.days = Array.isArray(data.days) ? data.days : [];
  account.stats.streak = streakFromDays(account.days);
  saveAccount(); stopLan(); startLan(); emitState(); return true;
}
async function pollMedia() {
  if (!settings.mediaDetection) return;
  try {
    let lines = [];
    if (process.platform === 'linux') {
      const { stdout: status } = await execFileAsync('playerctl', ['--all-players', 'status'], { timeout: 2500, maxBuffer: 8192 });
      if (!/playing/i.test(status)) return;
      const { stdout } = await execFileAsync('playerctl', ['--all-players', 'metadata', '--format', '{{playerName}}|{{title}}|{{artist}}'], { timeout: 2500, maxBuffer: 8192 });
      lines = stdout.trim().split(/\r?\n/).filter(Boolean);
    } else if (process.platform === 'win32') {
      const { stdout } = await execFileAsync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', path.join(__dirname, 'windows-media.ps1')], { timeout: 6000, maxBuffer: 8192 });
      lines = stdout.trim().split(/\r?\n/).filter(Boolean);
    }
    const signature = lines.slice().sort().join('\n');
    if (!signature) { lastOwnMedia = ''; ownMediaClassifications.clear(); return; }
    if (signature === lastOwnMedia) return;
    lastOwnMedia = signature;
    ownMediaClassifications.clear();
    for (const line of lines) {
      const result = classify(line, feedback);
      ownMediaClassifications.set(line, result);
      mediaEvents.unshift({ source: `This ${process.platform === 'win32' ? 'PC' : 'computer'}`, app: line.split('|')[0].slice(0, 100), title: line.slice(0, 180), category: result.category, label: result.label, confidence: result.confidence, at: Date.now(), own: true });
    }
    if (settings.autoPause && hasActiveFocus() && [...ownMediaClassifications.values()].some(result => result.category === 'entertainment' && result.confidence >= 0.76)) pauseOwnMedia();
    mediaEvents = mediaEvents.slice(0, 50); emitState();
  } catch { /* Optional OS media metadata provider may be unavailable. */ }
}
async function pauseOwnMedia() {
  try {
    if (process.platform === 'linux') await execFileAsync('playerctl', ['--all-players', 'pause'], { timeout: 2500 });
    else if (process.platform === 'win32') {
      const ps = "$src='[DllImport(\"user32.dll\")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);'; Add-Type -MemberDefinition $src -Name W -Namespace Aura; [Aura.W]::keybd_event(0xB3,0,0,[UIntPtr]::Zero); [Aura.W]::keybd_event(0xB3,0,2,[UIntPtr]::Zero)";
      await execFileAsync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', ps], { timeout: 3500 });
    }
  } catch {}
}
function createWindow() {
  win = new BrowserWindow({ width: 1050, height: 760, minWidth: 720, minHeight: 560, backgroundColor: '#090d15', title: 'Aura Sync', webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true } });
  win.loadFile(path.join(__dirname, 'index.html'));
  win.on('closed', () => { win = null; });
}
app.whenReady().then(() => { loadAccount(); configureScreenCapture(); createWindow(); if (account) startLan(); });
setInterval(() => {
  if (!account || !settings.clipboard) return;
  const value = readClipboardText();
  if (!value || value === lastDesktopClipboard) return;
  lastDesktopClipboard = value;
  if (value === ignoredClipboard) { ignoredClipboard = ''; return; }
  broadcast({ kind: 'clipboard', text: value, clipAt: Date.now(), origin: deviceId });
}, 700);
app.on('before-quit', () => { try { ocrWorkerPromise?.then(worker => worker.terminate()).catch(() => {}); } catch {} });
app.on('window-all-closed', () => { screenCaptureActive = false; stopLan(); app.quit(); });
ipcMain.handle('state', () => ({ connected: !!account, accountName: account?.name || '', accountId: account?.accountId || '', devices: [...peers.values()], media: mediaEvents, settings, points: account?.stats?.total ?? null, streak: account?.stats?.streak ?? null, focus: hasActiveFocus() }));
ipcMain.handle('open-dashboard', () => { const w = new BrowserWindow({ width: 1200, height: 850, parent: win, webPreferences: { partition: PARTITION, contextIsolation: true, nodeIntegration: false, sandbox: true } }); w.loadURL(URL); });
ipcMain.handle('sign-in', async (_event, credentials) => {
  const email = String(credentials?.email || '').trim().slice(0, 254);
  const password = String(credentials?.password || '');
  if (!email || password.length < 8 || password.length > 1024) throw Error('Enter your Aura email and password.');
  const ses = session.fromPartition(PARTITION);
  let response;
  try {
    response = await ses.fetch(`${URL}/api/auth/sign-in/email`, {
      method: 'POST', credentials: 'include', cache: 'no-store',
      headers: { 'content-type': 'application/json', origin: URL },
      body: JSON.stringify({ email, password, rememberMe: true })
    });
  } catch { throw Error('Could not reach Aura sign-in. Check your internet connection and try again.'); }
  const result = await response.json().catch(() => ({}));
  if (!response.ok) throw Error(result.message || result.error || (response.status === 401 ? 'Email or password is incorrect.' : `Aura sign-in failed (${response.status}).`));
  await refreshAccount();
  return true;
});
ipcMain.handle('check-account', async () => { await refreshAccount(); return true; });
ipcMain.handle('disconnect', () => { stopLan(); account = null; try { fs.unlinkSync(userFile('account.json')); } catch {} emitState(); });
ipcMain.handle('set-setting', (_e, name, value) => { if (!['clipboard', 'mediaDetection', 'autoPause'].includes(name)) return false; settings[name] = !!value; if (name === 'autoPause' && value) settings.mediaDetection = true; if (name === 'mediaDetection' && !value) { screenCaptureActive = false; screenCaptureArmed = false; } fs.writeFileSync(userFile('settings.json'), JSON.stringify(settings), { mode: 0o600 }); if (settings.mediaDetection) pollMedia(); emitState(); return true; });
ipcMain.handle('send-text', async (_e, peerId, value) => { const peer = peers.get(peerId); await sendEncrypted(peer, { name: `Text · ${os.hostname()}`, mime: 'text/plain', data: Buffer.from(String(value).slice(0, 100000)), text: true }); });
ipcMain.handle('send-file', async (_e, peerId) => { const peer = peers.get(peerId); const picked = await dialog.showOpenDialog(win, { properties: ['openFile'] }); if (picked.canceled || !picked.filePaths[0]) return; const file = picked.filePaths[0]; const data = fs.readFileSync(file); if (data.length > 50 * 1024 * 1024) throw Error('Files must be 50 MB or smaller.'); await sendEncrypted(peer, { name: path.basename(file), mime: 'application/octet-stream', data }); });
ipcMain.handle('classify', (_e, text) => classify(String(text || '').slice(0, 500), feedback));
ipcMain.handle('feedback', (_e, text, label) => { if (!['study', 'entertainment'].includes(label)) return; feedback.push([String(text).slice(0, 300), label]); feedback = feedback.slice(-500); fs.writeFileSync(userFile('feedback.json'), JSON.stringify(feedback), { mode: 0o600 }); return classify(text, feedback); });
ipcMain.on('arm-screen-capture', event => { screenCaptureArmed = Boolean(settings.mediaDetection && win && event.sender === win.webContents); event.returnValue = screenCaptureArmed; });
ipcMain.on('stop-screen-capture', event => { if (win && event.sender === win.webContents) { screenCaptureArmed = false; screenCaptureActive = false; emitState(); } });
ipcMain.handle('inspect-screen-frame', (event, dataUrl) => { if (!win || event.sender !== win.webContents) throw Error('Screen inspection is available only from Aura Sync.'); return inspectCapturedFrame(dataUrl); });
ipcMain.on('clipboard-changed', () => { if (!account || !settings.clipboard) return; const text = readClipboardText(); if (!text || text === ignoredClipboard) { ignoredClipboard = ''; return; } broadcast({ kind: 'clipboard', text, clipAt: Date.now(), origin: deviceId }); });
