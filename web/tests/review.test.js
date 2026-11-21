// Week in review, experiments, walking and Reels counts. Run: TZ=Asia/Kolkata node web/tests/review.test.js
const assert = require('assert');
const FA = require('../analytics.js');
let pass = 0; const t = (name, fn) => { fn(); pass++; console.log('PASS', name); };
const MIN = 60000, HOUR = 3600000, DAY = 86400000;
const today = new Date(); today.setHours(0, 0, 0, 0);
const T = today.getTime();
const apps = { gram: { n: 'Gram', cat: 'Social' }, notes: { n: 'Notes', cat: 'Education' }, chat: { n: 'Chat', cat: 'Chat' } };
const raw = extra => Object.assign({ apps, icons: {}, sessions: [], unlocks: [], screenOn: [], screenOff: [], notifs: [], steps: [], study: [], rules: [] }, extra);

/** 14 days ending today: the first 7 with `prev` minutes of Gram at noon, the last 7 with `cur`. */
function twoWeeks(prev, cur, extra) {
  const sessions = [];
  for (let i = 13; i >= 0; i--) {
    const ds = T - i * DAY, m = i >= 7 ? prev : cur;
    if (m) sessions.push(['gram', ds + 12 * HOUR, ds + 12 * HOUR + m * MIN]);
    sessions.push(['chat', ds + 9 * HOUR, ds + 9 * HOUR + 10 * MIN]);
  }
  return FA.prepare(raw(Object.assign({ sessions }, extra || {})));
}

t('this week against last week, per day', () => {
  const r = FA.weekReview(twoWeeks(90, 60), T + 20 * HOUR);
  assert.strictEqual(r.cur.days, 7);
  assert.strictEqual(Math.round(r.cur.screen), 7 * 70);
  assert.strictEqual(Math.round(r.prev.screen), 7 * 100);
  assert.strictEqual(Math.round(r.cur.social), 7 * 60);
  assert.ok(r.enough);
});

t('the top apps come with last week\'s minutes', () => {
  const r = FA.weekReview(twoWeeks(90, 60), T + 20 * HOUR);
  assert.strictEqual(r.top[0].n, 'Gram');
  assert.strictEqual(Math.round(r.top[0].m), 420);
  assert.strictEqual(Math.round(r.top[0].prev), 630);
});

t('fewer than 3 days is not enough for a review', () => {
  const P = FA.prepare(raw({ sessions: [['gram', T + HOUR, T + 2 * HOUR], ['gram', T - DAY + HOUR, T - DAY + 2 * HOUR]] }));
  assert.strictEqual(FA.weekReview(P, T + 20 * HOUR).enough, false);
});

t('late-night minutes count 23:00 to 05:00', () => {
  const sessions = [['gram', T - DAY + 23 * HOUR + 30 * MIN, T + 30 * MIN], ['gram', T + 20 * HOUR, T + 21 * HOUR]];
  const P = FA.prepare(raw({ sessions }));
  assert.strictEqual(Math.round(FA.metric(P, 'late', T - DAY, T + DAY)), 60);
});

t('a heavy social app suggests a limit 30% under its average', () => {
  const r = FA.weekReview(twoWeeks(90, 90), T + 20 * HOUR);
  const opts = FA.pickExperiment(FA.prepare(raw({})), r);
  const limit = opts.find(o => o.id === 'limit');
  assert.ok(limit, 'a limit is offered');
  assert.ok(/Gram/.test(limit.title));
  assert.ok(/65 minutes/.test(limit.how), limit.how);
});

t('ideas come best fit first, and there is always one', () => {
  const P = twoWeeks(0, 0);
  const opts = FA.pickExperiment(P, FA.weekReview(P, T + 20 * HOUR));
  assert.ok(opts.length >= 1);
  assert.strictEqual(opts[0].id, 'study', 'nothing timed yet, nothing heavy: study first');
  assert.ok(opts.every(o => Number.isFinite(o.baseline) && o.start > T));
});

t('an experiment is judged on its own number, from the day after it was chosen', () => {
  // chosen 3 days ago with a baseline of 90 minutes of Gram a day; since then 45 a day
  const start = T - 2 * DAY;
  const sessions = [0, 1, 2].map(i => ['gram', start + i * DAY + 12 * HOUR, start + i * DAY + 12 * HOUR + 45 * MIN]);
  const P = FA.prepare(raw({ sessions }));
  const res = FA.experimentResult(P, { metric: 'app', app: 'gram', dir: -1, baseline: 90, start }, T + 20 * HOUR);
  assert.strictEqual(res.days, 3);
  assert.strictEqual(Math.round(res.now), 45);
  assert.strictEqual(res.better, true);
  assert.strictEqual(res.done, false);
});

t('an experiment is done after 7 days', () => {
  const start = T - 8 * DAY;
  const res = FA.experimentResult(FA.prepare(raw({})), { metric: 'timed', dir: 1, baseline: 0, start }, T + 20 * HOUR);
  assert.strictEqual(res.days, 7);
  assert.strictEqual(res.done, true);
  assert.strictEqual(res.same, true);
});

t('an experiment chosen today starts tomorrow', () => {
  const res = FA.experimentResult(FA.prepare(raw({})), { metric: 'screen', dir: -1, baseline: 100, start: T + DAY }, T + 20 * HOUR);
  assert.strictEqual(res.days, 0);
});

t('walking for extra time is counted apart from giving in', () => {
  const P = FA.prepare(raw({ rules: [
    ['lock_kept', 'gram', T + HOUR, 0], ['lock_walked', 'gram', T + 2 * HOUR, 0], ['lock_snoozed', 'gram', T + 3 * HOUR, 0]
  ] }));
  const s = FA.ruleStats(P, T, T + DAY).screens[0];
  assert.deepStrictEqual([s.held, s.walked, s.gave], [1, 1, 1]);
});

t('Reels and Shorts closed, by feed', () => {
  const P = FA.prepare(raw({ rules: [
    ['reels_blocked', 'com.instagram.android', T + HOUR, 1], ['reels_blocked', 'com.instagram.android', T + 2 * HOUR, 1],
    ['reels_blocked', 'com.google.android.youtube', T + 3 * HOUR, 0]
  ] }));
  const st = FA.ruleStats(P, T, T + DAY);
  assert.strictEqual(st.reelsTotal, 3);
  assert.deepStrictEqual(st.reels[0], { name: 'Instagram Reels', c: 2 });
  assert.strictEqual(FA.weekReview(P, T + 20 * HOUR).cur.reels, 3);
});

t('held share in the review counts walked as not held', () => {
  const P = twoWeeks(30, 30, { rules: [['lock_kept', 'gram', T + HOUR, 0], ['lock_walked', 'gram', T + 2 * HOUR, 0]] });
  const r = FA.weekReview(P, T + 20 * HOUR);
  assert.deepStrictEqual([r.cur.held, r.cur.shown], [1, 2]);
});

console.log(`\n${pass} passed`);
