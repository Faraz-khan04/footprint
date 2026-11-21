// Steps and sleep. Run: TZ=Asia/Kolkata node web/tests/body.test.js
const assert = require('assert');
const FA = require('../analytics.js');
let pass = 0; const t = (name, fn) => { fn(); pass++; console.log('PASS', name); };
const MIN = 60000, HOUR = 3600000, DAY = 86400000;

const today = new Date(); today.setHours(0, 0, 0, 0);
const at = (dayOffset, h, m) => today.getTime() + dayOffset * DAY + h * HOUR + (m || 0) * MIN;

/** Builds a phone's history: each night sleeps from bedH to wakeH, with optional pre-bed scrolling. */
function build(nights, opts) {
  const o = opts || {};
  const raw = { apps: { app: { n: 'App', cat: 'Social' } }, icons: {}, sessions: [], unlocks: [],
                screenOn: [], screenOff: [], notifs: [], steps: [] };
  nights.forEach((n, i) => {
    const off = -(nights.length - 1 - i);
    // evening use ending at bedtime, so the gap that follows is the night's sleep
    const bed = at(off - 1, n.bedH, n.bedM || 0);
    raw.sessions.push([ 'app', bed - (n.beforeMin || 10) * MIN, bed ]);
    const wake = at(off, n.wakeH, n.wakeM || 0);
    raw.sessions.push([ 'app', wake, wake + 20 * MIN ]);
    raw.unlocks.push(wake);
    if (n.steps) for (const [h, c] of n.steps) raw.steps.push([at(off, h, 0), at(off, h + 1, 0), c]);
  });
  const P = FA.prepare(raw);
  FA.setOptions({ minSleepHours: 3, stepsGoal: o.goal || 8000, sleepTargetMin: 7 * 60 }, P);
  return P;
}

t('a week of nights gets an average, a shortest and a longest', () => {
  const P = build([{ bedH: 23, wakeH: 7 }, { bedH: 23, wakeH: 6 }, { bedH: 22, wakeH: 7 },
                   { bedH: 24 + 1, wakeH: 6 }, { bedH: 23, wakeH: 7 }, { bedH: 23, wakeH: 8 },
                   { bedH: 23, wakeH: 7 }]);
  const w = FA.sleepWeek(P, today.getTime(), 7);
  assert.strictEqual(w.nights.length, 7);
  assert.ok(w.have >= 6, 'nights detected: ' + w.have);
  assert.ok(w.avgMin > 5 * 60 && w.avgMin < 9 * 60, 'avg ' + w.avgMin);
  assert.ok(w.shortest.min <= w.avgMin && w.longest.min >= w.avgMin);
});

t('a wandering bedtime shows a bigger spread than a steady one', () => {
  const steady = FA.sleepWeek(build([{ bedH: 23, wakeH: 7 }, { bedH: 23, wakeH: 7 }, { bedH: 23, wakeH: 7 },
                                     { bedH: 23, wakeH: 7 }, { bedH: 23, wakeH: 7 }]), today.getTime(), 5);
  const messy = FA.sleepWeek(build([{ bedH: 21, wakeH: 7 }, { bedH: 24 + 2, wakeH: 7 }, { bedH: 22, wakeH: 7 },
                                    { bedH: 24 + 1, wakeH: 7 }, { bedH: 23, wakeH: 7 }]), today.getTime(), 5);
  assert.strictEqual(steady.bedSpreadMin, 0);
  assert.ok(messy.bedSpreadMin > 60, 'spread ' + messy.bedSpreadMin);
});

t('nights under the target are counted as debt', () => {
  const P = build([{ bedH: 24 + 2, wakeH: 6 }, { bedH: 24 + 2, wakeH: 6 }, { bedH: 23, wakeH: 7 },
                   { bedH: 23, wakeH: 7 }, { bedH: 23, wakeH: 7 }]);
  const w = FA.sleepWeek(P, today.getTime(), 5);
  assert.strictEqual(w.shortNights, 2);
  assert.ok(w.debtMin > 0 && w.debtMin < 7 * 60, 'debt ' + w.debtMin);
});

