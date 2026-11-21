// Exam mode. Run: TZ=Asia/Kolkata node web/tests/exam.test.js
const assert = require('assert');
const FA = require('../analytics.js');
let pass = 0; const t = (name, fn) => { fn(); pass++; console.log('PASS', name); };
const MIN = 60000, HOUR = 3600000, DAY = 86400000;
const today = new Date(); today.setHours(0, 0, 0, 0);
const key = off => {
  const d = new Date(today.getTime() + off * DAY);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

/** days: [{study: minutes, scroll: minutes}] oldest first, ending today */
function build(days) {
  const raw = { apps: { book: { n: 'Notes', cat: 'Education' }, gram: { n: 'Gram', cat: 'Social' } },
                icons: {}, sessions: [], unlocks: [], screenOn: [], screenOff: [], notifs: [], steps: [] };
  days.forEach((d, i) => {
    const base = today.getTime() - (days.length - 1 - i) * DAY + 10 * HOUR;
    if (d.study) raw.sessions.push(['book', base, base + d.study * MIN]);
    if (d.scroll) raw.sessions.push(['gram', base + 8 * HOUR, base + 8 * HOUR + d.scroll * MIN]);
  });
  return FA.prepare(raw);
}

t('the nearest exam still ahead is the one counted down to', () => {
  const P = build([{ study: 30 }]);
  const e = FA.examPlan(P, { exams: [{ name: 'OS', date: key(9) }, { name: 'DBMS', date: key(3) }] });
  assert.strictEqual(e.name, 'DBMS');
  assert.strictEqual(e.daysLeft, 3);
  assert.strictEqual(e.exams.length, 2);
  assert.strictEqual(e.exams[0].name, 'DBMS', 'exams sort by date');
});

t('a plan from the older single-exam version still works', () => {
  const e = FA.examPlan(build([{ study: 30 }]), { name: 'Maths', date: key(5), labels: {} });
  assert.strictEqual(e.name, 'Maths');
  assert.strictEqual(e.daysLeft, 5);
});

t('a passed exam does not break the countdown', () => {
  const e = FA.examPlan(build([{ study: 30 }]), { exams: [{ name: 'Old', date: key(-2) }] });
  assert.strictEqual(e.daysLeft, -2);
  assert.strictEqual(e.projectedMin, 0, 'nothing to project after the exam');
});

t('study and scrolling are separated per day', () => {
  const P = build([{ study: 60, scroll: 30 }, { study: 90, scroll: 15 }]);
  const e = FA.examPlan(P, { exams: [{ name: 'X', date: key(4) }] });
  assert.strictEqual(Math.round(e.today.study), 90);
  assert.strictEqual(Math.round(e.today.distract), 15);
  assert.strictEqual(Math.round(e.studyWeek), 150);
});

t('the target streak counts back from today and stops at a miss', () => {
  const P = build([{ study: 120 }, { study: 20 }, { study: 120 }, { study: 130 }]);
  const e = FA.examPlan(P, { exams: [{ name: 'X', date: key(4) }], targetMin: 60 });
  assert.strictEqual(e.streak, 2);
});

t('with no target there is no streak to claim', () => {
  const e = FA.examPlan(build([{ study: 120 }]), { exams: [{ name: 'X', date: key(4) }] });
  assert.strictEqual(e.streak, 0);
});

t('the projection follows the daily rate so far', () => {
  const P = build([{ study: 60 }, { study: 60 }, { study: 60 }]);
  const started = today.getTime() - 2 * DAY;
  const e = FA.examPlan(P, { exams: [{ name: 'X', date: key(5) }], startedMs: started });
  assert.strictEqual(e.days, 3);
  assert.strictEqual(e.perDay, 60);
  assert.strictEqual(e.projectedMin, 300, '60 a day for the 5 days left');
});

t('the best day since starting is reported', () => {
  const P = build([{ study: 30 }, { study: 200 }, { study: 45 }]);
  const e = FA.examPlan(P, { exams: [{ name: 'X', date: key(2) }], startedMs: today.getTime() - 2 * DAY });
  assert.strictEqual(Math.round(e.bestDay.study), 200);
});

t('no exam means no plan', () => {
  assert.strictEqual(FA.examPlan(build([{ study: 10 }]), null), null);
  assert.strictEqual(FA.examPlan(build([{ study: 10 }]), { exams: [] }), null);
});

console.log(`\n${pass} passed`);
