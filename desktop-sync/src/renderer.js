const $ = id => document.getElementById(id);
let current = { devices: [], media: [], settings: {} };
let toastTimer;
function toast(message) { const el = $('toast'); el.textContent = message; el.classList.add('show'); clearTimeout(toastTimer); toastTimer = setTimeout(() => el.classList.remove('show'), 2800); }
function safe(value) { const d = document.createElement('div'); d.textContent = String(value ?? ''); return d.innerHTML; }
function render(state) {
  current = state;
  $('connection').textContent = state.connected ? 'Aura connected' : 'Offline'; $('connection').classList.toggle('online', state.connected);
  $('accountName').textContent = state.connected ? state.accountName : 'Connect your Aura account'; $('accountHelp').textContent = state.connected ? 'Paired locally. Devices on the same Wi‑Fi are discovered automatically.' : 'Sign in once to pair this computer with Android. After setup, local device sharing works without a server.';
  $('disconnect').classList.toggle('hidden', !state.connected);
  $('points').textContent = state.points == null ? '—' : (Number(state.points) / 100).toFixed(2); $('streak').textContent = state.streak == null ? '—' : `${state.streak} days`; $('focus').textContent = state.focus ? 'Active' : 'Idle'; $('focus').style.color = state.focus ? 'var(--mint)' : '';
  $('deviceCount').textContent = String(state.devices.length);
  $('devices').innerHTML = state.devices.length ? state.devices.map(d => `<div class="device-row"><div><div class="device-name">${safe(d.name)}</div><div class="device-meta">${safe(d.ip)} · ${d.focusActive ? `Focus active${d.focusSubject ? ` · ${safe(d.focusSubject)}` : ''}` : 'Ready'} · ${safe(d.kind || 'Aura device')}</div></div><span class="status-dot"></span></div>`).join('') : '<div class="empty">No signed-in Aura devices found on this network yet.</div>';
  $('target').innerHTML = '<option value="">Choose a device</option>' + state.devices.map(d => `<option value="${safe(d.id)}">${safe(d.name)}</option>`).join('');
  $('clipboard').checked = !!state.settings.clipboard; $('mediaDetection').checked = !!state.settings.mediaDetection; $('autoPause').checked = !!state.settings.autoPause;
  $('mediaList').innerHTML = state.media.length ? state.media.map((m, i) => `<div class="media-item"><span class="badge ${m.category === 'entertainment' ? 'entertainment' : ''}">${safe(m.label)} · ${Math.round((m.confidence || 0) * 100)}%</span><div class="media-title">${safe(m.app)}</div>${m.title ? `<div class="media-meta">${safe(m.title)}</div>` : ''}<div class="media-meta">${safe(m.source)} · ${new Date(m.at).toLocaleTimeString()}</div>${m.category !== 'share' && m.category !== 'uncertain' ? `<div class="media-feedback"><button class="secondary" data-learn="study" data-index="${i}">Study / productivity</button><button class="secondary" data-learn="entertainment" data-index="${i}">Entertainment</button></div>` : ''}</div>`).join('') : '<div class="empty">No media has been checked. Turn on detection to start.</div>';
  $('mediaList').querySelectorAll('[data-learn]').forEach(button => button.addEventListener('click', async () => { const item = current.media[Number(button.dataset.index)]; const result = await window.aura.feedback(`${item.app} ${item.title || ''}`, button.dataset.learn); $('classification').textContent = `Updated local model: ${result.label} (${Math.round(result.confidence * 100)}% confidence).`; toast('Correction saved on this computer.'); }));
}
window.aura.onState(render); window.aura.state().then(render);
$('signin').onclick = () => window.aura.openAccount();
$('check').onclick = async () => { try { await window.aura.checkAccount(); toast('Aura account connected.'); } catch (e) { toast(e.message); } };
$('disconnect').onclick = () => window.aura.disconnect();
$('dashboard').onclick = () => window.aura.openDashboard();
$('clipboard').onchange = e => window.aura.setting('clipboard', e.target.checked);
$('mediaDetection').onchange = e => { window.aura.setting('mediaDetection', e.target.checked); if (e.target.checked) toast('Local media detection enabled.'); };
$('autoPause').onchange = e => { window.aura.setting('autoPause', e.target.checked); if (e.target.checked && !current.settings.mediaDetection) toast('Enable media detection first.'); };
$('sendText').onclick = async () => { try { const id = $('target').value; if (!id) throw Error('Choose a connected device first.'); const value = $('shareText').value; if (!value.trim()) throw Error('Enter text to send.'); await window.aura.sendText(id, value); $('shareText').value = ''; toast('Text sent over the local network.'); } catch (e) { toast(e.message); } };
$('sendFile').onclick = async () => { try { const id = $('target').value; if (!id) throw Error('Choose a connected device first.'); await window.aura.sendFile(id); toast('File sent over the local network.'); } catch (e) { toast(e.message); } };
$('classify').onclick = async () => { const text = $('classifyText').value.trim(); if (!text) return; const result = await window.aura.classify(text); $('classification').textContent = `${result.label} · ${Math.round(result.confidence * 100)}% confidence. This is an estimate.`; };
document.addEventListener('visibilitychange', () => { if (!document.hidden) window.aura.state().then(render); });
