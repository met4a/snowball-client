// Renders the NSIS installer artwork (sidebar/header BMPs and a multi-size ICO) with Electron.
// Run: npx electron scripts/installer-art.cjs   (outputs to build-resources/installer/)
const { app, BrowserWindow, nativeImage } = require('electron');
const { mkdirSync, readFileSync, rmSync, writeFileSync } = require('node:fs');
const { join } = require('node:path');
const { pathToFileURL } = require('node:url');

const root = join(__dirname, '..');
const outDir = join(root, 'build-resources', 'installer');
const fontUrl = pathToFileURL(join(root, 'src', 'renderer', 'assets', 'fonts', 'monocraft.ttf')).href;
const logoUrl = pathToFileURL(join(root, 'src', 'renderer', 'assets', 'logo.png')).href;
const version = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).version;

app.disableHardwareAcceleration();

/** NSIS (MUI2) only accepts uncompressed 24-bit BMPs. */
function bmp24(image) {
  const { width: w, height: h } = image.getSize();
  const src = image.toBitmap(); // BGRA
  const row = (w * 3 + 3) & ~3;
  const data = Buffer.alloc(row * h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const s = (y * w + x) * 4;
      const d = (h - 1 - y) * row + x * 3;
      data[d] = src[s];
      data[d + 1] = src[s + 1];
      data[d + 2] = src[s + 2];
    }
  }
  const header = Buffer.alloc(54);
  header.write('BM');
  header.writeUInt32LE(54 + data.length, 2);
  header.writeUInt32LE(54, 10);
  header.writeUInt32LE(40, 14);
  header.writeInt32LE(w, 18);
  header.writeInt32LE(h, 22);
  header.writeUInt16LE(1, 26);
  header.writeUInt16LE(24, 28);
  header.writeUInt32LE(data.length, 34);
  header.writeInt32LE(2835, 38);
  header.writeInt32LE(2835, 42);
  return Buffer.concat([header, data]);
}

/** ICO with embedded PNGs (supported since Windows Vista). */
function ico(source, sizes) {
  const pngs = sizes.map((s) => source.resize({ width: s, height: s, quality: 'best' }).toPNG());
  const head = Buffer.alloc(6 + 16 * sizes.length);
  head.writeUInt16LE(1, 2);
  head.writeUInt16LE(sizes.length, 4);
  let offset = head.length;
  sizes.forEach((s, i) => {
    const e = 6 + 16 * i;
    head.writeUInt8(s >= 256 ? 0 : s, e);
    head.writeUInt8(s >= 256 ? 0 : s, e + 1);
    head.writeUInt16LE(1, e + 4);
    head.writeUInt16LE(32, e + 6);
    head.writeUInt32LE(pngs[i].length, e + 8);
    head.writeUInt32LE(offset, e + 12);
    offset += pngs[i].length;
  });
  return Buffer.concat([head, ...pngs]);
}

/** Deterministic snow specks so the art is identical on every build. */
function snow(w, h, count, seed) {
  let x = seed;
  const rand = () => ((x = (x * 16807) % 2147483647) / 2147483647);
  let out = '';
  for (let i = 0; i < count; i++) {
    const size = rand() < 0.2 ? 2 : 1;
    out += `<i style="left:${Math.floor(rand() * w)}px;top:${Math.floor(rand() * h)}px;width:${size}px;height:${size}px;opacity:${(0.15 + rand() * 0.45).toFixed(2)}"></i>`;
  }
  return out;
}

const css = (w, h) => `
@font-face { font-family: M; src: url("${fontUrl}"); }
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body { width: ${w}px; height: ${h}px; overflow: hidden; background: #05070a; color: #f2f6fa; font-family: M, monospace; }
i { position: absolute; background: #dff3ff; }
.abs { position: absolute; }
.center { left: 0; right: 0; text-align: center; }
.logo { image-rendering: pixelated; }
`;

