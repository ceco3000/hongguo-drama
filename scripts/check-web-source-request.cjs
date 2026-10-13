// Offline regression: 官网直链只认浏览器身份。
// 实测（macOS, 2026-10-13）：同一个 /video/public/…/360p.mp4
//   Chrome UA + Referer: https://hongguoduanju.com/  → 200，4.4MB 完整可下
//   App UA（播放器当前使用的）                        → 404
// requestMedia 对下载、在线播放、转码输入是同一入口，所以它必须按片源类型选身份，
// 否则官网一路上永远是 404，用户只能靠反复点击赌回落 App 片源。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const { createRequire } = require('node:module');

const mainSource = path.resolve(__dirname, '../main.js');
const nativePath = path.join(__dirname, '../src/native/hongguo.js');
const req = createRequire(mainSource);
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'hongguo-web-source-'));

const WEB_URL = 'https://hongguoduanju.com/video/public/7688672046636600382/360p.mp4';
const APP_URL = 'https://v11-reading-videocdn302.qznovelvod.com/video/tos/cn/example.mp4';

/** 加载 main.js，替换 axios / electron / hongguo，返回可直接调用的内部函数 */
function load(axiosMock, nativeOverrides = {}) {
  const app = new EventEmitter();
  Object.assign(app, { getVersion: () => 'test', getPath: () => root, commandLine: { appendSwitch() {} }, whenReady: () => ({ then() {} }) });
  const electron = { app, ipcMain: { handle() {} }, protocol: { registerSchemesAsPrivileged() {}, handle() {} } };
  const native = {
    UA: 'com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860)',
    VIDEO_REFERER: 'https://novelquickapp.com/',
    // 官网身份与 UA/VIDEO_REFERER 是同一份来源定义的另外两个常量（由导出用例单独验证真实模块确实导出它们）。
    WEB_UA: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
    WEB_REFERER: 'https://hongguoduanju.com/',
    deriveKey: () => Buffer.alloc(16),
    decryptMp4Buffer: b => b,
    fetchPlayUrlSingle: async () => ({ url: WEB_URL, source: 'web' }),
    ...nativeOverrides,
  };
  const context = vm.createContext({
    require: name => {
      if (name === 'electron') return electron;
      if (name === 'axios') return axiosMock;
      if (name === './src/native/hongguo') return native;
      if (name === './src/store') return { saveTasks() {}, getSettings: () => ({ root }) };
      return req(name);
    },
    __dirname: path.dirname(mainSource),
    console: { log() {}, warn() {}, error() {} },
    process, Buffer, URL, Response, Request, Headers, AbortController, setTimeout, clearTimeout,
  });
  vm.runInContext(fs.readFileSync(mainSource, 'utf8'), context, { filename: mainSource });
  return context;
}

function okResponse() {
  return { status: 200, headers: { 'content-type': 'video/mp4', 'content-length': '10' }, data: { destroy() {} } };
}
/** 真实 axios 遇 403 会 reject，main.js 正是按 error.response.status 判断是否补 Referer 重试 */
function forbidden() {
  const error = new Error('Request failed with status code 403');
  error.response = { status: 403, headers: {}, data: { destroy() {} } };
  return error;
}

async function run(name, fn) {
  try { await fn(); console.log('PASS ' + name); return true; }
  catch (error) { console.error('FAIL ' + name + ': ' + error.message); return false; }
}

