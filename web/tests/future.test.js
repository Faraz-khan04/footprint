// Future You: the same wording as FutureYou.kt, own verdicts, and the quotes file.
// Run: TZ=Asia/Kolkata node web/tests/future.test.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const FA = require('../analytics.js');
let pass = 0; const t = (name, fn) => { fn(); pass++; console.log('PASS', name); };
const MIN = 60000, HOUR = 3600000, DAY = 86400000;
const today = new Date(); today.setHours(0, 0, 0, 0);
const T = today.getTime();
const apps = { gram: { n: 'Gram', cat: 'Social' }, tube: { n: 'Tube', cat: 'Video' }, book: { n: 'Notes', cat: 'Education' }, game: { n: 'Pixel', cat: 'Games' } };
const raw = extra => Object.assign({ apps, icons: {}, sessions: [], unlocks: [], screenOn: [], screenOff: [], notifs: [], steps: [], study: [], rules: [] }, extra);
const goal = o => Object.assign({ name: 'DBMS', unit: 'chapter', total: 12, left: 12, pace: 60, learned: false, deadline: 0 }, o);

t('time of day parts match the app', () => {
  assert.deepStrictEqual([23, 3, 5, 16, 21].map(FA.fyPart), [0, 0, 1, 2, 3]);
});
t('amounts read like the app', () => {
  assert.strictEqual(FA.fyAmount(0.25, 'chapter'), 'about a quarter of a chapter');
  assert.strictEqual(FA.fyAmount(0.5, 'exercise'), 'about half an exercise');
  assert.strictEqual(FA.fyAmount(0.8, 'topic'), 'about three quarters of a topic');
  assert.strictEqual(FA.fyAmount(1.0, 'lecture'), 'about one lecture');
  assert.strictEqual(FA.fyAmount(1.4, 'chapter'), 'about 1.5 chapters');
  assert.strictEqual(FA.fyAmount(3.1, 'chapter'), 'about 3 chapters');
  assert.strictEqual(FA.fyAmount(13.6, 'page'), 'about 14 pages');
  assert.strictEqual(FA.fyAmount(2, 'summary'), 'about 2 summaries');
  assert.strictEqual(FA.fyAmount(0.1, 'chapter'), null);
});
t('pace: guess first, your own after two', () => {
  assert.deepStrictEqual(FA.fyPace({ total: 12, left: 11, estimate: 60 }, 500), { pace: 60, learned: false });
  assert.deepStrictEqual(FA.fyPace({ total: 12, left: 10, estimate: 60 }, 180), { pace: 90, learned: true });
  assert.deepStrictEqual(FA.fyPace({ total: 12, left: 2, estimate: 60 }, 20), { pace: 5, learned: true });
});
t('cost line', () => {
  assert.strictEqual(FA.fyCost(90, goal()), "That's about 1.5 chapters of DBMS, at your estimate of 60 minutes each.");
  assert.strictEqual(FA.fyCost(45, goal({ pace: 90, learned: true })), "That's about half a chapter of DBMS, at your pace.");
  assert.strictEqual(FA.fyCost(4, goal()), null);
});
t('deadline line', () => {
  assert.strictEqual(FA.fyDeadline(goal({ deadline: 118 }), 100), '12 chapters left, 18 days to go: about 40m a day.');
  assert.strictEqual(FA.fyDeadline(goal({ deadline: 104 }), 100), '12 chapters left, 4 days to go: about 3h a day.');
  assert.strictEqual(FA.fyDeadline(goal({ left: 1, deadline: 101 }), 100), '1 chapter left, 1 day to go: about 1h a day.');
  assert.strictEqual(FA.fyDeadline(goal({ deadline: 100 }), 100), '12 chapters left, and the day is today.');
  assert.strictEqual(FA.fyDeadline(goal({ deadline: 90 }), 100), '12 chapters left. The date has passed.');
  assert.strictEqual(FA.fyDeadline(goal(), 100), null);
  assert.strictEqual(FA.fyDeadline(goal({ left: 0 }), 100), 'All 12 chapters done.');
});
t('epoch day is the local calendar day', () => {
  const d = new Date(2026, 9, 20, 23, 30);
  assert.strictEqual(FA.epochDay(d.getTime()), Math.round(Date.UTC(2026, 9, 20) / DAY));
});
t('distracting minutes: social, video and games only', () => {
  const P = FA.prepare(raw({ sessions: [['gram', T + HOUR, T + 2 * HOUR], ['tube', T + 3 * HOUR, T + 3 * HOUR + 30 * MIN],
    ['book', T + 4 * HOUR, T + 6 * HOUR], ['game', T + 7 * HOUR, T + 7 * HOUR + 10 * MIN]] }));
  assert.strictEqual(Math.round(FA.fyDistract(P, T)), 100);
});
t('own verdicts: app and time of day, half or more regretted, worst first', () => {
  const P = FA.prepare(raw({ sessions: [['gram', T, T + MIN]] }));
  const labels = {};
  for (let i = 1; i <= 9; i++) labels[`gram|${T - i * DAY + 23 * HOUR}`] = i === 5 ? 0 : 1;     // 8 of 9 at night
  for (let i = 1; i <= 4; i++) labels[`tube|${T - i * DAY + 14 * HOUR}`] = i <= 2 ? 1 : 0;      // 2 of 4 afternoon
  for (let i = 1; i <= 5; i++) labels[`gram|${T - i * DAY + 10 * HOUR}`] = 0;                   // mornings fine
  labels['book|0'] = 1; labels['broken'] = 1;
  const v = FA.fyVerdicts(P, labels, 5);
  assert.strictEqual(v.length, 2);
  assert.deepStrictEqual([v[0].n, v[0].part, v[0].regretted, v[0].asked], ['Gram', 'late at night', 8, 9]);
  assert.deepStrictEqual([v[1].n, v[1].part, v[1].regretted, v[1].asked], ['Tube', 'in the afternoon', 2, 4]);
});
t('verdicts use only the latest ten answers', () => {
  const P = FA.prepare(raw({}));
  const labels = {};
  for (let i = 1; i <= 30; i++) labels[`gram|${T - i * DAY + 20 * HOUR}`] = i <= 10 ? 1 : 0;
  const v = FA.fyVerdicts(P, labels);
  assert.deepStrictEqual([v[0].regretted, v[0].asked], [10, 10]);
});

