// Offline regression: 官网直链在红果 CDN 上会间歇性 404。
// 实测同一 URL、同一浏览器身份、连续三发，结果仍是 200/206/404 混合（约 1/3 失败），
// 所以「取源时探测一次」无法可靠预测这次播放会不会成功 —— 只能对真实请求的结果做反应。
// 期望行为：官网来源的媒体请求遇到 404 时，原样重试一次（同一地址、同一身份）；
// 重试只发生一次，且 App 来源不受影响 —— 避免把 App 片源的真实失败也拖成两次请求。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const { createRequire } = require('node:module');

const mainSource = path.resolve(__dirname, '../main.js');
const req = createRequire(mainSource);
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'hongguo-jitter-'));

const WEB_URL = 'https://hongguoduanju.com/video/public/7688672046636600382/360p.mp4';
const APP_URL = 'https://v11-reading-videocdn302.qznovelvod.com/video/tos/cn/example.mp4';

function httpError(status) {
  const error = new Error('Request failed with status code ' + status);
  error.response = { status, headers: {}, data: { destroy() {} } };
  return error;
}

function load(axiosImpl, nativeOverrides = {}) {
  const handlers = {}, protocols = {};
  const app = new EventEmitter();
  Object.assign(app, { getVersion: () => 'test', getPath: () => root, commandLine: { appendSwitch() {} }, whenReady: () => ({ then() {} }) });
  const electron = { app, ipcMain: { handle: (n, f) => { handlers[n] = f; } }, protocol: { registerSchemesAsPrivileged() {}, handle: (n, f) => { protocols[n] = f; } } };
  const native = {
    UA: 'com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860)',
    VIDEO_REFERER: 'https://novelquickapp.com/',
    WEB_UA: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
    WEB_REFERER: 'https://hongguoduanju.com/',
    deriveKey: () => Buffer.alloc(16),
    decryptMp4Buffer: b => b,
    fetchPlayUrlSingle: async () => ({ url: WEB_URL, source: 'web' }),
    markWebSourceFailed: () => {},
    ...nativeOverrides,
  };
  const context = vm.createContext({
    require: name => {
      if (name === 'electron') return electron;
      if (name === 'axios') return axiosImpl;
      if (name === './src/native/hongguo') return native;
      if (name === './src/store') return { saveTasks() {}, getSettings: () => ({ root }) };
      return req(name);
    },
    __dirname: path.dirname(mainSource),
    console: { log() {}, warn() {}, error() {} },
    process, Buffer, URL, Response, Request, Headers, AbortController, setTimeout, clearTimeout,
  });
  vm.runInContext(fs.readFileSync(mainSource, 'utf8'), context, { filename: mainSource });
  // 协议 handler 由 main.js 在 app 就绪后注册；这里显式调用，否则拿不到 hongguo-stream。
  context.registerStreamProtocol();
  context.registerLocalProtocol();
  context.handlers = handlers;
  context.protocols = protocols;
  return context;
}

function okResponse() {
  return { status: 200, headers: { 'content-type': 'video/mp4', 'content-length': '10' }, data: { destroy() {} } };
}

async function run(name, fn) {
  try { await fn(); console.log('PASS ' + name); return true; }
  catch (error) { console.error('FAIL ' + name + ': ' + error.message); return false; }
}

const checks = [
  ['官网源 404（CDN 抖动）时原样重试一次并成功', async () => {
    const calls = [];
    const ctx = load(async config => {
      calls.push(config);
      if (calls.length === 1) throw httpError(404);
      return okResponse();
    });
    const response = await ctx.requestMedia({ url: WEB_URL, source: 'web' });
    assert.equal(response.status, 200, '重试后应当成功');
    assert.equal(calls.length, 2, '官网来源 404 应当重试一次');
    assert.equal(calls[1].url, WEB_URL, '重试必须是同一地址（抖动是随机的，换地址没有依据）');
    assert.equal(calls[0].headers['User-Agent'], calls[1].headers['User-Agent'], '重试必须保持同一身份');
  }],

  ['重试只发生一次，不会无限重试', async () => {
    const calls = [];
    const ctx = load(async config => { calls.push(config); throw httpError(404); });
    await assert.rejects(() => ctx.requestMedia({ url: WEB_URL, source: 'web' }), /404/);
    assert.equal(calls.length, 2, '最多两次请求（原始 + 一次重试），实际 ' + calls.length);
  }],

  ['App 来源 404 不触发抖动重试（不掩盖真实失败）', async () => {
    const calls = [];
    const ctx = load(async config => { calls.push(config); throw httpError(404); });
    await assert.rejects(() => ctx.requestMedia({ url: APP_URL, source: 'app' }), /404/);
    assert.equal(calls.length, 1, 'App 来源失败应当立刻上报，不做抖动重试');
  }],

  ['403 不触发抖动重试（防盗链不是抖动，重试解决不了）', async () => {
    const calls = [];
    const ctx = load(async config => { calls.push(config); throw httpError(403); });
    await assert.rejects(() => ctx.requestMedia({ url: WEB_URL, source: 'web' }), /403/);
    assert.equal(calls.length, 1, '403 不得触发抖动重试');
  }],

  ['抖动重试标记不得泄漏给 axios 配置', async () => {
    const calls = [];
    const ctx = load(async config => { calls.push(config); if (calls.length === 1) throw httpError(404); return okResponse(); });
    await ctx.requestMedia({ url: WEB_URL, source: 'web' });
    for (const config of calls) {
      assert.ok(!Object.prototype.hasOwnProperty.call(config, 'webJitterRetry'), '内部重试标记不应进入 axios 配置');
    }
  }],

  ['5xx（网关抖动）与 404 一样重试一次', async () => {
    const calls = [];
    const ctx = load(async config => { calls.push(config); if (calls.length === 1) throw httpError(503); return okResponse(); });
    const response = await ctx.requestMedia({ url: WEB_URL, source: 'web' });
    assert.equal(response.status, 200, '5xx 重试后应当成功');
    assert.equal(calls.length, 2, '5xx 应当触发一次重试');
  }],

  ['App 来源 5xx 不重试（保持既有行为）', async () => {
    const calls = [];
    const ctx = load(async config => { calls.push(config); throw httpError(503); });
    await assert.rejects(() => ctx.requestMedia({ url: APP_URL, source: 'app' }), /503/);
    assert.equal(calls.length, 1, 'App 来源的 5xx 不得触发抖动重试');
  }],

  ['官网源真实播放失败后记录该分集，供下次取源换源', async () => {
    const marked = [];
    const h = load(async () => { throw httpError(404); }, { markWebSourceFailed: vid => marked.push(vid) });
    const prepared = await h.handlers['prepare-online-play']({}, { vid: 'v-mark', seriesId: 's', vidIndex: 1 });
    const response = await h.protocols['hongguo-stream'](new Request(prepared.url));
    assert.notEqual(response.status, 200);
    assert.deepEqual(marked, ['v-mark'], '官网源真实失败后必须记录该分集（下次取源据此换源）');
  }],

  ['App 来源失败不记录（没有别的来源可换）', async () => {
    const marked = [];
    const h = load(async () => { throw httpError(404); }, {
      fetchPlayUrlSingle: async () => ({ url: APP_URL, source: 'app' }),
      markWebSourceFailed: vid => marked.push(vid),
    });
    const prepared = await h.handlers['prepare-online-play']({}, { vid: 'v-app', seriesId: 's', vidIndex: 1 });
    await h.protocols['hongguo-stream'](new Request(prepared.url));
    assert.deepEqual(marked, [], 'App 来源失败不得记录为「官网源失败」');
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