const checks = [
  ['官网直链必须带浏览器身份（UA + 站点 Referer），否则 CDN 直接 404', async () => {
    const calls = [];
    const ctx = load(async config => { calls.push(config); return okResponse(); });
    await ctx.requestMedia({ url: WEB_URL, source: 'web', spadeA: null, codec: '' });
    assert.equal(calls.length, 1, '应当只发一次请求');
    const headers = calls[0].headers;
    assert.match(String(headers['User-Agent']), /Mozilla\/5\.0/, '官网直链需要浏览器 UA');
    assert.match(String(headers.Referer), /hongguoduanju\.com/, '官网直链需要站点 Referer');
  }],

  ['App 片源仍使用 App 身份，不能被浏览器身份覆盖', async () => {
    const calls = [];
    const ctx = load(async config => { calls.push(config); return okResponse(); });
    await ctx.requestMedia({ url: APP_URL, source: 'app', contentKey: 'a'.repeat(32), codec: 'hevc' });
    const headers = calls[0].headers;
    assert.match(String(headers['User-Agent']), /com\.phoenix\.read/, 'App 片源必须保留 App UA');
    assert.doesNotMatch(String(headers['User-Agent']), /Mozilla/, 'App 片源不得带浏览器 UA');
  }],

  ['旧接口与未知来源保持 App 身份（不改变既有行为）', async () => {
    const calls = [];
    const ctx = load(async config => { calls.push(config); return okResponse(); });
    await ctx.requestMedia({ url: APP_URL, source: 'api', spadeA: 'x' });
    assert.match(String(calls[0].headers['User-Agent']), /com\.phoenix\.read/);
    calls.length = 0;
    await ctx.requestMedia({ url: APP_URL });
    assert.match(String(calls[0].headers['User-Agent']), /com\.phoenix\.read/);
  }],

  ['hongguo.js 必须导出官网身份常量，供主进程复用同一份定义', async () => {
    const mod = require(nativePath);
    assert.equal(typeof mod.WEB_UA, 'string', 'WEB_UA 必须导出');
    assert.match(mod.WEB_UA, /Mozilla\/5\.0/, 'WEB_UA 必须是浏览器 UA');
    // 独立推导：只要求指向官网来源的站点根，不与源码字面量对拍（否则是同义反复）
    const referer = new URL(mod.WEB_REFERER);
    assert.equal(referer.hostname, 'hongguoduanju.com', 'WEB_REFERER 必须指向官网来源');
    assert.equal(referer.pathname, '/', 'WEB_REFERER 必须是站点根路径');
  }],

  ['常量缺失时必须回退到非空身份（不得出现空 UA / 空 Referer）', async () => {
    const calls = [];
    const ctx = load(async config => { calls.push(config); return okResponse(); }, { WEB_UA: '', WEB_REFERER: '' });
    await ctx.requestMedia({ url: WEB_URL, source: 'web' });
    const headers = calls[0].headers;
    assert.ok(headers['User-Agent'] && !/undefined/.test(String(headers['User-Agent'])), 'UA 必须回退到 App UA');
    assert.ok(headers.Referer && !/undefined/.test(String(headers.Referer)), 'Referer 必须回退到 App Referer');
  }],

  ['调用方传入的 headers 不得覆盖片源身份', async () => {
    const calls = [];
    const ctx = load(async config => { calls.push(config); return okResponse(); });
    await ctx.requestMedia({ url: WEB_URL, source: 'web' }, {
      headers: { 'User-Agent': 'attacker/1.0', Referer: 'https://evil.example/' },
    });
    const headers = calls[0].headers;
    assert.match(String(headers['User-Agent']), /Mozilla\/5\.0/, '身份不可被调用方覆盖');
    assert.equal(headers.Referer, 'https://hongguoduanju.com/', 'Referer 不可被调用方覆盖');
  }],

  ['身份之外的附加头仍然透传给上游（Range 等不能受影响）', async () => {
    const calls = [];
    const ctx = load(async config => { calls.push(config); return okResponse(); });
    await ctx.requestMedia({ url: WEB_URL, source: 'web' }, { headers: { Range: 'bytes=0-9' } });
    assert.equal(calls[0].headers.Range, 'bytes=0-9', 'Range 必须透传');
    assert.equal(calls[0].headers['Accept-Encoding'], 'identity', 'Accept-Encoding 必须保留');
  }],

  ['web 源 403 不重复请求（首次已带站点 Referer，同身份重试无意义）', async () => {
    const calls = [];
    const ctx = load(async config => { calls.push(config); throw forbidden(); });
    await assert.rejects(() => ctx.requestMedia({ url: WEB_URL, source: 'web' }), /403/);
    assert.equal(calls.length, 1, 'web 源 403 不应发起同身份重试');
  }],

  ['App 源 403 会用 App Referer 补试一次（保持既有容错）', async () => {
    const calls = [];
    const ctx = load(async config => {
      calls.push(config);
      if (calls.length === 1) throw forbidden();
      return okResponse();
    });
    const response = await ctx.requestMedia({ url: APP_URL, source: 'app' });
    assert.equal(response.status, 200, '补 Referer 重试应当成功');
    assert.equal(calls.length, 2, 'App 源 403 应重试一次');
    assert.equal(calls[1].headers.Referer, 'https://novelquickapp.com/', '重试必须补上 App Referer');
  }],
];

(async () => {
  let failures = 0;
  for (const [name, fn] of checks) {
    if (!(await run(name, fn))) failures++;
  }
  console.log(`${checks.length - failures}/${checks.length} checks passed; Node ${process.version}`);
  fs.rmSync(root, { recursive: true, force: true });
  process.exitCode = failures ? 1 : 0;
})();
