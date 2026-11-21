// Run: TZ=Asia/Kolkata node web/tests/health.test.js
const assert = require('assert');
const FA = require('../analytics.js');
const H = 3600 * 1000, D = 24 * H;
const now = new Date(2026, 8, 21, 12, 0).getTime();
let pass = 0; const t = (name, fn) => { fn(); pass++; console.log('PASS', name); };
const ready = { ready: true };

t('a fresh read, or a couple of missed runs, says nothing', () => {
  assert.strictEqual(FA.collectionHealth(now, now - 5 * 60000, ready).level, 'ok');
  assert.strictEqual(FA.collectionHealth(now, now - 2.5 * H, ready).level, 'ok');
});

t('a gap of hours warns, and says the time is still recoverable', () => {
  const h = FA.collectionHealth(now, now - 7 * H, ready);
  assert.strictEqual(h.level, 'stale');
  assert.strictEqual(h.hours, 7);
  assert.strictEqual(h.lost, false);
});

t('past the system window it reports the time as unrecoverable', () => {
  const h = FA.collectionHealth(now, now - 9 * D, ready);
  assert.strictEqual(h.level, 'lost');
  assert.strictEqual(h.lost, true);
  assert.strictEqual(Math.floor(h.hours / 24), 9);
});

t('the boundary sits inside the system window, not on it', () => {
  assert.strictEqual(FA.collectionHealth(now, now - 5.5 * D, ready).level, 'stale');
  assert.strictEqual(FA.collectionHealth(now, now - 6.5 * D, ready).level, 'lost');
});

t('before setup, or before the first read, it stays quiet', () => {
  assert.strictEqual(FA.collectionHealth(now, now - 9 * D, { ready: false }).level, 'ok');
  assert.strictEqual(FA.collectionHealth(now, 0, ready).level, 'ok');
  assert.strictEqual(FA.collectionHealth(now, null, ready).level, 'ok');
});

// ---------- the "Others" row ----------
const app = (n, m) => ({ n, m });

t('a long tail of one-minute apps folds into Others', () => {
  const apps = [app('Instagram', 90), app('YouTube', 40), app('Camera', 4), app('Photos', 3),
                app('Brave', 2), app('App Locker', 1), app('GPay', 1), app('Clock', 1)];
  const s = FA.splitOthers(apps, { minMin: 2, maxRows: 12 });
  assert.deepStrictEqual(s.shown.map(a => a.n), ['Instagram', 'YouTube', 'Camera', 'Photos', 'Brave']);
  assert.strictEqual(s.others.count, 3);
  assert.strictEqual(s.others.m, 3);
});

t('nothing is lost: shown plus others always equals the whole day', () => {
  const apps = Array.from({ length: 40 }, (_, i) => app('App' + i, 40 - i));
  const s = FA.splitOthers(apps, { minMin: 2, maxRows: 12 });
  const total = apps.reduce((t, a) => t + a.m, 0);
  assert.strictEqual(s.shown.reduce((t, a) => t + a.m, 0) + s.others.m, total);
  assert.strictEqual(s.shown.length, 12);           // never more rows than the cap
});

t('a single small app is shown rather than hidden behind Others', () => {
  const s = FA.splitOthers([app('Instagram', 90), app('GPay', 1)], { minMin: 2, maxRows: 12 });
  assert.strictEqual(s.others, null);
  assert.strictEqual(s.shown.length, 2);
});

t('a day where everything matters has no Others row', () => {
  const s = FA.splitOthers([app('A', 30), app('B', 20), app('C', 10)], { minMin: 2, maxRows: 12 });
  assert.strictEqual(s.others, null);
});

t('an empty day is handled', () => {
  const s = FA.splitOthers([], { minMin: 2, maxRows: 12 });
  assert.strictEqual(s.others, null);
  assert.strictEqual(s.shown.length, 0);
});

console.log(`\n${pass} passed`);
