// Shared page behaviour for every page: the header, the phone menu, the screenshot tabs, the Time
// Changer comparison and the fade-in as sections scroll into view. The menu demo lives in demo.js and
// the Snowball+ checkout in store.js.
(function () {
  'use strict';

  // ---------- header and phone menu ----------
  const header = document.querySelector('.site-header');
  const nav = header && header.querySelector('.nav');
  const toggle = header && header.querySelector('.nav-toggle');

  function setMenu(open) {
    header.classList.toggle('menu-open', open);
    toggle.setAttribute('aria-expanded', String(open));
    toggle.setAttribute('aria-label', open ? 'Close menu' : 'Open menu');
  }

  if (nav && toggle) {
    toggle.addEventListener('click', () => setMenu(!header.classList.contains('menu-open')));
    nav.addEventListener('click', (e) => {
      if (e.target.closest('a')) setMenu(false);
    });
    document.addEventListener('click', (e) => {
      if (!header.contains(e.target)) setMenu(false);
    });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && header.classList.contains('menu-open')) {
        setMenu(false);
        toggle.focus();
      }
    });
    window.matchMedia('(min-width: 821px)').addEventListener('change', (m) => {
      if (m.matches) setMenu(false);
    });
  }

  if (header) {
    let frame = 0;
    const update = () => {
      frame = 0;
      header.classList.toggle('scrolled', window.scrollY > 4);
    };
    window.addEventListener('scroll', () => {
      if (!frame) frame = requestAnimationFrame(update);
    }, { passive: true });
    update();
  }

  // The nav link of the section in the middle of the screen is lit.
  const navLinks = nav ? Array.from(nav.querySelectorAll('a[href^="#"]')) : [];
  if (navLinks.length && 'IntersectionObserver' in window) {
    const byId = new Map(navLinks.map((a) => [a.getAttribute('href').slice(1), a]));
    const spy = new IntersectionObserver((entries) => {
      for (const e of entries) byId.get(e.target.id).classList.toggle('is-active', e.isIntersecting);
    }, { rootMargin: '-45% 0px -54% 0px' });
    for (const id of byId.keys()) {
      const section = document.getElementById(id);
      if (section) spy.observe(section);
    }
  }

  // ---------- screenshot tabs ----------
  const tabs = Array.from(document.querySelectorAll('.tabs [role="tab"]'));
  function choose(tab, focus) {
    for (const t of tabs) {
      const on = t === tab;
      t.setAttribute('aria-selected', String(on));
      t.tabIndex = on ? 0 : -1;
      document.getElementById(t.getAttribute('aria-controls')).hidden = !on;
    }
    if (focus) tab.focus();
  }
  tabs.forEach((tab, i) => {
    tab.addEventListener('click', () => choose(tab, false));
    tab.addEventListener('keydown', (e) => {
      const step = { ArrowRight: 1, ArrowLeft: -1 }[e.key];
      if (step) {
        e.preventDefault();
        choose(tabs[(i + step + tabs.length) % tabs.length], true);
      } else if (e.key === 'Home' || e.key === 'End') {
        e.preventDefault();
        choose(tabs[e.key === 'Home' ? 0 : tabs.length - 1], true);
      }
    });
  });

  // ---------- Time Changer comparison ----------
  for (const fig of document.querySelectorAll('.compare')) {
    const range = fig.querySelector('.compare-range');
    const set = () => fig.style.setProperty('--pos', range.value + '%');
    range.addEventListener('input', set);
    set();
  }

  // ---------- fade in on scroll ----------
  // Elements in the same batch are staggered; once shown, the classes go so hover effects take over.
  const items = document.querySelectorAll('.reveal');
  if (!('IntersectionObserver' in window)) {
    items.forEach((el) => el.classList.add('in'));
    return;
  }
  const io = new IntersectionObserver((entries) => {
    let n = 0;
    for (const e of entries) {
      if (!e.isIntersecting) continue;
      const el = e.target;
      io.unobserve(el);
      const delay = Math.min(n++, 5) * 70;
      el.style.setProperty('--rd', delay + 'ms');
      el.classList.add('in');
      setTimeout(() => {
        el.classList.remove('reveal', 'in');
        el.style.removeProperty('--rd');
      }, 900 + delay);
    }
  }, { rootMargin: '0px 0px -6% 0px' });
  items.forEach((el) => io.observe(el));
})();
