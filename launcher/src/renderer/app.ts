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
    /** The chosen background picture as a data URL, read once at start-up. */
    background: null as string | null,
  };
  /** Redraws the people list where it stands, while the People tab is open. */
  let repaintPeople: (() => void) | null = null;

  /** Presets, and the hex each one sets. A custom colour is any other value. */
  const ACCENTS: Array<[string, string]> = [
    ['Ice', '#7fcbff'],
    ['Mint', '#6fe3c4'],
    ['Lime', '#a8e05f'],
    ['Gold', '#ffd166'],
    ['Coral', '#ff8a6b'],
    ['Rose', '#ff8ab5'],
    ['Violet', '#b57bff'],
    ['Cobalt', '#6d9dff'],
  ];

  /** Colour maths, so one chosen colour can fill in every shade the interface needs. */
  const rgbOf = (hex: string): [number, number, number] => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16)) as [number, number, number];

  /**
   * Pushes the appearance settings into CSS custom properties. Everything accented reads from
   * these, so one colour changes the whole launcher without a stylesheet per theme.
   */
  function applyAppearance(settings: Snowball.Settings): void {
    const a = settings.appearance;
    document.body.classList.toggle('reduce-motion', a.reduceMotion);
    document.body.classList.toggle('compact', a.density === 'compact');

    const accent = /^#[0-9a-f]{6}$/i.test(a.accent) ? a.accent : '#7fcbff';
    const [r, g, b] = rgbOf(accent);
    const root = document.documentElement.style;
    root.setProperty('--accent', accent);
    root.setProperty('--accent-soft', `rgba(${r}, ${g}, ${b}, 0.14)`);
    root.setProperty('--accent-glow', `rgba(${r}, ${g}, ${b}, 0.35)`);
    // The colour a primary button prints its label in: dark on a light accent, light on a dark one.
    const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    root.setProperty('--on-accent', luminance > 0.6 ? '#05070a' : '#ffffff');

    const backdrop = document.getElementById('backdrop');
    if (backdrop) {
      const on = a.background.enabled && Boolean(ui.background);
      backdrop.style.backgroundImage = on ? `url("${ui.background}")` : '';
      backdrop.style.opacity = on ? String(a.background.opacity / 100) : '0';
      backdrop.style.filter = a.background.blur ? `blur(${a.background.blur}px)` : '';
    }
  }

  // ---------- DOM helpers ----------
  type Child = Node | string | null | undefined | false;
  type Props = Record<string, unknown> & { class?: string; onClick?: (e: MouseEvent) => void };

  function h<K extends keyof HTMLElementTagNameMap>(tag: K, props: Props = {}, ...children: Child[]): HTMLElementTagNameMap[K] {
    const el = document.createElement(tag);
    for (const [key, value] of Object.entries(props)) {
      if (value === undefined || value === null || value === false) continue;
      if (key === 'class') el.className = String(value);
      // Through the CSSOM, not setAttribute: the page's CSP (style-src 'self') blocks a style
      // attribute, and did so silently for every style written here until 1.6.4 - progress bars
      // never moved and long names never truncated. The CSSOM is not covered by style-src.
      else if (key === 'style') el.style.cssText = String(value);
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
    folder: '<path d="M3 7a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.6.8l1 1.2H19a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
    check: '<path d="M20 6L9 17l-5-5"/>',
    refresh: '<path d="M20.5 12a8.5 8.5 0 1 1-2.5-6"/><path d="M20.5 4v5h-5"/>',
    download: '<path d="M12 3v12"/><path d="M7.5 10.5L12 15l4.5-4.5"/><path d="M4 20h16"/>',
    alert: '<circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5"/><path d="M12 16.5h.01"/>',
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
  /** 26.x comes after 1.21.x, which a plain string or number sort gets wrong. */
  const minecraftOrder = (id: string) => {
    const [a = 0, b = 0, c = 0] = id.split('.').map((n) => Number.parseInt(n, 10) || 0);
    return a === 1 ? [0, b, c] : [1, a, b];
  };
  const byMinecraftVersion = (x: string, y: string) => {
    const [p, q] = [minecraftOrder(x), minecraftOrder(y)];
    return p[0] - q[0] || p[1] - q[1] || p[2] - q[2];
  };
  /** Why an instance runs without Snowball Client, naming the versions that do have it. */
  const clientUnavailable = (loader: Snowball.LoaderId, minecraftVersion: string, works: string) => {
    const versions = [...new Set(ui.state!.snowballBuilds.flatMap((b) => b.minecraft.split(/,\s*/)).filter(Boolean))].sort(byMinecraftVersion);
    const list = versions.length > 1 ? `${versions.slice(0, -1).join(', ')} and ${versions[versions.length - 1]}` : versions[0];
    const where = loader === 'fabric' ? `Minecraft ${minecraftVersion}` : `${LOADER_NAMES[loader]} on Minecraft ${minecraftVersion}`;
    return `Snowball Client isn't available for ${where} yet${list ? ` - it runs with Fabric on ${list}` : ''}. ${works}`;
  };
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
    applyAppearance(state.settings);
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

  /**
   * What this account may do, according to the server. The client never decides this: it only
   * reflects the permission list the chat server sent, and the server checks again on every
   * action regardless of what the launcher chose to show.
   */
  const can = (permission: string): boolean => {
    const held = ui.chat?.permissions ?? [];
    return held.includes('everything') || held.includes(permission);
  };

  /** The admin tabs, and the permission each one needs. */
  const ADMIN_TABS: Array<[typeof ui.adminTab, string, string]> = [
    ['chat', 'Chat', 'chat.moderate'],
    ['ranks', 'Ranks', 'users.view'],
    ['people', 'People', 'users.view'],
    ['bugs', 'Bugs', 'bugs.triage'],
    ['flags', 'Features', 'flags.manage'],
  ];

  const adminTabs = () => ADMIN_TABS.filter(([, , permission]) => can(permission));

  /**
   * A labelled field. The label is a real <label> wrapping the control, so clicking it focuses
   * the field and a screen reader announces the two together - which a placeholder never does,
   * since it disappears the moment anyone types.
   */
  function field(label: string, control: HTMLElement, hint?: string): HTMLElement {
    return h('label', { class: 'admin-field' },
      h('span', { class: 'admin-field-label' }, label),
      control,
      hint ? h('span', { class: 'admin-field-hint' }, hint) : null);
  }

  /**
   * Announces what just happened. Visible, and marked as a live region so it is read out
   * rather than changing the page silently for anyone who cannot see it.
   */
  function statusLine(): HTMLElement {
    return h('div', { class: 'admin-status', role: 'status', 'aria-live': 'polite' });
  }

  function say(line: HTMLElement, message: string, kind: 'ok' | 'bad' = 'ok'): void {
    line.className = `admin-status show ${kind}`;
    line.textContent = message;
  }

  /** Whether this account can administer anything at all, which is what the nav item needs. */
  const canAdminister = () => adminTabs().length > 0;

  /**
   * The [Tag] chip used in chat, the people list and profiles. It wears the same nine-pixel mark
   * the player list draws, from the same files, so a rank looks identical in-game and here.
   */
  function rankChip(id: string | undefined, extra = ''): HTMLElement {
    const rank = rankInfo(id);
    // The same badge the player list draws, from the same file.
    const chip = h('span', { class: 'rank-chip' + (extra ? ' ' + extra : '') },
      h('img', { class: 'rank-mark', src: `assets/ranks/${rank.id}.png`, alt: '' }),
      rank.tag);
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
    const entries = NAV.filter(([view]) => view !== 'admin' || canAdminister());
    // An icon rail: the label is the tooltip and the accessible name, so the rail stays narrow
    // and the page gets the width instead.
    nav.replaceChildren(...entries.map(([view, label]) => h('button', {
      class: `nav-item${ui.view === view ? ' active' : ''}`,
      title: label,
      'aria-label': label,
      'aria-current': ui.view === view ? 'page' : undefined,
      onClick: () => { ui.view = view; render(); },
    }, icon(view), h('span', { class: 'nav-label' }, label))));

    const state = ui.state;
    const account = state?.accounts.find((a) => a.id === state.settings.accounts.selectedAccountId) ?? state?.accounts[0];
    document.getElementById('account-chip')!.replaceChildren(
      h('button', {
        class: 'account-chip',
        title: account ? `${account.name} \u00b7 ${account.type === 'msa' ? 'Microsoft' : 'Offline'}` : 'No account yet',
        'aria-label': account ? `Signed in as ${account.name}. Open settings.` : 'No account. Open settings.',
        onClick: () => { ui.view = 'settings'; render(); } },
        h('div', { class: 'avatar' }, account ? account.name.slice(0, 1).toUpperCase() : '?')),
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

  /**
   * The Snowball team's message of the day, when there is one. It comes over the chat socket once
   * you have joined, and from the public stats endpoint before that — an announcement is for
   * everyone, so it should not wait until somebody opens Chat.
   */
  function announcementBanner(): HTMLElement | null {
    const text = ui.chat?.announcement ?? ui.stats?.announcement ?? null;
    if (!text) return null;
    return h('div', { class: 'update-bar announce' },
      h('div', { class: 'update-dot' }),
      h('div', { class: 'grow' }, h('div', { class: 'muted', style: 'font-size:11px;letter-spacing:2px' }, 'SNOWBALL'), h('div', {}, text)));
  }

  const fmtBytes = (n: number) => (n >= 1 << 20 ? `${(n / (1 << 20)).toFixed(1)} MB` : `${Math.max(1, Math.round(n / 1024))} KB`);

  /**
   * A problem explained the way a person would explain it: what happened, why, what to try, and a
   * way to hand the technical text to somebody who can read it. Used for updates and for launches.
   */
  function errorPanel(opts: { title: string; message: string; hints?: string[]; detail?: string; onRetry?: () => void; retryLabel?: string }): HTMLElement {
    const actions = h('div', { class: 'row', style: 'margin-top:14px;flex-wrap:wrap' });
    if (opts.onRetry) actions.append(h('button', { class: 'btn small primary', onClick: opts.onRetry }, opts.retryLabel ?? 'Retry'));
    if (opts.detail) {
      actions.append(h('button', {
        class: 'btn small',
        onClick: (e: MouseEvent) => {
          void navigator.clipboard.writeText(`${opts.title}\n${opts.message}\n\n${opts.detail}`)
            .then(() => ((e.target as HTMLElement).textContent = 'Copied'))
            .catch(() => toast('Could not copy to the clipboard.', 'error'));
        },
      }, 'Copy details'));
    }
    actions.append(h('button', { class: 'btn small ghost', onClick: () => void guard(() => api.openLogFolder()) }, 'Open logs'));

    return h('div', { class: 'error-panel' },
      h('div', { class: 'error-head' }, icon('alert'), h('div', { class: 'error-title' }, opts.title)),
      h('p', { class: 'error-message' }, opts.message),
      opts.hints?.length
        ? h('ul', { class: 'error-hints' }, ...opts.hints.map((hint) => h('li', {}, hint)))
        : null,
      actions);
  }

  /**
   * Launch failures in the same shape as update failures: what happened, what it means, what to
   * try. The launcher's own message is kept as the detail, because it is often the useful part.
   */
  function describeLaunchFailure(raw: string): { title: string; message: string; hints: string[] } {
    const text = raw.toLowerCase();
    if (/java|jvm|jre|jdk/.test(text) && /not found|missing|could not|no such/.test(text)) {
      return {
        title: 'Java is missing',
        message: 'This version of Minecraft needs a Java runtime that Snowball could not find or download.',
        hints: ['Open the Java page and press Rescan.', 'Turn on "Download Java automatically" in Settings.', 'Check your internet connection if the download failed.'],
      };
    }
    if (/out of memory|heap space|could not reserve|memory/.test(text)) {
      return {
        title: 'Not enough memory',
        message: 'Minecraft asked for more memory than this computer could give it, so it stopped before starting.',
        hints: ['Lower the memory for this instance in Edit.', 'Close other programs and try again.'],
      };
    }
    if (/download|http|network|econn|etimedout|enotfound/.test(text)) {
      return {
        title: 'Some game files could not be downloaded',
        message: 'Snowball could not fetch everything Minecraft needs, so it stopped rather than starting a broken game.',
        hints: ['Check your internet connection and press Try again.', 'Use "Verify files" on the Home page to repair what is already there.'],
      };
    }
    if (/eacces|eperm|access is denied|permission/.test(text)) {
      return {
        title: 'Windows blocked a file Snowball needed',
        message: 'A game file could not be written or read, usually because antivirus or folder permissions got in the way.',
        hints: ['Check whether your antivirus quarantined something in the Snowball folder.', 'Open the launcher folder from Settings to see what is there.'],
      };
    }
    if (/mod|fabric|quilt|forge|neoforge|mixin/.test(text)) {
      return {
        title: 'A mod stopped the game from starting',
        message: 'Minecraft refused to start with the mods currently installed. This is almost always one mod that does not match the game or loader version.',
        hints: ['Open Mods and look for anything marked incompatible.', 'Turn off recently added mods and try again.', 'The technical output below names the mod in most cases.'],
      };
    }
    return {
      title: 'Minecraft could not start',
      message: 'The game stopped before it finished loading. The technical detail below usually names the cause.',
      hints: ['Press Try again — some failures are temporary.', 'Use "Verify files" on the Home page to repair the installation.', 'Copy the details if you want to report it.'],
    };
  }

  /** One line describing whatever the updater is doing, in the order a person experiences it. */
  function updateLine(state: Snowball.UpdateState): string {
    switch (state.status) {
      case 'checking': return 'Looking for a new version';
      case 'downloading': return `Downloading Snowball Client ${state.newVersion}`;
      case 'verifying': return `Checking Snowball Client ${state.newVersion}`;
      case 'ready': return `Snowball Client ${state.newVersion} is ready`;
      case 'installing': return 'Restarting Snowball';
      case 'up-to-date': return 'Snowball is up to date';
      case 'manual': return state.newVersion ? `Snowball Client ${state.newVersion} is available` : 'Updates are handled manually in this build';
      case 'error': return state.title ?? 'The update could not be checked';
      default: return 'No update has been checked for yet';
    }
  }

  async function retryUpdate(): Promise<void> {
    const next = await guard(() => api.checkForUpdates());
    if (next) {
      ui.update = next;
      render();
    }
  }

  /** The full update view, opened from the banner. Shows the stage, never raw updater output. */
  function updateDialog(): void {
    const state = ui.update;
    if (!state) return;
    const body = h('div', { class: 'stack' });

    if (state.status === 'error') {
      body.append(errorPanel({
        title: state.title ?? 'The update could not be checked',
        message: state.message ?? 'Something went wrong while looking for a new version.',
        hints: state.hints,
        detail: state.detail,
        onRetry: state.canRetry === false ? undefined : () => void retryUpdate(),
      }));
    } else {
      const pct = state.percent ?? 0;
      const done = state.status === 'ready' || state.status === 'installing';
      const rows: Child[] = [
        h('div', { class: 'update-versions' },
          h('div', {}, h('div', { class: 'k' }, 'Current version'), h('div', { class: 'v' }, `v${state.version}`)),
          h('div', { class: 'update-arrow' }, '→'),
          h('div', {}, h('div', { class: 'k' }, 'New version'), h('div', { class: 'v accent' }, state.newVersion ? `v${state.newVersion}` : '-'))),
        h('div', { class: 'update-stage' }, updateLine(state)),
        h('div', { class: `progress${state.status === 'checking' || state.status === 'verifying' || state.status === 'installing' ? ' indeterminate' : ''}` },
          h('div', { style: done ? 'width:100%' : `width:${pct}%` })),
        state.status === 'downloading' && state.total
          ? h('div', { class: 'muted', style: 'font-size:12px' }, `${fmtBytes(state.transferred ?? 0)} of ${fmtBytes(state.total)}${state.bytesPerSecond ? ` · ${fmtBytes(state.bytesPerSecond)}/s` : ''}`)
          : null,
        state.status === 'ready'
          ? h('p', { class: 'muted', style: 'font-size:13px;margin:0' }, 'Snowball restarts to finish, which takes a few seconds. Your instances, mods and worlds are untouched.')
          : null,
        state.status === 'manual' && state.downloadUrl
          ? h('p', { class: 'muted', style: 'font-size:13px;margin:0' }, state.message ?? '')
          : null,
      ];
      body.append(...rows.filter((r): r is Node => r instanceof Node));
    }

    const actions: ModalAction[] = [{ label: 'Close', kind: 'ghost', onClick: (c) => c() }];
    if (state.status === 'ready') actions.push({ label: 'Restart now', kind: 'primary', onClick: (c) => { c(); void api.installUpdate(); } });
    else if (state.status === 'up-to-date' || state.status === 'idle') actions.push({ label: 'Check again', kind: 'primary', onClick: () => void retryUpdate() });
    modal('Snowball Client update', body, actions);
  }

  /** Shown across the top while an update is in flight, or when one needs a decision. */
  function updateBanner(): HTMLElement | null {
    const state = ui.update;
    if (!state) return null;

    if (state.justUpdatedFrom && state.status !== 'error') {
      return h('div', { class: 'update-bar ready' },
        h('div', { class: 'update-dot' }),
        h('div', { class: 'grow' },
          h('div', {}, `Updated to Snowball Client ${state.version}`),
          h('div', { class: 'muted', style: 'font-size:12px' }, `You were on ${state.justUpdatedFrom}.`)),
        h('button', { class: 'btn small', onClick: () => whatsNewDialog(state.version) }, "See what's new"),
        h('button', { class: 'btn small ghost', onClick: () => { if (ui.update) ui.update = { ...ui.update, justUpdatedFrom: undefined }; render(); } }, 'Dismiss'));
    }
    if (state.status === 'downloading' || state.status === 'verifying') {
      return h('div', { class: 'update-bar', onClick: () => updateDialog(), style: 'cursor:pointer' },
        h('div', { class: 'update-dot' }),
        h('div', { class: 'grow' },
          h('div', {}, updateLine(state)),
          h('div', { class: 'progress thin' }, h('div', { style: `width:${state.percent ?? 0}%` }))),
        h('div', { class: 'muted', style: 'font-variant-numeric:tabular-nums' }, state.status === 'verifying' ? 'Checking' : `${state.percent ?? 0}%`));
    }
    if (state.status === 'ready') {
      return h('div', { class: 'update-bar ready' },
        h('div', { class: 'update-dot' }),
        h('div', { class: 'grow' },
          h('div', {}, updateLine(state)),
          h('div', { class: 'muted', style: 'font-size:12px' }, 'It takes a few seconds, and your instances are untouched.')),
        h('button', { class: 'btn small ghost', onClick: () => updateDialog() }, 'Details'),
        h('button', { class: 'btn small primary', onClick: () => void api.installUpdate() }, 'Restart now'));
    }
    if (state.status === 'manual' && state.newVersion) {
      return h('div', { class: 'update-bar' },
        h('div', { class: 'update-dot' }),
        h('div', { class: 'grow' }, h('div', {}, updateLine(state)), h('div', { class: 'muted', style: 'font-size:12px' }, state.message ?? '')),
        h('button', { class: 'btn small', onClick: () => updateDialog() }, 'How to update'));
    }
    if (state.status === 'error' && state.title) {
      return h('div', { class: 'update-bar failed' },
        h('div', { class: 'update-dot' }),
        h('div', { class: 'grow' }, h('div', {}, state.title), h('div', { class: 'muted', style: 'font-size:12px' }, 'Snowball is still working normally.')),
        h('button', { class: 'btn small', onClick: () => updateDialog() }, 'What happened?'));
    }
    return null;
  }

  /**
   * The release notes for a version, read from the changelog that ships with the build. Shown
   * after an update lands and from Settings, so "what changed?" never needs a browser.
   */
  function whatsNewDialog(version?: string): void {
    const body = h('div', { class: 'stack' }, h('div', { class: 'muted' }, 'Reading the release notes...'));
    modal(version ? `What's new in ${version}` : "What's new", body, [{ label: 'Close', kind: 'ghost', onClick: (c) => c() }]);

    void api.releaseNotes(version).then((result) => {
      const notes = Array.isArray(result) ? result : result ? [result] : [];
      if (!notes.length) {
        body.replaceChildren(h('p', { class: 'muted' }, 'This build does not carry its release notes. The full list is on the Snowball website.'));
        return;
      }
      body.replaceChildren(...notes.map((note) =>
        h('div', { class: 'notes-release' },
          // The dialog title already names the version when only one was asked for.
          notes.length > 1 ? h('div', { class: 'notes-version' }, note.version) : null,
          ...note.sections.map((section) =>
            h('div', { class: 'notes-section' },
              h('div', { class: 'section-title' }, section.heading),
              h('ul', { class: 'notes-list' }, ...section.items.map((item) => h('li', {}, item))))))));
    }).catch((err) => body.replaceChildren(h('p', { class: 'muted' }, errorMessage(err))));
  }

  /** While the launcher is handing over to the new build, nothing else should be clickable. */
  function installingOverlay(): void {
    if (document.getElementById('installing')) return;
    const el = h('div', { class: 'boot', id: 'installing' },
      h('img', { src: 'assets/logo.png', alt: '' }),
      h('div', { class: 'boot-name' }, 'UPDATING'),
      h('div', { class: 'boot-bar' }, h('div', {})),
      h('div', { class: 'boot-sub' }, 'Restarting Snowball'));
    document.body.append(el);
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

    return h('div', { class: 'page-home' },
      welcomeBar(picker),
      inst.error ? h('div', { class: 'issue' }, `This instance could not be loaded: ${inst.error}`) : null,
      h('div', { class: 'home-grid' },
        h('div', { class: 'home-main' },
          launchPanel(inst, modsValue, verifyButton),
          h('div', { class: 'card output-card' },
            h('div', { class: 'row log-head' }, h('div', { class: 'section-title' }, 'OUTPUT'), h('div', { class: 'spacer' }), logTabs),
            logView)),
        h('div', { class: 'home-aside' },
          snowballCard(inst),
          playerCount(),
          quickActions(inst))));
  }

  /**
   * Who you are and how many people are on, across the top. It replaces the page title on Home
   * only: everywhere else a heading says what the page is, but here you already know.
   */
  function welcomeBar(picker: HTMLElement): HTMLElement {
    const state = ui.state!;
    const account = state.accounts.find((a) => a.id === state.settings.accounts.selectedAccountId) ?? state.accounts[0];
    const rank = ui.chat?.rank;
    const online = ui.stats?.online ?? ui.chat?.online ?? 0;

    return h('div', { class: 'welcome' },
      h('div', { class: 'welcome-text' },
        h('div', { class: 'welcome-hello' },
          account ? `Welcome back, ${account.name}` : 'Welcome to Snowball',
          rank && rank !== 'snowball' ? rankChip(rank) : null),
        h('div', { class: 'welcome-sub' }, account ? 'Pick an instance and press play.' : 'Add a Microsoft account in Settings to get started.')),
      h('div', { class: 'spacer' }),
      picker,
      online
        ? h('div', { class: 'welcome-online', title: 'People using Snowball right now' },
            h('span', { class: 'count-dot' }),
            h('span', {}, `${online.toLocaleString()} online`))
        : null);
  }

  /**
   * The launch. One large panel, because on this page nothing competes with it: the instance it
   * will start, the button, and while it is working, what it is doing.
   */
  function launchPanel(inst: Snowball.Instance, modsValue: HTMLElement, verifyButton: HTMLElement): HTMLElement {
    const stat = (label: string, value: Node | string) =>
      h('div', { class: 'stat' }, h('div', { class: 'stat-label' }, label), typeof value === 'string' ? h('div', { class: 'stat-value' }, value) : value);

    return h('div', { class: 'card hero-main launch-panel' },
      h('div', { class: 'hero-top' },
        h('div', { class: 'grow' },
          h('div', { class: 'hero-name' }, inst.name),
          h('div', { class: 'hero-tags' },
            h('span', { class: 'tag' }, inst.minecraftVersion),
            h('span', { class: 'tag' }, inst.loaderName),
            inst.snowball.supported ? h('span', { class: 'tag accent' }, `SNOWBALL ${inst.snowball.version ?? ''}`) : null,
            inst.running ? h('span', { class: 'tag running' }, 'RUNNING') : null))),
      h('div', { class: 'stats' },
        stat('Minecraft', inst.minecraftVersion),
        stat('Loader', inst.loader === 'vanilla' ? 'Vanilla' : `${inst.loaderName} ${inst.loaderVersion ?? 'latest'}`),
        stat('Mods', modsValue),
        stat('Memory', fmtMemory(inst.memory.maxMb)),
        stat('Last played', fmtDate(inst.lastPlayed)),
        stat('Play time', fmtDuration(inst.totalPlayMs))),
      h('div', { class: 'hero-actions' },
        playButton(inst, true),
        h('button', { class: 'btn', onClick: () => editInstanceDialog(inst) }, 'Edit'),
        verifyButton),
      progressBar(inst.id));
  }
  /** What this build of Snowball is, and whether anything newer is waiting. */
  function snowballCard(inst: Snowball.Instance): HTMLElement {
    const update = ui.update;
    const updateText =
      update?.status === 'downloading' ? `Downloading ${update.newVersion}` :
      update?.status === 'ready' ? `${update.newVersion} ready` :
      update?.status === 'checking' ? 'Checking...' :
      update?.status === 'up-to-date' ? 'Up to date' :
      update?.status === 'error' ? 'Check failed' :
      update?.status === 'manual' ? 'Manual' : '-';
    const row = (key: string, value: string, tone = '') =>
      h('div', { class: 'mini-row' }, h('span', { class: 'k' }, key), h('span', { class: `v${tone ? ' ' + tone : ''}` }, value));
    return h('div', { class: 'mini-card' },
      h('div', { class: 'mini-head' },
        h('img', { class: 'core-logo', src: 'assets/logo.png', alt: '' }),
        h('div', { class: 'section-title' }, 'SNOWBALL')),
      row('Client', inst.snowball.supported ? inst.snowball.version ?? 'Bundled' : 'Not on this version', inst.snowball.supported ? '' : 'muted'),
      row('Launcher', ui.version ? `v${ui.version}` : '-'),
      row('Updates', updateText, update?.status === 'error' ? 'danger-text' : ''),
      update?.status === 'ready'
        ? h('button', { class: 'btn small primary', style: 'width:100%;margin-top:12px', onClick: () => void api.installUpdate() }, 'Restart to finish')
        : null);
  }

  /** The four things people actually reach for, as a list rather than loose buttons. */
  function quickActions(inst: Snowball.Instance): HTMLElement {
    const action = (name: string, label: string, onClick: () => void, disabled = false) =>
      h('button', { class: 'mini-action', disabled, onClick }, icon(name), label);
    return h('div', { class: 'mini-card' },
      h('div', { class: 'section-title' }, 'QUICK ACTIONS'),
      h('div', { class: 'mini-actions' },
        action('folder', 'Open game folder', () => void guard(() => api.openInstanceFolder(inst.id, 'root'))),
        action('mods', 'Manage mods', () => { ui.view = 'mods'; render(); }),
        action('admin', 'Check mods for malware', () => scanDialog(inst.id), inst.loader === 'vanilla'),
        action('refresh', 'Check for updates', () => void guard(() => api.checkForUpdates()))));
  }

  /**
   * How many people are on Snowball, counted by the Snowball server itself. It is hidden when this
   * build has no server set, and when the server cannot be reached, rather than showing a zero.
   */
  function playerCount(): HTMLElement | null {
    const stats = ui.stats;
    if (!stats || (!stats.online && !stats.week && !stats.total)) return null;
    const number = (value: number, label: string) => h('div', { class: 'count-item grow' },
      h('div', { class: 'n' }, value.toLocaleString()), h('div', { class: 'l' }, label));
    return h('div', { class: 'mini-card count-card' },
      h('div', { class: 'section-title' }, 'SNOWBALL PLAYERS'),
      h('div', { class: 'count-now' },
        h('div', { class: 'count-dot' }),
        h('div', { class: 'count-value' }, stats.online.toLocaleString())),
      h('div', { class: 'count-label' }, stats.online === 1 ? 'playing right now' : 'playing right now'),
      h('div', { class: 'count-split' },
        number(stats.today, 'Today'),
        number(stats.week, 'This week'),
        number(stats.total, 'All time')));
  }

  function refreshStats(): void {
    void api.snowballStats().then((stats) => {
      if (!stats) return;
      const changed = JSON.stringify(stats) !== JSON.stringify(ui.stats);
      // The banner shows on every page, so a new announcement has to repaint wherever you are.
      // The counts only show on Home, and repainting elsewhere would take focus out of whatever
      // the person was typing in.
      const announcementChanged = (stats.announcement ?? null) !== (ui.stats?.announcement ?? null);
      ui.stats = stats;
      if (announcementChanged || (changed && ui.view === 'home')) render();
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
        coreEl.replaceChildren(h('div', { class: 'issue warning' }, clientUnavailable(inst.loader, inst.minecraftVersion, 'This instance works without it.')));
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
    if (!canAdminister()) {
      return h('div', { class: 'stack' },
        header('ADMIN', 'Snowball staff tools'),
        h('div', { class: 'card empty' },
          h('h2', { class: 'page-title' }, state.status === 'online' ? 'Not your rank' : 'Join chat first'),
          h('p', { class: 'muted' }, state.status === 'online'
            ? 'Your account does not hold a rank that can administer Snowball.'
            : 'Join chat so the Snowball server can confirm which account you are, then come back.'),
          state.status === 'online' ? null : h('button', { class: 'btn primary', onClick: () => void guard(() => api.joinChat()) }, 'Join chat')));
    }
    const rank = rankInfo(state.rank);
    return h('div', { class: 'stack' },
      header('ADMIN', `Signed in as ${rank.name}  \u00b7  ${state.online} online`),
      adminCard());
  }

  /** Only an account the server trusts sees this, and the server checks that again for every action. */
  function adminCard(): HTMLElement {
    const allowed = adminTabs();
    // A rank that cannot use the tab it last had open lands on the first one it can.
    if (!allowed.some(([id]) => id === ui.adminTab)) ui.adminTab = allowed[0][0];

    const open = (id: typeof ui.adminTab) => {
      ui.adminTab = id;
      if (id === 'people') void api.chatAdmin('people');
      if (id === 'bugs') void api.chatAdmin('bugs');
      render();
      // Focus follows the tab, so the keyboard does not jump back to the top of the page.
      setTimeout(() => document.querySelector<HTMLElement>(`#admin-tab-${id}`)?.focus(), 0);
    };

    /**
     * A real tablist. Left/Right move between tabs, Home/End jump to the ends, and
     * aria-selected tells a screen reader which one is showing - none of which plain buttons do.
     */
    const tab = ([id, label]: [typeof ui.adminTab, string, string], index: number) => {
      const current = ui.adminTab === id;
      const button = h('button', {
        id: `admin-tab-${id}`,
        class: `admin-tab${current ? ' active' : ''}`,
        role: 'tab',
        'aria-selected': String(current),
        'aria-controls': 'admin-panel',
        tabindex: current ? '0' : '-1',
        onClick: () => open(id),
      }, label);
      button.addEventListener('keydown', (e: KeyboardEvent) => {
        const last = allowed.length - 1;
        const to =
          e.key === 'ArrowRight' ? (index === last ? 0 : index + 1)
          : e.key === 'ArrowLeft' ? (index === 0 ? last : index - 1)
          : e.key === 'Home' ? 0
          : e.key === 'End' ? last
          : -1;
        if (to < 0) return;
        e.preventDefault();
        open(allowed[to][0]);
      });
      return button;
    };

    const tabStrip = h('div', { class: 'admin-tabs', role: 'tablist', 'aria-label': 'Admin sections' }, ...allowed.map(tab));

    if (ui.adminTab !== 'people') repaintPeople = null;
    const body =
      ui.adminTab === 'ranks' ? rankManager()
      : ui.adminTab === 'people' ? peopleList()
      : ui.adminTab === 'bugs' ? bugList()
      : ui.adminTab === 'flags' ? flagList()
      : chatTools();

    // The tabs sit above the panel they switch, at a size that reads as navigation, and each panel
    // lays its blocks out in columns on a wide window instead of stretching one across all of it.
    return h('div', { class: 'admin' },
      tabStrip,
      h('div', { id: 'admin-panel', class: 'admin-panel', role: 'tabpanel', 'aria-labelledby': `admin-tab-${ui.adminTab}` }, body));
  }

  /** A labelled input with its buttons beside it, rather than stranded on the far side of the page. */
  let fieldIds = 0;
  function inlineField(label: string, control: HTMLElement, actions: Child[], hint?: string): HTMLElement {
    const id = control.id || `admin-field-${++fieldIds}`;
    control.id = id;
    return h('div', { class: 'admin-field' },
      h('label', { class: 'admin-field-label', for: id }, label),
      h('div', { class: 'admin-inline' }, control, ...actions),
      hint ? h('span', { class: 'admin-field-hint' }, hint) : null);
  }

  /** Announcements, muting and clearing chat. */
  function chatTools(): HTMLElement {
    const line = statusLine();
    const run = async (fn: () => Promise<{ ok: boolean; reason?: string }>, done: string) => {
      const result = await guard(fn);
      if (!result) return;
      if (result.ok) say(line, done);
      else say(line, result.reason ?? 'That did not work.', 'bad');
    };

    const announcement = h('input', { class: 'input', maxlength: '300', value: ui.chat?.announcement ?? '', placeholder: 'Nothing is being announced' }) as HTMLInputElement;
    const muteName = h('input', { class: 'input', placeholder: 'Minecraft name or account id' }) as HTMLInputElement;
    const muteMinutes = h('input', { class: 'input admin-minutes', type: 'number', min: '1', max: '1440', value: '10', 'aria-label': 'Minutes' }) as HTMLInputElement;

    /** Accepts a name as well as an id, because nobody reads ids off the people list by hand. */
    const muteTarget = async (): Promise<string | null> => {
      const typed = muteName.value.trim();
      if (/^[a-f0-9-]{32,36}$/i.test(typed)) return typed;
      if (!typed) { say(line, 'Type who to mute first.', 'bad'); return null; }
      const found = ui.people.find((p) => p.name.toLowerCase() === typed.toLowerCase());
      if (found) return found.uuid;
      say(line, `Nobody called ${typed} in the people list. Open People, or paste their account id.`, 'bad');
      return null;
    };

    const post = () => void run(() => api.announce(announcement.value), 'Announcement posted.');
    announcement.addEventListener('keydown', (e: KeyboardEvent) => {
      if (e.key === 'Enter') { e.preventDefault(); post(); }
    });
    const rows: Child[] = [line];
    if (can('chat.announce')) {
      rows.push(h('div', { class: 'admin-block' },
        inlineField('Announcement', announcement, [
          h('button', { class: 'btn small primary', onClick: post }, 'Post'),
          h('button', { class: 'btn small', onClick: () => { announcement.value = ''; void run(() => api.announce(null), 'Announcement cleared.'); } }, 'Take it down'),
        ], 'Shown to everyone in the launcher, whether or not they have joined chat. Enter posts it.')));
    }
    if (can('chat.moderate')) {
      rows.push(h('div', { class: 'admin-block' },
        inlineField('Mute somebody', muteName, [
          h('span', { class: 'admin-for' }, 'for'),
          muteMinutes,
          h('span', { class: 'admin-for' }, 'min'),
        ], 'A Minecraft name from the People tab, or an account id.'),
        h('div', { class: 'row' },
          h('button', { class: 'btn small primary', onClick: async () => { const id = await muteTarget(); if (id) void run(() => api.moderateChat('mute', id, Number(muteMinutes.value)), `Muted for ${muteMinutes.value} minutes.`); } }, 'Mute'),
          h('button', { class: 'btn small', onClick: async () => { const id = await muteTarget(); if (id) void run(() => api.moderateChat('unmute', id), 'Unmuted.'); } }, 'Unmute'))));

      rows.push(h('div', { class: 'admin-block danger-block' },
        h('div', { class: 'row admin-danger-row' },
          h('div', { class: 'grow' },
            h('div', {}, 'Clear the chat for everyone'),
            h('div', { class: 'muted', style: 'font-size:12px' }, 'Wipes every recent message the server keeps. It cannot be undone.')),
          h('button', {
            class: 'btn small danger',
            onClick: () => confirmDialog(
              'Clear the chat?',
              'Every recent message is deleted for everyone, and nobody can get them back. Are you sure?',
              'Clear it',
              async () => { await run(() => api.moderateChat('clear'), 'Chat cleared.'); }),
          }, 'Clear chat'))));
    }

    return h('div', { class: 'admin-body' }, ...rows);
  }
  /**
   * Look a player up by their Minecraft name, see the rank they hold and who gave it, and change
   * it. The server checks the permission again and tells the player at once, so nobody reinstalls.
   */
  function rankManager(): HTMLElement {
    const search = h('input', { class: 'input', maxlength: '16', value: ui.lookup?.name ?? '', autocomplete: 'off', spellcheck: 'false' }) as HTMLInputElement;
    const line = statusLine();
    const findButton = h('button', { class: 'btn small primary' }, 'Find') as HTMLButtonElement;

    const find = async () => {
      const name = search.value.trim();
      if (!name) { say(line, 'Type a Minecraft name first.', 'bad'); return; }
      findButton.disabled = true;
      findButton.textContent = 'Looking...';
      say(line, `Looking up ${name}...`);
      const result = await guard(() => api.lookupPlayer(name));
      findButton.disabled = false;
      findButton.textContent = 'Find';
      if (result && !result.ok) say(line, result.reason ?? 'That did not work.', 'bad');
    };
    findButton.addEventListener('click', () => void find());
    search.addEventListener('keydown', (e: KeyboardEvent) => {
      if (e.key === 'Enter') { e.preventDefault(); void find(); }
    });

    const found = ui.lookup;
    const result: Child[] = [];

    if (found && !found.found) {
      result.push(h('p', { class: 'muted' }, `Mojang has no account called ${found.name}.`));
    } else if (found) {
      const picker = h('select', { class: 'input', style: 'max-width:200px', 'aria-label': `Rank for ${found.name}` },
        ...RANKS.filter((r) => r.id !== 'owner').map((r) => h('option', { value: r.id, selected: r.id === found.rank }, r.name))) as HTMLSelectElement;

      result.push(h('div', { class: 'admin-person' },
        h('div', { class: 'admin-person-head' },
          h('div', { class: 'grow' },
            h('div', { class: 'admin-person-name' }, found.name, ' ', rankChip(found.rank)),
            h('div', { class: 'muted', style: 'font-size:11px;user-select:text' }, found.uuid ?? '')),
          found.seen ? h('div', { class: 'muted', style: 'font-size:11px' }, `Last seen ${fmtWhen(found.seen.last)}`) : null),
        found.given
          ? h('div', { class: 'muted', style: 'font-size:12px' }, `${rankInfo(found.given.rank).name} given by ${found.given.byName} ${fmtWhen(found.given.at)}`)
          : h('div', { class: 'muted', style: 'font-size:12px' }, 'No rank has ever been given to this account.'),
        can('ranks.manage')
          ? h('div', { class: 'row', style: 'margin-top:12px' },
              picker,
              h('button', { class: 'btn small primary', onClick: () => void applyRank(found.uuid!, picker.value, line) }, 'Set rank'),
              found.rank && found.rank !== 'snowball'
                ? h('button', {
                    class: 'btn small danger',
                    onClick: () => confirmDialog(
                      `Take ${found.name}'s rank away?`,
                      `${found.name} goes back to plain Snowball and loses everything that rank allowed. You can give it back at any time.`,
                      'Revoke it',
                      async () => { await applyRank(found.uuid!, 'snowball', line); }),
                  }, 'Revoke')
                : null)
          : h('p', { class: 'muted', style: 'font-size:12px;margin:8px 0 0' }, 'Your rank can look people up, but not change what they hold.')));

      if (found.history?.length) {
        result.push(h('div', { class: 'section-title', style: 'margin-top:16px' }, 'HISTORY'));
        result.push(h('ol', { class: 'admin-history' }, ...found.history.slice(0, 12).map((entry) =>
          h('li', {},
            rankChip(entry.rank),
            h('span', { class: 'muted' }, ` by ${entry.byName}, ${fmtWhen(entry.at)}`)))));
      }
    }

    search.placeholder = 'Minecraft name';
    return h('div', { class: 'admin-body' },
      line,
      h('div', { class: 'admin-block' },
        inlineField('Find a player', search, [findButton], 'Their Minecraft name. Enter searches too.')),
      // Beside the search on a wide window, under it on a narrow one.
      result.length ? h('div', { class: 'admin-block' }, ...result) : null);
  }
  async function applyRank(uuid: string, rank: string, line?: HTMLElement): Promise<void> {
    const result = await guard(() => api.setRank(uuid, rank));
    if (!result) return;
    if (!result.ok) {
      if (line) say(line, result.reason ?? 'That rank was not changed.', 'bad');
      else toast(result.reason ?? 'That rank was not changed.', 'error');
      return;
    }
    const message = `Rank set to ${rankInfo(rank).name}. They see it straight away - nothing to reinstall.`;
    if (line) say(line, message);
    else toast(message);
  }
  /** Everyone the Snowball server has seen, newest first, with a filter for finding one. */
  function peopleList(): HTMLElement {
    const line = statusLine();
    const list = h('div', { class: 'list' });
    const filter = h('input', { class: 'input', placeholder: 'Start typing a name', autocomplete: 'off' }) as HTMLInputElement;
    const count = h('div', { class: 'muted', style: 'font-size:12px', role: 'status', 'aria-live': 'polite' });

    const paint = () => {
      const wanted = filter.value.trim().toLowerCase();
      const people = ui.people.filter((p) => !wanted || p.name.toLowerCase().includes(wanted));
      count.textContent = ui.people.length
        ? `${people.length} of ${ui.people.length} ${ui.people.length === 1 ? 'person' : 'people'}`
        : 'Nobody has joined yet, or the list is still coming.';
      list.replaceChildren(...people.slice(0, 80).map((person) =>
        h('div', { class: 'list-row' },
          h('div', { class: 'grow' },
            h('div', {}, person.name, ' ', person.rank && person.rank !== 'snowball' ? rankChip(person.rank) : null),
            h('div', { class: 'muted', style: 'font-size:11px' }, `last seen ${fmtWhen(person.last)}${person.muted ? ' \u00b7 muted' : ''}`)),
          can('ranks.manage')
            ? h('button', {
                class: 'btn small',
                'aria-label': `${person.rank === 'snowball' ? 'Give Snowball+ to' : 'Remove the rank from'} ${person.name}`,
                onClick: async () => {
                  await applyRank(person.uuid, person.rank === 'snowball' ? 'plus' : 'snowball', line);
                  setTimeout(() => void api.chatAdmin('people'), 400);
                },
              }, person.rank === 'snowball' ? 'Give Snowball+' : 'Remove rank')
            : null,
          can('chat.moderate')
            ? h('button', {
                class: 'btn small',
                'aria-label': `${person.muted ? 'Unmute' : 'Mute'} ${person.name}`,
                onClick: async () => {
                  const r = await guard(() => api.moderateChat(person.muted ? 'unmute' : 'mute', person.uuid, 10));
                  if (r?.ok) say(line, `${person.name} ${person.muted ? 'can talk again' : 'is muted for 10 minutes'}.`);
                  else if (r) say(line, r.reason ?? 'That did not work.', 'bad');
                  setTimeout(() => void api.chatAdmin('people'), 400);
                },
              }, person.muted ? 'Unmute' : 'Mute 10m')
            : null)));
    };
    filter.addEventListener('input', paint);
    paint();
    repaintPeople = () => {
      if (list.isConnected) paint();
      else repaintPeople = null;
    };

    return h('div', { class: 'admin-body admin-wide' },
      h('div', { class: 'admin-block admin-narrow' },
        field('Filter', filter),
        count),
      line,
      h('div', { class: 'admin-people' }, list));
  }
  /**
   * Bug reports. Each one is a disclosure rather than a wall: the title and state are always
   * visible, and the detail, steps and versions open when you ask for them.
   */
  function bugList(): HTMLElement {
    const line = statusLine();
    if (!ui.bugs.length) return h('div', { class: 'admin-body' }, h('p', { class: 'muted' }, 'No bug reports yet.'));
    const states: Snowball.BugReport['status'][] = ['open', 'investigating', 'fixed', 'duplicate', 'invalid'];

    const open = ui.bugs.filter((b) => b.status === 'open').length;
    const summary = h('div', { class: 'muted', style: 'font-size:12px' },
      `${ui.bugs.length} report${ui.bugs.length === 1 ? '' : 's'}, ${open} still open`);

    const rows = ui.bugs.map((bug) => {
      const detail = h('div', { class: 'admin-bug-detail' },
        h('p', { style: 'margin:0 0 8px;white-space:pre-wrap' }, bug.detail),
        bug.steps ? h('div', { class: 'muted', style: 'font-size:12px;white-space:pre-wrap' }, `Steps: ${bug.steps}`) : null,
        h('div', { class: 'muted', style: 'font-size:11px;margin-top:8px' },
          [bug.minecraft && `Minecraft ${bug.minecraft}`, bug.loader, bug.snowball && `Snowball ${bug.snowball}`].filter(Boolean).join(' \u00b7 ')));
      detail.hidden = true;

      const toggle = h('button', {
        class: 'btn small ghost',
        'aria-expanded': 'false',
        'aria-label': `Details of ${bug.title}`,
        onClick: (e: MouseEvent) => {
          detail.hidden = !detail.hidden;
          const b = e.currentTarget as HTMLButtonElement;
          b.setAttribute('aria-expanded', String(!detail.hidden));
          b.textContent = detail.hidden ? 'Details' : 'Hide';
        },
      }, 'Details');

      const status = h('select', {
        class: 'input',
        style: 'max-width:150px',
        'aria-label': `State of ${bug.title}`,
        onChange: async (e: Event) => {
          const value = (e.target as HTMLSelectElement).value;
          const r = await guard(() => api.chatAdmin('bug-status', { id: bug.id, status: value }));
          if (r?.ok) say(line, `"${bug.title}" marked ${value}.`);
          else if (r) say(line, r.reason ?? 'That did not work.', 'bad');
        },
      }, ...states.map((state) => h('option', { value: state, selected: state === bug.status }, state)));

      return h('div', { class: 'admin-bug' },
        h('div', { class: 'row' },
          h('span', { class: `bug-state ${bug.status}` }, bug.status.toUpperCase()),
          h('div', { class: 'grow' },
            h('div', {}, bug.title),
            h('div', { class: 'muted', style: 'font-size:11px' }, `${bug.by} \u00b7 ${fmtWhen(Date.parse(bug.at))}`)),
          toggle,
          can('bugs.triage') ? status : null),
        detail);
    });

    return h('div', { class: 'admin-body admin-wide' }, summary, line, h('div', { class: 'admin-cards admin-cards-wide' }, ...rows));
  }
  /**
   * Feature switches. Each one uses the same toggle as the rest of the launcher, so its state
   * is visible at a glance rather than having to be read off a button label.
   */
  function flagList(): HTMLElement {
    const line = statusLine();
    const known: [string, string][] = [
      ['chat', 'Global chat'],
      ['bug_reports', 'Bug reporting'],
      ['snowball_plus', 'Snowball+ features'],
      ['beta', 'Beta builds'],
    ];
    const flags = ui.chat?.flags ?? {};
    const custom = h('input', { class: 'input', placeholder: 'another_feature_key', autocomplete: 'off' }) as HTMLInputElement;

    const row = (key: string, label: string) => {
      const on = flags[key] !== false;
      return h('div', { class: 'list-row' },
        h('div', { class: 'grow' },
          h('div', {}, label),
          h('div', { class: 'muted', style: 'font-size:11px' }, `${key} \u00b7 ${on ? 'on for everyone' : 'off for everyone'}`)),
        toggle(on, async (value) => {
          const r = await guard(() => api.chatAdmin('flag', { key, value }));
          if (r?.ok) say(line, `${label} turned ${value ? 'on' : 'off'} for everyone.`);
          else if (r) say(line, r.reason ?? 'That did not work.', 'bad');
        }));
    };

    return h('div', { class: 'admin-body admin-wide' },
      line,
      // Cards in columns keep each switch next to its name; a full-width row put them a screen apart.
      h('div', { class: 'admin-cards' },
        ...known.map(([key, label]) => row(key, label)),
        ...Object.keys(flags).filter((key) => !known.some(([k]) => k === key)).map((key) => row(key, key))),
      h('div', { class: 'admin-block admin-narrow' },
        inlineField('Add a switch', custom, [
          h('button', {
            class: 'btn small',
            onClick: async () => {
              const key = custom.value.trim();
              if (!key) { say(line, 'Type a key first.', 'bad'); return; }
              const r = await guard(() => api.chatAdmin('flag', { key, value: true }));
              if (r?.ok) { say(line, `${key} added and turned on.`); custom.value = ''; }
              else if (r) say(line, r.reason ?? 'That did not work.', 'bad');
            },
          }, 'Add'),
        ], 'For a feature that is not listed yet. It starts turned on.')));
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
  /**
   * Colour, density and a picture behind the launcher. Everything here applies the moment it
   * changes: nothing to save, and no restart.
   */
  function appearanceCard(s: Snowball.Settings, update: (patch: Partial<Snowball.Settings>) => Promise<void>): HTMLElement {
    const set = (patch: Partial<Snowball.Settings['appearance']>) => {
      const next = { ...s.appearance, ...patch };
      // Paint first, save second: the change should feel instant even on a slow disk.
      applyAppearance({ ...s, appearance: next });
      void update({ appearance: next });
    };

    const swatches = h('div', { class: 'swatches', role: 'radiogroup', 'aria-label': 'Accent colour' },
      ...ACCENTS.map(([name, hex]) => {
        const chosen = s.appearance.accent.toLowerCase() === hex;
        const dot = h('button', {
          class: `swatch${chosen ? ' chosen' : ''}`,
          role: 'radio',
          'aria-checked': String(chosen),
          title: name,
          'aria-label': name,
          onClick: () => { set({ accent: hex }); render(); },
        });
        dot.style.setProperty('--swatch', hex);
        return dot;
      }));

    const custom = h('input', { type: 'color', class: 'swatch-custom', value: s.appearance.accent, 'aria-label': 'Custom accent colour' }) as HTMLInputElement;
    custom.addEventListener('input', () => set({ accent: custom.value.toLowerCase() }));
    custom.addEventListener('change', () => render());

    const slider = (label: string, value: number, min: number, max: number, unit: string, onChange: (v: number) => void) => {
      const out = h('span', { class: 'slider-value' }, `${value}${unit}`);
      const input = h('input', { type: 'range', min: String(min), max: String(max), value: String(value), 'aria-label': label }) as HTMLInputElement;
      input.addEventListener('input', () => {
        out.textContent = `${input.value}${unit}`;
        onChange(Number(input.value));
      });
      return h('div', { class: 'list-row' },
        h('div', { class: 'grow' }, h('div', {}, label), input),
        out);
    };

    const rows: Child[] = [
      h('div', { class: 'list-row' },
        h('div', { class: 'grow' },
          h('div', {}, 'Accent colour'),
          h('div', { class: 'muted', style: 'font-size:13px' }, 'Used by every highlight, button and badge in the launcher.')),
        swatches,
        custom),
      h('div', { class: 'list-row' },
        h('div', { class: 'grow' },
          h('div', {}, 'Compact spacing'),
          h('div', { class: 'muted', style: 'font-size:13px' }, 'Tightens everything up, so more fits on a small screen.')),
        toggle(s.appearance.density === 'compact', (v) => { set({ density: v ? 'compact' : 'cosy' }); render(); })),
      h('div', { class: 'list-row' },
        h('div', { class: 'grow' },
          h('div', {}, 'Background picture'),
          h('div', { class: 'muted', style: 'font-size:13px' }, ui.background ? 'A picture of your own, behind the launcher.' : 'Choose a PNG, JPG or WEBP to sit behind the launcher.')),
        ui.background
          ? toggle(s.appearance.background.enabled, (v) => { set({ background: { ...s.appearance.background, enabled: v } }); render(); })
          : null,
        h('button', { class: 'btn small', onClick: () => void chooseBackground() }, ui.background ? 'Change' : 'Choose'),
        ui.background
          ? h('button', { class: 'btn small ghost', onClick: () => void removeBackground() }, 'Remove')
          : null),
    ];

    if (ui.background && s.appearance.background.enabled) {
      rows.push(slider('How strong', s.appearance.background.opacity, 0, 100, '%', (v) => set({ background: { ...s.appearance.background, opacity: v } })));
      rows.push(slider('Blur', s.appearance.background.blur, 0, 40, 'px', (v) => set({ background: { ...s.appearance.background, blur: v } })));
    }

    return h('div', { class: 'card' }, h('div', { class: 'list' }, ...rows));
  }

  async function chooseBackground(): Promise<void> {
    const result = await guard(() => api.pickBackground());
    if (!result) return;
    if (result.reason) { toast(result.reason, 'error'); return; }
    if (!result.ok || !result.dataUrl) return;
    ui.background = result.dataUrl;
    const settings = ui.state?.settings;
    if (settings) {
      const appearance = { ...settings.appearance, background: { ...settings.appearance.background, enabled: true } };
      applyAppearance({ ...settings, appearance });
      await guard(() => api.updateSettings({ appearance }));
    }
    render();
  }

  async function removeBackground(): Promise<void> {
    await guard(() => api.clearBackground());
    ui.background = null;
    const settings = ui.state?.settings;
    if (settings) {
      const appearance = { ...settings.appearance, background: { ...settings.appearance.background, enabled: false } };
      applyAppearance({ ...settings, appearance });
      await guard(() => api.updateSettings({ appearance }));
    }
    render();
  }

  function updatesCard(s: Snowball.Settings, update: (patch: Partial<Snowball.Settings>) => Promise<void>): HTMLElement {
    const state = ui.update;
    const line = (): string => {
      if (!state) return 'No update has been checked for yet.';
      switch (state.status) {
        case 'downloading': return `${updateLine(state)} (${state.percent ?? 0}%)`;
        case 'up-to-date': return state.checkedAt ? `You have the newest version. Last checked ${fmtAgo(state.checkedAt)}.` : 'You have the newest version.';
        case 'manual': return state.message ?? 'Updates are handled manually in this build.';
        case 'error': return state.message ?? 'The update could not be checked.';
        default: return `${updateLine(state)}.`;
      }
    };
    const status = h('div', { class: 'muted', style: 'font-size:13px' }, line());
    const action = state?.status === 'ready'
      ? h('button', { class: 'btn small primary', onClick: () => void api.installUpdate() }, 'Restart now')
      : state?.status === 'error'
        ? h('button', { class: 'btn small', onClick: () => updateDialog() }, 'What happened?')
        : h('button', {
            class: 'btn small',
            disabled: state?.status === 'checking' || state?.status === 'downloading',
            onClick: async () => {
              status.textContent = 'Looking for a new version...';
              const r = await guard(() => api.checkForUpdates());
              if (r) { ui.update = r; render(); }
            },
          }, 'Check now');

    return h('div', { class: 'card' },
      h('div', { class: 'section-title' }, 'UPDATES'),
      h('div', { class: 'list' },
        h('div', { class: 'list-row' },
          h('div', { class: 'grow' },
            h('div', {}, 'Update automatically'),
            h('div', { class: 'muted', style: 'font-size:13px' }, 'Snowball updates itself when it starts, so you never run the installer again. It waits if a game is running.')),
          toggle(s.updates.automatic, (v) => void update({ updates: { ...s.updates, automatic: v } }))),
        h('div', { class: 'list-row' },
          h('div', { class: 'grow' }, h('div', {}, 'This launcher'), h('div', { class: 'muted', style: 'font-size:13px' }, ui.version ? `Version ${ui.version}` : 'Reading the version...')),
          h('button', { class: 'btn small ghost', onClick: () => whatsNewDialog(ui.version || undefined) }, "What's new"),
          h('span', { class: 'tag accent' }, ui.version ? `v${ui.version}` : '...')),
        h('div', { class: 'list-row' },
          h('div', { class: 'grow' }, h('div', {}, 'Status'), status),
          action)));
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

  /**
   * Snowball's own mod check. Known releases are recognised by fingerprint and set aside, so what
   * is left on screen is only what was actually read here - and what, if anything, is worth a look.
   */
  function scanDialog(instanceId: string): void {
    const VERDICT_LABELS: Record<Snowball.ScanVerdict, string> = { dangerous: 'Check first', suspicious: 'Worth a look', watch: 'Worth knowing', clean: 'Looks fine', known: 'Known' };
    const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;
    const body = h('div', { class: 'scan' });
    const summary = h('p', { class: 'scan-summary' }, 'Checking every mod in this instance...');
    const content = h('div', { class: 'stack' });
    const note = h('p', { class: 'muted scan-note' });
    body.append(summary, content, note);
    modal('MOD CHECK', body, [{ label: 'Close', kind: 'ghost', onClick: (c) => c() }]);

    const toggle = (label: string, panel: HTMLElement) => {
      const button = h('button', { class: 'btn small', 'aria-expanded': 'false' }, label) as HTMLButtonElement;
      button.addEventListener('click', () => {
        panel.hidden = !panel.hidden;
        button.setAttribute('aria-expanded', String(!panel.hidden));
        button.textContent = panel.hidden ? label : 'Hide';
      });
      return button;
    };

    const readRow = (r: Snowball.ScanResult) => {
      const findings = h('div', { class: 'scan-findings' },
        ...r.findings.map((f) =>
          h('div', { class: 'scan-finding' },
            h('div', { class: 'scan-finding-title' }, f.title),
            h('div', { class: 'muted' }, f.detail),
            h('code', { class: 'scan-where' }, f.where))),
        r.verdict === 'dangerous' || r.verdict === 'suspicious'
          ? h('div', { class: 'scan-advice' }, 'Not sure where this file came from? Turn it off on the Mods page until you are. Mods from Modrinth are recognised automatically, so a well-known mod showing up here usually means it came from somewhere else.')
          : null);
      findings.hidden = true;
      const detail = r.error ? `Could not be read: ${r.error}` : r.findings.length ? plural(r.findings.length, 'thing') + ' to know' : 'Nothing unusual inside';
      return h('div', { class: 'scan-item' },
        h('div', { class: 'list-row scan-row' },
          h('span', { class: `verdict ${r.verdict}` }, VERDICT_LABELS[r.verdict]),
          h('div', { class: 'grow' }, h('div', { class: 'truncate', title: r.fileName }, r.fileName), h('div', { class: 'muted scan-sub' }, detail)),
          r.findings.length ? toggle('Details', findings) : null),
        findings);
    };

    void api.scanMods(instanceId).then(({ results, recognised }) => {
      if (!results.length) {
        summary.textContent = 'This instance has no mods to check.';
        return;
      }
      const known = results.filter((r) => r.verdict === 'known');
      const look = results.filter((r) => r.verdict === 'dangerous' || r.verdict === 'suspicious' || r.verdict === 'watch');
      const fine = results.filter((r) => r.verdict === 'clean');

      const parts = [
        known.length ? `${plural(known.length, 'known mod')}` : '',
        fine.length ? `${fine.length} that look${fine.length === 1 ? 's' : ''} fine` : '',
        look.length ? `${look.length} worth a look` : '',
      ].filter(Boolean);
      summary.textContent = `Checked ${plural(results.length, 'mod')}: ${parts.join(', ')}.${look.length ? '' : ' Nothing needs your attention.'}`;

      const sections: Node[] = [];
      if (look.length) sections.push(h('div', {}, h('div', { class: 'section-title' }, 'WORTH A LOOK'), h('div', { class: 'list' }, ...look.map(readRow))));
      if (fine.length) sections.push(h('div', {}, h('div', { class: 'section-title' }, 'READ ON THIS COMPUTER'), h('div', { class: 'list' }, ...fine.map(readRow))));
      if (known.length) {
        const names = h('div', { class: 'scan-known-list' },
          ...known
            .slice()
            .sort((a, b) => (a.known?.project ?? a.fileName).localeCompare(b.known?.project ?? b.fileName))
            .map((r) => h('div', { class: 'scan-known' },
              h('span', { class: 'truncate' }, r.known?.project ?? r.fileName),
              h('span', { class: 'muted truncate' }, r.known?.version ?? ''))));
        names.hidden = true;
        sections.push(h('div', {},
          h('div', { class: 'section-title' }, 'KNOWN MODS'),
          h('div', { class: 'list-row scan-row' },
            h('span', { class: 'verdict known' }, VERDICT_LABELS.known),
            h('div', { class: 'grow' },
              h('div', {}, `${plural(known.length, 'mod')} exactly as published`),
              h('div', { class: 'muted scan-sub' }, 'Same file, byte for byte, as the release on Modrinth, so there is nothing to check.')),
            toggle('Show', names)),
          names));
      }
      content.replaceChildren(...sections);
      note.textContent = recognised
        ? 'Known mods are recognised by fingerprint: only that was sent to Modrinth. Every other file was read on this computer and nothing was uploaded.'
        : 'Modrinth could not be reached, so no mod could be recognised as a known release this time. Every file was read on this computer instead.';
    }).catch((err) => {
      summary.textContent = 'The check could not finish.';
      content.replaceChildren(h('div', { class: 'muted' }, errorMessage(err)));
    });
  }

  function settingsView(): Node {
    const state = ui.state!;
    const s = state.settings;
    const update = async (patch: Partial<Snowball.Settings>) => {
      if (await guard(() => api.updateSettings(patch))) await refresh();
    };
    const settingRow = (label: string, description: string, ...controls: Child[]) =>
      h('div', { class: 'list-row' }, h('div', { class: 'grow' }, h('div', {}, label), h('div', { class: 'muted', style: 'font-size:13px' }, description)), ...controls);

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
        h('div', { class: 'section-title' }, 'APPEARANCE'),
        appearanceCard(s, update),
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
          settingRow('Logs', 'Everything Snowball did, with account tokens always redacted.',
            h('button', {
              class: 'btn',
              onClick: async (e: MouseEvent) => {
                const button = e.target as HTMLButtonElement;
                const text = await guard(() => api.copyLogs());
                if (text === undefined) return;
                try {
                  await navigator.clipboard.writeText(text);
                  button.textContent = 'Copied';
                  setTimeout(() => (button.textContent = 'Copy'), 1600);
                } catch {
                  toast('Could not copy to the clipboard.', 'error');
                }
              },
            }, 'Copy'),
            h('button', { class: 'btn', onClick: () => void guard(() => api.openLauncherFolder('logs')) }, 'Open folder')),
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
        clientNote.textContent = s.supported
          ? `Snowball Client ${s.version} and ${s.fabricApi} are set up automatically.`
          : clientUnavailable(loader.value as Snowball.LoaderId, version.value, 'The instance works without it.');
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
    const described = describeLaunchFailure(e.message);
    modal('Minecraft could not start', errorPanel({
      title: described.title,
      message: described.message,
      hints: described.hints,
      detail: e.message,
      retryLabel: 'Try again',
      onRetry: () => void launch(e.instanceId),
    }), [{ label: 'Close', kind: 'ghost', onClick: (close) => close() }]);
  });
  // A fault in the renderer used to leave a half-drawn page with no explanation. It now says so,
  // rather than looking like the launcher simply stopped responding.
  window.addEventListener('error', (e) => {
    toast(`Something in the launcher went wrong: ${e.message}\nThe details are in Settings → Logs.`, 'error');
  });
  window.addEventListener('unhandledrejection', (e) => {
    toast(`Something in the launcher went wrong: ${errorMessage(e.reason)}\nThe details are in Settings → Logs.`, 'error');
  });
  api.on('launcher-log', (r: Snowball.LauncherLog) => {
    ui.launcherLogs.push(r);
    if (ui.launcherLogs.length > 500) ui.launcherLogs.shift();
  });
  api.on('state', () => void refresh());
  // Admin moved from the chat page to its own; these used to redraw only 'chat', so the people
  // list, bug reports and lookups arrived and were never shown.
  const whatAdminShows = (s: Snowball.ChatState | null) => JSON.stringify([s?.status, s?.rank, s?.permissions, s?.flags]);
  api.on('chat-state', (state: Snowball.ChatState) => {
    const was = ui.chat;
    ui.chat = state;
    // The admin page has half-typed fields, so it is only redrawn when what it offers changed -
    // not every time somebody joins and the online count moves.
    const adminChanged = ui.view === 'admin' && whatAdminShows(was) !== whatAdminShows(state);
    if (ui.view === 'chat' || adminChanged || (was?.announcement ?? null) !== state.announcement) render();
  });
  api.on('chat-lookup', (result: Snowball.RankLookup) => {
    ui.lookup = result;
    if (ui.view === 'admin' && ui.adminTab === 'ranks') render();
  });
  api.on('chat-rank-set', (result: { uuid: string; rank: string; history: Snowball.RankLookup['history'] }) => {
    if (ui.lookup?.uuid === result.uuid) {
      ui.lookup = { ...ui.lookup, rank: result.rank, history: result.history };
      if (ui.view === 'admin' && ui.adminTab === 'ranks') render();
    }
  });
  api.on('chat-people', (people: Snowball.SnowballPerson[]) => {
    ui.people = people;
    if (ui.view !== 'admin' || ui.adminTab !== 'people') return;
    // Repainted in place, so a name typed into the filter survives a refresh.
    if (repaintPeople) repaintPeople();
    else render();
  });
  api.on('chat-bugs', (bugs: Snowball.BugReport[]) => {
    ui.bugs = bugs;
    if (ui.view === 'admin' && ui.adminTab === 'bugs') render();
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
    // Once the handover starts the window is about to go away; block the UI so nothing is
    // half-clicked on the way out.
    if (state.status === 'installing') installingOverlay();
    render();
  });
  refreshStats();
  setInterval(refreshStats, 60_000);
  void api.chatState().then((state) => {
    ui.chat = state;
    if (state.configured) render();
  }).catch(() => {});
  void api.getBackground().then((dataUrl) => {
    ui.background = dataUrl;
    if (ui.state) applyAppearance(ui.state.settings);
  }).catch(() => undefined);
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
