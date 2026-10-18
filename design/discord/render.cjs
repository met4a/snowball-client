// Renders the Discord application's cover image to PNG at exactly 1024x576, and checks that
// Monocraft loaded and the logo and words sit inside the frame with room to spare.
//   npx electron ../design/discord/render.cjs        (from launcher/, which has Electron installed)
const { app, BrowserWindow } = require('electron');
const { join } = require('node:path');
const { writeFileSync } = require('node:fs');

const here = __dirname;
const WIDTH = 1024;
const HEIGHT = 576;
const MARGIN = 40;
app.commandLine.appendSwitch('force-device-scale-factor', '1');

app.whenReady().then(async () => {
  const win = new BrowserWindow({ width: WIDTH, height: HEIGHT, useContentSize: true, show: false, enableLargerThanScreen: true, webPreferences: { offscreen: true } });
  win.setContentSize(WIDTH, HEIGHT);
  await win.loadFile(join(here, 'cover.html'));
  await win.webContents.executeJavaScript('document.fonts.ready.then(() => Promise.all([...document.images].map((i) => i.decode())))');
  await new Promise((r) => setTimeout(r, 400));
  const r = await win.webContents.executeJavaScript(`(() => {
    const words = [...document.querySelectorAll('.name, .line')].map((e) => e.getBoundingClientRect());
    // The snowball itself, not its transparent margin: logo.png's pixels span about 35..221 of 256.
    const img = document.querySelector('.logo img').getBoundingClientRect();
    const k = img.width / 256;
    const ball = { left: img.left + 35 * k, top: img.top + 35 * k, right: img.left + 221 * k, bottom: img.top + 221 * k };
    const all = [ball, ...words];
    return { font: document.fonts.check('52px Monocraft'), left: Math.min(...all.map((b) => b.left)), top: Math.min(...all.map((b) => b.top)), right: Math.max(...all.map((b) => b.right)), bottom: Math.max(...all.map((b) => b.bottom)) };
  })()`);
  const image = await win.webContents.capturePage({ x: 0, y: 0, width: WIDTH, height: HEIGHT });
  win.destroy();
  const size = image.getSize();
  if (size.width !== WIDTH || size.height !== HEIGHT) throw new Error(`came out ${size.width}x${size.height}, not ${WIDTH}x${HEIGHT}`);
  writeFileSync(join(here, 'snowball-discord-cover.png'), image.toPNG());
  const inside = r.left >= MARGIN && r.top >= MARGIN && r.right <= WIDTH - MARGIN && r.bottom <= HEIGHT - MARGIN;
  console.log(`content ${Math.round(r.left)},${Math.round(r.top)} to ${Math.round(r.right)},${Math.round(r.bottom)}: ${inside ? 'inside' : 'OUTSIDE'} the ${MARGIN}px margin; Monocraft loaded: ${r.font}`);
  app.exit(inside && r.font ? 0 : 1);
}).catch((err) => {
  console.error(err);
  app.exit(1);
});
