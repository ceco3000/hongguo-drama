// Run the single-file portable EXE on disposable Windows CI with an isolated profile and CC0 footage.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync, spawn } = require('node:child_process');
const { createHash } = require('node:crypto');
const pkg = require('../package.json');
const root = path.resolve(__dirname, '..');
const out = path.join(root, 'dist/windows-verification');
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'hongguo-windows-'));
const packaged = path.join(root, 'dist/win-unpacked');
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
  const portable = path.join(root, `dist/${pkg.build.productName}-${pkg.version}-便携版.exe`);
  result.authenticode = execFileSync('pwsh.exe', ['-NoProfile', '-Command', '(Get-AuthenticodeSignature -LiteralPath $env:HONGGUO_VERIFY_EXE).Status.ToString()'], { encoding: 'utf8', env: { ...process.env, HONGGUO_VERIFY_EXE: portable } }).trim();
  assert.equal(result.authenticode, 'NotSigned');
  const exe = path.join(packaged, pkg.build.productName + '.exe');
  const ffmpeg = path.join(packaged, 'resources/bin/ffmpeg.exe');
  const ffprobe = path.join(packaged, 'resources/bin/ffprobe.exe');
  for (const file of [exe, ffmpeg, ffprobe]) pe64(file);
  result.checks.push('Portable payload: Electron/FFmpeg/FFprobe are PE x64');
  for (const file of ['LICENSE.txt', 'NOTICE.txt', 'THIRD-PARTY-NOTICES.md', 'LICENSE.electron.txt', 'LICENSES.chromium.html', 'resources/bin/WINDOWS-NOTICE.txt', 'resources/bin/WINDOWS-X264-LICENSE.txt', 'resources/bin/WINDOWS-MINGW-LICENSE.txt', 'resources/bin/WINDOWS-GCC-LICENSE.txt', 'resources/bin/COPYING.GPLv3']) assert(fs.statSync(path.join(packaged, file)).size > 20, file);
  result.checks.push('Application, Electron, Chromium, FFmpeg and x264 notices present');
  result.ffmpeg = execFileSync(ffmpeg, ['-version'], { encoding: 'utf8', windowsHide: true }).split('\n')[0];
  const mediaDir = path.join(temp, 'media');
  fs.mkdirSync(mediaDir);
  const sample = path.join(mediaDir, 'windows-sample.mp4');
  const response = await fetch('https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4');
  assert(response.ok, 'CC0 fixture download failed');
  const flower = Buffer.from(await response.arrayBuffer());
  assert.equal(createHash('sha256').update(flower).digest('hex'), '0cd83d944a6ca7822b4a8306cecc60a36e859b041f6702c6a1ad9ead78924451');
  const original = path.join(mediaDir, 'flower-original.mp4'); fs.writeFileSync(original, flower);
  execFileSync(ffmpeg, ['-y', '-v', 'error', '-stream_loop', '2', '-i', original, '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-map', '0:v:0', '-map', '1:a:0', '-t', '15', '-vf', 'scale=640:360', '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '20', '-pix_fmt', 'yuv420p', '-c:a', 'aac', sample], { timeout: 30000, windowsHide: true });
  const probe = JSON.parse(execFileSync(ffprobe, ['-v', 'error', '-show_streams', '-of', 'json', sample], { encoding: 'utf8', windowsHide: true }));
  assert(probe.streams.some(s => s.codec_name === 'h264'));
  assert(probe.streams.some(s => s.codec_name === 'aac'));
  result.checks.push('Bundled FFmpeg encodes H.264/AAC; FFprobe reads output');
  const sid = 'windows-smoke';
  fs.writeFileSync(path.join(profile, 'data.json'), JSON.stringify({ settings: { root: mediaDir, watch_updates_enabled: false, watch_sequels_enabled: false }, tasks: [{ id: 'fixture', status: 'completed', savePath: sample, hongguoInfo: { series_id: sid, series_title: 'Windows verification', vid: 'fixture', vid_index: 1 } }], series: [{ series_id: sid, series_title: 'Windows verification', episodes: [{ vid: 'fixture', vid_index: 1, title: 'CC0 flower footage' }] }] }));
  child = spawn(portable, ['--user-data-dir=' + profile, '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=9439', '--autoplay-policy=no-user-gesture-required'], { stdio: 'ignore' });
  let target;
  for (let i = 0; i < 150; i++) {
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
  for (let i = 0; i < 50; i++) {
    if (await evaluate('Boolean(window.electronAPI && document.querySelector("#root")?.children.length)')) break;
    await delay(100);
  }
  const info = await evaluate('window.electronAPI.getAppInfo()');
  assert.equal(info.version, pkg.version); assert.equal(info.platform, 'win32');
  const settings = await evaluate('window.electronAPI.getSettings()');
  assert.equal(settings.root, mediaDir, 'Temporary profile must isolate all user state');
  assert(await evaluate('document.querySelector("#root").children.length > 0'));
  const media = await evaluate('window.electronAPI.getFfmpegStatus()');
  assert(media.available);
  const hash = file => createHash('sha256').update(fs.readFileSync(file)).digest('hex');
  assert.equal(hash(media.ffmpeg), hash(ffmpeg)); assert.equal(hash(media.ffprobe), hash(ffprobe));
  assert.notEqual(media.ffmpeg, ffmpeg, 'Must run the self-extracted portable EXE, not win-unpacked');
  result.checks.push('Single-file portable EXE opens; matching version; isolated profile; extracted media matches payload');
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
  assert.equal((await evaluate(`window.electronAPI.playSeries({seriesId:${JSON.stringify(sid)}, vidIndex:1})`)).success, true);
  const played = await evaluate(`(async () => {
    let v;
    for (let i=0; i<100; i++) { v=document.querySelector('video'); if(v?.readyState>=2) break; await new Promise(r=>setTimeout(r,100)); }
    if(!v || v.readyState<2) throw new Error('Player did not load local episode'); v.muted=true;
    if(v.paused) document.querySelector('[aria-label="播放"]').click();
    await new Promise(r => setTimeout(r, 1200));
    return { time: v.currentTime, width: v.videoWidth, height: v.videoHeight, error: v.error?.message };
  })()`);
  assert(played.time > 0.5 && played.width === 640 && played.height === 360, JSON.stringify(played));
  result.playback = played;
  const paused = await evaluate(`(async () => { document.querySelector('[aria-label="暂停"]').click(); await new Promise(r=>setTimeout(r,100)); return document.querySelector('video').paused; })()`);
  assert.equal(paused, true);
  result.checks.push('Actual application player plays CC0 flower H.264/AAC through local protocol; pause control works');
  const screenshot = await cdp('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(out, 'portable-windows-playback.png'), Buffer.from(screenshot.data, 'base64'));
  result.passed = true;
  console.log('Windows release smoke: PASS (' + result.checks.length + ' checks)');
})().catch(error => { result.passed = false; result.error = error.stack; console.error(error.stack); process.exitCode = 1; })
  .finally(() => {
    socket?.close();
    if (child?.pid) { try { execFileSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' }); } catch {} }
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(result, null, 2) + '\n');
  });
