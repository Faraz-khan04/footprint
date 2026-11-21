// Run: TZ=Asia/Kolkata node web/tests/worth.test.js
const assert = require('assert');
const FA = require('../analytics.js');
let pass = 0; const t = (name, fn) => { fn(); pass++; console.log('PASS', name); };
const MIN = 60000;

// a day of sessions: [pkg, category, startHour, minutes]
function build(rows, dayStart) {
  const raw = { apps: {}, icons: {}, sessions: [], unlocks: [], screenOn: [], screenOff: [], notifs: [], steps: [] };
  for (const [pkg, cat, hour, min] of rows) {
    raw.apps[pkg] = { n: pkg, cat };
    const s = dayStart + hour * 3600000;
    raw.sessions.push([pkg, s, s + min * MIN]);
  }
  return FA.prepare(raw);
}
const today = new Date(); today.setHours(0, 0, 0, 0);
const dayStart = today.getTime() - 86400000;   // yesterday, so sessions are finished

t('a phone call is never offered for review', () => {
  const P = build([['dialer', 'Other', 23, 64], ['com.instagram.android', 'Social', 20, 25]], dayStart);
  FA.setOptions({ askMinMinutes: 5, dialer: 'dialer', neverAsk: [] }, P);
  const c = FA.askCandidates(P, {}, { days: 7, max: 12 });
  assert.ok(c.length > 0, 'should still offer something');
  assert.ok(!c.some(x => x.pkg === 'dialer'), 'the dialer must not be asked about');
});

t('an app you opted out of is never offered again', () => {
  const P = build([['locker', 'Other', 14, 30], ['com.instagram.android', 'Social', 20, 25]], dayStart);
  FA.setOptions({ askMinMinutes: 5, dialer: '', neverAsk: ['locker'] }, P);
  const c = FA.askCandidates(P, {}, { days: 7, max: 12 });
  assert.ok(!c.some(x => x.pkg === 'locker'));
});

t('a session far longer than usual is flagged as the reason', () => {
  const rows = [];
  for (let h = 8; h < 14; h++) rows.push(['com.instagram.android', 'Social', h, 6]);   // usual: 6m
  rows.push(['com.instagram.android', 'Social', 20, 40]);                              // unusual: 40m
  const P = build(rows, dayStart);
  FA.setOptions({ askMinMinutes: 5, dialer: '', neverAsk: [] }, P);
  const c = FA.askCandidates(P, {}, { days: 7, max: 12 });
  const long = c.find(x => Math.round(x.min) === 40);
  assert.ok(long, 'the long session should be a candidate');
  assert.ok(/longer than your usual/.test(long.reason || ''), 'reason: ' + long.reason);
  assert.ok(long.score > c.filter(x => x !== long)[0].score, 'the unusual one should be asked first');
});

t('questions are spread across apps instead of hammering one', () => {
  const rows = [];
  for (let h = 8; h < 14; h++) rows.push(['com.instagram.android', 'Social', h, 20]);
  rows.push(['com.reddit.frontpage', 'Social', 15, 20], ['tube', 'Video', 16, 20]);
  const P = build(rows, dayStart);
  FA.setOptions({ askMinMinutes: 5, dialer: '', neverAsk: [] }, P);
  const c = FA.askCandidates(P, {}, { days: 7, max: 12 });
  assert.notStrictEqual(c[0].pkg, c[1].pkg, 'first two questions should not be the same app');
});

t('zombie unlocks are compared with the days before', () => {
  const days = Array.from({ length: 8 }, (_, i) => ({ ds: 'd' + i, unlocks: 20, why: { zombies: 6 } }));
  days[7].why.zombies = 2;
  const better = FA.zombieCompare(days, 'd7');
  assert.strictEqual(better.level, 'better');
  assert.strictEqual(better.avg, 6);
  days[7].why.zombies = 12;
  assert.strictEqual(FA.zombieCompare(days, 'd7').level, 'worse');
  days[7].why.zombies = 6;
  assert.strictEqual(FA.zombieCompare(days, 'd7').level, 'same');
});

t('no comparison is shown before there is history to compare with', () => {
  const days = [{ ds: 'a', unlocks: 10, why: { zombies: 3 } }, { ds: 'b', unlocks: 10, why: { zombies: 4 } }];
  assert.strictEqual(FA.zombieCompare(days, 'b'), null);
  assert.strictEqual(FA.zombieCompare(days, 'nope'), null);
});

console.log(`\n${pass} passed`);
