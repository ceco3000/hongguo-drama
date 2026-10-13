// Offline regression: 官网直链的接受条件。
// 实测（macOS, 2026-10-13）：这个 CDN 对同一地址会间歇返回 404 —— 同一 URL、同一浏览器身份
// 连发三次就出现 200/404 混合（GET 类请求约 21% 为 404）。单次存活探测分不清「永久死链」与
// 「抖动」，判死会丢掉可用片源（实测 12 次探测误杀 1 次，最坏还会阻塞起播 3~6s）。
// 所以取址阶段不做存活判定：真实播放失败交给 requestMedia 的一次抖动重试兜底
// （见 check-web-source-fallback.cjs）。取址阶段只需保证地址本身可用作片源。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');

const sourcePath = path.join(__dirname, '../src/native/hongguo.js');
const source = fs.readFileSync(sourcePath, 'utf8');
const nativeRequire = createRequire(sourcePath);
const SITE = 'https://hongguoduanju.com';

const LIVE = `${SITE}/video/public/7688672046636600382/360p.mp4`;
const APP_URL = 'https://v11-reading-videocdn302.qznovelvod.com/video/tos/cn/example.mp4';

function fixture(handler, overrides = {}) {
  const calls = [];
  const request = method => async (url, config = {}) => {
    calls.push({ method, url, config });
    const res = await handler(url, config, method);
    const ok = typeof config.validateStatus === 'function'
      ? config.validateStatus(res.status)
      : res.status >= 200 && res.status < 300;
    if (!ok) {
      const error = new Error('Request failed with status code ' + res.status);
      error.response = res;
      throw error;
    }
    return res;
  };
  const axios = {
    get: request('get'),
    head: request('head'),
    post: async () => { throw new Error('unexpected axios.post in this path'); },
  };
  const context = {
    module: { exports: {} },
    Buffer, URL, URLSearchParams, AbortSignal, setTimeout, clearTimeout,
    console: { warn() {}, log() {}, error() {} },
    process: { env: {} },
    require: name => {
      if (name === 'axios') return axios;
      if (overrides[name]) return overrides[name];
      return nativeRequire(name);
    },
  };
  vm.runInNewContext(source, context, { filename: sourcePath });
  return { ...context.module.exports, calls };
}

const htmlWith = url => `<html><script>window.__PLAYER__={"main_url":"${url}","vid_list":[]}</script></html>`;
const isPlayerPage = url => url.startsWith(`${SITE}/player/`);
const playerRes = url => ({ status: 200, headers: { 'content-type': 'text/html' }, data: htmlWith(url) });

async function run(name, fn) {
  try { await fn(); console.log('PASS ' + name); return true; }
  catch (error) { console.error('FAIL ' + name + ': ' + error.message); return false; }
}

const PRIVATE_URLS = [
  ['回环 127.0.0.1', 'http://127.0.0.1:8080/a.mp4'],
  ['私网 10.x', 'http://10.0.0.5/a.mp4'],
  ['私网 192.168.x', 'http://192.168.1.10:8080/a.mp4'],
  ['私网 172.16-31.x', 'http://172.16.0.9/a.mp4'],
  ['链路本地 169.254.x', 'http://169.254.169.254/latest/meta-data'],
  ['IPv6 回环', 'http://[::1]:8080/a.mp4'],
  ['mDNS .local', 'http://nas.local/a.mp4'],
  ['localhost', 'http://localhost:9000/a.mp4'],
];