t('screen time before bed is compared against the quiet nights', () => {
  // scrolling nights go to bed at 1am and rise at 6; quiet nights sleep 23:00-07:00
  const P = build([{ bedH: 24 + 1, wakeH: 6, beforeMin: 50 }, { bedH: 24 + 1, wakeH: 6, beforeMin: 45 },
                   { bedH: 23, wakeH: 7, beforeMin: 2 }, { bedH: 23, wakeH: 7, beforeMin: 3 },
                   { bedH: 24 + 1, wakeH: 6, beforeMin: 40 }, { bedH: 23, wakeH: 7, beforeMin: 1 }]);
  const r = FA.screenBeforeBed(P, today.getTime(), 7);
  assert.ok(r, 'should find a comparison');
  assert.strictEqual(r.busy, 3);
  assert.strictEqual(r.calm, 3);
  assert.ok(r.diff > 60, 'quiet nights should be over an hour longer, got ' + r.diff);
});

t('no comparison is offered from too few nights', () => {
  assert.strictEqual(FA.screenBeforeBed(build([{ bedH: 23, wakeH: 7 }]), today.getTime(), 7), null);
});

t('steps get a total, an average, a best day and goal days', () => {
  const P = build([{ bedH: 23, wakeH: 7, steps: [[9, 3000], [18, 2000]] },
                   { bedH: 23, wakeH: 7, steps: [[9, 6000], [18, 4000]] },
                   { bedH: 23, wakeH: 7, steps: [[9, 1000]] }], { goal: 8000 });
  const s = FA.stepsSummary(P, today.getTime(), 3);
  assert.strictEqual(s.total, 16000);
  assert.strictEqual(s.best.steps, 10000);
  assert.strictEqual(s.goalDays, 1);
  assert.strictEqual(s.avg, Math.round(16000 / 3));
});

t('a goal streak counts back from today and stops at a miss', () => {
  const P = build([{ bedH: 23, wakeH: 7, steps: [[9, 9000]] }, { bedH: 23, wakeH: 7, steps: [[9, 1000]] },
                   { bedH: 23, wakeH: 7, steps: [[9, 9000]] }, { bedH: 23, wakeH: 7, steps: [[9, 9000]] }],
                  { goal: 8000 });
  assert.strictEqual(FA.stepsSummary(P, today.getTime(), 4).streak, 2);
});

t('the part of the day you move most is named', () => {
  const P = build([{ bedH: 23, wakeH: 7, steps: [[8, 500], [19, 6000]] },
                   { bedH: 23, wakeH: 7, steps: [[9, 400], [20, 5000]] }]);
  const p = FA.stepsByPartOfDay(P, today.getTime(), 2);
  assert.strictEqual(p.top, 'evening');
  assert.ok(p.topShare > 80, 'share ' + p.topShare);
});

t('a phone with no step data says so rather than guessing', () => {
  assert.strictEqual(FA.stepsByPartOfDay(build([{ bedH: 23, wakeH: 7 }]), today.getTime(), 2), null);
});

t('nights shorter than the nap threshold are reported, not silently dropped', () => {
  const nights = [{ bedH: 24 + 2, wakeH: 5 }, { bedH: 24 + 2, wakeH: 5, }, { bedH: 23, wakeH: 7 }];
  const P = build(nights);
  FA.setOptions({ minSleepHours: 5 }, P);            // higher than two of these nights
  const w = FA.sleepWeek(P, today.getTime(), 3);
  assert.strictEqual(w.thresholdH, 5);
  assert.ok(w.missing >= 2, 'missing ' + w.missing);
  FA.setOptions({ minSleepHours: 3 }, P);            // put it back for the other tests
  assert.strictEqual(FA.sleepWeek(P, today.getTime(), 3).missing, 0);
});

console.log(`\n${pass} passed`);
