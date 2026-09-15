/// <reference path="../shared/api.d.ts" />
// Renderer for the Snowball Client launcher. Plain DOM + the preload bridge; no Node access.

(() => {
  const api = window.snowball;

  type View = 'home' | 'instances' | 'mods' | 'browse' | 'java' | 'settings';

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
    instances: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
    mods: '<path d="M12 2l9 5v10l-9 5-9-5V7z"/><path d="M12 22V12M21 7l-9 5-9-5"/>',
    browse: '<circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/>',
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
  const NAV: Array<[View, string]> = [
    ['home', 'HOME'],
    ['instances', 'INSTANCES'],
    ['mods', 'MODS'],
    ['browse', 'BROWSE'],
    ['java', 'JAVA'],
    ['settings', 'SETTINGS'],
  ];

  function render(): void {
    const nav = document.getElementById('nav')!;
    nav.replaceChildren(...NAV.map(([view, label]) => h('button', { class: `nav-item${ui.view === view ? ' active' : ''}`, onClick: () => { ui.view = view; render(); } }, icon(view), label)));

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
    const views: Record<View, () => Node> = { home: homeView, instances: instancesView, mods: modsView, browse: browseView, java: javaView, settings: settingsView };
    content.replaceChildren(views[ui.view]());
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
      h('button', { class: 'btn primary', onClick: () => createInstanceDialog() }, 'Create instance'));
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

  function progressBar(id: string): HTMLElement | null {
    if (!ui.busy.has(id)) return null;
    const p = ui.progress.get(id);
    const pct = p?.total ? Math.round((100 * (p.completed ?? 0)) / p.total) : null;
    return h('div', {},
      h('div', { class: `progress${pct === null ? ' indeterminate' : ''}` }, h('div', { style: pct === null ? '' : `width:${pct}%` })),
      h('div', { class: 'muted', style: 'margin-top:6px;font-size:13px' }, p ? `${p.stage}${pct !== null ? ` - ${pct}% (${p.completed}/${p.total})` : ''}` : 'Preparing...'));
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
    fillLog(logView, inst.id);
    if (inst.running && !ui.logs.get(inst.id)?.length) {
      void api.gameLogs(inst.id).then((lines) => {
        ui.logs.set(inst.id, lines);
        fillLog(logView, inst.id);
      });
    }

    return h('div', {},
      header('HOME', 'Selected instance', picker),
      inst.error ? h('div', { class: 'issue' }, `This instance could not be loaded: ${inst.error}`) : null,
      h('div', { class: 'hero' },
        h('div', { class: 'card hero-main' },
          h('div', { class: 'row' },
            h('span', { class: 'tag accent' }, LOADER_NAMES[inst.loader]),
            inst.clientProfile === 'snowballclient' ? h('span', { class: 'tag accent' }, 'SNOWBALL CLIENT') : null,
            inst.running ? h('span', { class: 'tag running' }, 'RUNNING') : null),
          h('div', { class: 'hero-name' }, inst.name),
          h('div', { class: 'stats' },
            stat('Minecraft', inst.minecraftVersion),
            stat('Loader', inst.loader === 'vanilla' ? 'Vanilla' : `${LOADER_NAMES[inst.loader]} ${inst.loaderVersion ?? '(latest)'}`),
            stat('Mods', modsValue),
            stat('Memory', fmtMemory(inst.memory.maxMb)),
            stat('Last played', fmtDate(inst.lastPlayed)),
            stat('Play time', fmtDuration(inst.totalPlayMs))),
          h('div', { class: 'row' },
            playButton(inst, true),
            h('button', { class: 'btn', onClick: () => editInstanceDialog(inst) }, 'Edit'),
            h('button', { class: 'btn ghost', onClick: () => void guard(() => api.openInstanceFolder(inst.id, 'root')) }, 'Open folder')),
          progressBar(inst.id)),
        h('div', { class: 'card', style: 'display:flex;flex-direction:column' },
          h('div', { class: 'section-title' }, 'GAME OUTPUT'),
          logView)));
  }

  function fillLog(view: HTMLElement, id: string): void {
    const lines = (ui.logs.get(id) ?? []).slice(-300);
    if (lines.length === 0) {
      view.replaceChildren(h('span', { class: 'muted' }, 'Game output appears here while the instance is running.'));
      return;
    }
    view.replaceChildren(...lines.map((l) => h('div', { class: l.stream === 'stderr' || /ERROR|Exception/.test(l.line) ? 'err' : '' }, l.line)));
    view.scrollTop = view.scrollHeight;
  }

  function instancesView(): Node {
    const state = ui.state!;
    const newButton = h('button', { class: 'btn primary', onClick: () => createInstanceDialog() }, '+ New instance');
    if (state.instances.length === 0) return h('div', {}, header('INSTANCES', 'Isolated game installations'), emptyState('Each instance has its own version, mods, settings and worlds.'));
    return h('div', {},
      header('INSTANCES', `${state.instances.length} isolated installation${state.instances.length === 1 ? '' : 's'}`, newButton),
      h('div', { class: 'grid cards' }, ...state.instances.map((inst) =>
        h('div', { class: `card instance-card${inst.id === ui.selectedId ? ' selected' : ''}`, onClick: () => select(inst.id) },
          h('div', { class: 'row' },
            h('div', { class: 'instance-icon' }, h('img', { src: 'assets/logo.png', alt: '' })),
            h('div', { class: 'grow', style: 'min-width:0' }, h('div', { class: 'instance-title truncate' }, inst.name), h('div', { class: 'muted' }, `${inst.minecraftVersion} - ${LOADER_NAMES[inst.loader]}`)),
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
    const load = async () => {
      const result = await guard(() => api.listMods(inst.id));
      if (!result) return;
      issuesEl.replaceChildren(...result.issues.map((i) => h('div', { class: `issue${i.severity === 'warning' ? ' warning' : ''}` }, i.message)));
      if (result.mods.length === 0) {
        listEl.replaceChildren(h('div', { class: 'muted' }, inst.loader === 'vanilla' ? 'This instance has no mod loader. Choose Fabric, Quilt, Forge or NeoForge in the instance editor.' : 'No mods installed yet.'));
        return;
      }
      listEl.replaceChildren(...result.mods.map((m) =>
        h('div', { class: 'list-row' },
          toggle(m.enabled, async (value) => { await guard(() => api.setModEnabled(inst.id, m.fileName, value)); await load(); }, inst.running),
          h('div', { class: 'grow' },
            h('div', { class: 'truncate' }, m.name, m.version ? h('span', { class: 'muted' }, `  ${m.version}`) : null),
            h('div', { class: 'muted truncate', style: 'font-size:12px' }, `${m.fileName} - ${m.loader}${m.managed ? ' - managed by performance profile' : ''}`, m.error ? h('span', { class: 'danger-text' }, ` - ${m.error}`) : null)),
          updateButton(m),
          h('button', { class: 'btn small danger', disabled: inst.running, onClick: () => confirmDialog('Remove mod', `Remove ${m.fileName} from ${inst.name}? The file will be deleted.`, 'Remove', async () => { await api.removeMod(inst.id, m.fileName); await load(); }) }, 'Remove'))));
    };
    void load();

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
        h('button', { class: 'btn ghost', onClick: () => void guard(() => api.openInstanceFolder(inst.id, 'mods')) }, 'Open folder')),
      h('div', { class: 'card' },
        h('div', { class: 'section-title' }, 'PERFORMANCE PROFILE'),
        h('div', { class: 'row' }, h('div', { style: 'flex:1' }, profileSelect), applyButton),
        profileInfo),
      issuesEl,
      h('div', { class: 'card' },
        h('div', { class: 'row', style: 'margin-bottom:12px' }, h('div', { class: 'section-title', style: 'margin:0' }, 'INSTALLED MODS'), h('div', { class: 'spacer' }), updatesButton,
          h('button', { class: 'btn small primary', disabled: inst.loader === 'vanilla', onClick: () => { ui.view = 'browse'; render(); } }, 'Browse mods')),
        listEl));
  }

  const SORTS: Array<[string, string]> = [['relevance', 'Relevance'], ['downloads', 'Downloads'], ['follows', 'Popularity'], ['updated', 'Recently updated'], ['newest', 'Newest'], ['name', 'Name (A-Z)']];
  const MOD_LOADER_IDS: Snowball.LoaderId[] = ['fabric', 'quilt', 'forge', 'neoforge'];

  function browseView(): Node {
    const state = ui.state!;
    const inst = selected();
    if (!inst) return h('div', {}, header('BROWSE', 'Find and install mods'), emptyState('Create an instance first.'));
    const b = ui.browse;
    if (b.instanceId !== inst.id) {
      // Filters start at the instance's own version and loader so every result can be installed.
      Object.assign(b, { instanceId: inst.id, version: inst.minecraftVersion, loader: inst.loader === 'vanilla' ? '' : inst.loader, hits: [], total: 0, error: null, searched: false, installed: new Set<string>() });
    }
    const compatible = inst.loader === 'quilt' ? ['quilt', 'fabric'] : [inst.loader];

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
    const loaderSelect = dropdown([['', 'Any loader'], ...MOD_LOADER_IDS.map((l): [string, string] => [l, LOADER_NAMES[l]])], b.loader, (v) => { b.loader = v; void runSearch(false); });
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
        toast(extra.length ? `Installed ${hit.title} with ${extra.join(', ')}` : `Installed ${hit.title}`);
      }
      await loadInstalled();
      b.paint();
    };

    const modRow = (hit: Snowball.ModSearchHit): HTMLElement => {
      const fits = hit.gameVersions.includes(inst.minecraftVersion) && hit.loaders.some((l) => compatible.includes(l));
      const installed = b.installed.has(hit.projectId);
      const installing = b.installing.has(`${inst.id}:${hit.projectId}`);
      const why = inst.loader === 'vanilla' ? 'This instance has no mod loader' : `No ${LOADER_NAMES[inst.loader]} version for Minecraft ${inst.minecraftVersion}`;
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
            ...hit.loaders.map((l) => h('span', { class: `tag${compatible.includes(l) ? ' accent' : ''}` }, LOADER_NAMES[l as Snowball.LoaderId] ?? l)),
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
      header('BROWSE', `Mods from Modrinth for ${inst.minecraftVersion} ${LOADER_NAMES[inst.loader]}`, picker,
        h('button', { class: 'btn ghost', onClick: () => { ui.view = 'mods'; render(); } }, 'Installed mods')),
      inst.loader === 'vanilla' ? h('div', { class: 'issue warning' }, 'This instance has no mod loader. Choose Fabric, Quilt, Forge or NeoForge in the instance editor to install mods.') : null,
      h('div', { class: 'browse-toolbar' }, search, sortSelect, loaderSelect, versionSelect),
      h('div', { class: 'card' }, results, footer));
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
          settingRow('Snowball Client mod', state.clientJarAvailable ? `Bundled, for Fabric ${state.clientMinecraftVersion}` : 'Not bundled in this build', h('span', { class: `tag${state.clientJarAvailable ? ' accent' : ''}` }, state.clientJarAvailable ? 'READY' : 'MISSING')))));
  }

  // ---------- dialogs ----------
  function createInstanceDialog(): void {
    const state = ui.state!;
    const name = h('input', { class: 'input', value: 'Snowball', maxlength: '64' }) as HTMLInputElement;
    const version = h('select', { class: 'input' }, h('option', {}, 'Loading...')) as HTMLSelectElement;
    const snapshots = h('input', { type: 'checkbox' }) as HTMLInputElement;
    const loader = h('select', { class: 'input' }, ...(['fabric', 'vanilla', 'quilt', 'forge', 'neoforge'] as Snowball.LoaderId[]).map((l) => h('option', { value: l }, LOADER_NAMES[l]))) as HTMLSelectElement;
    const loaderVersion = h('select', { class: 'input' }) as HTMLSelectElement;
    const client = h('input', { type: 'checkbox', checked: true }) as HTMLInputElement;
    const clientNote = h('span', { class: 'muted' });
    const profile = h('select', { class: 'input' }, ...state.profiles.map((p) => h('option', { value: p.id, selected: p.id === 'fps-boost' }, p.name))) as HTMLSelectElement;

    const loadVersions = async () => {
      const list = await guard(() => api.listMinecraftVersions(snapshots.checked));
      if (!list) return;
      version.replaceChildren(...list.map((v) => h('option', { value: v.id, selected: v.id === state.clientMinecraftVersion }, v.type === 'release' ? v.id : `${v.id} (${v.type})`)));
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
    const updateClient = () => {
      const supported = loader.value === 'fabric' && version.value === state.clientMinecraftVersion && state.clientJarAvailable;
      client.disabled = !supported;
      if (!supported) client.checked = false;
      clientNote.textContent = supported ? 'Adds the Snowball radial menu, HUD and QoL modules.' : `Available for Fabric ${state.clientMinecraftVersion}${state.clientJarAvailable ? '' : ' (mod not bundled in this build)'}.`;
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
      h('label', { class: 'field full' }, h('span', { class: 'row' }, client, 'Install Snowball Client'), clientNote));

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
            clientProfile: client.checked ? 'snowballclient' : 'none',
            performanceProfile: profile.value as Snowball.PerformanceProfileId,
          }));
          if (!created) return;
          close();
          ui.selectedId = created.id;
          ui.view = 'home';
          await refresh();
          toast(`Created ${created.name}. Press Play to install and launch.`);
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
    const client = h('input', { type: 'checkbox', checked: inst.clientProfile === 'snowballclient' }) as HTMLInputElement;
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
        h('label', { class: 'field' }, h('span', { class: 'row' }, client, 'Snowball Client'), h('span', { class: 'muted' }, `Fabric ${state.clientMinecraftVersion} only`))),
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
            clientProfile: client.checked ? 'snowballclient' : 'none',
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
  api.on('game-log', (l: Snowball.GameLog) => {
    const lines = ui.logs.get(l.instanceId) ?? [];
    lines.push(l);
    if (lines.length > 2000) lines.splice(0, lines.length - 2000);
    ui.logs.set(l.instanceId, lines);
    const view = document.getElementById('home-log');
    if (view && ui.view === 'home' && ui.selectedId === l.instanceId) {
      if (view.firstElementChild?.classList.contains('muted')) view.replaceChildren();
      const atBottom = view.scrollTop + view.clientHeight >= view.scrollHeight - 30;
      view.append(h('div', { class: l.stream === 'stderr' || /ERROR|Exception/.test(l.line) ? 'err' : '' }, l.line));
      while (view.childElementCount > 300) view.firstElementChild?.remove();
      if (atBottom) view.scrollTop = view.scrollHeight;
    }
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

  render();
  void refresh().catch((err) => toast(errorMessage(err), 'error'));
})();
