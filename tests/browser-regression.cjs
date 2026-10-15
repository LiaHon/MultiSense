// Requires Playwright and Python 3; see docs/DEVELOPMENT.md. Uses only synthetic data.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { chromium } = require(process.env.MULTISENSE_PLAYWRIGHT || 'playwright');
const root = path.resolve(__dirname, '..');
const output = path.join(root, '.build', 'browser-test');
fs.mkdirSync(output, { recursive: true });
const errors = [];

async function openPage(browser, fixture) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1050 } });
  page.on('pageerror', error => errors.push(error.message));
  await page.route('http://multisense.test/**', route => {
    const name = new URL(route.request().url()).pathname;
    const files = {
      '/': ['app/src/main/assets/index.html', 'text/html'],
      '/app.js': ['app/src/main/assets/app.js', 'text/javascript'],
      '/style.css': ['app/src/main/assets/style.css', 'text/css'],
      '/preview-adapter.js': ['preview/preview-adapter.js', 'text/javascript']
    };
    const file = files[name];
    return file ? route.fulfill({ path: path.join(root, file[0]), contentType: file[1] }) : route.abort();
  });
  // Android's host has no WebChromeClient: label editing must not depend on prompt().
  await page.addInitScript(() => { window.prompt = () => { throw new Error('Unexpected prompt()'); }; });
  if (fixture) await page.addInitScript(data => {
    window.MultiSenseNative = {
      snapshot: () => JSON.stringify({ native: true, recording: false, inventory: [] }),
      sessions: () => JSON.stringify([data.manifest]),
      review: () => JSON.stringify(data)
    };
  }, fixture);
  await page.goto('http://multisense.test/');
  return page;
}

async function reviewFixture(browser, fixture, expected) {
  const page = await openPage(browser, fixture);
  await page.locator('.sidebar [data-tab="history"]').click();
  await page.locator('[data-review]').click();
  await page.waitForFunction(() => !document.getElementById('review-banner').hidden);
  assert.match(await page.locator('#course-check').textContent(), new RegExp('动作状态 ' + expected.actions + ' /'));
  assert.equal(await page.locator('#state-interval-bar > div').count(), expected.intervals);
  assert.match(await page.locator('#active-state-display').textContent(), new RegExp(expected.lastAction));
  assert.deepEqual(await page.locator('#state-timeline .timeline-item strong').allTextContents(), expected.timeline);
  await page.close();
}

