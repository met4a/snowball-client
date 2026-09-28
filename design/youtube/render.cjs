// Renders the YouTube profile picture and banner to PNG at their exact sizes, checks that the
// banner's logo and words fit the 1546x423 centre every device shows, and draws a preview of how
// YouTube crops them.
//   npx electron design/youtube/render.cjs        (from launcher/, which has Electron installed)
const { app, BrowserWindow } = require('electron');
const { join } = require('node:path');
const { writeFileSync } = require('node:fs');

const here = __dirname;
const SAFE = { left: 507, top: 508, right: 2053, bottom: 931 };
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
app.commandLine.appendSwitch('force-device-scale-factor', '1');
// Each picture closes its window before the next opens; Electron would quit at that moment.
app.on('window-all-closed', () => {});

async function render(file, width, height, check) {
  // Windows are clamped to the screen when created; the size is set again once it exists.
  const win = new BrowserWindow({ width, height, useContentSize: true, show: false, enableLargerThanScreen: true, webPreferences: { offscreen: true } });
  win.setContentSize(width, height);
  await win.loadFile(join(here, file));
  await win.webContents.executeJavaScript('document.fonts.ready.then(() => Promise.all([...document.images].map((i) => i.decode())))');
  await wait(400);
  const result = check ? await win.webContents.executeJavaScript(check) : null;
  const image = await win.webContents.capturePage({ x: 0, y: 0, width, height });
  win.destroy();
  const size = image.getSize();
  if (size.width !== width || size.height !== height) throw new Error(`${file} came out ${size.width}x${size.height}, not ${width}x${height}`);
  return { image, result };
}

app.whenReady().then(async () => {
  const profile = await render('profile.html', 800, 800);
  writeFileSync(join(here, 'snowball-youtube-profile.png'), profile.image.toPNG());

  const banner = await render('banner.html', 2560, 1440, `(() => {
    const words = [...document.querySelectorAll('.name, .line, .facts')].map((e) => e.getBoundingClientRect());
    // The snowball itself, not its transparent margin: logo.png's pixels span about 35..221 of 256.
    const img = document.querySelector('.logo img').getBoundingClientRect();
    const k = img.width / 256;
    const ball = { left: img.left + 35 * k, top: img.top + 35 * k, right: img.left + 221 * k, bottom: img.top + 221 * k };
    const all = [ball, ...words];
    return { font: document.fonts.check('100px Monocraft'), left: Math.min(...all.map((b) => b.left)), top: Math.min(...all.map((b) => b.top)), right: Math.max(...all.map((b) => b.right)), bottom: Math.max(...all.map((b) => b.bottom)) };
  })()`);
  writeFileSync(join(here, 'snowball-youtube-banner.png'), banner.image.toPNG());
  const r = banner.result;
  const inside = r.left >= SAFE.left && r.right <= SAFE.right && r.top >= SAFE.top && r.bottom <= SAFE.bottom;
  console.log(`banner content ${Math.round(r.left)},${Math.round(r.top)} to ${Math.round(r.right)},${Math.round(r.bottom)}; safe area ${SAFE.left},${SAFE.top} to ${SAFE.right},${SAFE.bottom}: ${inside ? 'inside' : 'OUTSIDE'}; Monocraft loaded: ${r.font}`);

  const preview = await render('preview.html', 1600, 1500);
  writeFileSync(join(here, 'preview.png'), preview.image.toPNG());
  app.exit(inside && r.font ? 0 : 1);
}).catch((err) => {
  console.error(err);
  app.exit(1);
});
