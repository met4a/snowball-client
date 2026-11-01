// The page's own behaviour: the screenshot tabs, the Time Changer comparison and the fade-in as
// sections scroll into view. The menu demo lives in demo.js.
(function () {
  'use strict';
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

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
  const items = document.querySelectorAll('.reveal');
  if (reduce || !('IntersectionObserver' in window)) {
    items.forEach((el) => el.classList.add('in'));
    return;
  }
  const io = new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (e.isIntersecting) {
        e.target.classList.add('in');
        io.unobserve(e.target);
      }
    }
  }, { rootMargin: '0px 0px -8% 0px' });
  items.forEach((el) => io.observe(el));
})();