// ---------------------------------------------------------------- quotes.js
const file = fs.readFileSync(path.join(__dirname, '..', 'quotes.js'), 'utf8');
const ctx = { window: {} }; vm.runInNewContext(file, ctx);
const Q = ctx.window.FQ;
const TAGS = new Set(['time', 'habit', 'focus', 'discipline', 'study', 'start', 'comeback', 'night', 'future', 'rest']);
t('quotes: loads in the page', () => assert.ok(Array.isArray(Q) && Q.length >= 140));
t('quotes: the app can parse the bracketed part as JSON', () => {
  const parsed = JSON.parse(file.slice(file.indexOf('[', file.indexOf('window.FQ')), file.lastIndexOf(']') + 1));
  assert.strictEqual(parsed.length, Q.length);
});
t('quotes: every quote has text, a known tag, and no duplicates', () => {
  const seen = new Set();
  for (const q of Q) {
    assert.ok(typeof q.t === 'string' && q.t.length >= 10 && q.t.length <= 180, q.t);
    assert.ok(typeof q.by === 'string');
    assert.ok(Array.isArray(q.tags) && q.tags.length && q.tags.every(x => TAGS.has(x)), q.t);
    assert.ok(!seen.has(q.t), 'duplicate: ' + q.t); seen.add(q.t);
  }
});
t('quotes: every screen has plenty to choose from', () => {
  for (const tag of TAGS) assert.ok(Q.filter(q => q.tags.includes(tag)).length >= 5, tag);
});
t('quote of the day: same all day, same formula as the app', () => {
  const a = FA.fyQuoteOfDay(Q, 20000), b = FA.fyQuoteOfDay(Q, 20000), c = FA.fyQuoteOfDay(Q, 20001);
  assert.strictEqual(a, b); assert.notStrictEqual(a, c);
  assert.strictEqual(a, Q[(20000 * 37 + 11) % Q.length]);
  assert.strictEqual(FA.fyQuoteOfDay([], 1), null);
});
console.log(`${pass} passed`);
