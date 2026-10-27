const $ = id => document.getElementById(id);
let current = { devices: [], media: [], settings: {} };
let toastTimer;
let screenCaptureStream = null;
let screenScanVideo = null;
let screenScanTimer = null;
let screenScanBusy = false;
function toast(message) { const el = $('toast'); el.textContent = message; el.classList.add('show'); clearTimeout(toastTimer); toastTimer = setTimeout(() => el.classList.remove('show'), 2800); }
function safe(value) { const d = document.createElement('div'); d.textContent = String(value ?? ''); return d.innerHTML; }
function render(state) {
  current = state;
  if (!state.settings.mediaDetection && screenCaptureStream) stopScreenScan(false);
  $('connection').textContent = state.connected ? 'Aura connected' : 'Offline'; $('connection').classList.toggle('online', state.connected);
  $('accountName').textContent = state.connected ? state.accountName : 'Connect your Aura account'; $('accountHelp').textContent = state.connected ? 'Paired locally. Devices on the same Wi‑Fi are discovered automatically.' : 'Sign in once to pair this computer with Android. After setup, local device sharing works without a server.';
  $('disconnect').classList.toggle('hidden', !state.connected);
  $('points').textContent = state.points == null ? '—' : (Number(state.points) / 100).toFixed(2); $('streak').textContent = state.streak == null ? '—' : `${state.streak} days`; $('focus').textContent = state.focus ? 'Active' : 'Idle'; $('focus').style.color = state.focus ? 'var(--mint)' : '';
  $('deviceCount').textContent = String(state.devices.length);
  $('devices').innerHTML = state.devices.length ? state.devices.map(d => `<div class="device-row"><div><div class="device-name">${safe(d.name)}</div><div class="device-meta">${safe(d.ip)} · ${d.focusActive ? `Focus active${d.focusSubject ? ` · ${safe(d.focusSubject)}` : ''}` : 'Ready'} · ${safe(d.kind || 'Aura device')}</div></div><span class="status-dot"></span></div>`).join('') : '<div class="empty">No signed-in Aura devices found on this network yet.</div>';
  $('target').innerHTML = '<option value="">Choose a device</option>' + state.devices.map(d => `<option value="${safe(d.id)}">${safe(d.name)}</option>`).join('');
  $('clipboard').checked = !!state.settings.clipboard; $('mediaDetection').checked = !!state.settings.mediaDetection; $('autoPause').checked = !!state.settings.autoPause; $('screenCapture').checked = !!state.screenCaptureActive;
  $('mediaList').innerHTML = state.media.length ? state.media.map((m, i) => `<div class="media-item"><span class="badge ${m.category === 'entertainment' ? 'entertainment' : ''}">${safe(m.label)} · ${Math.round((m.confidence || 0) * 100)}%</span><div class="media-title">${safe(m.app)}</div>${m.title ? `<div class="media-meta">${safe(m.title)}</div>` : ''}<div class="media-meta">${safe(m.source)} · ${new Date(m.at).toLocaleTimeString()}</div>${m.category !== 'share' ? `<div class="media-feedback"><button class="secondary" data-learn="study" data-index="${i}">Study / productivity</button><button class="secondary" data-learn="entertainment" data-index="${i}">Entertainment</button></div>` : ''}</div>`).join('') : '<div class="empty">No media has been checked. Turn on detection to start.</div>';
  $('mediaList').querySelectorAll('[data-learn]').forEach(button => button.addEventListener('click', async () => { const item = current.media[Number(button.dataset.index)]; const result = await window.aura.feedback(`${item.app} ${item.title || ''}`, button.dataset.learn); $('classification').textContent = `Updated local model: ${result.label} (${Math.round(result.confidence * 100)}% confidence).`; toast('Correction saved on this computer.'); }));
}
function stopScreenScan(notifyMain = true) {
  clearInterval(screenScanTimer); screenScanTimer = null;
  const stream = screenCaptureStream;
  screenCaptureStream = null;
  if (stream) for (const track of stream.getTracks()) track.stop();
  if (screenScanVideo) { screenScanVideo.pause(); screenScanVideo.srcObject = null; screenScanVideo.remove(); }
  screenScanVideo = null;
  screenScanBusy = false;
  if (notifyMain) window.aura.stopScreenCapture();
}
async function inspectWindowFrame() {
  if (!screenScanVideo?.videoWidth || screenScanBusy || !current.settings.mediaDetection) return;
  screenScanBusy = true;
  try {
    const scale = Math.min(1, 960 / screenScanVideo.videoWidth, 540 / screenScanVideo.videoHeight);
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(screenScanVideo.videoWidth * scale));
    canvas.height = Math.max(1, Math.round(screenScanVideo.videoHeight * scale));
    canvas.getContext('2d', { alpha: false }).drawImage(screenScanVideo, 0, 0, canvas.width, canvas.height);
    const frame = canvas.toDataURL('image/jpeg', 0.48);
    const { text, result } = await window.aura.inspectScreenFrame(frame);
    if (text) $('classification').textContent = `Screen text: ${text} · ${result.label} · ${Math.round(result.confidence * 100)}% confidence`;
    else $('classification').textContent = 'The selected window has no readable media text yet.';
  } catch (error) {
    $('classification').textContent = error.message || 'Could not inspect the selected window.';
    toast('Visual scan stopped. Check screen-capture permission.');
    stopScreenScan();
  } finally {
    screenScanBusy = false;
  }
}
async function startScreenScan() {
  if (!current.settings.mediaDetection) throw Error('Turn on media detection first.');
  if (!navigator.mediaDevices?.getDisplayMedia) throw Error('Screen capture is not available on this system.');
  if (!window.aura.armScreenCapture()) throw Error('Aura could not arm screen capture.');
  try {
    screenCaptureStream = await navigator.mediaDevices.getDisplayMedia({
      video: { frameRate: { ideal: 1, max: 1 } },
      audio: false
    });
    screenScanVideo = document.createElement('video');
    screenScanVideo.muted = true;
    screenScanVideo.playsInline = true;
    screenScanVideo.style.display = 'none';
    screenScanVideo.srcObject = screenCaptureStream;
    document.body.appendChild(screenScanVideo);
    await screenScanVideo.play();
    for (const track of screenCaptureStream.getTracks()) track.addEventListener('ended', () => stopScreenScan());
    await new Promise(resolve => setTimeout(resolve, 900));
    await inspectWindowFrame();
    screenScanTimer = setInterval(inspectWindowFrame, 12000);
  } catch (error) {
    stopScreenScan();
    throw error;
  }
}
window.aura.onState(render); window.aura.state().then(render);
const signInDialog = $('signInDialog'), signInForm = $('signInForm');
function openSignIn(message = '') { $('signInError').textContent = message; signInDialog.showModal(); $('signInForm').elements.email.focus(); }
$('signin').onclick = () => openSignIn();
$('signInClose').onclick = () => signInDialog.close();
signInDialog.addEventListener('close', () => { signInForm.reset(); $('signInError').textContent = ''; });
signInDialog.addEventListener('click', event => { if (event.target === signInDialog) signInDialog.close(); });
signInForm.addEventListener('submit', async event => {
  event.preventDefault();
  const email = String(signInForm.elements.email.value || '').trim();
  const password = String(signInForm.elements.password.value || '');
  const submit = $('signInSubmit');
  submit.disabled = true; submit.textContent = 'Signing in…'; $('signInError').textContent = '';
  try { await window.aura.signIn({ email, password }); signInForm.reset(); signInDialog.close(); toast('Aura account connected. Looking for devices on this Wi‑Fi…'); }
  catch (error) { $('signInError').textContent = error.message || 'Could not sign in. Check your details and try again.'; }
  finally { submit.disabled = false; submit.textContent = 'Sign in'; }
});
$('check').onclick = async () => {
  if (!current.connected) { openSignIn('Sign in here first. Aura Sync uses its own secure session.'); return; }
  try { await window.aura.checkAccount(); toast('Aura account connected.'); } catch (e) { toast(e.message); }
};
$('disconnect').onclick = () => window.aura.disconnect();
$('dashboard').onclick = () => window.aura.openDashboard();
$('clipboard').onchange = e => window.aura.setting('clipboard', e.target.checked);
$('mediaDetection').onchange = e => { window.aura.setting('mediaDetection', e.target.checked); if (e.target.checked) toast('Local media detection enabled.'); };
$('screenCapture').onchange = async e => {
  if (!e.target.checked) { stopScreenScan(); toast('Visual scan stopped.'); return; }
  try { await startScreenScan(); toast('Local visual scan is running.'); }
  catch (error) { e.target.checked = false; stopScreenScan(); toast(error.message || 'Could not start screen capture.'); }
};
$('autoPause').onchange = e => { window.aura.setting('autoPause', e.target.checked); if (e.target.checked && !current.settings.mediaDetection) toast('Enable media detection first.'); };
$('sendText').onclick = async () => { try { const id = $('target').value; if (!id) throw Error('Choose a connected device first.'); const value = $('shareText').value; if (!value.trim()) throw Error('Enter text to send.'); await window.aura.sendText(id, value); $('shareText').value = ''; toast('Text sent over the local network.'); } catch (e) { toast(e.message); } };
$('sendFile').onclick = async () => { try { const id = $('target').value; if (!id) throw Error('Choose a connected device first.'); await window.aura.sendFile(id); toast('File sent over the local network.'); } catch (e) { toast(e.message); } };
$('classify').onclick = async () => { const text = $('classifyText').value.trim(); if (!text) return; const result = await window.aura.classify(text); $('classification').textContent = `${result.label} · ${Math.round(result.confidence * 100)}% confidence. This is an estimate.`; };
document.addEventListener('visibilitychange', () => { if (!document.hidden) window.aura.state().then(render); });