function sidebar(tagline, footer) {
  return `
<div class="abs" style="inset:0;background:radial-gradient(150px 170px at 50% 92px, rgba(127,203,255,.26), transparent 72%), linear-gradient(180deg,#0d1620 0%,#070a0e 55%,#05070a 100%)"></div>
${snow(164, 314, 46, 7)}
<div class="abs" style="left:0;right:0;top:0;height:2px;background:linear-gradient(90deg,transparent,#7fcbff,transparent)"></div>
<div class="abs" style="left:32px;top:42px;width:100px;height:100px;border-radius:50%;border:1px solid rgba(221,243,255,.10);box-shadow:0 0 0 10px rgba(221,243,255,.03),0 0 0 20px rgba(221,243,255,.02)"></div>
<img class="abs logo" src="${logoUrl}" style="left:46px;top:56px;width:72px;height:72px;filter:drop-shadow(0 0 12px rgba(127,203,255,.6))">
<div class="abs center" style="top:164px;font-size:16px;letter-spacing:2px">SNOWBALL</div>
<div class="abs center" style="top:186px;font-size:10px;letter-spacing:6px;padding-left:6px;color:#7fcbff">CLIENT</div>
<div class="abs" style="top:214px;left:40px;right:40px;height:1px;background:linear-gradient(90deg,transparent,rgba(127,203,255,.45),transparent)"></div>
<div class="abs center" style="top:226px;font-size:10px;line-height:16px;color:#9aa6b2">${tagline}</div>
<div class="abs center" style="bottom:12px;font-size:10px;letter-spacing:1px;color:#4f5a66">${footer}</div>`;
}

const header = `
<div class="abs" style="inset:0;background:linear-gradient(90deg,#05070a 0%,#0b121a 100%)"></div>
${snow(150, 57, 14, 3)}
<img class="abs logo" src="${logoUrl}" style="left:12px;top:11px;width:34px;height:34px;filter:drop-shadow(0 0 7px rgba(127,203,255,.55))">
<div class="abs" style="left:54px;top:15px;font-size:13px;letter-spacing:1px">SNOWBALL</div>
<div class="abs" style="left:55px;top:33px;font-size:9px;letter-spacing:4px;color:#7fcbff">CLIENT</div>
<div class="abs" style="left:0;right:0;bottom:0;height:1px;background:linear-gradient(90deg,transparent,rgba(127,203,255,.6))"></div>`;

let win = null;

async function render(name, w, h, body) {
  const html = join(outDir, `_art-${name}.html`);
  writeFileSync(html, `<!doctype html><html><head><meta charset="utf-8"><style>${css(w, h)}</style></head><body>${body}</body></html>`);
  win ??= new BrowserWindow({ width: w, height: h, useContentSize: true, show: false, frame: false, backgroundColor: '#05070a', webPreferences: { offscreen: true } });
  win.setContentSize(w, h);
  await win.loadFile(html);
  await win.webContents.executeJavaScript('document.fonts.ready.then(() => new Promise((r) => setTimeout(r, 400)))');
  let image = await win.webContents.capturePage({ x: 0, y: 0, width: w, height: h });
  if (image.getSize().width !== w || image.getSize().height !== h) image = image.resize({ width: w, height: h, quality: 'best' });
  writeFileSync(join(outDir, `${name}.bmp`), bmp24(image));
  writeFileSync(join(outDir, `preview-${name}.png`), image.toPNG());
  rmSync(html);
}

app.whenReady().then(async () => {
  mkdirSync(outDir, { recursive: true });
  await render('sidebar', 164, 314, sidebar('Launcher, mods<br>and FPS Boost<br>for Minecraft', `VERSION ${version}`));
  await render('uninstall-sidebar', 164, 314, sidebar('Thanks for<br>playing.<br>See you soon!', `VERSION ${version}`));
  await render('header', 150, 57, header);
  writeFileSync(join(outDir, 'icon.ico'), ico(nativeImage.createFromPath(join(root, 'build-resources', 'icon.png')), [16, 24, 32, 48, 64, 128, 256]));
  console.log(`Installer art written to ${outDir}`);
  app.quit();
}).catch((err) => {
  console.error(err);
  app.exit(1);
});
