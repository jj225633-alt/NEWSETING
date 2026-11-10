// 只給本機設定頁使用；手機的網頁版不會載入這個 preload。
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('orbisDesktop', {
  getConfig: () => ipcRenderer.invoke('config:get'),
  saveConfig: (config) => ipcRenderer.invoke('config:save', config),
  testConnection: (input) => ipcRenderer.invoke('config:test', input),
  reconnect: () => ipcRenderer.invoke('app:reconnect'),
  openSetup: () => ipcRenderer.invoke('app:setup'),
  bridgeGet: () => ipcRenderer.invoke('bridge:get'),
  bridgeSave: (input) => ipcRenderer.invoke('bridge:save', input),
});
