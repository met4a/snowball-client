// The Snowball+ store: finds the Minecraft account for the name typed, then opens Stripe's checkout
// for it. Stripe tells Snowball's worker when someone pays (server/chat-worker, /store/stripe), and
// the worker gives that account Snowball+.
(function () {
  'use strict';

  // The Snowball+ Payment Link from the Stripe dashboard. The price on the page is written in
  // store.html; keep it the same as the link's.
  const CHECKOUT = 'https://buy.stripe.com/cNi7sLbdAcm9fTJfpJbV600';
  // Mojang's own lookup refuses requests from web pages, so names are looked up here.
  const LOOKUP = 'https://playerdb.co/api/player/minecraft/';

  const form = document.getElementById('buy');
  const input = document.getElementById('mc-name');
  const button = document.getElementById('subscribe');
  const msg = document.getElementById('msg');

  if (new URLSearchParams(location.search).has('thanks')) document.getElementById('thanks').hidden = false;
  if (!CHECKOUT) {
    msg.textContent = 'The store opens soon.';
    return;
  }
  button.disabled = false;

  /** The account behind a name, or a reason to show the player. */
  async function account(name) {
    let res;
    let data;
    try {
      res = await fetch(LOOKUP + encodeURIComponent(name));
      data = await res.json();
    } catch {
      throw new Error('The name could not be checked just now. Try again in a moment.');
    }
    if (data.code === 'minecraft.invalid_username') throw new Error('No Minecraft: Java Edition account has that name. Check the spelling.');
    const player = data.data?.player;
    const uuid = String(player?.raw_id ?? '').toLowerCase();
    if (!res.ok || !/^[0-9a-f]{32}$/.test(uuid)) throw new Error('The name could not be checked just now. Try again in a moment.');
    return { uuid, name: String(player.username || name) };
  }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const name = input.value.trim();
    if (!/^[A-Za-z0-9_]{3,16}$/.test(name)) {
      msg.textContent = 'That is not a Minecraft name: 3 to 16 letters, numbers or underscores.';
      input.focus();
      return;
    }
    msg.textContent = '';
    button.disabled = true;
    button.textContent = 'Checking...';
    try {
      const player = await account(name);
      button.textContent = 'Opening checkout...';
      // The worker reads the account back from this when Stripe says the checkout was paid.
      const reference = encodeURIComponent(player.uuid + '_' + player.name);
      location.href = CHECKOUT + (CHECKOUT.includes('?') ? '&' : '?') + 'client_reference_id=' + reference;
    } catch (error) {
      msg.textContent = error.message;
      button.disabled = false;
      button.textContent = 'Subscribe';
    }
  });

  // Back from Stripe's checkout with the browser's Back button, the page comes back as it was left.
  window.addEventListener('pageshow', () => {
    button.disabled = false;
    button.textContent = 'Subscribe';
  });
})();
