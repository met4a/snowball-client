import { contextBridge, ipcRenderer } from 'electron';

// The renderer runs sandboxed with no Node access; it can only call these whitelisted channels.
const invoke = (channel: string) => (...args: unknown[]) => ipcRenderer.invoke(channel, ...args);
const EVENTS = new Set(['progress', 'game-log', 'game-exit', 'launch-error', 'launcher-log', 'state', 'activity', 'update', 'chat-state', 'chat-message', 'chat-history', 'chat-people', 'chat-bugs', 'chat-bug-filed', 'chat-lookup', 'chat-rank-set', 'chat-history-ranks']);

const api = {
  getState: invoke('state:get'),
  listMinecraftVersions: invoke('versions:list'),
  listLoaderVersions: invoke('loaders:versions'),
  createInstance: invoke('instances:create'),
  updateInstance: invoke('instances:update'),
  deleteInstance: invoke('instances:delete'),
  cloneInstance: invoke('instances:clone'),
  openInstanceFolder: invoke('instances:open-folder'),
  listMods: invoke('mods:list'),
  setModEnabled: invoke('mods:set-enabled'),
  removeMod: invoke('mods:remove'),
  addMods: invoke('mods:add'),
  searchMods: invoke('browse:search'),
  installMod: invoke('browse:install'),
  installedModProjects: invoke('mods:installed-projects'),
  checkModUpdates: invoke('mods:check-updates'),
  updateMod: invoke('mods:update'),
  applyPerformanceProfile: invoke('perf:apply'),
  detectJava: invoke('java:detect'),
  validateJava: invoke('java:validate'),
  browseJava: invoke('java:browse'),
  launch: invoke('game:launch'),
  stop: invoke('game:stop'),
  gameLogs: invoke('game:logs'),
  gameActivity: invoke('game:activity'),
  coreStatus: invoke('core:status'),
  repairCore: invoke('core:repair'),
  verifyGameFiles: invoke('game:verify-files'),
  snowballSupport: invoke('core:supports'),
  updateSettings: invoke('settings:update'),
  addOfflineAccount: invoke('accounts:add-offline'),
  removeAccount: invoke('accounts:remove'),
  selectAccount: invoke('accounts:select'),
  signInMicrosoft: invoke('accounts:sign-in-microsoft'),
  openLauncherFolder: invoke('app:open-folder'),
  scanMods: invoke('mods:scan'),
  findOtherLaunchers: invoke('import:detect'),
  importFromLauncher: invoke('import:run'),
  chatState: invoke('chat:state'),
  joinChat: invoke('chat:connect'),
  leaveChat: invoke('chat:disconnect'),
  sendChat: invoke('chat:send'),
  announce: invoke('chat:announce'),
  moderateChat: invoke('chat:moderate'),
  setSnowballPlus: invoke('chat:set-plus'),
  setRank: invoke('chat:set-rank'),
  lookupPlayer: invoke('chat:lookup'),
  snowballStats: invoke('chat:stats'),
  reportBug: invoke('chat:report-bug'),
  chatAdmin: invoke('chat:admin'),
  appVersion: invoke('app:version'),
  updateState: invoke('updates:state'),
  checkForUpdates: invoke('updates:check'),
  installUpdate: invoke('updates:install'),
  updateDiagnostics: invoke('updates:diagnostics'),
  openLogFolder: () => ipcRenderer.invoke('app:open-folder', 'logs'),
  on(event: string, listener: (payload: unknown) => void): () => void {
    if (!EVENTS.has(event)) throw new Error(`Unknown event ${event}`);
    const wrapped = (_e: unknown, payload: unknown) => listener(payload);
    ipcRenderer.on(`evt:${event}`, wrapped);
    return () => ipcRenderer.removeListener(`evt:${event}`, wrapped);
  },
};

contextBridge.exposeInMainWorld('snowball', api);
