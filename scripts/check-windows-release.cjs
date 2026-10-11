// Run only on disposable Windows CI: installs in a temporary folder and uses synthetic media.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync, spawn } = require('node:child_process');
const pkg = require('../package.json');
const root = path.resolve(__dirname, '..');
const out = path.join(root, 'dist/windows-verification');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'hongguo-windows-'));
const installed = path.join(temp, 'installed');
const profile = path.join(temp, 'profile');
fs.mkdirSync(out, { recursive: true });
fs.mkdirSync(profile);
const result = { version: pkg.version, hostPlatform: process.platform, hostArch: process.arch, signed: false, checks: [] };
let child, socket, seq = 0;
const pending = new Map();
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
function pe64(file) {
  const data = fs.readFileSync(file);
  assert.equal(data.toString('ascii', 0, 2), 'MZ');
  const pe = data.readUInt32LE(0x3c);
  assert.equal(data.readUInt32LE(pe), 0x4550);
  assert.equal(data.readUInt16LE(pe + 4), 0x8664, path.basename(file));
}
function cdp(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++seq;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('CDP timeout: ' + method)); }, 20000);
    pending.set(id, { resolve: value => { clearTimeout(timer); resolve(value); }, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const reply = await cdp('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  assert(!reply.exceptionDetails, JSON.stringify(reply.exceptionDetails));
  return reply.result.value;
}
(async () => {
  assert.equal(process.platform, 'win32');
  assert.equal(process.arch, 'x64');
  const setup = path.join(root, `dist/${pkg.build.productName}-Setup-${pkg.version}.exe`);
  execFileSync(setup, ['/S', '/D=' + installed], { timeout: 90000 });
  const exe = path.join(installed, pkg.build.productName + '.exe');
  const ffmpeg = path.join(installed, 'resources/bin/ffmpeg.exe');
  const ffprobe = path.join(installed, 'resources/bin/ffprobe.exe');
  for (const file of [exe, ffmpeg, ffprobe]) pe64(file);
  result.checks.push('NSIS silent install; Electron/FFmpeg/FFprobe are PE x64');
  for (const file of ['LICENSE.txt', 'NOTICE.txt', 'THIRD-PARTY-NOTICES.md', 'LICENSE.electron.txt', 'LICENSES.chromium.html', 'resources/bin/WINDOWS-NOTICE.txt', 'resources/bin/WINDOWS-X264-LICENSE.txt', 'resources/bin/COPYING.GPLv3']) assert(fs.statSync(path.join(installed, file)).size > 20, file);
  result.checks.push('Application, Electron, Chromium, FFmpeg and x264 notices present');
  result.ffmpeg = execFileSync(ffmpeg, ['-version'], { encoding: 'utf8', windowsHide: true }).split('\n')[0];
  const mediaDir = path.join(temp, 'media');
  fs.mkdirSync(mediaDir);
  const sample = path.join(mediaDir, 'windows-sample.mp4');
  execFileSync(ffmpeg, ['-y', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=24', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-t', '4', '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '20', '-pix_fmt', 'yuv420p', '-c:a', 'aac', sample], { timeout: 30000, windowsHide: true });
  const probe = JSON.parse(execFileSync(ffprobe, ['-v', 'error', '-show_streams', '-of', 'json', sample], { encoding: 'utf8', windowsHide: true }));
  assert(probe.streams.some(s => s.codec_name === 'h264'));
  assert(probe.streams.some(s => s.codec_name === 'aac'));
  result.checks.push('Installed FFmpeg encodes H.264/AAC; FFprobe reads output');
  const sid = 'windows-smoke';
  fs.writeFileSync(path.join(profile, 'data.json'), JSON.stringify({ settings: { root: mediaDir, watch_updates_enabled: false, watch_sequels_enabled: false }, tasks: [{ id: 'fixture', status: 'completed', savePath: sample, hongguoInfo: { series_id: sid, series_title: 'Windows verification', vid: 'fixture', vid_index: 1 } }], series: [{ series_id: sid, series_title: 'Windows verification', episodes: [{ vid: 'fixture', vid_index: 1, title: 'Synthetic sample' }] }] }));
  child = spawn(exe, ['--user-data-dir=' + profile, '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=9439', '--autoplay-policy=no-user-gesture-required'], { stdio: 'ignore' });
  let target;
  for (let i = 0; i < 50; i++) {
    try { target = (await (await fetch('http://127.0.0.1:9439/json')).json()).find(t => t.type === 'page' && t.url.startsWith('file:')); } catch {}
    if (target) break;
    await delay(200);
  }
  assert(target, 'Packaged application did not open its renderer');
  socket = new WebSocket(target.webSocketDebuggerUrl);
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (!message.id) return;
    const call = pending.get(message.id); pending.delete(message.id);
    if (call) message.error ? call.reject(new Error(JSON.stringify(message.error))) : call.resolve(message.result);
  });
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
  const info = await evaluate('window.electronAPI.getAppInfo()');
  assert.equal(info.version, pkg.version); assert.equal(info.platform, 'win32');
  const settings = await evaluate('window.electronAPI.getSettings()');
  assert.equal(settings.root, mediaDir, 'Temporary profile must isolate all user state');
  assert(await evaluate('document.querySelector("#root").children.length > 0'));
  const media = await evaluate('window.electronAPI.getFfmpegStatus()');
  assert(media.available); assert.equal(media.ffmpeg, ffmpeg); assert.equal(media.ffprobe, ffprobe);
  result.checks.push('Installed app renders; version 1.1.9; isolated profile; bundled media IPC');
  assert.equal((await evaluate('window.electronAPI.setWindowLayout("portrait")')).success, true);
  assert.equal(await evaluate('window.electronAPI.getWindowLayout()'), 'portrait');
  assert.equal(await evaluate('window.electronAPI.setAlwaysOnTop(true)'), true);
  assert.equal(await evaluate('window.electronAPI.setAlwaysOnTop(false)'), false);
  assert((await evaluate('window.electronAPI.setEpisodePanelOpen(true)')) >= 0);
  await evaluate('window.electronAPI.setEpisodePanelOpen(false)');
  assert.equal((await evaluate('window.electronAPI.setWindowLayout("landscape")')).success, true);
  result.checks.push('Portrait/landscape, always-on-top and integrated episode panel');
  const episodes = await evaluate(`window.electronAPI.getSeriesEpisodes(${JSON.stringify(sid)})`);
  assert(episodes.success && episodes.data.episodes[0].fileUrl);
  const played = await evaluate(`(async () => {
    const v = document.createElement('video'); v.muted = true; v.controls = true;
    v.style.cssText = 'position:fixed;bottom:24px;right:24px;width:480px;z-index:9999';
    v.src = ${JSON.stringify(episodes.data.episodes[0].fileUrl)}; document.body.append(v);
    await v.play(); await new Promise(r => setTimeout(r, 1200));
    return { time: v.currentTime, width: v.videoWidth, height: v.videoHeight, error: v.error?.message };
  })()`);
  assert(played.time > 0.5 && played.width === 640 && played.height === 360, JSON.stringify(played));
  result.playback = played;
  result.checks.push('Packaged local-media protocol streams and Chromium plays synthetic H.264/AAC');
  const screenshot = await cdp('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(out, 'installed-windows-playback.png'), Buffer.from(screenshot.data, 'base64'));
  result.passed = true;
  console.log('Windows release smoke: PASS (' + result.checks.length + ' checks)');
})().catch(error => { result.passed = false; result.error = error.stack; console.error(error.stack); process.exitCode = 1; })
  .finally(() => {
    socket?.close();
    if (child?.pid) { try { execFileSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' }); } catch {} }
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(result, null, 2) + '\n');
  });
