// Steps recorded late by Android, and nights with a quick look at the phone.
// Run: TZ=Asia/Kolkata node web/tests/gaps.test.js
const assert = require('assert');
const FA = require('../analytics.js');
let pass = 0; const t = (name, fn) => { fn(); pass++; console.log('PASS', name); };
const MIN = 60000, HOUR = 3600000, DAY = 86400000;
const today = new Date(); today.setHours(0, 0, 0, 0);
const at = (d, h, m) => today.getTime() + d * DAY + h * HOUR + (m || 0) * MIN;
const raw = extra => Object.assign({ apps: { app: { n: 'App', cat: 'Social' } }, icons: {}, sessions: [], unlocks: [],
  screenOn: [], screenOff: [], notifs: [], steps: [] }, extra);

// ---------------------------------------------------------------- steps
t('a reading covering two days is split between them, not dumped on the first', () => {
  // last reading Monday 18:00, next one Wednesday 18:00: 48 hours, 9,600 steps
  const P = FA.prepare(raw({ steps: [[at(-2, 18), at(0, 18), 9600]] }));
  const d2 = FA.day(P, at(-2, 0)), d1 = FA.day(P, at(-1, 0)), d0 = FA.day(P, at(0, 0));
  assert.strictEqual(d2.steps, 1200);   // 6 of 48 hours
  assert.strictEqual(d1.steps, 4800);   // 24 of 48
  assert.strictEqual(d0.steps, 3600);   // 18 of 48
  assert.ok(d1.stepsEst && d0.stepsEst);
});
t('normal 15-minute readings stay exact', () => {
  const P = FA.prepare(raw({ steps: [[at(0, 9), at(0, 9, 15), 800], [at(0, 9, 15), at(0, 9, 30), 400]] }));
  const d = FA.day(P, at(0, 0));
  assert.strictEqual(d.steps, 1200); assert.strictEqual(d.stepsEst, false);
});
t('a one-hour reading across midnight stays on the day it started', () => {
  const P = FA.prepare(raw({ steps: [[at(-1, 23, 30), at(0, 0, 30), 500]] }));
  assert.strictEqual(FA.day(P, at(-1, 0)).steps, 500);
  assert.strictEqual(FA.day(P, at(0, 0)).steps, 0);
});
t('spread readings are left out of part-of-day claims', () => {
  const P = FA.prepare(raw({ steps: [[at(0, 8), at(0, 9), 3000], [at(-3, 12), at(-1, 12), 2000]] }));
  const p = FA.stepsByPartOfDay(P, at(0, 0), 7);
  assert.strictEqual(p.total, 3000); assert.strictEqual(p.top, 'morning');
});
t('the day total is the sum of the pieces, nothing lost', () => {
  const P = FA.prepare(raw({ steps: [[at(-4, 7, 13), at(0, 15, 41), 12345]] }));
  let s = 0; for (let i = -4; i <= 0; i++) s += FA.day(P, at(i, 0)).steps;
  assert.ok(Math.abs(s - 12345) <= 3, 'sum ' + s);
});
t('a long reading puts few steps in the hours you slept', () => {
  // phone used until 23:00 and again from 07:00; one reading from 18:00 to 18:00 next day
  const P = FA.prepare(raw({ steps: [[at(-1, 18), at(0, 18), 6000]],
    sessions: [['app', at(-1, 22), at(-1, 23)], ['app', at(0, 7), at(0, 8)]], unlocks: [at(-1, 22), at(0, 7)] }));
  const night = P.steps.filter(x => x.s >= at(0, 0) && x.s < at(0, 7)).reduce((a, x) => a + x.c, 0);
  const day = P.steps.filter(x => x.s >= at(0, 8) && x.s < at(0, 15)).reduce((a, x) => a + x.c, 0);
  assert.ok(night * 5 < day, `night ${night} day ${day}`);
  const sum = P.steps.reduce((a, x) => a + x.c, 0);
  assert.ok(Math.abs(sum - 6000) < 1e-6);
});
t('an alarm lighting the screen at night does not wake you up for the step count', () => {
  const P = FA.prepare(raw({ steps: [[at(-1, 18), at(0, 18), 6000]],
    sessions: [['app', at(-1, 22), at(-1, 23)], ['app', at(0, 4, 50), at(0, 4, 50, 5 / 60)], ['app', at(0, 7), at(0, 8)]],
    unlocks: [at(-1, 22), at(0, 7)], screenOn: [at(0, 4, 50), at(0, 6)] }));
  const night = P.steps.filter(x => x.s >= at(0, 0) && x.s < at(0, 7)).reduce((a, x) => a + x.c, 0);
  const day = P.steps.filter(x => x.s >= at(0, 8) && x.s < at(0, 15)).reduce((a, x) => a + x.c, 0);
  assert.ok(night * 5 < day, `night ${night} day ${day}`);
});
t('two days covered by one reading are no longer identical when the nights differ', () => {
  const P = FA.prepare(raw({ steps: [[at(-3, 0), at(-1, 0), 10000]],
    sessions: [['app', at(-3, 9), at(-3, 10)], ['app', at(-3, 20), at(-3, 21)], ['app', at(-2, 3), at(-2, 4)], ['app', at(-2, 23), at(-2, 23, 30)]],
    unlocks: [] }));
  assert.notStrictEqual(FA.day(P, at(-3, 0)).steps, FA.day(P, at(-2, 0)).steps);
});
t('no claim about when you walk while most steps are guessed', () => {
  const P = FA.prepare(raw({ steps: [[at(0, 8), at(0, 9), 500], [at(-3, 12), at(-1, 12), 9000]] }));
  assert.strictEqual(FA.stepsByPartOfDay(P, at(0, 0), 7), null);
});

