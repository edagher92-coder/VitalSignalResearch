import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';
import test from 'node:test';

const html = await readFile(new URL('./index.html', import.meta.url));

const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

async function waitFor(check, timeoutMillis = 15_000) {
  const deadline = Date.now() + timeoutMillis;
  while (Date.now() < deadline) {
    const value = await check();
    if (value) return value;
    await sleep(50);
  }
  throw new Error('Timed out waiting for browser state');
}

class Cdp {
  constructor(url) {
    this.nextId = 1;
    this.pending = new Map();
    this.socket = new WebSocket(url);
  }

  async open() {
    await new Promise((resolve, reject) => {
      this.socket.addEventListener('open', resolve, { once: true });
      this.socket.addEventListener('error', reject, { once: true });
    });
    this.socket.addEventListener('message', event => {
      const message = JSON.parse(event.data);
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id);
      if (message.error) pending.reject(new Error(JSON.stringify(message.error)));
      else pending.resolve(message.result);
    });
    return this;
  }

  send(method, params = {}) {
    const id = this.nextId++;
    this.socket.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }));
  }

  close() {
    this.socket.close();
  }
}

function chromeExecutable() {
  const candidates = [
    process.env.CHROME_BIN,
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
  ].filter(Boolean);
  const executable = candidates.find(existsSync);
  assert.ok(executable, 'Chrome/Chromium is required for executable prototype tests');
  return executable;
}

