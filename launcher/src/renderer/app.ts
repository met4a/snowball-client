/// <reference path="../shared/api.d.ts" />
// Renderer for the Snowball Client launcher. Plain DOM + the preload bridge; no Node access.

(() => {
  const api = window.snowball;

  type View = 'home' | 'instances' | 'mods' | 'browse' | 'chat' | 'admin' | 'java' | 'settings';

  const ui = {
    state: null as Snowball.AppState | null,
    view: 'home' as View,
    selectedId: null as string | null,
    progress: new Map<string, Snowball.Progress>(),
    logs: new Map<string, Snowball.GameLog[]>(),
    launcherLogs: [] as Snowball.LauncherLog[],
    busy: new Set<string>(),
    /** Mod browser state survives re-renders so results and filters are not lost. */
    browse: {
      instanceId: null as string | null,
      query: '',
      sort: 'relevance',
      loader: '',
      version: '',
      hits: [] as Snowball.ModSearchHit[],
      total: 0,
      loading: false,
      searched: false,
      error: null as string | null,
      installed: new Set<string>(),
      installing: new Set<string>(),
      versions: [] as string[],
      seq: 0,
      paint: () => {},
    },
    updates: new Map<string, Map<string, Snowball.ModUpdate>>(),
    activity: new Map<string, Snowball.ActivityEvent[]>(),
    logMode: 'activity' as 'activity' | 'technical',
    update: null as Snowball.UpdateState | null,
    version: '',
    chat: null as Snowball.ChatState | null,
    chatMessages: [] as Snowball.ChatMessage[],
    stats: null as Snowball.SnowballStats | null,
    people: [] as Snowball.SnowballPerson[],
    bugs: [] as Snowball.BugReport[],
    adminTab: 'chat' as 'chat' | 'ranks' | 'people' | 'bugs' | 'flags',
    lookup: null as Snowball.RankLookup | null,
  };

  // ---------- DOM helpers ----------
  type Child = Node | string | null | undefined | false;
  type Props = Record<string, unknown> & { class?: string; onClick?: (e: MouseEvent) => void };

  function h<K extends keyof HTMLElementTagNameMap>(tag: K, props: Props = {}, ...children: Child[]): HTMLElementTagNameMap[K] {
    const el = document.createElement(tag);
    for (const [key, value] of Object.entries(props)) {
      if (value === undefined || value === null || value === false) continue;
      if (key === 'class') el.className = String(value);
      else if (key === 'onClick') el.addEventListener('click', value as EventListener);
      else if (key === 'onInput') el.addEventListener('input', value as EventListener);
      else if (key === 'onChange') el.addEventListener('change', value as EventListener);
      else if (key in el && typeof value !== 'string') (el as unknown as Record<string, unknown>)[key] = value;
      else el.setAttribute(key, String(value));
    }
    for (const child of children) {
      if (child === null || child === undefined || child === false) continue;
      el.append(typeof child === 'string' ? document.createTextNode(child) : child);
    }
    return el;
  }

  // Static, trusted icon markup (no user data is ever inserted here).
  const ICONS: Record<string, string> = {
    home: '<path d="M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/>',
    chat: '<path d="M21 12a8 8 0 0 1-8 8H7l-4 3V12a8 8 0 0 1 8-8h2a8 8 0 0 1 8 8z"/>',
    admin: '<path d="M12 3l7 3v6c0 4.5-3 8-7 9-4-1-7-4.5-7-9V6z"/>',
    instances: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
    mods: '<path d="M12 2l9 5v10l-9 5-9-5V7z"/><path d="M12 22V12M21 7l-9 5-9-5"/>',
    browse: '<circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/>',
    lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
    java: '<path d="M6 8h11v5a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5z"/><path d="M17 9h1.5a2.5 2.5 0 0 1 0 5H17M9 2v3M13 2v3"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
  };

  function icon(name: string): SVGSVGElement {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('fill', 'none');
    svg.setAttribute('stroke', 'currentColor');
    svg.setAttribute('stroke-width', '1.8');
    svg.setAttribute('stroke-linecap', 'round');
    svg.setAttribute('stroke-linejoin', 'round');
    svg.innerHTML = ICONS[name] ?? '';
    return svg;
  }

  function toggle(on: boolean, onChange: (value: boolean) => void, disabled = false): HTMLButtonElement {
    const el = h('button', { class: `toggle${on ? ' on' : ''}`, role: 'switch', 'aria-checked': String(on), disabled });
    el.addEventListener('click', () => onChange(!el.classList.contains('on')));
    return el;
  }

  function errorMessage(err: unknown): string {
    return String((err as Error)?.message ?? err).replace(/^Error invoking remote method '[^']+': (Error: )?/, '');
  }

  function toast(message: string, kind: 'info' | 'error' = 'info'): void {
    const el = h('div', { class: `toast${kind === 'error' ? ' error' : ''}` }, message);
    document.getElementById('toasts')!.append(el);
    setTimeout(() => el.remove(), kind === 'error' ? 7000 : 3500);
  }

  async function guard<T>(fn: () => Promise<T>): Promise<T | undefined> {
    try {
      return await fn();
    } catch (err) {
      toast(errorMessage(err), 'error');
      return undefined;
    }
  }

  interface ModalAction {
    label: string;
    kind?: 'primary' | 'danger' | 'ghost';
    onClick: (close: () => void) => void | Promise<void>;
  }

  function modal(title: string, body: Node, actions: ModalAction[]): () => void {
    const root = document.getElementById('modal-root')!;
    const close = () => {
      backdrop.remove();
      document.removeEventListener('keydown', onKey);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close();
    };
    const buttons = actions.map((a) => {
      const b = h('button', { class: `btn ${a.kind ?? ''}` }, a.label);
      b.addEventListener('click', async () => {
        b.disabled = true;
        try {
          await a.onClick(close);
        } finally {
          b.disabled = false;
        }
      });
      return b;
    });
    const backdrop = h('div', { class: 'modal-backdrop' }, h('div', { class: 'modal', role: 'dialog', 'aria-label': title }, h('h2', {}, title), body, h('div', { class: 'modal-actions' }, ...buttons)));
    backdrop.addEventListener('mousedown', (e) => {
      if (e.target === backdrop) close();
    });
    document.addEventListener('keydown', onKey);
    root.append(backdrop);
    return close;
  }

  function confirmDialog(title: string, message: string, confirmLabel: string, onConfirm: () => Promise<void>): void {
    modal(title, h('p', { class: 'muted' }, message), [
      { label: 'Cancel', kind: 'ghost', onClick: (close) => close() },
      { label: confirmLabel, kind: 'danger', onClick: async (close) => { await guard(onConfirm); close(); } },
    ]);
  }

  // ---------- formatting ----------
  const LOADER_NAMES: Record<Snowball.LoaderId, string> = { vanilla: 'Vanilla', fabric: 'Fabric', quilt: 'Quilt', forge: 'Forge', neoforge: 'NeoForge' };
  const fmtMemory = (mb: number) => (mb >= 1024 ? `${(mb / 1024).toFixed(mb % 1024 ? 1 : 0)} GB` : `${mb} MB`);
  const fmtDuration = (ms: number) => {
    const h = Math.floor(ms / 3_600_000);
    const m = Math.floor(ms / 60_000) % 60;
    return h ? `${h}h ${m}m` : `${m}m`;
  };
  const fmtCount = (n: number) => (n >= 1e6 ? `${(n / 1e6).toFixed(1)}M` : n >= 1e3 ? `${(n / 1e3).toFixed(1)}K` : String(n));
  const fmtAgo = (iso: string) => {
    const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000);
    if (!Number.isFinite(days)) return 'unknown';
    if (days < 1) return 'today';
    if (days < 30) return `${days} day${days === 1 ? '' : 's'} ago`;
    if (days < 365) return `${Math.floor(days / 30)} month${days < 60 ? '' : 's'} ago`;
    return `${Math.floor(days / 365)} year${days < 730 ? '' : 's'} ago`;
  };
  /** "3 min ago" for anything recent, a date for anything older. */
  const fmtWhen = (at: number): string => {
    const ms = Date.now() - at;
    if (ms < 60_000) return 'just now';
    if (ms < 3_600_000) return `${Math.round(ms / 60_000)} min ago`;
    if (ms < 86_400_000) return `${Math.round(ms / 3_600_000)} h ago`;
    return new Date(at).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
  };
  const fmtDate = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) : 'Never');

  // ---------- state ----------
  async function refresh(): Promise<void> {
    const state = await api.getState();
    ui.state = state;
    document.body.classList.toggle('reduce-motion', state.settings.appearance.reduceMotion);
    if (!ui.selectedId || !state.instances.some((i) => i.id === ui.selectedId)) {
      ui.selectedId = state.settings.selectedInstanceId && state.instances.some((i) => i.id === state.settings.selectedInstanceId) ? state.settings.selectedInstanceId : state.instances[0]?.id ?? null;
    }
    render();
  }

  function selected(): Snowball.Instance | undefined {
    return ui.state?.instances.find((i) => i.id === ui.selectedId);
  }

  function select(id: string): void {
    ui.selectedId = id;
    void api.updateSettings({ selectedInstanceId: id });
    render();
  }

  // ---------- layout ----------
  /** The ranks, their short tags and their colours: one table the whole launcher reads from. */
  const RANKS: { id: string; tag: string; name: string; color: string }[] = [
    { id: 'snowball', tag: 'Snowball', name: 'Snowball', color: '#c7d2dd' },
    { id: 'plus', tag: 'Snowball+', name: 'Snowball Plus', color: '#9fd8ff' },
    { id: 'tester', tag: 'Tester', name: 'Snowball Tester', color: '#5cd6a8' },
    { id: 'bug_hunter', tag: 'Bug Hunter', name: 'Snowball Bug Hunter', color: '#ffd166' },
    { id: 'partner', tag: 'Partner', name: 'Snowball Partner', color: '#ffa24d' },
    { id: 'staff', tag: 'Staff', name: 'Snowball Staff', color: '#5c8cff' },
    { id: 'developer', tag: 'Developer', name: 'Snowball Developer', color: '#b57bff' },
    { id: 'owner', tag: 'Owner', name: 'Snowball Owner', color: '#7fcbff' },
  ];

  const rankInfo = (id: string | undefined) => RANKS.find((r) => r.id === id) ?? RANKS[0];

  /** The little [Tag] chip used in chat, the people list and profiles. */
  function rankChip(id: string | undefined, extra = ''): HTMLElement {
    const rank = rankInfo(id);
    const chip = h('span', { class: 'rank-chip' + (extra ? ' ' + extra : '') }, rank.tag);
    chip.style.setProperty('--rank', rank.color);
    return chip;
  }

  const NAV: Array<[View, string]> = [
    ['home', 'HOME'],
    ['instances', 'INSTANCES'],
    ['mods', 'MODS'],
    ['browse', 'BROWSE'],
    ['chat', 'CHAT'],
    ['admin', 'ADMIN'],
    ['java', 'JAVA'],
    ['settings', 'SETTINGS'],
  ];

  function render(): void {
    const nav = document.getElementById('nav')!;
    // Admin only appears for an account the Snowball server says may use it.
    const entries = NAV.filter(([view]) => view !== 'admin' || Boolean(ui.chat?.admin));
    nav.replaceChildren(...entries.map(([view, label]) => h('button', { class: `nav-item${ui.view === view ? ' active' : ''}`, onClick: () => { ui.view = view; render(); } }, icon(view), label)));

    const state = ui.state;
    const account = state?.accounts.find((a) => a.id === state.settings.accounts.selectedAccountId) ?? state?.accounts[0];
    document.getElementById('account-chip')!.replaceChildren(
      h('button', { class: 'account-chip', onClick: () => { ui.view = 'settings'; render(); } },
        h('div', { class: 'avatar' }, account ? account.name.slice(0, 1).toUpperCase() : '?'),
        h('div', { class: 'grow' }, h('div', {}, account ? account.name : 'No account'), h('div', { class: 'muted', style: 'font-size:12px' }, account ? (account.type === 'msa' ? 'Microsoft' : 'Offline') : 'Add one in Settings'))),
    );

    const content = document.getElementById('content')!;
    if (!state) {
      content.replaceChildren(h('div', { class: 'empty muted' }, 'Loading...'));
      return;
    }
    const views: Record<View, () => Node> = { home: homeView, instances: instancesView, mods: modsView, browse: browseView, chat: chatView, admin: adminView, java: javaView, settings: settingsView };
    const banners = [announcementBanner(), updateBanner()].filter((b): b is HTMLElement => b !== null);
    content.replaceChildren(...banners, views[ui.view]());
  }

  /** The Snowball team's message of the day, when there is one. */
  function announcementBanner(): HTMLElement | null {
    const text = ui.chat?.announcement;
    if (!text) return null;
    return h('div', { class: 'update-bar announce' },
      h('div', { class: 'update-dot' }),
      h('div', { class: 'grow' }, h('div', { class: 'muted', style: 'font-size:11px;letter-spacing:2px' }, 'SNOWBALL'), h('div', {}, text)));
  }

  /** Shown while a new launcher version downloads and once it is ready to take over. */
  function updateBanner(): HTMLElement | null {
    const state = ui.update;
    if (!state) return null;
    if (state.status === 'downloading') {
      return h('div', { class: 'update-bar' },
        h('div', { class: 'update-dot' }),
        h('div', { class: 'grow' },
          h('div', {}, `Downloading Snowball Client ${state.newVersion}`),
          h('div', { class: 'progress thin' }, h('div', { style: `width:${state.percent}%` }))),
        h('div', { class: 'muted' }, `${state.percent}%`));
    }
    if (state.status === 'ready') {
      return h('div', { class: 'update-bar ready' },
        h('div', { class: 'update-dot' }),
        h('div', { class: 'grow' },
          h('div', {}, `Snowball Client ${state.newVersion} is ready`),
          h('div', { class: 'muted', style: 'font-size:13px' }, 'It takes a few seconds, and your instances are untouched.')),
        h('button', { class: 'btn small primary', onClick: () => void api.installUpdate() }, 'Restart now'));
    }
    return null;
  }

  function header(title: string, subtitle: string, ...actions: Child[]): HTMLElement {
    return h('div', { class: 'page-head' },
      h('div', { class: 'page-head-text' }, h('h1', { class: 'page-title' }, title), h('p', { class: 'page-sub' }, subtitle)),
      h('div', { class: 'page-head-actions' }, ...actions));
  }

  function emptyState(message: string): HTMLElement {
    return h('div', { class: 'card empty' },
      h('img', { src: 'assets/logo.png', alt: '' }),
      h('h2', { class: 'page-title', style: 'margin-top:16px' }, 'NO INSTANCES'),
      h('p', { class: 'muted' }, message),
      h('div', { class: 'row', style: 'justify-content:center' },
        h('button', { class: 'btn primary', onClick: () => createInstanceDialog() }, 'Create instance'),
        h('button', { class: 'btn', onClick: () => importDialog() }, 'Escaping another launcher?')));
  }

  // ---------- play controls ----------
  function playButton(inst: Snowball.Instance, big = false): HTMLElement {
    const progress = ui.progress.get(inst.id);
    const preparing = ui.busy.has(inst.id);
    if (inst.running) {
      return h('button', { class: `btn danger${big ? ' play' : ''}`, onClick: (e: MouseEvent) => { e.stopPropagation(); void guard(() => api.stop(inst.id)); } }, 'STOP');
    }
    return h('button', {
      class: `btn primary${big ? ' play' : ''}`,
      disabled: preparing || !!inst.error,
      onClick: (e: MouseEvent) => {
        e.stopPropagation();
        void launch(inst.id);
      },
    }, preparing ? (progress?.stage ? progress.stage.toUpperCase().slice(0, 22) : 'PREPARING') : 'PLAY');
  }

  async function launch(id: string): Promise<void> {
    const state = ui.state!;
    if (state.accounts.length === 0) {
      toast('Add a Microsoft account in Settings before playing.', 'error');
      ui.view = 'settings';
      render();
      return;
    }
    ui.busy.add(id);
    ui.logs.set(id, []);
    render();
    const ok = await guard(() => api.launch(id));
    if (ok === undefined) {
      ui.busy.delete(id);
      render();
    }
  }

  /** The steps a launch goes through, so the progress card can show what is done and what is next. */
  const LAUNCH_STEPS = ['Checking game files', 'Downloading libraries', 'Downloading game assets', 'Checking core files', 'Loading Minecraft'];

  function stepIndex(stage: string | undefined): number {
    if (!stage) return 0;
    const lower = stage.toLowerCase();
    const found = LAUNCH_STEPS.findIndex((step) => lower.startsWith(step.toLowerCase().slice(0, 12)));
    return found < 0 ? 0 : found;
  }

  function progressBar(id: string): HTMLElement | null {
    if (!ui.busy.has(id)) return null;
    const p = ui.progress.get(id);
    const pct = p?.total ? Math.round((100 * (p.completed ?? 0)) / p.total) : null;
    const current = stepIndex(p?.stage);
    return h('div', { class: 'launch-progress' },
      h('div', { class: 'launch-progress-head' },
        h('div', { class: 'launch-stage' }, p?.stage ?? 'Preparing'),
        h('div', { class: 'launch-pct' }, pct === null ? '' : `${pct}%`)),
      h('div', { class: `progress${pct === null ? ' indeterminate' : ''}` }, h('div', { style: pct === null ? '' : `width:${pct}%` })),
      h('div', { class: 'launch-steps' }, ...LAUNCH_STEPS.map((step, i) =>
        h('div', { class: `launch-step${i < current ? ' done' : i === current ? ' active' : ''}` }, h('span', { class: 'launch-step-dot' }), step))),
      p && pct !== null ? h('div', { class: 'muted', style: 'font-size:12px' }, `${p.completed} of ${p.total} files`) : null);
  }

  // ---------- views ----------
  function homeView(): Node {
    const state = ui.state!;
    const inst = selected();
    if (!inst) return h('div', {}, header('HOME', 'Welcome to Snowball Client'), emptyState('Create an instance to install and play Minecraft.'));

    const picker = h('select', { class: 'input picker', onChange: (e: Event) => select((e.target as HTMLSelectElement).value) },
      ...state.instances.map((i) => h('option', { value: i.id, selected: i.id === inst.id }, i.name)));

    const modsValue = h('div', { class: 'stat-value' }, '...');
    if (inst.loader !== 'vanilla') {
      void api.listMods(inst.id).then((r) => (modsValue.textContent = `${r.mods.filter((m) => m.enabled).length} enabled`)).catch(() => (modsValue.textContent = '-'));
    } else {
      modsValue.textContent = 'None';
    }
    const stat = (label: string, value: Node | string) => h('div', { class: 'stat' }, h('div', { class: 'stat-label' }, label), typeof value === 'string' ? h('div', { class: 'stat-value' }, value) : value);

    const logView = h('div', { class: 'log-view', id: 'home-log' });
    const logTabs = h('div', { class: 'log-tabs' });
    const paintTabs = () => logTabs.replaceChildren(...(['activity', 'technical'] as const).map((mode) =>
      h('button', { class: `log-tab${ui.logMode === mode ? ' active' : ''}`, onClick: () => { ui.logMode = mode; paintTabs(); fillLog(logView, inst.id); } }, mode === 'activity' ? 'ACTIVITY' : 'TECHNICAL')));
    paintTabs();
    fillLog(logView, inst.id);
    if (!ui.activity.has(inst.id)) {
      void api.gameActivity(inst.id).then((events) => {
        if (ui.activity.get(inst.id)?.length) return;
        ui.activity.set(inst.id, events);
        fillLog(logView, inst.id);
      }).catch(() => undefined);
    }
    if (inst.running && !ui.logs.get(inst.id)?.length) {
      void api.gameLogs(inst.id).then((lines) => {
        ui.logs.set(inst.id, lines);
        fillLog(logView, inst.id);
      });
    }

    const verifyButton = h('button', { class: 'btn ghost', disabled: inst.running }, 'Verify files') as HTMLButtonElement;
    verifyButton.addEventListener('click', async () => {
      verifyButton.disabled = true;
      verifyButton.textContent = 'Checking...';
      const result = await guard(() => api.verifyGameFiles(inst.id));
      verifyButton.disabled = inst.running;
      verifyButton.textContent = 'Verify files';
      if (result) toast('Game files checked. Anything missing or damaged was downloaded again.');
    });

    return h('div', {},
      header('HOME', 'Selected instance', picker),
      playerCount(),
      inst.error ? h('div', { class: 'issue' }, `This instance could not be loaded: ${inst.error}`) : null,
      h('div', { class: 'hero' },
        h('div', { class: 'card hero-main' },
          h('div', { class: 'row' },
            h('span', { class: 'tag accent' }, inst.loaderName),
            inst.snowball.supported ? h('span', { class: 'tag accent' }, `SNOWBALL CLIENT ${inst.snowball.version}`) : null,
            inst.running ? h('span', { class: 'tag running' }, 'RUNNING') : null),
          h('div', { class: 'hero-name' }, inst.name),
          h('div', { class: 'stats' },
            stat('Minecraft', inst.minecraftVersion),
            stat('Loader', inst.loader === 'vanilla' ? 'Vanilla' : `${inst.loaderName} ${inst.loaderVersion ?? '(latest)'}`),
            stat('Mods', modsValue),
            stat('Memory', fmtMemory(inst.memory.maxMb)),
            stat('Last played', fmtDate(inst.lastPlayed)),
            stat('Play time', fmtDuration(inst.totalPlayMs))),
          h('div', { class: 'row' },
            playButton(inst, true),
            h('button', { class: 'btn', onClick: () => editInstanceDialog(inst) }, 'Edit'),
            h('button', { class: 'btn ghost', onClick: () => void guard(() => api.openInstanceFolder(inst.id, 'root')) }, 'Open folder'),
            verifyButton),
          progressBar(inst.id)),
        h('div', { class: 'card output-card' },
          h('div', { class: 'row log-head' }, h('div', { class: 'section-title' }, 'OUTPUT'), h('div', { class: 'spacer' }), logTabs),
          logView)));
  }

  /**
   * How many people are on Snowball, counted by the Snowball server itself. It is hidden when this
   * build has no server set, and when the server cannot be reached, rather than showing a zero.
   */
  function playerCount(): HTMLElement | null {
    const stats = ui.stats;
    if (!stats || (!stats.online && !stats.week && !stats.total)) return null;
    const number = (value: number, label: string) => h('div', { class: 'count-item' },
      h('div', { class: 'count-value' }, value.toLocaleString()), h('div', { class: 'count-label' }, label));
    return h('div', { class: 'card count-card' },
      h('div', { class: 'count-dot' }),
      h('div', { class: 'grow' },
        h('div', { class: 'section-title' }, 'SNOWBALL PLAYERS'),
        h('div', { class: 'muted', style: 'font-size:12px' }, stats.online === 1 ? 'One person is playing right now' : `${stats.online.toLocaleString()} people are playing right now`)),
      number(stats.today, 'TODAY'),
      number(stats.week, 'THIS WEEK'),
      number(stats.total, 'ALL TIME'));
  }

  function refreshStats(): void {
    void api.snowballStats().then((stats) => {
      if (!stats) return;
      const changed = JSON.stringify(stats) !== JSON.stringify(ui.stats);
      ui.stats = stats;
      if (changed && ui.view === 'home') render();
    }).catch(() => undefined);
  }

  function activityRow(e: Snowball.ActivityEvent): HTMLElement {
    const time = new Date(e.time).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    return h('div', { class: `act ${e.level}` }, h('span', { class: 'act-time' }, time), h('span', { class: 'act-tag' }, '[Snowball]'), h('span', { class: 'act-msg' }, e.message));
  }

  function techRow(l: Snowball.GameLog): HTMLElement {
    const bad = l.level ? l.level === 'ERROR' || l.level === 'FATAL' : l.stream === 'stderr' || /ERROR|Exception/.test(l.line);
    return h('div', { class: bad ? 'err' : l.level === 'WARN' ? 'warn' : '' }, l.line);
  }

  /** Activity is the readable timeline; the technical log is the game's own output. */
  function fillLog(view: HTMLElement, id: string): void {
    if (ui.logMode === 'activity') {
      const events = (ui.activity.get(id) ?? []).slice(-300);
      view.replaceChildren(...(events.length ? events.map(activityRow) : [h('span', { class: 'muted' }, 'Press Play and Snowball shows each step here.')]));
    } else {
      const lines = (ui.logs.get(id) ?? []).slice(-300);
      view.replaceChildren(...(lines.length ? lines.map(techRow) : [h('span', { class: 'muted' }, 'Game output appears here while the instance is running.')]));
    }
    view.scrollTop = view.scrollHeight;
  }

  function instancesView(): Node {
    const state = ui.state!;
    const newButton = h('button', { class: 'btn primary', onClick: () => createInstanceDialog() }, '+ New instance');
    const importButton = h('button', { class: 'btn', onClick: () => importDialog() }, 'Import from another launcher');
    if (state.instances.length === 0) return h('div', {}, header('INSTANCES', 'Isolated game installations'), emptyState('Each instance has its own version, mods, settings and worlds.'));
    return h('div', {},
      header('INSTANCES', `${state.instances.length} isolated installation${state.instances.length === 1 ? '' : 's'}`, importButton, newButton),
      h('div', { class: 'grid cards' }, ...state.instances.map((inst) =>
        h('div', { class: `card instance-card${inst.id === ui.selectedId ? ' selected' : ''}`, onClick: () => select(inst.id) },
          h('div', { class: 'row' },
            h('div', { class: 'instance-icon' }, h('img', { src: 'assets/logo.png', alt: '' })),
            h('div', { class: 'grow', style: 'min-width:0' }, h('div', { class: 'instance-title truncate' }, inst.name), h('div', { class: 'muted' }, `${inst.minecraftVersion} - ${inst.loaderName}`)),
            inst.running ? h('span', { class: 'tag running' }, 'LIVE') : null),
          inst.error ? h('div', { class: 'danger-text' }, 'Broken instance.json') : h('div', { class: 'muted' }, `Last played: ${fmtDate(inst.lastPlayed)}`),
          h('div', { class: 'row' },
            playButton(inst),
            h('button', { class: 'btn', onClick: (e: MouseEvent) => { e.stopPropagation(); editInstanceDialog(inst); } }, 'Edit')),
          progressBar(inst.id)))));
  }

  function modsView(): Node {
    const state = ui.state!;
    const inst = selected();
    if (!inst) return h('div', {}, header('MODS', 'Per-instance mod management'), emptyState('Create an instance first.'));
    const picker = h('select', { class: 'input picker', onChange: (e: Event) => select((e.target as HTMLSelectElement).value) },
      ...state.instances.map((i) => h('option', { value: i.id, selected: i.id === inst.id }, i.name)));
    const listEl = h('div', { class: 'list' }, h('div', { class: 'muted' }, 'Loading mods...'));
    const issuesEl = h('div', {});

    const updateButton = (m: Snowball.Mod): HTMLElement | null => {
      const u = ui.updates.get(inst.id)?.get(m.fileName);
      if (!u) return null;
      const button = h('button', {
        class: 'btn small primary',
        disabled: inst.running,
        title: `${u.currentVersion} -> ${u.newVersion}`,
        onClick: async () => {
          button.disabled = true;
          button.textContent = 'Updating...';
          const r = await guard(() => api.updateMod(inst.id, m.fileName));
          if (r) {
            ui.updates.get(inst.id)?.delete(m.fileName);
            toast(`Updated ${m.name} to ${r.version}`);
          }
          await load();
        },
      }, 'Update');
      return button;
    };
    const openBrowse = (query: string) => {
      ui.browse.query = query;
      ui.browse.searched = false;
      ui.view = 'browse';
      render();
    };
    // "X needs Y to run": well-known mods install in one click, anything else opens a search for it.
    const issueRow = (i: Snowball.ModIssue): HTMLElement => {
      const dep = i.dependency;
      const action = dep && inst.loader !== 'vanilla'
        ? h('button', {
          class: 'btn small',
          style: 'margin-left:12px',
          disabled: inst.running,
          onClick: async (e: MouseEvent) => {
            if (!dep.slug) return openBrowse(dep.name);
            const button = e.currentTarget as HTMLButtonElement;
            button.disabled = true;
            button.textContent = 'Installing...';
            try {
              const r = await api.installMod(inst.id, dep.slug);
              toast(`Installed ${r.installed.join(', ') || dep.name}`);
              await load();
            } catch (err) {
              toast(`${errorMessage(err)}\nSearching Modrinth for ${dep.name} instead.`, 'error');
              openBrowse(dep.name);
            }
          },
        }, dep.slug ? `Install ${dep.name}` : `Find ${dep.name}`)
        : null;
      return h('div', { class: `issue row${i.severity === 'warning' ? ' warning' : ''}` }, h('span', { class: 'issue-text' }, i.message), action);
    };
    // Snowball Client status with Verify / Repair; protection itself is enforced by the launcher core.
    const coreEl = h('div', {});
    const loadCore = async () => {
      if (inst.loader === 'vanilla') return;
      const report = await api.coreStatus(inst.id).catch(() => null);
      if (!report) return;
      if (!report.supported) {
        const available = state.snowballBuilds.map((b) => b.minecraft).join(', ');
        coreEl.replaceChildren(h('div', { class: 'issue warning' }, `Snowball Client isn't available for ${inst.loaderName} ${inst.minecraftVersion} yet${available ? ` (it runs on Fabric ${available})` : ''}. This instance works without it.`));
        return;
      }
      const healthy = report.problems.length === 0;
      const repair = h('button', {
        class: `btn small${healthy ? '' : ' primary'}`,
        disabled: inst.running,
        onClick: async () => {
          repair.disabled = true;
          repair.textContent = healthy ? 'Checking...' : 'Repairing...';
          const r = await guard(() => api.repairCore(inst.id));
          if (r) toast(healthy ? 'Snowball Client verified' : 'Snowball Client repaired');
          await load();
          await loadCore();
        },
      }, healthy ? 'Verify' : 'Repair now');
      coreEl.replaceChildren(h('div', { class: 'card core-card' },
        h('img', { class: 'core-logo', src: 'assets/logo.png', alt: '' }),
        h('div', { class: 'grow-1' },
          h('div', { class: 'core-title' }, `SNOWBALL CLIENT ${report.build?.version ?? ''}`),
          h('div', { class: 'muted' }, healthy ? `Loaded by the launcher for Minecraft ${report.build?.minecraft}, not a mod file. Checked before every launch.` : `${report.problems.join('. ')}. It is repaired automatically when you press Play.`)),
        h('span', { class: `tag${healthy ? ' accent' : ' warn'}` }, healthy ? 'VERIFIED' : 'NEEDS REPAIR'),
        repair));
    };
    const load = async () => {
      const result = await guard(() => api.listMods(inst.id));
      if (!result) return;
      issuesEl.replaceChildren(...result.issues.map(issueRow));
      if (result.mods.length === 0) {
        listEl.replaceChildren(h('div', { class: 'muted' }, inst.loader === 'vanilla' ? 'This instance has no mod loader. Choose Fabric, Quilt, Forge or NeoForge in the instance editor.' : 'No mods installed yet.'));
        return;
      }
      const rank = (m: Snowball.Mod) => (m.protection === 'required' ? 0 : 1);
      listEl.replaceChildren(...[...result.mods].sort((a, b) => rank(a) - rank(b)).map((m) => {
        const locked = m.protection === 'required';
        const note = locked ? ' - needed by Snowball Client' : m.managed ? ' - managed by performance profile' : '';
        return h('div', { class: 'list-row' },
          locked
            ? h('span', { class: 'lock', title: 'Required by Snowball Client' }, icon('lock'))
            : toggle(m.enabled, async (value) => { await guard(() => api.setModEnabled(inst.id, m.fileName, value)); await load(); }, inst.running),
          h('div', { class: 'grow' },
            h('div', { class: 'truncate' }, m.name, m.version ? h('span', { class: 'muted' }, `  ${m.version}`) : null),
            h('div', { class: 'muted truncate', style: 'font-size:12px' }, `${m.fileName} - ${m.loader}${note}`, m.error ? h('span', { class: 'danger-text' }, ` - ${m.error}`) : null)),
          updateButton(m),
          locked
            ? h('span', { class: 'tag accent' }, 'REQUIRED')
            : h('button', { class: 'btn small danger', disabled: inst.running, onClick: () => confirmDialog('Remove mod', `Remove ${m.fileName} from ${inst.name}? The file will be deleted.`, 'Remove', async () => { await api.removeMod(inst.id, m.fileName); await load(); }) }, 'Remove'));
      }));
    };
    void load();
    void loadCore();

    const profileSelect = h('select', { class: 'input' }, ...state.profiles.map((p) => h('option', { value: p.id, selected: p.id === inst.performanceProfile }, p.name)));
    const profileInfo = h('div', { class: 'muted', style: 'margin-top:8px' });
    const describe = () => {
      const p = state.profiles.find((x) => x.id === profileSelect.value)!;
      profileInfo.textContent = p.mods.length ? `${p.description} Includes: ${p.mods.join(', ')}.` : p.description;
    };
    profileSelect.addEventListener('change', describe);
    describe();
    const applyButton = h('button', {
      class: 'btn primary',
      disabled: inst.running,
      onClick: async () => {
        applyButton.disabled = true;
        applyButton.textContent = 'Applying...';
        const result = await guard(() => api.applyPerformanceProfile(inst.id, profileSelect.value as Snowball.PerformanceProfileId));
        applyButton.disabled = false;
        applyButton.textContent = 'Apply';
        if (result) {
          toast(result.installed.length ? `Installed ${result.installed.join(', ')}` : 'Performance profile updated');
          if (result.unavailable.length) toast(`Not available for ${inst.minecraftVersion}: ${result.unavailable.join(', ')}`, 'error');
          await refresh();
        }
      },
    }, 'Apply');

    const updatesButton = h('button', {
      class: 'btn small',
      disabled: inst.loader === 'vanilla',
      onClick: async () => {
        updatesButton.disabled = true;
        updatesButton.textContent = 'Checking...';
        const list = await guard(() => api.checkModUpdates(inst.id));
        updatesButton.disabled = false;
        updatesButton.textContent = 'Check for updates';
        if (!list) return;
        ui.updates.set(inst.id, new Map(list.map((u) => [u.fileName, u])));
        toast(list.length ? `${list.length} update${list.length === 1 ? '' : 's'} available` : 'Your Modrinth mods are up to date');
        await load();
      },
    }, 'Check for updates');

    return h('div', { class: 'stack' },
      header('MODS', 'Install, toggle and check compatibility', picker,
        h('button', { class: 'btn', disabled: inst.running || inst.loader === 'vanilla', onClick: async () => { const r = await guard(() => api.addMods(inst.id)); if (r) { if (r.added) toast(`Added ${r.added} mod${r.added === 1 ? '' : 's'}`); r.errors.forEach((e) => toast(e, 'error')); await load(); } } }, '+ Add mods'),
        h('button', { class: 'btn', disabled: inst.loader === 'vanilla', onClick: () => scanDialog(inst.id) }, 'Check mods'),
        h('button', { class: 'btn ghost', onClick: () => void guard(() => api.openInstanceFolder(inst.id, 'mods')) }, 'Open folder')),
      h('div', { class: 'card' },
        h('div', { class: 'section-title' }, 'PERFORMANCE PROFILE'),
        h('div', { class: 'row' }, h('div', { style: 'flex:1' }, profileSelect), applyButton),
        profileInfo),
      coreEl,
      issuesEl,
      h('div', { class: 'card' },
        h('div', { class: 'row', style: 'margin-bottom:12px' }, h('div', { class: 'section-title', style: 'margin:0' }, 'INSTALLED MODS'), h('div', { class: 'spacer' }), updatesButton,
          h('button', { class: 'btn small primary', disabled: inst.loader === 'vanilla', onClick: () => { ui.view = 'browse'; render(); } }, 'Browse mods')),
        listEl));
  }

  const SORTS: Array<[string, string]> = [['relevance', 'Relevance'], ['downloads', 'Downloads'], ['follows', 'Popularity'], ['updated', 'Recently updated'], ['newest', 'Newest'], ['name', 'Name (A-Z)']];
  const MOD_LOADERS: Array<[string, string]> = [['fabric', 'Fabric'], ['legacy-fabric', 'Legacy Fabric'], ['quilt', 'Quilt'], ['forge', 'Forge'], ['neoforge', 'NeoForge']];

  function browseView(): Node {
    const state = ui.state!;
    const inst = selected();
    if (!inst) return h('div', {}, header('BROWSE', 'Find and install mods'), emptyState('Create an instance first.'));
    const b = ui.browse;
    if (b.instanceId !== inst.id) {
      // Filters start at the instance's own version and loader so every result can be installed.
      Object.assign(b, { instanceId: inst.id, version: inst.minecraftVersion, loader: inst.modLoaders[0] ?? '', hits: [], total: 0, error: null, searched: false, installed: new Set<string>() });
    }
    const compatible = inst.modLoaders;

    const picker = h('select', { class: 'input picker', onChange: (e: Event) => select((e.target as HTMLSelectElement).value) },
      ...state.instances.map((i) => h('option', { value: i.id, selected: i.id === inst.id }, i.name)));
    const results = h('div', { class: 'list' });
    const footer = h('div', { style: 'margin-top:12px' });

    const dropdown = (options: Array<[string, string]>, value: string, onChange: (v: string) => void): HTMLSelectElement => {
      const el = h('select', { class: 'input' }, ...options.map(([v, label]) => h('option', { value: v, selected: v === value }, label))) as HTMLSelectElement;
      el.addEventListener('change', () => onChange(el.value));
      return el;
    };
    const search = h('input', { class: 'input', placeholder: 'Search Modrinth...', value: b.query, maxlength: '100' }) as HTMLInputElement;
    let timer = 0;
    search.addEventListener('input', () => {
      b.query = search.value;
      clearTimeout(timer);
      timer = window.setTimeout(() => void runSearch(false), 300);
    });
    const sortSelect = dropdown(SORTS, b.sort, (v) => { b.sort = v; if (v === 'name') paint(); else void runSearch(false); });
    const loaderSelect = dropdown([['', 'Any loader'], ...MOD_LOADERS], b.loader, (v) => { b.loader = v; void runSearch(false); });
    const versionOptions = (): Array<[string, string]> => [['', 'Any version'], ...[...new Set([inst.minecraftVersion, ...b.versions])].map((v): [string, string] => [v, v])];
    const versionSelect = dropdown(versionOptions(), b.version, (v) => { b.version = v; void runSearch(false); });
    if (!b.versions.length) {
      void api.listMinecraftVersions(false).then((list) => {
        b.versions = list.map((v) => v.id);
        versionSelect.replaceChildren(...versionOptions().map(([v, label]) => h('option', { value: v, selected: v === b.version }, label)));
      }).catch(() => undefined);
    }

    const runSearch = async (append: boolean) => {
      const seq = ++b.seq;
      b.loading = true;
      b.error = null;
      if (!append) {
        b.hits = [];
        b.total = 0;
      }
      b.paint();
      try {
        const r = await api.searchMods({ query: b.query.trim(), sort: (b.sort === 'name' ? 'relevance' : b.sort) as Snowball.ModSort, loader: b.loader || null, gameVersion: b.version || null, offset: append ? b.hits.length : 0 });
        if (seq !== b.seq) return;
        b.hits = append ? [...b.hits, ...r.hits] : r.hits;
        b.total = r.total;
      } catch (err) {
        if (seq !== b.seq) return;
        b.error = errorMessage(err);
      }
      b.loading = false;
      b.searched = true;
      b.paint();
    };

    const loadInstalled = async () => {
      const ids = await api.installedModProjects(inst.id).catch(() => null);
      if (ids && b.instanceId === inst.id) {
        b.installed = new Set(ids);
        b.paint();
      }
    };

    const installHit = async (hit: Snowball.ModSearchHit) => {
      const key = `${inst.id}:${hit.projectId}`;
      b.installing.add(key);
      b.paint();
      const r = await guard(() => api.installMod(inst.id, hit.projectId));
      b.installing.delete(key);
      if (r) {
        const extra = r.installed.filter((t) => t !== hit.title);
        const names = extra.length > 1 ? `${extra.slice(0, -1).join(', ')} and ${extra[extra.length - 1]}` : extra[0];
        toast(extra.length ? `Installed ${hit.title}.\n${hit.title} needs ${names} to run, so ${extra.length === 1 ? 'it was' : 'they were'} installed too.` : `Installed ${hit.title}`);
      }
      await loadInstalled();
      b.paint();
    };

    const modRow = (hit: Snowball.ModSearchHit): HTMLElement => {
      const fits = hit.gameVersions.includes(inst.minecraftVersion) && hit.loaders.some((l) => compatible.includes(l));
      const installed = b.installed.has(hit.projectId);
      const installing = b.installing.has(`${inst.id}:${hit.projectId}`);
      const why = inst.loader === 'vanilla' ? 'This instance has no mod loader' : `No ${inst.loaderName} version for Minecraft ${inst.minecraftVersion}`;
      const button = h('button', {
        class: `btn small${installed || !fits ? '' : ' primary'}`,
        disabled: installed || installing || !fits || inst.running,
        title: fits ? '' : why,
        onClick: () => void installHit(hit),
      }, installed ? 'Installed' : installing ? 'Installing...' : fits ? 'Install' : 'Unavailable');
      const placeholder = h('div', { class: 'mod-icon placeholder' }, hit.title.slice(0, 1).toUpperCase());
      let iconEl: HTMLElement = placeholder;
      if (hit.iconUrl) {
        const img = h('img', { class: 'mod-icon', src: hit.iconUrl, alt: '', loading: 'lazy' });
        img.addEventListener('error', () => img.replaceWith(placeholder));
        iconEl = img;
      }
      return h('div', { class: 'mod-row' },
        iconEl,
        h('div', { class: 'grow' },
          h('div', { class: 'truncate' }, h('span', { class: 'mod-title' }, hit.title), hit.author ? h('span', { class: 'muted' }, `  by ${hit.author}`) : null),
          h('div', { class: 'muted mod-desc' }, hit.description),
          h('div', { class: 'row mod-meta' },
            ...hit.loaders.map((l) => h('span', { class: `tag${compatible.includes(l) ? ' accent' : ''}` }, MOD_LOADERS.find(([id]) => id === l)?.[1] ?? l)),
            hit.versionRange ? h('span', { class: 'muted' }, `MC ${hit.versionRange}`) : null)),
        h('div', { class: 'mod-side' },
          h('div', { class: 'muted' }, `${fmtCount(hit.downloads)} downloads`),
          h('div', { class: 'muted' }, `Updated ${fmtAgo(hit.updated)}`),
          button));
    };

    const paint = () => {
      if (b.error) {
        results.replaceChildren(h('div', { class: 'issue' },
          h('div', {}, `Could not load mods from Modrinth: ${b.error}`),
          h('button', { class: 'btn small', style: 'margin-top:8px', onClick: () => void runSearch(false) }, 'Retry')));
        footer.replaceChildren();
        return;
      }
      const hits = b.sort === 'name' ? [...b.hits].sort((x, y) => x.title.localeCompare(y.title)) : b.hits;
      if (!hits.length) {
        results.replaceChildren(h('div', { class: 'muted' }, b.loading || !b.searched ? 'Searching...' : 'No mods match these filters.'));
        footer.replaceChildren();
        return;
      }
      results.replaceChildren(...hits.map(modRow));
      footer.replaceChildren(h('div', { class: 'row' },
        h('span', { class: 'muted' }, `Showing ${hits.length} of ${fmtCount(b.total)}`),
        h('div', { class: 'spacer' }),
        hits.length < b.total ? h('button', { class: 'btn', disabled: b.loading, onClick: () => void runSearch(true) }, b.loading ? 'Loading...' : 'Load more') : null));
    };
    b.paint = paint;
    if (!b.searched && !b.loading) void runSearch(false);
    else paint();
    void loadInstalled();

    return h('div', { class: 'stack' },
      header('BROWSE', `Mods from Modrinth for ${inst.minecraftVersion} ${inst.loaderName}`, picker,
        h('button', { class: 'btn ghost', onClick: () => { ui.view = 'mods'; render(); } }, 'Installed mods')),
      inst.loader === 'vanilla' ? h('div', { class: 'issue warning' }, 'This instance has no mod loader. Choose Fabric, Quilt, Forge or NeoForge in the instance editor to install mods.') : null,
      h('div', { class: 'browse-toolbar' }, search, sortSelect, loaderSelect, versionSelect),
      h('div', { class: 'card' }, results, footer));
  }

  function chatView(): Node {
    const state = ui.chat;
    if (!state || !state.configured) {
      return h('div', { class: 'stack' },
        header('CHAT', 'Talk to other Snowball players'),
        h('div', { class: 'card empty' },
          h('h2', { class: 'page-title' }, 'CHAT IS NOT SWITCHED ON'),
          h('p', { class: 'muted' }, 'This build has no chat server set, so there is nothing to join yet.')));
    }

    const messages = h('div', { class: 'chat-log', id: 'chat-log' });
    paintChat(messages);
    const input = h('input', { class: 'input', placeholder: state.status === 'online' ? 'Say something' : 'Join chat to talk', maxlength: '240', disabled: state.status !== 'online' }) as HTMLInputElement;
    const send = async () => {
      const text = input.value.trim();
      if (!text) return;
      const result = await guard(() => api.sendChat(text));
      if (!result) return;
      if (!result.ok) {
        toast(result.reason ?? 'That message was not sent.', 'error');
        return;
      }
      input.value = '';
    };
    input.addEventListener('keydown', (e: KeyboardEvent) => {
      if (e.key === 'Enter') void send();
    });

    const status = state.status === 'online'
      ? `${state.online} online`
      : state.status === 'connecting' ? 'Connecting...' : state.message ?? 'Not connected';

    return h('div', { class: 'stack' },
      header('CHAT', status,
        state.status === 'online'
          ? h('button', { class: 'btn', onClick: () => void api.leaveChat() }, 'Leave')
          : h('button', { class: 'btn primary', onClick: () => void guard(() => api.joinChat()) }, 'Join chat')),
      h('div', { class: 'card chat-card' },
        messages,
        h('div', { class: 'row chat-input' }, input, h('button', { class: 'btn primary', disabled: state.status !== 'online', onClick: () => void send() }, 'Send')),
        h('p', { class: 'muted', style: 'font-size:12px;margin:8px 0 0' }, 'No links, no images and no abuse. Everyone here signed in with Microsoft, so names are real.')),
      bugReportCard());
  }

  /** Anyone signed in can report a bug; it is filed under their account, not a name they type. */
  function bugReportCard(): HTMLElement {
    const title = h('input', { class: 'input', placeholder: 'What went wrong, in a few words', maxlength: '120' }) as HTMLInputElement;
    const detail = h('textarea', { class: 'input', rows: '3', placeholder: 'What happened, and what you expected instead', maxlength: '4000' }) as HTMLTextAreaElement;
    const steps = h('input', { class: 'input', placeholder: 'How to make it happen again (optional)', maxlength: '1000' }) as HTMLInputElement;
    const inst = selected();
    const send = async () => {
      const result = await guard(() => api.reportBug({
        title: title.value,
        detail: detail.value,
        steps: steps.value,
        minecraft: inst?.minecraftVersion ?? '',
        snowball: inst?.snowball.version ?? '',
        loader: inst?.loaderName ?? '',
      }));
      if (!result) return;
      if (!result.ok) {
        toast(result.reason ?? 'That report was not sent.', 'error');
        return;
      }
      title.value = '';
      detail.value = '';
      steps.value = '';
      toast('Thank you - the report went straight to the Snowball team.');
    };
    return h('div', { class: 'card' },
      h('div', { class: 'section-title' }, 'REPORT A BUG'),
      h('p', { class: 'muted', style: 'font-size:12px;margin:0 0 10px' }, 'Your Minecraft version, Snowball version and loader are attached automatically.'),
      h('div', { class: 'stack' }, title, detail, steps),
      h('div', { class: 'row', style: 'margin-top:10px' },
        h('div', { class: 'spacer' }),
        h('button', { class: 'btn primary', onClick: () => void send() }, 'Send report')));
  }

  /**
   * The owner's own screen: ranks, people, bug reports and feature switches. It is only reachable
   * when the server says this account may use it, and every button is checked there again.
   */
  function adminView(): Node {
    const state = ui.chat;
    if (!state?.configured) {
      return h('div', { class: 'stack' },
        header('ADMIN', 'Snowball owner tools'),
        h('div', { class: 'card empty' },
          h('h2', { class: 'page-title' }, 'NO SNOWBALL SERVER'),
          h('p', { class: 'muted' }, 'This build has no Snowball server set, so there is nothing to administer.')));
    }
    if (!state.admin) {
      return h('div', { class: 'stack' },
        header('ADMIN', 'Snowball owner tools'),
        h('div', { class: 'card empty' },
          h('h2', { class: 'page-title' }, state.status === 'online' ? 'NOT YOUR RANK' : 'JOIN CHAT FIRST'),
          h('p', { class: 'muted' }, state.status === 'online'
            ? 'Your account does not hold a rank that can administer Snowball.'
            : 'Join chat so the Snowball server can confirm which account you are, then come back.'),
          state.status === 'online' ? null : h('button', { class: 'btn primary', onClick: () => void guard(() => api.joinChat()) }, 'Join chat')));
    }
    return h('div', { class: 'stack' },
      header('ADMIN', `Signed in as the owner  \u00b7  ${state.online} online`),
      adminCard());
  }

  /** Only an account the server trusts sees this, and the server checks that again for every action. */
  function adminCard(): HTMLElement {
    const announcement = h('input', { class: 'input', placeholder: 'Announcement everyone sees', maxlength: '300', value: ui.chat?.announcement ?? '' }) as HTMLInputElement;
    const muteUuid = h('input', { class: 'input', placeholder: 'UUID to mute' }) as HTMLInputElement;
    const plusUuid = h('input', { class: 'input', placeholder: 'UUID to give Snowball+' }) as HTMLInputElement;
    const muteMinutes = h('input', { class: 'input', type: 'number', min: '1', max: '1440', value: '10', style: 'max-width:110px' }) as HTMLInputElement;
    const run = async (fn: () => Promise<{ ok: boolean; reason?: string }>) => {
      const result = await guard(fn);
      if (result && !result.ok) toast(result.reason ?? 'That did not work.', 'error');
      else if (result) toast('Done');
    };
    const tab = (id: typeof ui.adminTab, label: string) =>
      h('button', { class: `log-tab${ui.adminTab === id ? ' active' : ''}`, onClick: () => {
        ui.adminTab = id;
        if (id === 'people') void api.chatAdmin('people');
        if (id === 'bugs') void api.chatAdmin('bugs');
        render();
      } }, label);

    if (ui.adminTab !== 'chat') {
      return h('div', { class: 'card' },
        h('div', { class: 'row' }, h('div', { class: 'section-title' }, 'ADMIN'), h('div', { class: 'spacer' }),
          h('div', { class: 'log-tabs' }, tab('chat', 'CHAT'), tab('ranks', 'RANKS'), tab('people', 'PEOPLE'), tab('bugs', 'BUGS'), tab('flags', 'FEATURES'))),
        ui.adminTab === 'ranks' ? rankManager() : ui.adminTab === 'people' ? peopleList() : ui.adminTab === 'bugs' ? bugList() : flagList());
    }

    return h('div', { class: 'card' },
      h('div', { class: 'row' }, h('div', { class: 'section-title' }, 'ADMIN'), h('div', { class: 'spacer' }),
        h('div', { class: 'log-tabs' }, tab('chat', 'CHAT'), tab('ranks', 'RANKS'), tab('people', 'PEOPLE'), tab('bugs', 'BUGS'), tab('flags', 'FEATURES'))),
      h('div', { class: 'list' },
        h('div', { class: 'list-row' },
          h('div', { class: 'grow' }, announcement),
          h('button', { class: 'btn small primary', onClick: () => void run(() => api.announce(announcement.value)) }, 'Post'),
          h('button', { class: 'btn small', onClick: () => { announcement.value = ''; void run(() => api.announce(null)); } }, 'Clear')),
        h('div', { class: 'list-row' },
          h('div', { class: 'grow' }, muteUuid),
          muteMinutes,
          h('button', { class: 'btn small', onClick: () => void run(() => api.moderateChat('mute', muteUuid.value.trim(), Number(muteMinutes.value))) }, 'Mute'),
          h('button', { class: 'btn small', onClick: () => void run(() => api.moderateChat('unmute', muteUuid.value.trim())) }, 'Unmute')),
        h('div', { class: 'list-row' },
          h('div', { class: 'grow' }, plusUuid),
          h('button', { class: 'btn small primary', onClick: () => void run(() => api.setSnowballPlus(plusUuid.value.trim(), true)) }, 'Give Snowball+'),
          h('button', { class: 'btn small', onClick: () => void run(() => api.setSnowballPlus(plusUuid.value.trim(), false)) }, 'Remove')),
        h('div', { class: 'list-row' },
          h('div', { class: 'grow' }, h('div', {}, 'Clear the chat for everyone'), h('div', { class: 'muted', style: 'font-size:12px' }, 'Wipes the recent messages the server keeps.')),
          h('button', { class: 'btn small danger', onClick: () => void run(() => api.moderateChat('clear')) }, 'Clear chat'))));
  }

  /**
   * Look a player up by their Minecraft name, see the rank they hold and who gave it, and change
   * it. The server checks the permission again and tells the player at once, so nobody reinstalls.
   */
  function rankManager(): HTMLElement {
    const search = h('input', { class: 'input', placeholder: 'Minecraft username', maxlength: '16' }) as HTMLInputElement;
    const find = () => {
      const name = search.value.trim();
      if (!name) return;
      void guard(() => api.lookupPlayer(name));
    };
    search.addEventListener('keydown', (e: KeyboardEvent) => {
      if (e.key === 'Enter') find();
    });
    const found = ui.lookup;

    const result: Node[] = [];
    if (found && !found.found) {
      result.push(h('p', { class: 'muted' }, 'Mojang has no account called ' + found.name + '.'));
    } else if (found) {
      const current = rankInfo(found.rank);
      const picker = h('select', { class: 'input', style: 'max-width:190px' },
        ...RANKS.filter((r) => r.id !== 'owner').map((r) => h('option', { value: r.id, selected: r.id === found.rank }, r.name))) as HTMLSelectElement;
      result.push(h('div', { class: 'list-row' },
        h('div', { class: 'grow' },
          h('div', {}, found.name, ' ', rankChip(found.rank)),
          h('div', { class: 'muted', style: 'font-size:11px' }, found.uuid ?? ''),
          found.given ? h('div', { class: 'muted', style: 'font-size:11px' }, 'Given by ' + found.given.byName + ' ' + fmtWhen(found.given.at)) : null,
          found.seen ? h('div', { class: 'muted', style: 'font-size:11px' }, 'Last seen ' + fmtWhen(found.seen.last)) : null),
        picker,
        h('button', { class: 'btn small primary', onClick: () => void applyRank(found.uuid!, picker.value) }, 'Set rank'),
        h('button', { class: 'btn small', onClick: () => void applyRank(found.uuid!, 'snowball') }, 'Revoke')));
      if (found.history?.length) {
        result.push(h('div', { class: 'section-title', style: 'margin-top:12px' }, 'HISTORY'));
        result.push(h('div', { class: 'list' }, ...found.history.slice(0, 12).map((entry) =>
          h('div', { class: 'list-row' },
            h('div', { class: 'grow' }, rankChip(entry.rank), ' ', h('span', { class: 'muted', style: 'font-size:12px' }, 'by ' + entry.byName)),
            h('div', { class: 'muted', style: 'font-size:11px' }, fmtWhen(entry.at))))));
      } else if (current.id === 'snowball') {
        result.push(h('p', { class: 'muted' }, 'No rank has ever been given to this account.'));
      }
    }

    return h('div', { class: 'stack' },
      h('div', { class: 'row' },
        h('div', { class: 'grow' }, search),
        h('button', { class: 'btn small primary', onClick: find }, 'Find')),
      ...result);
  }

  async function applyRank(uuid: string, rank: string): Promise<void> {
    const result = await guard(() => api.setRank(uuid, rank));
    if (!result) return;
    if (!result.ok) {
      toast(result.reason ?? 'That rank was not changed.', 'error');
      return;
    }
    toast('Rank set to ' + rankInfo(rank).name);
  }

  /** Everyone the Snowball server has seen, newest first. */
  function peopleList(): HTMLElement {
    if (!ui.people.length) return h('p', { class: 'muted' }, 'Nobody has joined yet, or the list is still coming.');
    return h('div', { class: 'list' }, ...ui.people.slice(0, 60).map((person) =>
      h('div', { class: 'list-row' },
        h('div', { class: 'grow' },
          h('div', {}, person.name, ' ', person.rank && person.rank !== 'snowball' ? rankChip(person.rank) : null),
          h('div', { class: 'muted', style: 'font-size:11px' }, `last seen ${fmtWhen(person.last)}${person.muted ? ' - muted' : ''}`)),
        h('button', { class: 'btn small', onClick: () => void api.setSnowballPlus(person.uuid, person.rank === 'snowball').then(() => setTimeout(() => void api.chatAdmin('people'), 400)) },
          person.rank === 'snowball' ? 'Give Snowball+' : 'Remove rank'),
        h('button', { class: 'btn small', onClick: () => void api.moderateChat(person.muted ? 'unmute' : 'mute', person.uuid, 10).then(() => setTimeout(() => void api.chatAdmin('people'), 400)) },
          person.muted ? 'Unmute' : 'Mute 10m'))));
  }

  /** Bug reports, with the states the owner can move them through. */
  function bugList(): HTMLElement {
    if (!ui.bugs.length) return h('p', { class: 'muted' }, 'No bug reports yet.');
    const states: Snowball.BugReport['status'][] = ['open', 'investigating', 'fixed', 'duplicate', 'invalid'];
    return h('div', { class: 'list' }, ...ui.bugs.map((bug) =>
      h('div', { class: 'list-row bug-row' },
        h('div', { class: 'grow' },
          h('div', {}, h('span', { class: `bug-state ${bug.status}` }, bug.status.toUpperCase()), ' ', bug.title),
          h('div', { class: 'muted', style: 'font-size:12px' }, bug.detail),
          h('div', { class: 'muted', style: 'font-size:11px' },
            `${bug.by} - ${fmtWhen(Date.parse(bug.at))}${bug.minecraft ? ` - Minecraft ${bug.minecraft}` : ''}${bug.loader ? ` - ${bug.loader}` : ''}${bug.snowball ? ` - Snowball ${bug.snowball}` : ''}`),
          bug.steps ? h('div', { class: 'muted', style: 'font-size:11px' }, `Steps: ${bug.steps}`) : null),
        h('select', { class: 'input', style: 'max-width:150px', onChange: (e: Event) => void api.chatAdmin('bug-status', { id: bug.id, status: (e.target as HTMLSelectElement).value }) },
          ...states.map((state) => h('option', { value: state, selected: state === bug.status }, state))))));
  }

  /** Feature switches: the launcher and the client ask the server what is on. */
  function flagList(): HTMLElement {
    const known: [string, string][] = [
      ['chat', 'Global chat'],
      ['bug_reports', 'Bug reporting'],
      ['snowball_plus', 'Snowball+ features'],
      ['beta', 'Beta builds'],
    ];
    const flags = ui.chat?.flags ?? {};
    const custom = h('input', { class: 'input', placeholder: 'Another feature key' }) as HTMLInputElement;
    const row = (key: string, label: string) => {
      const on = flags[key] !== false;
      return h('div', { class: 'list-row' },
        h('div', { class: 'grow' }, h('div', {}, label), h('div', { class: 'muted', style: 'font-size:11px' }, key)),
        h('button', { class: `btn small${on ? '' : ' primary'}`, onClick: () => void api.chatAdmin('flag', { key, value: !on }) }, on ? 'Turn off' : 'Turn on'));
    };
    return h('div', { class: 'list' },
      ...known.map(([key, label]) => row(key, label)),
      ...Object.keys(flags).filter((key) => !known.some(([k]) => k === key)).map((key) => row(key, key)),
      h('div', { class: 'list-row' },
        h('div', { class: 'grow' }, custom),
        h('button', { class: 'btn small', onClick: () => void api.chatAdmin('flag', { key: custom.value.trim(), value: false }) }, 'Add, switched off')));
  }

  function paintChat(view: HTMLElement): void {
    if (!ui.chatMessages.length) {
      view.replaceChildren(h('div', { class: 'muted' }, 'Nothing said yet.'));
      return;
    }
    view.replaceChildren(...ui.chatMessages.map(chatRow));
    view.scrollTop = view.scrollHeight;
  }

  function chatRow(message: Snowball.ChatMessage): HTMLElement {
    const time = new Date(message.at);
    return h('div', { class: `chat-line${message.system ? ' system' : ''}` },
      h('span', { class: 'chat-time' }, Number.isNaN(time.getTime()) ? '' : time.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })),
      message.rank && message.rank !== 'snowball' ? rankChip(message.rank) : null,
      h('span', { class: `chat-name${message.staff ? ' staff' : ''}` }, message.name),
      h('span', { class: 'chat-text' }, message.text));
  }

  function javaView(): Node {
    const state = ui.state!;
    const listEl = h('div', { class: 'list' }, h('div', { class: 'muted' }, 'Detecting Java installations...'));
    const detect = async () => {
      listEl.replaceChildren(h('div', { class: 'muted' }, 'Detecting Java installations...'));
      const list = await guard(() => api.detectJava());
      if (!list) return;
      listEl.replaceChildren(...(list.length ? list.map((j) =>
        h('div', { class: 'list-row' },
          h('span', { class: 'tag accent' }, `Java ${j.majorVersion}`),
          h('div', { class: 'grow' }, h('div', { class: 'truncate' }, `${j.vendor} ${j.version} (${j.arch}${j.is64Bit ? '' : ', 32-bit'})`), h('div', { class: 'muted truncate', style: 'font-size:12px' }, j.path)),
          h('span', { class: 'tag' }, j.source))) : [h('div', { class: 'muted' }, 'No Java found. The launcher will download the runtime Minecraft needs when you press Play.')]));
    };
    void detect();

    const pathInput = h('input', { class: 'input', placeholder: 'Path to java executable' }) as HTMLInputElement;
    const result = h('div', { class: 'muted', style: 'margin-top:8px' });
    return h('div', { class: 'stack' },
      header('JAVA', 'Runtimes and memory', h('button', { class: 'btn', onClick: () => void detect() }, 'Rescan')),
      h('div', { class: 'grid', style: 'grid-template-columns:1fr 1fr' },
        h('div', { class: 'card' },
          h('div', { class: 'section-title' }, 'MEMORY'),
          h('div', { class: 'stats', style: 'margin:0' },
            h('div', { class: 'stat' }, h('div', { class: 'stat-label' }, 'System'), h('div', { class: 'stat-value' }, fmtMemory(state.memory.totalMb))),
            h('div', { class: 'stat' }, h('div', { class: 'stat-label' }, 'Recommended'), h('div', { class: 'stat-value' }, fmtMemory(state.memory.recommendedMaxMb))),
            h('div', { class: 'stat' }, h('div', { class: 'stat-label' }, 'Safe maximum'), h('div', { class: 'stat-value' }, fmtMemory(state.memory.safeUpperLimitMb))))),
        h('div', { class: 'card' },
          h('div', { class: 'section-title' }, 'CHECK A JAVA EXECUTABLE'),
          h('div', { class: 'row' }, pathInput,
            h('button', { class: 'btn', onClick: async () => { const p = await guard(() => api.browseJava()); if (p) pathInput.value = p; } }, 'Browse'),
            h('button', { class: 'btn primary', onClick: async () => { const r = await guard(() => api.validateJava(pathInput.value, null)); if (r) result.textContent = r.java ? `Java ${r.java.version} (${r.java.vendor})${r.message ? ` - ${r.message}` : ''}` : r.message ?? 'Not a Java executable'; } }, 'Check')),
          result)),
      h('div', { class: 'card' },
        h('div', { class: 'section-title' }, 'DETECTED INSTALLATIONS'),
        h('p', { class: 'muted', style: 'margin-top:0' }, 'Each instance picks the Java version its Minecraft version requires, or the executable you choose in the instance editor.'),
        listEl));
  }

  /** Everything about keeping the launcher itself current, in one place. */
  function updatesCard(s: Snowball.Settings, update: (patch: Partial<Snowball.Settings>) => Promise<void>): HTMLElement {
    const state = ui.update;
    const line = (): string => {
      if (!state) return 'No update has been checked for yet.';
      switch (state.status) {
        case 'checking': return 'Looking for a new version...';
        case 'downloading': return `Downloading ${state.newVersion} (${state.percent}%)`;
        case 'ready': return `Version ${state.newVersion} is ready to install.`;
        case 'up-to-date': return 'You have the newest version.';
        case 'unsupported': return state.reason;
        case 'error': return `Could not check: ${state.message}`;
        default: return 'No update has been checked for yet.';
      }
    };
    const status = h('div', { class: 'muted', style: 'font-size:13px' }, line());
    return h('div', { class: 'card' },
      h('div', { class: 'section-title' }, 'UPDATES'),
      h('div', { class: 'list' },
        h('div', { class: 'list-row' },
          h('div', { class: 'grow' },
            h('div', {}, 'Update by itself'),
            h('div', { class: 'muted', style: 'font-size:13px' }, 'New versions install in the background, so you never run the installer again.')),
          toggle(s.updates.checkOnStartup, (v) => void update({ updates: { ...s.updates, checkOnStartup: v } }))),
        h('div', { class: 'list-row' },
          h('div', { class: 'grow' }, h('div', {}, 'This launcher'), h('div', { class: 'muted', style: 'font-size:13px' }, ui.version ? `Version ${ui.version}` : 'Reading the version...')),
          h('span', { class: 'tag accent' }, ui.version ? `v${ui.version}` : '...')),
        h('div', { class: 'list-row' },
          h('div', { class: 'grow' }, h('div', {}, 'Status'), status),
          state?.status === 'ready'
            ? h('button', { class: 'btn small primary', onClick: () => void api.installUpdate() }, 'Restart now')
            : h('button', {
                class: 'btn small',
                onClick: async () => {
                  status.textContent = 'Looking for a new version...';
                  const r = await guard(() => api.checkForUpdates());
                  if (r) { ui.update = r; render(); }
                },
              }, 'Check now'))));
  }

  /** "Escaping from another launcher": finds instances elsewhere on this computer and copies one over. */
  function importDialog(): void {
    const list = h('div', { class: 'list' }, h('div', { class: 'muted' }, 'Looking for other launchers...'));
    const picks: Snowball.ImportOptions = { mods: true, config: true, resourcePacks: true, shaderPacks: true, saves: true, options: true };
    const optionRow = (label: string, key: keyof Snowball.ImportOptions) =>
      h('div', { class: 'list-row' }, h('div', { class: 'grow' }, label), toggle(picks[key], (v) => { picks[key] = v; }));
    const options = h('div', { class: 'list', style: 'margin-top:12px' },
      optionRow('Mods', 'mods'),
      optionRow('Mod settings (config)', 'config'),
      optionRow('Resource packs', 'resourcePacks'),
      optionRow('Shader packs', 'shaderPacks'),
      optionRow('Worlds', 'saves'),
      optionRow('Game options and servers', 'options'));
    const body = h('div', {},
      h('p', { class: 'muted' }, 'Nothing is moved or deleted: your other launcher keeps working exactly as it does now.'),
      list,
      h('div', { class: 'section-title', style: 'margin-top:16px' }, 'BRING ACROSS'),
      options);
    const close = modal('IMPORT FROM ANOTHER LAUNCHER', body, [{ label: 'Close', kind: 'ghost', onClick: (c) => c() }]);

    void api.findOtherLaunchers().then((found) => {
      if (!found.length) {
        list.replaceChildren(h('div', { class: 'muted' }, 'No other launcher was found on this computer. Modrinth, CurseForge, Prism, MultiMC, ATLauncher, GDLauncher and the official launcher are all checked.'));
        return;
      }
      list.replaceChildren(...found.map((f) => h('div', { class: 'list-row' },
        h('div', { class: 'grow' },
          h('div', {}, f.name, h('span', { class: 'tag', style: 'margin-left:8px' }, f.launcher.toUpperCase())),
          h('div', { class: 'muted', style: 'font-size:12px' }, `Minecraft ${f.minecraftVersion} - ${LOADER_NAMES[f.loader]} - ${f.mods} mod${f.mods === 1 ? '' : 's'}, ${f.worlds} world${f.worlds === 1 ? '' : 's'}`)),
        h('button', {
          class: 'btn small primary',
          onClick: async (e: MouseEvent) => {
            const button = e.currentTarget as HTMLButtonElement;
            button.disabled = true;
            button.textContent = 'Copying...';
            const created = await guard(() => api.importFromLauncher(f, picks));
            button.disabled = false;
            button.textContent = 'Import';
            if (created) {
              toast(`Imported ${created.name}`);
              close();
              await refresh();
              select(created.id);
            }
          },
        }, 'Import'))));
    }).catch((err) => list.replaceChildren(h('div', { class: 'muted' }, errorMessage(err))));
  }

  /** Snowball's own mod check: reads each jar on this computer and reports what it can do. */
  function scanDialog(instanceId: string): void {
    const body = h('div', {});
    const list = h('div', { class: 'list' }, h('div', { class: 'muted' }, 'Reading every mod in this instance...'));
    const summary = h('p', { class: 'muted' }, 'Nothing is uploaded: each jar is read here and compared with what stealers and loaders do.');
    body.append(summary, list);
    modal('MOD CHECK', body, [{ label: 'Close', kind: 'ghost', onClick: (c) => c() }]);

    void api.scanMods(instanceId).then((results) => {
      if (!results.length) {
        list.replaceChildren(h('div', { class: 'muted' }, 'This instance has no mods to check.'));
        return;
      }
      const bad = results.filter((r) => r.verdict === 'dangerous' || r.verdict === 'suspicious').length;
      summary.textContent = bad
        ? `${bad} of ${results.length} mods do things worth a second look. Nothing was uploaded; everything was checked here.`
        : `All ${results.length} mods look ordinary. Nothing was uploaded; everything was checked here.`;
      list.replaceChildren(...results.map((r) => {
        const findings = h('div', { class: 'scan-findings' }, ...r.findings.map((f) =>
          h('div', { class: 'scan-finding' },
            h('div', {}, f.title),
            h('div', { class: 'muted', style: 'font-size:12px' }, f.detail),
            h('div', { class: 'muted', style: 'font-size:11px' }, f.where))));
        findings.hidden = true;
        const row = h('div', { class: 'list-row scan-row' },
          h('span', { class: `verdict ${r.verdict}` }, r.verdict.toUpperCase()),
          h('div', { class: 'grow' },
            h('div', {}, r.fileName),
            h('div', { class: 'muted', style: 'font-size:12px' }, r.error ? r.error : r.findings.length ? `${r.findings.length} thing${r.findings.length === 1 ? '' : 's'} found` : 'Nothing unusual')),
          r.findings.length ? h('button', { class: 'btn small', onClick: () => { findings.hidden = !findings.hidden; } }, 'Details') : null);
        return h('div', {}, row, findings);
      }));
    }).catch((err) => list.replaceChildren(h('div', { class: 'muted' }, errorMessage(err))));
  }

  function settingsView(): Node {
    const state = ui.state!;
    const s = state.settings;
    const update = async (patch: Partial<Snowball.Settings>) => {
      if (await guard(() => api.updateSettings(patch))) await refresh();
    };
    const settingRow = (label: string, description: string, control: Node) =>
      h('div', { class: 'list-row' }, h('div', { class: 'grow' }, h('div', {}, label), h('div', { class: 'muted', style: 'font-size:13px' }, description)), control);

    const accounts = h('div', { class: 'list' }, ...(state.accounts.length ? state.accounts.map((a) =>
      h('div', { class: 'list-row' },
        h('div', { class: 'avatar' }, a.name.slice(0, 1).toUpperCase()),
        h('div', { class: 'grow' }, h('div', {}, a.name, (s.accounts.selectedAccountId ?? state.accounts[0]?.id) === a.id ? h('span', { class: 'tag accent', style: 'margin-left:8px' }, 'ACTIVE') : null), h('div', { class: 'muted', style: 'font-size:12px' }, a.type === 'msa' ? 'Microsoft account' : 'Offline profile (singleplayer / LAN)')),
        h('button', { class: 'btn small', onClick: () => void guard(() => api.selectAccount(a.id)).then(refresh) }, 'Use'),
        h('button', { class: 'btn small danger', onClick: () => confirmDialog('Remove account', `Sign out and remove ${a.name}?`, 'Remove', async () => { await api.removeAccount(a.id); await refresh(); }) }, 'Remove'))) : [h('div', { class: 'muted' }, 'No accounts yet.')]));

    const maxMemory = h('input', { class: 'input', type: 'number', min: '512', step: '256', value: s.java.defaultMaxMemoryMb ?? '', placeholder: `Recommended (${state.memory.recommendedMaxMb} MB)` }) as HTMLInputElement;

    return h('div', { class: 'stack' },
      header('SETTINGS', `Launcher ${state.launcherVersion}`),
      h('div', { class: 'card' },
        h('div', { class: 'section-title' }, 'ACCOUNTS'),
        accounts,
        h('div', { class: 'row', style: 'margin-top:12px' },
          h('button', { class: 'btn primary', onClick: () => microsoftSignIn() }, 'Sign in with Microsoft'),
          h('button', { class: 'btn', disabled: !state.canAddOffline, title: state.canAddOffline ? '' : 'Requires a Microsoft account first', onClick: () => offlineDialog() }, 'Add offline profile'))),
      updatesCard(s, update),
      h('div', { class: 'card' },
        h('div', { class: 'section-title' }, 'LAUNCHER'),
        h('div', { class: 'list' },
          settingRow('Reduce motion', 'Turn off interface animations.', toggle(s.appearance.reduceMotion, (v) => void update({ appearance: { ...s.appearance, reduceMotion: v } }))),
          settingRow('Minimize when the game starts', 'Keeps the launcher out of the way while playing.', toggle(s.game.closeLauncherOnLaunch, (v) => void update({ game: { ...s.game, closeLauncherOnLaunch: v } }))),
          settingRow('Download Java automatically', 'Install the exact runtime Minecraft requests when none is found.', toggle(s.java.autoDownloadRuntime, (v) => void update({ java: { ...s.java, autoDownloadRuntime: v } }))),
          settingRow('Default maximum memory', 'Used for new instances.', h('div', { class: 'row', style: 'width:260px' }, maxMemory, h('button', { class: 'btn', onClick: () => void update({ java: { ...s.java, defaultMaxMemoryMb: maxMemory.value ? Number(maxMemory.value) : null } }) }, 'Save'))),
          settingRow('Parallel downloads', `${s.downloads.concurrency} at a time`, h('div', { style: 'width:200px;flex:none' }, h('input', { type: 'range', min: '1', max: '16', value: String(s.downloads.concurrency), onChange: (e: Event) => void update({ downloads: { ...s.downloads, concurrency: Number((e.target as HTMLInputElement).value) } }) }))),
          settingRow('Debug logging', 'Write detailed diagnostics to the launcher log.', toggle(s.logs.debug, (v) => void update({ logs: { ...s.logs, debug: v } }))))),
      h('div', { class: 'card' },
        h('div', { class: 'section-title' }, 'DATA'),
        h('div', { class: 'list' },
          settingRow('Launcher folder', state.dataRoot, h('button', { class: 'btn', onClick: () => void guard(() => api.openLauncherFolder('root')) }, 'Open')),
          settingRow('Logs', 'Launcher logs (tokens are always redacted).', h('button', { class: 'btn', onClick: () => void guard(() => api.openLauncherFolder('logs')) }, 'Open')),
          settingRow('Snowball Client', state.snowballBuilds.length ? state.snowballBuilds.map((b) => `${b.version} for Fabric ${b.minecraft}`).join(', ') : 'Not bundled in this build', h('span', { class: `tag${state.snowballBuilds.length ? ' accent' : ''}` }, state.snowballBuilds.length ? 'BUILT IN' : 'MISSING')))));
  }

  // ---------- dialogs ----------
  function createInstanceDialog(): void {
    const state = ui.state!;
    const name = h('input', { class: 'input', value: 'Snowball', maxlength: '64' }) as HTMLInputElement;
    const version = h('select', { class: 'input' }, h('option', {}, 'Loading...')) as HTMLSelectElement;
    const snapshots = h('input', { type: 'checkbox' }) as HTMLInputElement;
    const loader = h('select', { class: 'input' }, ...(['fabric', 'vanilla', 'quilt', 'forge', 'neoforge'] as Snowball.LoaderId[]).map((l) => h('option', { value: l }, LOADER_NAMES[l]))) as HTMLSelectElement;
    const loaderVersion = h('select', { class: 'input' }) as HTMLSelectElement;
    const clientNote = h('div', { class: 'muted core-note' });
    const defaultVersion = state.snowballBuilds[0]?.minecraft.split(', ')[0] ?? '';
    const profile = h('select', { class: 'input' }, ...state.profiles.map((p) => h('option', { value: p.id, selected: p.id === 'fps-boost' }, p.name))) as HTMLSelectElement;

    const loadVersions = async () => {
      const list = await guard(() => api.listMinecraftVersions(snapshots.checked));
      if (!list) return;
      version.replaceChildren(...list.map((v) => h('option', { value: v.id, selected: v.id === defaultVersion }, v.type === 'release' ? v.id : `${v.id} (${v.type})`)));
      await loadLoaders();
    };
    const loadLoaders = async () => {
      updateClient();
      if (loader.value === 'vanilla') {
        loaderVersion.replaceChildren(h('option', { value: '' }, 'Not needed'));
        loaderVersion.disabled = true;
        return;
      }
      loaderVersion.disabled = false;
      loaderVersion.replaceChildren(h('option', { value: '' }, 'Loading...'));
      const list = await guard(() => api.listLoaderVersions(loader.value as Snowball.LoaderId, version.value));
      loaderVersion.replaceChildren(h('option', { value: '' }, 'Latest stable'), ...(list ?? []).slice(0, 60).map((v) => h('option', { value: v.version }, v.stable ? v.version : `${v.version} (beta)`)));
      if (list && list.length === 0) loaderVersion.replaceChildren(h('option', { value: '' }, `No ${LOADER_NAMES[loader.value as Snowball.LoaderId]} build for ${version.value}`));
    };
    let supportCheck = 0;
    const updateClient = () => {
      const check = ++supportCheck;
      void api.snowballSupport(version.value, loader.value as Snowball.LoaderId).then((s) => {
        if (check !== supportCheck) return;
        const available = state.snowballBuilds.map((b) => b.minecraft).join(', ');
        clientNote.textContent = s.supported
          ? `Snowball Client ${s.version} and ${s.fabricApi} are set up automatically.`
          : `Snowball Client isn't available for ${LOADER_NAMES[loader.value as Snowball.LoaderId]} ${version.value} yet${available ? ` (it runs on Fabric ${available})` : ''}. The instance works without it.`;
        if (!s.performanceProfiles) {
          profile.disabled = true;
          profile.value = 'none';
        }
      }).catch(() => undefined);
      profile.disabled = !(loader.value === 'fabric' || loader.value === 'quilt');
      if (profile.disabled) profile.value = 'none';
    };
    version.addEventListener('change', () => void loadLoaders());
    loader.addEventListener('change', () => void loadLoaders());
    snapshots.addEventListener('change', () => void loadVersions());
    void loadVersions();

    const body = h('div', { class: 'form-grid' },
      h('label', { class: 'field full' }, 'Name', name),
      h('label', { class: 'field' }, 'Minecraft version', version, h('span', { class: 'row' }, snapshots, 'Show snapshots')),
      h('label', { class: 'field' }, 'Mod loader', loader),
      h('label', { class: 'field' }, 'Loader version', loaderVersion),
      h('label', { class: 'field' }, 'Performance profile', profile),
      clientNote);

    modal('NEW INSTANCE', body, [
      { label: 'Cancel', kind: 'ghost', onClick: (close) => close() },
      {
        label: 'Create',
        kind: 'primary',
        onClick: async (close) => {
          if (!name.value.trim()) return toast('Enter a name for the instance.', 'error');
          const created = await guard(() => api.createInstance({
            name: name.value.trim(),
            minecraftVersion: version.value,
            loader: loader.value as Snowball.LoaderId,
            loaderVersion: loaderVersion.value || null,
            performanceProfile: profile.value as Snowball.PerformanceProfileId,
          }));
          if (!created) return;
          close();
          ui.selectedId = created.id;
          ui.view = 'home';
          await refresh();
          toast(created.snowball.supported ? `Created ${created.name} with Snowball Client ${created.snowball.version}. Press Play to start.` : `Created ${created.name}. Press Play to install and launch.`);
        },
      },
    ]);
  }

  function editInstanceDialog(inst: Snowball.Instance): void {
    const state = ui.state!;
    const tabs = ['General', 'Java & Memory', 'Window', 'Folders'] as const;
    let active: (typeof tabs)[number] = 'General';

    const name = h('input', { class: 'input', value: inst.name, maxlength: '64' }) as HTMLInputElement;
    const version = h('input', { class: 'input', value: inst.minecraftVersion }) as HTMLInputElement;
    const loader = h('select', { class: 'input' }, ...(Object.keys(LOADER_NAMES) as Snowball.LoaderId[]).map((l) => h('option', { value: l, selected: l === inst.loader }, LOADER_NAMES[l]))) as HTMLSelectElement;
    const loaderVersion = h('input', { class: 'input', value: inst.loaderVersion ?? '', placeholder: 'Latest stable' }) as HTMLInputElement;
    const javaPath = h('input', { class: 'input', value: inst.javaExecutable ?? '', placeholder: 'Automatic (recommended)' }) as HTMLInputElement;
    const maxMem = h('input', { type: 'range', min: '1024', max: String(state.memory.safeUpperLimitMb), step: '256', value: String(inst.memory.maxMb) }) as HTMLInputElement;
    const minMem = h('input', { type: 'range', min: '512', max: String(state.memory.safeUpperLimitMb), step: '256', value: String(inst.memory.minMb) }) as HTMLInputElement;
    const memLabel = h('div', { class: 'muted' });
    const syncMem = () => {
      if (Number(minMem.value) > Number(maxMem.value)) minMem.value = maxMem.value;
      memLabel.textContent = `Min ${fmtMemory(Number(minMem.value))} - Max ${fmtMemory(Number(maxMem.value))} (recommended max ${fmtMemory(state.memory.recommendedMaxMb)})`;
    };
    maxMem.addEventListener('input', syncMem);
    minMem.addEventListener('input', syncMem);
    syncMem();
    const jvmArgs = h('textarea', { class: 'input' }, inst.jvmArgs) as HTMLTextAreaElement;
    const gameArgs = h('textarea', { class: 'input' }, inst.gameArgs) as HTMLTextAreaElement;
    const width = h('input', { class: 'input', type: 'number', min: '320', value: String(inst.window.width) }) as HTMLInputElement;
    const height = h('input', { class: 'input', type: 'number', min: '240', value: String(inst.window.height) }) as HTMLInputElement;
    const fullscreen = h('input', { type: 'checkbox', checked: inst.window.fullscreen }) as HTMLInputElement;

    const panes: Record<(typeof tabs)[number], HTMLElement> = {
      General: h('div', { class: 'form-grid' },
        h('label', { class: 'field full' }, 'Name', name),
        h('label', { class: 'field' }, 'Minecraft version', version),
        h('label', { class: 'field' }, 'Mod loader', loader),
        h('label', { class: 'field' }, 'Loader version', loaderVersion),
        h('div', { class: 'muted core-note' }, inst.snowball.supported ? `Snowball Client ${inst.snowball.version} is built in and checked before every launch.` : "Snowball Client isn't available for this Minecraft version and loader yet.")),
      'Java & Memory': h('div', { class: 'form-grid' },
        h('label', { class: 'field full' }, 'Java executable', h('div', { class: 'row' }, javaPath, h('button', { class: 'btn', onClick: async () => { const p = await guard(() => api.browseJava()); if (p) javaPath.value = p; } }, 'Browse'), h('button', { class: 'btn ghost', onClick: () => (javaPath.value = '') }, 'Auto'))),
        h('label', { class: 'field full' }, 'Maximum memory', maxMem),
        h('label', { class: 'field full' }, 'Minimum memory', minMem, memLabel),
        h('label', { class: 'field full' }, 'JVM arguments', jvmArgs),
        h('label', { class: 'field full' }, 'Game arguments', gameArgs)),
      Window: h('div', { class: 'form-grid' },
        h('label', { class: 'field' }, 'Width', width),
        h('label', { class: 'field' }, 'Height', height),
        h('label', { class: 'field full' }, h('span', { class: 'row' }, fullscreen, 'Start in fullscreen'))),
      Folders: h('div', { class: 'row', style: 'flex-wrap:wrap' },
        ...(['mods', 'resourcepacks', 'shaderpacks', 'saves', 'screenshots', 'config', 'logs', 'crash-reports'] as Snowball.FolderId[]).map((f) => h('button', { class: 'btn', onClick: () => void guard(() => api.openInstanceFolder(inst.id, f)) }, f))),
    };

    const tabBar = h('div', { class: 'tabs' });
    const paneHost = h('div', {});
    const showTab = () => {
      tabBar.replaceChildren(...tabs.map((t) => h('button', { class: `tab${t === active ? ' active' : ''}`, onClick: () => { active = t; showTab(); } }, t.toUpperCase())));
      paneHost.replaceChildren(panes[active]);
    };
    showTab();

    modal(`EDIT ${inst.name.toUpperCase()}`, h('div', {}, tabBar, paneHost), [
      { label: 'Delete', kind: 'danger', onClick: (close) => confirmDialog('Delete instance', `Delete "${inst.name}" including its worlds, mods and screenshots? This cannot be undone.`, 'Delete', async () => { await api.deleteInstance(inst.id); close(); await refresh(); }) },
      { label: 'Duplicate', kind: 'ghost', onClick: async (close) => { const copy = await guard(() => api.cloneInstance(inst.id, `${inst.name} Copy`)); if (copy) { close(); ui.selectedId = copy.id; await refresh(); } } },
      { label: 'Cancel', kind: 'ghost', onClick: (close) => close() },
      {
        label: 'Save',
        kind: 'primary',
        onClick: async (close) => {
          const saved = await guard(() => api.updateInstance(inst.id, {
            name: name.value.trim(),
            minecraftVersion: version.value.trim(),
            loader: loader.value as Snowball.LoaderId,
            loaderVersion: loaderVersion.value.trim() || null,
            javaExecutable: javaPath.value.trim() || null,
            memory: { minMb: Number(minMem.value), maxMb: Number(maxMem.value) },
            jvmArgs: jvmArgs.value,
            gameArgs: gameArgs.value,
            window: { width: Number(width.value), height: Number(height.value), fullscreen: fullscreen.checked },
          }));
          if (saved) {
            close();
            await refresh();
            toast('Instance saved');
          }
        },
      },
    ]);
  }

  function offlineDialog(): void {
    const name = h('input', { class: 'input', maxlength: '16', placeholder: 'Player name' }) as HTMLInputElement;
    modal('OFFLINE PROFILE', h('div', {}, h('p', { class: 'muted' }, 'Offline profiles work in singleplayer and on LAN, not on online-mode servers.'), h('label', { class: 'field' }, 'Name', name)), [
      { label: 'Cancel', kind: 'ghost', onClick: (close) => close() },
      { label: 'Add', kind: 'primary', onClick: async (close) => { if (await guard(() => api.addOfflineAccount(name.value.trim()))) { close(); await refresh(); } } },
    ]);
  }

  function microsoftSignIn(): void {
    if (!ui.state!.microsoftSignInConfigured) {
      microsoftSetupDialog();
      return;
    }
    const close = modal('SIGN IN WITH MICROSOFT', h('div', {},
      h('p', {}, 'A Microsoft sign-in window has opened.'),
      h('p', { class: 'muted' }, 'Enter your email and password there. They go straight to Microsoft; Snowball Client never sees your password.'),
      h('div', { class: 'progress indeterminate' }, h('div', {}))), []);
    void api.signInMicrosoft()
      .then(async (account) => {
        toast(`Signed in as ${account.name}`);
        await refresh();
      })
      .catch((err) => toast(errorMessage(err), 'error'))
      .finally(close);
  }

  function microsoftSetupDialog(): void {
    // Players never configure anything; the developer ships the app ID in app-config.json (see README).
    modal('SIGN-IN UNAVAILABLE', h('p', { class: 'muted' }, 'Microsoft sign-in is not enabled in this build of Snowball Client yet. Please update to the latest version.'), [
      { label: 'OK', kind: 'primary', onClick: (close) => close() },
    ]);
  }

  // ---------- events ----------
  api.on('progress', (p: Snowball.Progress) => {
    ui.progress.set(p.instanceId, p);
    if (p.stage === 'Running') ui.busy.delete(p.instanceId);
    else ui.busy.add(p.instanceId);
    if (ui.view === 'home' || ui.view === 'instances') render();
  });
  const appendToOutput = (instanceId: string, mode: 'activity' | 'technical', row: HTMLElement) => {
    const view = document.getElementById('home-log');
    if (!view || ui.view !== 'home' || ui.selectedId !== instanceId || ui.logMode !== mode) return;
    if (view.firstElementChild?.classList.contains('muted')) view.replaceChildren();
    const atBottom = view.scrollTop + view.clientHeight >= view.scrollHeight - 30;
    view.append(row);
    while (view.childElementCount > 300) view.firstElementChild?.remove();
    if (atBottom) view.scrollTop = view.scrollHeight;
  };
  api.on('game-log', (l: Snowball.GameLog) => {
    const lines = ui.logs.get(l.instanceId) ?? [];
    lines.push(l);
    if (lines.length > 2000) lines.splice(0, lines.length - 2000);
    ui.logs.set(l.instanceId, lines);
    appendToOutput(l.instanceId, 'technical', techRow(l));
  });
  api.on('activity', (e: Snowball.ActivityEvent) => {
    const events = ui.activity.get(e.instanceId) ?? [];
    events.push(e);
    if (events.length > 400) events.splice(0, events.length - 400);
    ui.activity.set(e.instanceId, events);
    appendToOutput(e.instanceId, 'activity', activityRow(e));
  });
  api.on('game-exit', (e: Snowball.GameExit) => {
    ui.busy.delete(e.instanceId);
    ui.progress.delete(e.instanceId);
    if (e.crashed) {
      modal('GAME CRASHED', h('div', {},
        h('p', {}, `Minecraft exited with code ${e.code ?? 'unknown'} after ${fmtDuration(e.durationMs)}.`),
        e.crashReport ? h('p', { class: 'muted' }, `Crash report: ${e.crashReport}`) : h('p', { class: 'muted' }, 'Check the game output on the Home page for details.')), [
        { label: 'Open crash reports', onClick: () => void guard(() => api.openInstanceFolder(e.instanceId, 'crash-reports')) },
        { label: 'Close', kind: 'primary', onClick: (close) => close() },
      ]);
    }
  });
  api.on('launch-error', (e: { instanceId: string; message: string }) => {
    ui.busy.delete(e.instanceId);
    ui.progress.delete(e.instanceId);
    render();
    modal('COULD NOT START', h('p', { style: 'white-space:pre-wrap' }, e.message), [{ label: 'OK', kind: 'primary', onClick: (close) => close() }]);
  });
  api.on('launcher-log', (r: Snowball.LauncherLog) => {
    ui.launcherLogs.push(r);
    if (ui.launcherLogs.length > 500) ui.launcherLogs.shift();
  });
  api.on('state', () => void refresh());
  api.on('chat-state', (state: Snowball.ChatState) => {
    const wasAnnouncement = ui.chat?.announcement ?? null;
    ui.chat = state;
    if (ui.view === 'chat' || wasAnnouncement !== state.announcement) render();
  });
  api.on('chat-lookup', (result: Snowball.RankLookup) => {
    ui.lookup = result;
    if (ui.view === 'chat') render();
  });
  api.on('chat-rank-set', (result: { uuid: string; rank: string; history: Snowball.RankLookup['history'] }) => {
    if (ui.lookup?.uuid === result.uuid) {
      ui.lookup = { ...ui.lookup, rank: result.rank, history: result.history };
      if (ui.view === 'chat') render();
    }
  });
  api.on('chat-people', (people: Snowball.SnowballPerson[]) => {
    ui.people = people;
    if (ui.view === 'chat' && ui.adminTab === 'people') render();
  });
  api.on('chat-bugs', (bugs: Snowball.BugReport[]) => {
    ui.bugs = bugs;
    if (ui.view === 'chat' && ui.adminTab === 'bugs') render();
  });
  api.on('chat-history', (messages: Snowball.ChatMessage[]) => {
    ui.chatMessages = messages.slice(-200);
    const view = document.getElementById('chat-log');
    if (view) paintChat(view);
  });
  api.on('chat-message', (message: Snowball.ChatMessage) => {
    ui.chatMessages.push(message);
    if (ui.chatMessages.length > 200) ui.chatMessages.shift();
    const view = document.getElementById('chat-log');
    if (view) {
      const atBottom = view.scrollTop + view.clientHeight >= view.scrollHeight - 40;
      view.append(chatRow(message));
      if (atBottom) view.scrollTop = view.scrollHeight;
    }
  });
  api.on('update', (state: Snowball.UpdateState) => {
    ui.update = state;
    render();
  });
  refreshStats();
  setInterval(refreshStats, 60_000);
  void api.chatState().then((state) => {
    ui.chat = state;
    if (state.configured) render();
  }).catch(() => {});
  void api.appVersion().then((version) => {
    ui.version = version;
    const label = document.getElementById('app-version');
    if (label) label.textContent = `v${version}`;
  }).catch(() => undefined);
  void api.updateState().then((state) => {
    ui.update = state;
    render();
  }).catch(() => {});

  render();
  void refresh()
    .catch((err) => toast(errorMessage(err), 'error'))
    .finally(() => {
      // The splash stays until the first real frame is on screen, then fades out of the way.
      const boot = document.getElementById('boot');
      if (!boot) return;
      boot.classList.add('leaving');
      setTimeout(() => boot.remove(), 300);
    });
})();