const checks = [
  ['公网 https 直链照常返回，且取址阶段不发起任何存活探测', async () => {
    const h = fixture(url => (isPlayerPage(url) ? playerRes(LIVE) : { status: 404, headers: {}, data: '' }));
    const result = await h.fetchPlayUrlFromWeb('7688672046636600382', '7688646466708966462');
    assert.ok(result, '公网直链必须被返回（不做单次存活判定）');
    assert.equal(result.url, LIVE);
    assert.equal(result.source, 'web');
    assert.equal(h.calls.filter(c => c.url === LIVE).length, 0,
      '取址阶段不得对直链发起探测请求（抖动使单次判定无信息量且会误杀）');
  }],

  ...PRIVATE_URLS.map(([label, url]) => [`拒绝内网片源地址：${label}`, async () => {
    const h = fixture(candidate => (isPlayerPage(candidate) ? playerRes(url) : { status: 200, headers: {}, data: '' }));
    const result = await h.fetchPlayUrlFromWeb('7688672046636600382', 'S');
    assert.equal(result, null, label + ' 不是有效片源，必须跳过');
    assert.equal(h.calls.filter(c => c.url === url).length, 0, '绝不允许对内网地址发起请求');
  }]),

  ['拒绝非 http(s) 协议与带账号密码的地址', async () => {
    for (const bad of ['file:///etc/passwd', 'data:video/mp4;base64,AAAA', 'https://user:pass@hongguoduanju.com/a.mp4']) {
      const h = fixture(candidate => (isPlayerPage(candidate) ? playerRes(bad) : { status: 200, headers: {}, data: '' }));
      assert.equal(await h.fetchPlayUrlFromWeb('7688672046636600382', 'S'), null, bad + ' 必须被拒绝');
      assert.equal(h.calls.filter(c => c.url === bad).length, 0, bad + ' 不得被请求');
    }
  }],

  ['首个候选给内网地址时继续尝试下一个候选页', async () => {
    const bad = 'http://192.168.1.10:8080/a.mp4';
    const h = fixture(url => {
      if (url === `${SITE}/player/7688646466708966462/7688672046636600382`) return playerRes(bad);
      if (url === `${SITE}/player/_/7688672046636600382`) return playerRes(LIVE);
      return { status: 200, headers: {}, data: '' };
    });
    const result = await h.fetchPlayUrlFromWeb('7688672046636600382', '7688646466708966462');
    assert.ok(result, '内网地址被拒后必须继续找可用候选');
    assert.equal(result.url, LIVE);
  }],

  ['官网页没有可用直链时回落到 App 片源', async () => {
    const h = fixture(() => ({ status: 200, headers: { 'content-type': 'text/html' }, data: '<html>no player data</html>' }), {
      './app-source': { fetchAppPlayUrl: async () => ({ url: APP_URL, contentKey: 'a'.repeat(32), codec: 'hevc', source: 'app' }) },
    });
    const result = await h.fetchPlayUrlSingle('7688677210814680126', '7688646466708966462');
    assert.equal(result.source, 'app', '官网无源时必须回落 App 片源');
    assert.equal(result.url, APP_URL);
  }],

  ['官网 player 页请求失败（404/异常）时回落到 App 片源', async () => {
    const h = fixture(() => { throw new Error('Request failed with status code 404'); }, {
      './app-source': { fetchAppPlayUrl: async () => ({ url: APP_URL, source: 'app' }) },
    });
    const result = await h.fetchPlayUrlSingle('7688677210814680126', 'S');
    assert.equal(result.source, 'app');
  }],

  ['真实播放失败过的分集：下次取源直接跳过官网源（永久死链的唯一兜底）', async () => {
    let appCalls = 0;
    const h = fixture(url => (isPlayerPage(url) ? playerRes(LIVE) : { status: 200, headers: {}, data: '' }), {
      './app-source': { fetchAppPlayUrl: async () => { appCalls++; return { url: APP_URL, source: 'app' }; } },
    });
    // 1) 第一次取源：官网页有 main_url，按设计直接返回（不做存活判定）
    const first = await h.fetchPlayUrlSingle('vid-fallback', 'S');
    assert.equal(first.source, 'web', '首次仍应优先使用官网源');
    assert.equal(appCalls, 0, '首次不应调用 App 片源');
    // 2) 该官网源真实播放失败 → 主进程记录
    assert.equal(typeof h.markWebSourceFailed, 'function', 'markWebSourceFailed 必须导出');
    h.markWebSourceFailed('vid-fallback');
    // 3) 下次取源（绕过 30s 结果的 force 重取）：必须跳过官网源，直接给 App 片源
    const second = await h.fetchPlayUrlSingle('vid-fallback', 'S', { force: true });
    assert.equal(second.source, 'app', '记录过失败的官网源必须被跳过，改用 App 片源');
    assert.equal(second.url, APP_URL);
    assert.equal(appCalls, 1, '跳过官网源后应当调用一次 App 片源');
  }],

  ['记录只对受影响的分集生效，不影响其它分集', async () => {
    const h = fixture(url => (isPlayerPage(url) ? playerRes(LIVE) : { status: 200, headers: {}, data: '' }), {
      './app-source': { fetchAppPlayUrl: async () => ({ url: APP_URL, source: 'app' }) },
    });
    h.markWebSourceFailed('vid-a');
    const other = await h.fetchPlayUrlSingle('vid-b', 'S');
    assert.equal(other.source, 'web', '未失败过的分集仍应优先用官网源');
  }],
];

(async () => {
  let failures = 0;
  for (const [name, fn] of checks) {
    if (!(await run(name, fn))) failures++;
  }
  console.log(`${checks.length - failures}/${checks.length} checks passed; Node ${process.version}`);
  process.exitCode = failures ? 1 : 0;
})();
