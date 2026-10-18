// Interactive copy of the in-game radial menu. Module names, descriptions, order and defaults
// mirror the client's ModuleRegistry; geometry mirrors WheelGeometry (design units).
(function () {
  'use strict';
  const root = document.getElementById('demo');
  if (!root) return;

  const NS = 'http://www.w3.org/2000/svg';
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // Line icons on a 24-unit grid, in the client's category order (the same set the page uses).
  const ICONS = [
    ['M3 6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z', 'M9 20h6M12 16v4'],
    ['M13 2 4.5 13.5H11L10 22l8.5-11.5H12z'],
    ['M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z', 'M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z'],
    ['M12 3 19.5 6v5.5c0 4.6-3.2 7.9-7.5 9.5-4.3-1.6-7.5-4.9-7.5-9.5V6z', 'm8.8 12 2.3 2.3 4.2-4.6'],
    ['m5 6 6 6-6 6M13 6l6 6-6 6'],
    ['M4 20 15 9M13 7l4 4M18 3v3M16.5 4.5h3M20.5 9.5v2M19.5 10.5h2'],
    ['M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z', 'M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18'],
    ['M4 7h9M17 7h3M4 12h3M11 12h9M4 17h11M19 17h1', 'M17 7a2 2 0 1 1-4 0 2 2 0 0 1 4 0zM11 12a2 2 0 1 1-4 0 2 2 0 0 1 4 0zM19 17a2 2 0 1 1-4 0 2 2 0 0 1 4 0z'],
    ['M12 3c.7 5 2.5 7.3 8 9-5.5 1.7-7.3 4-8 9-.7-5-2.5-7.3-8-9 5.5-1.7 7.3-4 8-9z'],
    ['M4 4h16a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z', 'M5 9v10h14V9M10 13h4'],
  ];

  // Categories and rows as ModuleCategory and ModuleRegistry have them in Snowball Client 1.11.0.
  const CATS = [
    { name: 'CLIENT', rows: ['client_hud', 'presets', 'hud_layout', 'interface', 'cosmetics', 'skins', 'menu_3d', 'watermark', 'custom_text', 'fps_counter', 'ping_display', 'coordinates', 'direction_hud', 'memory_usage', 'pack_display', 'anti_crash'] },
    { name: 'FPS BOOST', cards: true, rows: ['fps_boost', 'smart_culling', 'advanced', 'mod_sodium', 'mod_lithium', 'mod_ferritecore', 'mod_entityculling', 'mod_immediatelyfast', 'animation_optimization', 'inactive_fps', 'particle_limiter', 'performance_advisor'] },
    { name: 'RENDER', rows: ['fps_boost', 'smart_culling', 'fov_settings', 'weather_effects', 'potion_hud', 'no_hurt_shake', 'low_fire', 'block_outline', 'time_changer', 'zoom', 'freelook', 'third_person'] },
    { name: 'PVP', rows: ['crosshair', 'armor_hud', 'cps_counter', 'combo_counter', 'reach_display', 'target_info', 'crystal_glow'] },
    { name: 'MOVE', rows: ['speed_display', 'toggle_sprint'] },
    { name: 'VISUALS', rows: ['menu_3d', 'fullbright', 'glass_gui', 'hit_color', 'crystal_glow', 'zoom', 'freelook', 'snaplook'] },
    { name: 'WORLD', rows: ['render_distance', 'entity_distance', 'waypoints'] },
    { name: 'MISC', rows: ['screenshot_utility', 'clock', 'server_info', 'notifications', 'chat_settings', 'session_timer', 'nick_hider', 'discord', 'spotify', 'emotes'] },
    { name: 'QOL', rows: ['keystrokes', 'ez_pots', 'nether_travel', 'third_person', 'coordinates'] },
    { name: 'STORAGE', rows: ['screenshots', 'config_profiles', 'mod_profiles', 'resource_packs', 'shader_packs', 'container_preview', 'container_search', 'item_counter', 'map_art_viewer'] },
  ];
  const N = CATS.length;
  const STEP = 360 / N;
  const midOf = (i) => i * STEP - STEP / 2;

  // [name, description, kind, enabled, has settings, status, advanced]
  // kind: t = toggle, a = opens a screen, x = the Advanced row
  const MODULES = {
    client_hud: ['Client HUD', 'Show or hide all HUD elements', 't', true, false],
    crosshair: ['Custom Crosshair', 'Your own crosshair style', 't', false, true],
    fps_counter: ['FPS Counter', 'Shows frames per second', 't', true, true],
    ping_display: ['Ping Counter', 'Shows your ping', 't', false, true],
    coordinates: ['Coordinates', 'Shows your position', 't', false, true],
    direction_hud: ['Direction HUD', 'Compass for where you face', 't', true, true],
    speed_display: ['Speed', 'Shows how fast you move', 't', false, true],
    combo_counter: ['Combo Counter', 'Hits in a row without damage', 't', false, true],
    reach_display: ['Reach Display', 'Distance of your last hit', 't', false, true],
    memory_usage: ['Memory Usage', 'Shows game memory use', 't', false, true],
    pack_display: ['Pack Display', 'Shows your resource pack', 't', false, true],
    armor_hud: ['Armor HUD', 'Shows armour and durability', 't', false, true],
    interface: ['Menu & Accessibility', 'Colours, size, contrast, motion', 'a'],
    presets: ['Presets', 'Ready-made setups you can apply in one click', 'a'],
    hud_layout: ['HUD Layout', 'Move, resize and turn your HUD', 'a'],
    cosmetics: ['Cosmetics', 'Capes other Snowball players see', 'a'],
    skins: ['Skins', 'Change your Minecraft skin', 'a'],
    menu_3d: ['3D Menus', 'Menus stand in the world as windows', 't', false, true],
    watermark: ['Watermark', 'The Snowball mark in the corner', 't', false, true],
    custom_text: ['Custom Text', 'Your own words on screen', 't', false, true],
    target_info: ['Target Info', 'Who you are looking at, and their health', 't', false, true],
    smart_culling: ['Smart Culling', 'Skip drawing what walls hide: more FPS', 't', true, true],
    glass_gui: ['Glass GUI', "See through Minecraft's menus", 't', false, true],
    discord: ['Discord presence', 'Tell Discord you are on Snowball Client', 't', false, true],
    spotify: ['Spotify', 'The song Spotify is playing', 't', false, true],
    emotes: ['Emotes', 'Wheel of emotes others can see', 't', true, true],
    ez_pots: ['EZ Pots', 'Hear when a potion is about to run out', 't', false, true],
    nether_travel: ['Nether Travel', 'Overworld and Nether coordinates', 'a'],
    fps_boost: ['Boost', 'More FPS with one switch', 't', false, true],
    advanced: ['Advanced', 'Optimisation mods, fine-tuning', 'x'],
    mod_sodium: ['Faster Rendering', 'Sodium, after restart', 't', false, false, 'MISSING', true],
    mod_lithium: ['Faster Game Logic', 'Lithium, after restart', 't', false, false, 'MISSING', true],
    mod_ferritecore: ['Lower Memory Use', 'FerriteCore, after restart', 't', false, false, 'MISSING', true],
    mod_entityculling: ['Skip Hidden Mobs', 'Entity Culling, after restart', 't', false, false, 'MISSING', true],
    mod_immediatelyfast: ['Faster HUD', 'ImmediatelyFast, after restart', 't', false, false, 'MISSING', true],
    animation_optimization: ['Animation Optimization', 'Slower animated textures', 't', false, true, null, true],
    inactive_fps: ['Background FPS Limit', 'Lower FPS when tabbed out', 't', true, true, null, true],
    particle_limiter: ['Particle Limiter', 'Fewer particles, smoother', 't', false, true, null, true],
    performance_advisor: ['Performance Advisor', 'Warns about mod conflicts', 't', true, true, null, true],
    fullbright: ['Fullbright', 'See clearly in the dark', 't', false, true],
    zoom: ['Zoom', 'Hold a key to zoom in', 't', true, true],
    freelook: ['Freelook', 'Look around without turning', 't', false, true],
    third_person: ['Third-Person Camera', 'Set third-person distance', 't', false, true],
    time_changer: ['Time Changer', 'Pick the time of day you see', 't', false, true],
    hit_color: ['Hit Colour', 'Colour of the hit flash', 't', false, true],
    crystal_glow: ['Crystal Glow', 'End crystals in your colour, glowing', 't', false, true],
    block_outline: ['Block Outline', 'Colour of the block outline', 't', false, true],
    no_hurt_shake: ['No Hurt Shake', 'No camera shake when hurt', 't', false, false],
    low_fire: ['Low Fire', 'Lower the fire overlay', 't', false, true],
    fov_settings: ['FOV Settings', 'Field of view options', 't', false, true],
    render_distance: ['Render Distance', 'Change view distance fast', 't', false, true],
    entity_distance: ['Entity Distance', 'Hide far-away entities', 't', false, true],
    weather_effects: ['Weather Effects', 'Hide rain and snow', 't', false, false],
    potion_hud: ['Potion HUD', 'Shows effects and timers', 't', false, true],
    chat_settings: ['Chat Upgrades', 'Stack spam, mentions, copy', 't', true, true],
    nick_hider: ['Nick Hider', 'Swap your name, on your screen only', 't', false, true],
    screenshot_utility: ['Screenshot Utility', 'Actions after a screenshot', 't', true, true],
    notifications: ['Notifications', 'Pop-up cards for events', 't', true, true],
    clock: ['Time Display', 'Shows the real time', 't', false, true],
    server_info: ['Server Information', 'Server address and players', 't', false, true],
    session_timer: ['Session Timer', 'Time spent in this world', 't', false, true],
    keystrokes: ['Keystrokes', 'Shows your keys and clicks', 't', true, true],
    toggle_sprint: ['Toggle Sprint', 'Sprint without holding', 't', false, true],
    snaplook: ['Snaplook', 'Hold to see yourself', 't', false, true],
    waypoints: ['Waypoints', 'Mark places on screen', 't', true, true],
    cps_counter: ['CPS Counter', 'Shows clicks per second', 't', false, true],
    screenshots: ['Screenshots', 'Browse your screenshots', 'a'],
    config_profiles: ['Config Profiles', 'Save and load setups', 'a'],
    mod_profiles: ['Mod Profiles', 'Turn mod groups on or off', 'a'],
    resource_packs: ['Resource Packs', 'Open resource packs', 'a'],
    shader_packs: ['Shader Packs', 'Open shader settings', 'a', false, false, 'NO IRIS'],
    container_preview: ['Container Preview', 'See inside shulker boxes', 't', true, true],
    container_search: ['Container Search', 'Find items in chests', 't', true, true],
    item_counter: ['Item Counter', 'Count the item you hold', 't', false, true],
    map_art_viewer: ['Map Art Viewer', "Show a map's picture in its slot", 't', true, false],
    anti_crash: ['Anti-Crash', 'Keep the game open after most mod errors', 't', true, false],
  };
  const CARDS = [['max_fps', 'MAX FPS', 'Most FPS'], ['balanced', 'BALANCED', 'Best of both'], ['quality', 'QUALITY', 'Best visuals'], ['off', 'OFF', 'Your settings']];
  const BOOST_FPS = { off: 118, max_fps: 236, balanced: 184, quality: 146 };
  const MAX_QUERY = 24;

  const on = {};
  for (const id in MODULES) on[id] = !!MODULES[id][3];
  let preset = 'balanced';
  let current = 0;
  let query = '';
  let expanded = false;
  let angle = midOf(0);
  let shown = midOf(0);
  let animating = false;

  // ---------- helpers ----------
  const make = (tag, attrs, parent) => {
    const n = document.createElementNS(NS, tag);
    for (const k in attrs) n.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(n);
    return n;
  };
  const el = (tag, cls, text) => {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text) n.textContent = text;
    return n;
  };
  const f = (n) => n.toFixed(2);
  const rad = (d) => (d * Math.PI) / 180;
  const pt = (r, a) => f(r * Math.sin(a)) + ',' + f(-r * Math.cos(a));
  const sector = (r0, r1, a0, a1, gap) => {
    const d0 = Math.asin(gap / 2 / r0);
    const d1 = Math.asin(gap / 2 / r1);
    return `M${pt(r1, a0 + d1)}A${r1},${r1} 0 0 1 ${pt(r1, a1 - d1)}L${pt(r0, a1 - d0)}A${r0},${r0} 0 0 0 ${pt(r0, a0 + d0)}Z`;
  };
  const arc = (r, a0, a1) => `M${pt(r, a0)}A${r},${r} 0 0 1 ${pt(r, a1)}`;
  const lineIcon = (i, parent) => {
    for (const d of ICONS[i]) make('path', { d }, parent);
  };
  const wrapDeg = (d) => ((((d + 180) % 360) + 360) % 360) - 180;
  const categoryOf = (id) => CATS.find((c) => c.rows.includes(id)).name;

  // ---------- wheel ----------
  const svg = root.querySelector('.wheel');
  const defs = make('defs', {}, svg);
  const selGrad = make('radialGradient', { id: 'wSel', cx: 0, cy: 0, r: 89, gradientUnits: 'userSpaceOnUse' }, defs);
  make('stop', { offset: '0.49', 'stop-color': '#3d7aa8' }, selGrad);
  make('stop', { offset: '1', 'stop-color': '#a3d8fb' }, selGrad);
  const hubGrad = make('radialGradient', { id: 'wHub', cx: 0, cy: -10, r: 46, gradientUnits: 'userSpaceOnUse' }, defs);
  make('stop', { offset: '0', 'stop-color': '#263b4d' }, hubGrad);
  make('stop', { offset: '1', 'stop-color': '#0a1016' }, hubGrad);
  const glow = make('filter', { id: 'wGlow', x: '-50%', y: '-50%', width: '200%', height: '200%' }, defs);
  make('feGaussianBlur', { stdDeviation: 3.2 }, glow);

  for (const r of [106, 112, 118]) make('circle', { r, class: 'w-ring' }, svg);
  make('circle', { r: 100, class: 'w-rim' }, svg);
  make('circle', { r: 93, class: 'w-base' }, svg);

  const segs = CATS.map((c, i) => {
    const mid = midOf(i);
    const p = make('path', { d: sector(44, 89, rad(mid - STEP / 2), rad(mid + STEP / 2), 2.5), class: 'w-seg', tabindex: 0, role: 'button', 'aria-label': c.name + ' category' }, svg);
    p.addEventListener('click', () => select(i));
    p.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        select(i);
      }
    });
    return p;
  });
  for (let k = 0; k < N; k++) {
    const a = rad(k * STEP);
    make('circle', { cx: f(96.5 * Math.sin(a)), cy: f(-96.5 * Math.cos(a)), r: 0.9, class: 'w-dot' }, svg);
  }

  const half = STEP / 2;
  const selG = make('g', { class: 'w-sel' }, svg);
  make('path', { d: arc(101, rad(-half + 1.5), rad(half - 1.5)), class: 'w-sel-glow', filter: 'url(#wGlow)' }, selG);
  make('path', { d: arc(96.5, rad(-half + 0.8), rad(half - 0.8)), class: 'w-sel-rim' }, selG);
  make('path', { d: sector(44, 89, rad(-half), rad(half), 2.5), class: 'w-sel-fill' }, selG);
  make('path', { d: arc(88.4, rad(-half + 1), rad(half - 1)), class: 'w-sel-edge' }, selG);
  selG.setAttribute('transform', `rotate(${angle})`);

  make('circle', { r: 44, class: 'w-hubring' }, svg);
  make('circle', { r: 39, class: 'w-hub' }, svg);
  make('image', { href: 'assets/logo.png', x: -14, y: -21, width: 28, height: 28, class: 'w-logo' }, svg);
  const hubText = make('text', { x: 0, y: 19, class: 'w-hubtext' }, svg);

  // Like the client, a segment shows only its icon; the chosen one adds its name underneath.
  const labels = CATS.map((c, i) => {
    const a = rad(midOf(i));
    const g = make('g', { class: c.name.includes(' ') ? 'w-label two' : 'w-label', transform: `translate(${f(67 * Math.sin(a))} ${f(-67 * Math.cos(a))})` }, svg);
    const lift = make('g', { class: 'w-lift' }, g);
    lineIcon(i, make('g', { class: 'w-ic', transform: 'translate(-5.5 -5.5) scale(0.46)' }, lift));
    const text = make('text', { x: 0, y: 0 }, g);
    const words = c.name.split(' ');
    words.forEach((w, k) => {
      make('tspan', { x: 0, y: 3.5 + k * 7.5 - (words.length - 1) * 2 }, text).textContent = w;
    });
    return g;
  });

  // Eases the highlight toward the selected segment, like the client's ~210 ms delta-time animation.
  function animateSel() {
    if (reduce) {
      shown = angle;
      selG.setAttribute('transform', `rotate(${f(shown)})`);
      return;
    }
    if (animating) return;
    animating = true;
    let last = performance.now();
    const step = (now) => {
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      shown += (angle - shown) * (1 - Math.exp(-14 * dt));
      if (Math.abs(angle - shown) < 0.05) shown = angle;
      selG.setAttribute('transform', `rotate(${f(shown)})`);
      if (shown !== angle) requestAnimationFrame(step);
      else animating = false;
    };
    requestAnimationFrame(step);
  }

  // ---------- panel ----------
  const panel = root.querySelector('.panel');
  const tip = root.querySelector('.demo-tip');

  const head = el('div', 'p-head');
  const headIcon = make('svg', { viewBox: '0 0 24 24', class: 'p-icon', 'aria-hidden': 'true' });
  const headTitle = el('span', 'p-title');
  const headCount = el('span', 'p-count');
  head.append(headIcon, headTitle, headCount);

  const search = el('label', 'p-search');
  const glass = make('svg', { viewBox: '0 0 10 10', 'aria-hidden': 'true' });
  make('circle', { cx: 4, cy: 4, r: 3 }, glass);
  make('path', { d: 'M6.3 6.3 9 9' }, glass);
  const input = el('input');
  input.type = 'text';
  input.placeholder = 'Type to search';
  input.maxLength = MAX_QUERY;
  input.setAttribute('aria-label', 'Search modules');
  input.autocomplete = 'off';
  input.spellcheck = false;
  search.append(glass, input);

  const cards = el('div', 'p-cards');
  const cardEls = CARDS.map(([id, title, blurb]) => {
    const b = el('button', 'p-card');
    b.type = 'button';
    b.append(el('b', '', title), el('small', '', blurb));
    b.addEventListener('click', () => chooseCard(id));
    b.addEventListener('mouseenter', () => setTip(title, id === 'off' ? 'Boost off: your own video settings' : blurb + ' - Boost picks the right mods and settings'));
    cards.appendChild(b);
    return [id, b];
  });

  const list = el('div', 'p-rows');
  list.setAttribute('role', 'list');
  panel.append(head, search, cards, list);

  function setTip(label, text) {
    tip.textContent = '';
    tip.append(el('b', '', label), text);
  }
  const defaultTip = () => setTip('TIP', 'Hover a module to see what it does, or type to search');

  const searching = () => query.trim().length > 0;

  function visibleRows() {
    if (searching()) {
      const words = query.trim().toLowerCase().split(/\s+/);
      const seen = new Set();
      const nameHits = [];
      const otherHits = [];
      for (const cat of CATS) {
        for (const id of cat.rows) {
          if (seen.has(id) || MODULES[id][2] === 'x') continue;
          seen.add(id);
          const name = MODULES[id][0].toLowerCase();
          const hay = `${name} ${MODULES[id][1].toLowerCase()} ${categoryOf(id).toLowerCase()}`;
          if (!words.every((w) => hay.includes(w))) continue;
          (words.every((w) => name.includes(w)) ? nameHits : otherHits).push(id);
        }
      }
      return nameHits.concat(otherHits);
    }
    return CATS[current].rows.filter((id) => expanded || !MODULES[id][6]);
  }

  function statusOf(id) {
    if (id === 'fps_boost') return on.fps_boost ? CARDS.find((c) => c[0] === preset)[1] : null;
    if (id === 'advanced') return expanded ? 'HIDE' : 'SHOW';
    return MODULES[id][5] || null;
  }

  function syncRow(b) {
    const id = b.dataset.id;
    if (MODULES[id][2] !== 't') return;
    b.classList.toggle('on', on[id]);
    b.setAttribute('aria-checked', String(on[id]));
    const status = b.querySelector('.p-status');
    const text = statusOf(id);
    status.textContent = text || '';
    status.hidden = !text;
  }

  function rowEl(id) {
    const m = MODULES[id];
    const b = el('button', 'p-row');
    b.type = 'button';
    b.dataset.id = id;
    const text = el('span', 'p-text');
    const line = el('span', 'p-line');
    const status = el('span', 'p-status');
    line.append(el('span', 'p-name', m[0]), status);
    text.append(line, el('span', 'p-desc', searching() ? `${categoryOf(id)} - ${m[1]}` : m[1]));
    b.append(text);
    if (m[2] === 'x') {
      b.classList.toggle('expanded', expanded);
      b.append(el('span', 'p-tri'));
    } else if (m[2] === 't') {
      if (m[4]) b.append(el('span', 'p-dots'));
      b.setAttribute('role', 'switch');
      b.append(el('span', 'p-switch'));
      if (m[5] === 'MISSING') b.classList.add('locked');
    } else {
      b.append(el('span', 'p-chev'));
    }
    const statusText = statusOf(id);
    status.textContent = statusText || '';
    status.hidden = !statusText;
    syncRow(b);
    b.addEventListener('click', (e) => activate(id, b, !!e.target.closest('.p-dots')));
    b.addEventListener('mouseenter', () => setTip(m[0], m[1]));
    b.addEventListener('focus', () => setTip(m[0], m[1]));
    return b;
  }

  function updateHead() {
    headIcon.textContent = '';
    if (searching()) {
      headTitle.textContent = 'SEARCH';
      make('circle', { cx: 10.5, cy: 10.5, r: 6.5 }, headIcon);
      make('path', { d: 'm15.5 15.5 5 5' }, headIcon);
      const n = visibleRows().length;
      headCount.textContent = n + (n === 1 ? ' result' : ' results');
      headCount.classList.remove('accent');
    } else {
      headTitle.textContent = CATS[current].name;
      lineIcon(current, headIcon);
      if (CATS[current].cards) {
        headCount.textContent = fps() + ' FPS';
        headCount.classList.add('accent');
      } else {
        const toggles = visibleRows().filter((id) => MODULES[id][2] === 't');
        headCount.textContent = toggles.length ? `${toggles.filter((id) => on[id]).length} / ${toggles.length}` : '';
        headCount.classList.remove('accent');
      }
    }
  }

  function updateCards() {
    cards.hidden = searching() || !CATS[current].cards;
    const chosen = on.fps_boost ? preset : 'off';
    for (const [id, b] of cardEls) {
      b.classList.toggle('sel', id === chosen);
      b.setAttribute('aria-pressed', String(id === chosen));
    }
  }

  function renderRows(animate) {
    const rows = visibleRows();
    list.textContent = '';
    list.classList.remove('swap');
    if (animate && !reduce) {
      void list.offsetWidth;
      list.classList.add('swap');
    }
    if (!rows.length) list.append(el('p', 'p-empty', `Nothing matches "${query.trim()}"`));
    else rows.forEach((id) => list.appendChild(rowEl(id)));
    list.scrollTop = 0;
    updateHead();
    updateCards();
  }

  function select(i) {
    const target = midOf(i);
    angle += ((((target - angle) % 360) + 540) % 360) - 180;
    animateSel();
    const changed = i !== current || searching() || !list.firstChild;
    current = i;
    if (searching()) {
      query = '';
      input.value = '';
    }
    labels.forEach((l, k) => l.classList.toggle('sel', k === i));
    segs.forEach((s, k) => s.setAttribute('aria-pressed', String(k === i)));
    hubText.textContent = CATS[i].name;
    if (changed) renderRows(true);
  }

  function refreshBoostRows() {
    list.querySelectorAll('.p-row[data-id="fps_boost"]').forEach(syncRow);
    updateCards();
    updateHead();
  }

  function chooseCard(id) {
    if (id === 'off') {
      on.fps_boost = false;
      notify('Boost', 'Off - your own settings');
    } else {
      preset = id;
      on.fps_boost = true;
      notify('Boost', CARDS.find((c) => c[0] === id)[1]);
    }
    refreshBoostRows();
    slowTick();
  }

  function activate(id, b, settings) {
    const m = MODULES[id];
    if (m[2] === 'x') {
      expanded = !expanded;
      renderRows(false);
      const row = list.querySelector('.p-row[data-id="advanced"]');
      if (row) row.focus();
      return;
    }
    if (settings) {
      setTip(m[0], 'In game this opens its settings');
      return;
    }
    if (m[2] !== 't') {
      setTip(m[0], 'In game this opens ' + m[0]);
      return;
    }
    if (m[5] === 'MISSING') {
      setTip(m[0], 'Turn on Boost to install it');
      notify(m[0], 'Turn on Boost to install it');
      return;
    }
    on[id] = !on[id];
    syncRow(b);
    updateHead();
    applyEffects();
    notify(m[0], on[id] ? 'Enabled' : 'Disabled');
    if (id === 'fps_boost') {
      refreshBoostRows();
      slowTick();
    }
    if (id === 'zoom' && on[id]) setTip(m[0], 'Hold C to try it');
    else if (id === 'direction_hud' && on[id]) setTip(m[0], 'Move your mouse to turn');
    else if ((id === 'combo_counter' || id === 'reach_display') && on[id]) setTip(m[0], 'Click the world to land hits');
  }

  // ---------- HUD preview ----------
  const hudEls = Array.from(root.querySelectorAll('[data-hud]'));
  const val = (name) => root.querySelector(`[data-v="${name}"]`);
  const keyEl = (name) => root.querySelector(`[data-key="${name}"]`);
  const toast = root.querySelector('.hud-toast');
  let toastTimer = 0;

  // Combo and reach: clicking the world (not the menu) lands a "hit".
  let combo = 0;
  let lastHit = 0;
  function combatVisible(id) {
    const age = Date.now() - lastHit;
    return id === 'combo_counter' ? combo > 0 && age < 2500 : lastHit > 0 && age < 3000;
  }

  function applyEffects() {
    hudEls.forEach((node) => {
      const enabled = on.client_hud && on[node.dataset.hud];
      node.hidden = !enabled || (node.dataset.needs === 'combat' && !combatVisible(node.dataset.hud));
    });
    root.classList.toggle('fullbright', on.fullbright);
    if (!on.zoom) root.classList.remove('zooming');
  }

  function notify(title, text) {
    if (!on.notifications) return;
    toast.querySelector('b').textContent = title;
    toast.querySelector('span').textContent = text;
    toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove('show'), 1600);
  }

  // Direction HUD strip: the heading follows the mouse across the scene.
  const strip = root.querySelector('.hud-dir-strip');
  const DIR_LABELS = ['S', 'SW', 'W', 'NW', 'N', 'NE', 'E', 'SE'];
  const marks = [];
  for (let d = 0; d < 360; d += 15) {
    const label = d % 45 === 0 ? DIR_LABELS[d / 45] : null;
    const mark = label ? el('span', label.length === 1 ? 'l' : 'l minor', label) : el('i');
    strip.appendChild(mark);
    marks.push([d, mark]);
  }
  let heading = 90;
  let headingQueued = false;
  function drawHeading() {
    headingQueued = false;
    for (const [d, mark] of marks) {
      const offset = wrapDeg(d - heading) * 1.4;
      mark.hidden = Math.abs(offset) > 68;
      mark.style.left = `calc(50% + var(--u) * ${offset.toFixed(1)})`;
    }
    val('deg').textContent = Math.round(((heading % 360) + 360) % 360) + '°';
  }
  root.addEventListener('mousemove', (e) => {
    const rect = root.getBoundingClientRect();
    heading = 90 + ((e.clientX - rect.left) / rect.width - 0.5) * 140;
    if (!headingQueued) {
      headingQueued = true;
      requestAnimationFrame(drawHeading);
    }
  });

  let visible = false;
  const clicks = { lmb: [], rmb: [] };
  const started = Date.now();
  const pad = (n) => String(n).padStart(2, '0');
  const moving = () => ['w', 'a', 's', 'd'].some((k) => keyEl(k).classList.contains('down'));
  const fps = () => BOOST_FPS[on.fps_boost ? preset : 'off'];

  function tick() {
    if (!visible) return;
    const now = Date.now();
    for (const k in clicks) clicks[k] = clicks[k].filter((t) => now - t < 1000);
    val('lcps').textContent = clicks.lmb.length;
    val('rcps').textContent = clicks.rmb.length;
    val('cps').textContent = clicks.lmb.length + ' | ' + clicks.rmb.length;
    val('speed').textContent = moving() ? (on.toggle_sprint ? 5.61 : 4.32).toFixed(2) : '0.00';
    applyEffects();
  }
  function slowTick() {
    if (!visible) return;
    const frames = fps() + Math.floor(Math.random() * 12) - 6;
    val('fps').textContent = frames;
    if (!searching() && CATS[current].cards) headCount.textContent = frames + ' FPS';
    val('ping').textContent = 18 + Math.floor(Math.random() * 6);
    const d = new Date();
    val('clock').textContent = pad(d.getHours()) + ':' + pad(d.getMinutes());
    const s = Math.floor((Date.now() - started) / 1000);
    val('session').textContent = pad(Math.floor(s / 60)) + ':' + pad(s % 60);
    const used = 1300 + Math.floor(Math.random() * 180);
    val('mem').textContent = `${Math.round((used / 4096) * 100)}%  ${used}/4096 MB`;
  }
  setInterval(tick, 100);
  setInterval(slowTick, 500);

  const KEYS = { KeyW: 'w', KeyA: 'a', KeyS: 's', KeyD: 'd', Space: 'space' };
  const typing = (e) => e.target instanceof HTMLElement && e.target.closest('input, textarea, select, [contenteditable]');

  window.addEventListener('keydown', (e) => {
    if (!visible || typing(e)) return;
    if (KEYS[e.code]) keyEl(KEYS[e.code]).classList.add('down');
    if (e.code === 'KeyC' && on.zoom && root.classList.contains('open')) root.classList.add('zooming');
    if (e.code === 'ShiftRight') {
      root.classList.toggle('open');
      setTip('RIGHT SHIFT', root.classList.contains('open') ? 'Menu opened' : 'Menu closed - press Right Shift to open it again');
    }
  });
  window.addEventListener('keyup', (e) => {
    if (KEYS[e.code]) keyEl(KEYS[e.code]).classList.remove('down');
    if (e.code === 'KeyC') root.classList.remove('zooming');
  });
  window.addEventListener('blur', () => {
    root.querySelectorAll('.hud-key.down').forEach((k) => k.classList.remove('down'));
    root.classList.remove('zooming');
  });

  root.addEventListener('mousedown', (e) => {
    const k = e.button === 0 ? 'lmb' : e.button === 2 ? 'rmb' : null;
    if (!k) return;
    clicks[k].push(Date.now());
    keyEl(k).classList.add('down');
    if (k === 'lmb' && !e.target.closest('.wheel, .panel, .demo-tip')) {
      const now = Date.now();
      combo = now - lastHit > 2500 ? 1 : combo + 1;
      lastHit = now;
      val('combo').textContent = combo;
      val('reach').textContent = (2.55 + Math.random() * 0.5).toFixed(2);
    }
    tick();
  });
  window.addEventListener('mouseup', () => {
    keyEl('lmb').classList.remove('down');
    keyEl('rmb').classList.remove('down');
  });
  root.addEventListener('contextmenu', (e) => e.preventDefault());

  // Search: typing anywhere in the demo goes to the search box, like typing in the in-game menu.
  input.addEventListener('input', () => {
    query = input.value.slice(0, MAX_QUERY);
    renderRows(false);
  });
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      if (input.value) {
        input.value = '';
        query = '';
        renderRows(false);
      } else {
        input.blur();
      }
    } else if (e.key === 'Enter') {
      const first = list.querySelector('.p-row');
      if (first) first.click();
    } else if (e.key === 'ArrowDown') {
      const first = list.querySelector('.p-row');
      if (first) {
        e.preventDefault();
        first.focus();
      }
    }
  });

  root.addEventListener('keydown', (e) => {
    if (typing(e)) return;
    if (e.key.length === 1 && e.key !== ' ' && !e.ctrlKey && !e.metaKey && !e.altKey && root.classList.contains('open')) {
      input.focus();
      return;
    }
    if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
      e.preventDefault();
      const inPanel = panel.contains(document.activeElement);
      select((current + (e.key === 'ArrowRight' ? 1 : N - 1)) % N);
      const first = list.querySelector('.p-row');
      if (inPanel && first) first.focus();
      else segs[current].focus();
    } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      const rows = Array.from(list.querySelectorAll('.p-row'));
      if (!rows.length) return;
      let i = rows.indexOf(document.activeElement);
      i = e.key === 'ArrowDown' ? Math.min(rows.length - 1, i + 1) : Math.max(0, i - 1);
      e.preventDefault();
      rows[i].focus();
    }
  });
  panel.addEventListener('mouseleave', defaultTip);

  // ---------- start ----------
  select(0);
  applyEffects();
  defaultTip();
  drawHeading();

  if ('IntersectionObserver' in window) {
    new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        visible = entry.isIntersecting;
        if (visible) {
          root.classList.add('open');
          slowTick();
        }
      });
    }, { threshold: 0.25 }).observe(root);
  } else {
    visible = true;
    root.classList.add('open');
    slowTick();
  }
})();
