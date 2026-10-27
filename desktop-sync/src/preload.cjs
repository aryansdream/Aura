const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('aura', {
  state: () => ipcRenderer.invoke('state'),
  signIn: credentials => ipcRenderer.invoke('sign-in', credentials),
  checkAccount: () => ipcRenderer.invoke('check-account'),
  openDashboard: () => ipcRenderer.invoke('open-dashboard'),
  disconnect: () => ipcRenderer.invoke('disconnect'),
  setting: (name, value) => ipcRenderer.invoke('set-setting', name, value),
  sendText: (deviceId, value) => ipcRenderer.invoke('send-text', deviceId, value),
  sendFile: deviceId => ipcRenderer.invoke('send-file', deviceId),
  classify: text => ipcRenderer.invoke('classify', text),
  feedback: (text, label) => ipcRenderer.invoke('feedback', text, label),
  armScreenCapture: () => ipcRenderer.sendSync('arm-screen-capture'),
  stopScreenCapture: () => ipcRenderer.send('stop-screen-capture'),
  inspectScreenFrame: dataUrl => ipcRenderer.invoke('inspect-screen-frame', dataUrl),
  onState: callback => ipcRenderer.on('state', (_event, value) => callback(value)),
  clipboardChanged: () => ipcRenderer.send('clipboard-changed')
});