(async () => {
  const launch = { headless: true };
  if (process.env.MULTISENSE_CHROME) launch.executablePath = process.env.MULTISENSE_CHROME;
  const browser = await chromium.launch(launch);
  try {
    const legacy = {
      manifest: { id: 'legacy', title: '旧格式模拟记录', scene: '室内', initialState: '静止',
        pose: '裤袋', durationS: 1200, status: 'saved' },
      streams: {}, route: [], raw: [], states: [],
      markers: [{ s: 300, label: '常速走', changesState: true },
        { s: 400, label: '经过正门', changesState: false },
        { s: 500, label: '常速走', changesState: true },
        { s: 900, label: '电梯' }]
    };
    await reviewFixture(browser, legacy, { actions: 3, intervals: 3, lastAction: '电梯',
      timeline: ['室内 + 静止 （初始生效）', '室内 + 常速走 ', '室内 + 电梯 ', '采集结束'] });
    await reviewFixture(browser, { ...legacy, markers: [] }, { actions: 1, intervals: 1, lastAction: '静止',
      timeline: ['室内 + 静止 （初始生效）', '采集结束'] });
    await reviewFixture(browser, { ...legacy, states: [
      { s: 0, scene: '室内', action: '步行' },
      { s: 300, scene: '室外', action: '步行' },
      { s: 900, scene: '室外', action: '跑步' }
    ] }, { actions: 2, intervals: 3, lastAction: '跑步',
      timeline: ['室内 + 步行 （初始生效）', '室外 + 步行 ', '室外 + 跑步 ', '采集结束'] });

    const page = await openPage(browser);
    await page.waitForFunction(() => window.PreviewBackend);
    await page.locator('#settings-button').click();
    await page.locator('#open-labels-btn').click();
    await page.locator('[data-edit-scene="0"]').click();
    await page.locator('#label-edit-name').fill("O'Reilly <室内>");
    await page.locator('#label-edit-form button[type="submit"]').click();
    await page.locator('[data-edit-action="0"]').click();
    await page.locator('#label-edit-name').fill('静止');
    await page.locator('#label-edit-form button[type="submit"]').click();
    assert.equal(await page.locator('#label-edit-dialog').evaluate(el => el.open), true);
    assert.equal(await page.locator('#toast').textContent(), '动作名称已存在');
    await page.locator('#label-edit-name').fill('慢步');
    await page.locator('#label-edit-form button[type="submit"]').click();
    await page.locator('[data-edit-action="0"]').click();
    await page.locator('#label-edit-name').fill('放弃修改');
    await page.locator('#label-edit-dialog .close-dialog').click();
    let labels = await page.evaluate(() => JSON.parse(localStorage.getItem('multisense-labels')));
    assert.equal(labels.defaultScene, "O'Reilly <室内>");
    assert.equal(labels.defaultAction, '慢步');
    assert.equal(labels.actions.includes('放弃修改'), false);
    await page.locator('#labels-dialog > .close-dialog').click();
    await page.locator('#input-pose').selectOption('裤袋');
    await page.locator('#settings-form button[type="submit"]').click();
    await page.reload();
    await page.waitForFunction(() => window.PreviewBackend);
    labels = await page.evaluate(() => JSON.parse(localStorage.getItem('multisense-labels')));
    assert.equal(labels.defaultAction, '慢步');
    await page.locator('#capture-button').click();
    await page.waitForFunction(() => JSON.parse(window.PreviewBackend.snapshot()).recording);
    assert.equal(await page.locator('#settings-button').isDisabled(), true);
    await page.locator('[data-select-scene="室外"]').click();
    assert.match(await page.locator('#active-state-display').textContent(), /O'Reilly <室内>/);
    await page.locator('#cancel-state-btn').click();
    assert.equal(await page.locator('#confirm-state-btn').isDisabled(), true);
    await page.locator('[data-select-scene="室外"]').click();
    await page.locator('#confirm-state-btn').click();
    await page.waitForFunction(() => JSON.parse(window.PreviewBackend.snapshot()).scene === '室外');
    await page.locator('[data-marker="检查点"]').click();
    await page.locator('[data-select-action="跑步"]').click();
    await page.locator('#confirm-state-btn').click();
    await page.waitForFunction(() => JSON.parse(window.PreviewBackend.snapshot()).rows > 800);
    assert.match(await page.locator('#course-check').textContent(), /动作状态 2 \//);
    await page.screenshot({ path: path.join(output, 'desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.screenshot({ path: path.join(output, 'mobile.png'), fullPage: true });
    await page.locator('#capture-button').click();
    await page.locator('.mobile-nav [data-tab="history"]').click();
    await page.locator('[data-review]').click();
    await page.waitForFunction(() => !document.getElementById('review-banner').hidden);
    assert.equal(await page.locator('#state-interval-bar > div').count(), 3);
    assert.match(await page.locator('#course-check').textContent(), /动作状态 2 \//);
    const downloadPromise = page.waitForEvent('download');
    await page.locator('#review-export').click();
    await (await downloadPromise).saveAs(path.join(output, 'demo.zip'));
    const summary = JSON.parse(execFileSync(process.env.PYTHON || 'python3', ['-c', `
import csv, io, json, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    assert z.testzip() is None
    assert len(z.namelist()) == 12
    def read(name): return z.read(next(n for n in z.namelist() if n.endswith('/' + name))).decode()
    states = list(csv.DictReader(io.StringIO(read('states.csv'))))
    labels = list(csv.reader(io.StringIO(read('labels.csv'))))
    events = [json.loads(line) for line in read('samples.jsonl').splitlines()]
    assert len(states) == 3 and len(labels) == 2
    assert states[0]['action'] == states[1]['action'] == '慢步'
    assert [row[2] for row in labels] == ['慢步', '跑步']
    assert sum(e['kind'] == 'marker' for e in events) == 1
    print(json.dumps({'files': len(z.namelist()), 'events': len(events)}))
`, path.join(output, 'demo.zip')], { encoding: 'utf8' }));
    assert.ok(fs.statSync(path.join(output, 'demo.zip')).size > 65535);
    await page.locator('#exit-review').click();
    await page.locator('#settings-button').click();
    await page.locator('#open-labels-btn').click();
    await page.locator('[data-edit-action="0"]').click();
    assert.equal(await page.locator('#label-edit-dialog').evaluate(el => el.getBoundingClientRect().width <= innerWidth), true);
    await page.screenshot({ path: path.join(output, 'mobile-label-editor.png'), fullPage: true });
    assert.deepEqual(errors, []);
    console.log('PASS: legacy/new history, label editing without prompt, confirmation/cancellation, pose lock, desktop/mobile layout, ZIP >64 KB with all 12 files and continuous action labels', summary);
    await page.close();
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