// ---------------------------------------------------------------- sleep
const night = extra => {
  const r = raw({ sessions: [['app', at(-1, 22, 50), at(-1, 23)], ['app', at(0, 7), at(0, 7, 20)]], unlocks: [at(0, 7)] });
  for (const [s, e] of extra) { r.sessions.push(['app', s, e]); r.unlocks.push(s); }
  const P = FA.prepare(r); FA.setOptions({ minSleepHours: 3 }, P); return P;
};
t('a 2-minute look at 03:00 does not cut the night in half', () => {
  const s = FA.sleepFor(night([[at(0, 3), at(0, 3, 2)]]), at(0, 0));
  assert.strictEqual(s.bedMs, at(-1, 23)); assert.strictEqual(s.wakeMs, at(0, 7));
  assert.strictEqual(s.min, 8 * 60 - 2); assert.strictEqual(s.woke, 1);
});
t('two short looks still make one night', () => {
  const s = FA.sleepFor(night([[at(0, 1, 30), at(0, 1, 33)], [at(0, 4, 10), at(0, 4, 15)]]), at(0, 0));
  assert.strictEqual(s.woke, 2); assert.strictEqual(s.min, 8 * 60 - 8);
});
t('half an hour on the phone at 3 is getting up, so the longer part counts', () => {
  const s = FA.sleepFor(night([[at(0, 3), at(0, 3, 30)]]), at(0, 0));
  assert.strictEqual(s.wakeMs, at(0, 3)); assert.strictEqual(s.min, 4 * 60); assert.strictEqual(s.woke, 0);
});
t('an undisturbed night is unchanged', () => {
  const s = FA.sleepFor(night([]), at(0, 0));
  assert.strictEqual(s.min, 8 * 60); assert.strictEqual(s.woke, 0);
});
t('a quick look in the afternoon is not treated as a night wake-up', () => {
  const r = raw({ sessions: [['app', at(0, 12), at(0, 12, 5)], ['app', at(0, 13, 30), at(0, 13, 33)], ['app', at(0, 15), at(0, 15, 10)]] });
  const P = FA.prepare(r); FA.setOptions({ minSleepHours: 3 }, P);
  assert.strictEqual(FA.sleepFor(P, at(0, 0)), null);
});

console.log(`gaps: ${pass} passed`);