test('executes reveal, concern, dialog, reduced-motion and mobile flows in Chromium', async () => {
  const server = createServer((request, response) => {
    response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    response.end(html);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();

  const profile = await mkdtemp(join(tmpdir(), 'evidessa-chrome-'));
  const chrome = spawn(chromeExecutable(), [
    '--headless=new',
    '--no-sandbox',
    '--disable-gpu',
    '--disable-dev-shm-usage',
    '--remote-debugging-port=0',
    `--user-data-dir=${profile}`,
    'about:blank',
  ], { stdio: 'ignore' });

  let browser;
  let page;
  try {
    const activePort = join(profile, 'DevToolsActivePort');
    const [debugPort] = (await waitFor(async () => {
      if (!existsSync(activePort)) return null;
      return (await readFile(activePort, 'utf8')).trim().split('\n');
    })).slice(0, 1);
    const target = await fetch(
      `http://127.0.0.1:${debugPort}/json/new?${encodeURIComponent(`http://127.0.0.1:${port}/`)}`,
      { method: 'PUT' },
    ).then(response => response.json());
    page = await new Cdp(target.webSocketDebuggerUrl).open();
    await page.send('Page.enable');
    await page.send('Runtime.enable');

    const evaluate = async expression => {
      const result = await page.send('Runtime.evaluate', {
        expression,
        awaitPromise: true,
        returnByValue: true,
      });
      if (result.exceptionDetails) throw new Error(result.exceptionDetails.text);
      return result.result.value;
    };
    await waitFor(() => evaluate('document.readyState === "complete"'));

    const initial = await evaluate(`({
      probability: document.getElementById('forecast-value').textContent,
      explanationVisible: getComputedStyle(document.getElementById('forecast-explain')).display !== 'none'
    })`);
    assert.equal(initial.probability, 'Absent from locked view');
    assert.equal(initial.explanationVisible, false);

    await evaluate(`(() => {
      const values = { energy: '4', fatigue: '7', stress: '6', sleep: '3', gi: '0' };
      Object.entries(values).forEach(([id, value]) => {
        const field = document.getElementById(id);
        field.value = value;
        field.dispatchEvent(new Event('change', { bubbles: true }));
      });
      [...document.querySelectorAll('.toggle')].filter(button =>
        ['Poor sleep', 'High stress'].includes(button.textContent.trim())
      ).forEach(button => button.click());
      document.getElementById('save').click();
    })()`);
    await sleep(100);
    const revealed = await evaluate(`({
      probability: document.getElementById('forecast-value').textContent,
      band: document.getElementById('forecast-status').textContent,
      explanationVisible: getComputedStyle(document.getElementById('forecast-explain')).display !== 'none',
      explanation: document.getElementById('forecast-explain').innerText
    })`);
    assert.equal(revealed.probability, '36% fixture probability');
    assert.equal(revealed.band, 'Engineering band · 22–51% · unvalidated');
    assert.equal(revealed.explanationVisible, true);
    assert.match(revealed.explanation, /13 of 40 \(32\.5%\) → 33\.0% weighted → 36\.4% posterior/);
    assert.match(revealed.explanation, /Similarity is not causality/);

    await evaluate(`document.getElementById('global-concern-action').click()`);
    await sleep(50);
    const held = await evaluate(`({
      concern: !document.getElementById('global-concern-hold').hidden,
      probabilityVisible: document.body.innerText.includes('36% fixture probability'),
      explanationVisible: getComputedStyle(document.getElementById('forecast-explain')).display !== 'none',
      todayChildrenVisible: [...document.querySelectorAll('#today > :not(.hero)')]
        .some(node => getComputedStyle(node).display !== 'none')
    })`);
    assert.equal(held.concern, true);
    assert.equal(held.probabilityVisible, false);
    assert.equal(held.explanationVisible, false);
    assert.equal(held.todayChildrenVisible, false);

    await evaluate(`document.querySelector('[data-view="log"]').click()`);
    await evaluate(`document.getElementById('resolve-concern').click()`);
    const modal = await evaluate(`({
      open: !document.getElementById('resolve-dialog').hidden,
      inert: document.querySelector('.shell').inert,
      focused: document.activeElement.id
    })`);
    assert.deepEqual(modal, { open: true, inert: true, focused: 'keep-concern' });

    await evaluate(`document.getElementById('resolve-dialog').dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })
    )`);
    assert.equal(await evaluate(`document.activeElement.id`), 'resolve-concern');
    await evaluate(`document.getElementById('resolve-concern').click()`);
    await evaluate(`document.getElementById('confirm-resolve').click()`);
    await sleep(50);
    const resolved = await evaluate(`({
      concernHidden: document.getElementById('global-concern-hold').hidden,
      locked: document.getElementById('forecast-value').textContent,
      focused: document.activeElement.id,
      inert: document.querySelector('.shell').inert
    })`);
    assert.deepEqual(resolved, {
      concernHidden: true,
      locked: 'Absent from locked view',
      focused: 'hero-title',
      inert: false,
    });

    await page.send('Page.addScriptToEvaluateOnNewDocument', {
      source: `window.__animationCalls = 0;
        const originalAnimate = Element.prototype.animate;
        Element.prototype.animate = function(...args) {
          window.__animationCalls += 1;
          return originalAnimate.apply(this, args);
        };`,
    });
    await page.send('Emulation.setEmulatedMedia', {
      features: [{ name: 'prefers-reduced-motion', value: 'reduce' }],
    });
    await page.send('Page.navigate', { url: `http://127.0.0.1:${port}/?reduced=1` });
    await waitFor(() => evaluate('document.readyState === "complete"'));
    await evaluate(`document.querySelector('[data-view="evidence"]').click()`);
    assert.equal(await evaluate('window.__animationCalls'), 0);

    await page.send('Emulation.setDeviceMetricsOverride', {
      width: 400,
      height: 900,
      deviceScaleFactor: 1,
      mobile: true,
    });
    await page.send('Page.navigate', { url: `http://127.0.0.1:${port}/?mobile=1` });
    await waitFor(() => evaluate('document.readyState === "complete"'));
    const mobile = await evaluate(`(() => {
      const banner = document.querySelector('.sim-banner').getBoundingClientRect();
      return {
        bannerHeight: banner.height,
        fits: document.documentElement.scrollWidth <= window.innerWidth,
        fontSize: getComputedStyle(document.querySelector('.sim-banner')).fontSize
      };
    })()`);
    assert.equal(mobile.fits, true);
    assert.ok(mobile.bannerHeight <= 115, `mobile banner too tall: ${mobile.bannerHeight}`);
    assert.equal(mobile.fontSize, '12px');
  } finally {
    page?.close();
    browser?.close();
    if (chrome.exitCode === null) {
      const exited = new Promise(resolve => chrome.once('exit', resolve));
      chrome.kill('SIGTERM');
      await Promise.race([exited, sleep(3_000)]);
    }
    await new Promise(resolve => server.close(resolve));
    await rm(profile, { recursive: true, force: true });
  }
});
