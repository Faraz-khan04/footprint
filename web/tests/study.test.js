// Study timer and rule-screen answers. Run: TZ=Asia/Kolkata node web/tests/study.test.js
const assert = require('assert');
const FA = require('../analytics.js');
let pass = 0; const t = (name, fn) => { fn(); pass++; console.log('PASS', name); };
const MIN = 60000, HOUR = 3600000, DAY = 86400000;
const today = new Date(); today.setHours(0, 0, 0, 0);
const T = today.getTime();
const key = off => {
  const d = new Date(T + off * DAY);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};
const apps = { book: { n: 'Notes', cat: 'Education' }, gram: { n: 'Gram', cat: 'Social' } };
const raw = extra => Object.assign({ apps, icons: {}, sessions: [], unlocks: [], screenOn: [], screenOff: [], notifs: [], steps: [], study: [], rules: [] }, extra);
const cfg = { exams: [{ name: 'DBMS', date: key(5) }], targetMin: 60 };

t('timed sessions are clipped to the window and merged', () => {
  const P = FA.prepare(raw({ study: [['A', T - HOUR, T + HOUR], ['B', T + 30 * MIN, T + 2 * HOUR]] }));
  assert.deepStrictEqual(FA.timedIn(P, T, T + DAY), [[T, T + 2 * HOUR]]);
});

t('broken study rows are dropped', () => {
  const P = FA.prepare(raw({ study: [['A', T + HOUR, T + HOUR], null, ['B', T + 2 * HOUR, T + HOUR]] }));
  assert.strictEqual(P.study.length, 0);
});

t('a timed session counts as study in exam mode', () => {
  const P = FA.prepare(raw({ study: [['DBMS', T + 9 * HOUR, T + 9 * HOUR + 50 * MIN]] }));
  const e = FA.examPlan(P, cfg, T + 20 * HOUR);
  assert.strictEqual(Math.round(e.today.study), 50);
  assert.strictEqual(Math.round(e.today.timed), 50);
});

t('a study app used during a timed session is only counted once', () => {
  const s = T + 9 * HOUR;
  const P = FA.prepare(raw({
    sessions: [['book', s + 10 * MIN, s + 30 * MIN], ['book', s + 60 * MIN, s + 70 * MIN]],
    study: [['DBMS', s, s + 50 * MIN]]
  }));
  const e = FA.examPlan(P, cfg, T + 20 * HOUR);
  // 50 timed + 10 in the study app after the session ended
  assert.strictEqual(Math.round(e.today.study), 60);
});

t('distraction during a timed session still counts as distraction', () => {
  const s = T + 9 * HOUR;
  const P = FA.prepare(raw({ sessions: [['gram', s + 5 * MIN, s + 15 * MIN]], study: [['DBMS', s, s + 50 * MIN]] }));
  const e = FA.examPlan(P, cfg, T + 20 * HOUR);
  assert.strictEqual(Math.round(e.today.distract), 10);
  assert.strictEqual(Math.round(e.today.study), 50);
});

t('timed study keeps the target streak going', () => {
  const study = [0, 1, 2].map(i => ['Maths', T - i * DAY + 8 * HOUR, T - i * DAY + 8 * HOUR + 70 * MIN]);
  const e = FA.examPlan(FA.prepare(raw({ study })), cfg, T + 20 * HOUR);
  assert.strictEqual(e.streak, 3);
});

t('study summary: today, sessions and subjects', () => {
  const P = FA.prepare(raw({ study: [
    ['DBMS', T + 8 * HOUR, T + 8 * HOUR + 25 * MIN],
    ['DBMS', T + 10 * HOUR, T + 10 * HOUR + 25 * MIN],
    ['OS', T - DAY + 8 * HOUR, T - DAY + 8 * HOUR + 40 * MIN],
    ['Old', T - 9 * DAY, T - 9 * DAY + 50 * MIN]
  ] }));
  const s = FA.studySummary(P, T + 20 * HOUR);
  assert.strictEqual(s.today, 50);
  assert.strictEqual(s.sessionsToday, 2);
  assert.strictEqual(s.week, 90);
  assert.deepStrictEqual(s.subjects.map(x => x.name), ['DBMS', 'OS'], 'older than a week is left out');
  assert.strictEqual(s.streak, 2);
});

t('a session in progress shows in today', () => {
  const P = FA.prepare(raw({}));
  const s = FA.studySummary(P, T + 10 * HOUR, { start: T + 10 * HOUR - 12 * MIN, subject: 'Maths' });
  assert.strictEqual(s.today, 12);
  assert.strictEqual(s.subjects[0].name, 'Maths');
});

t('no study today yet does not break yesterday\'s streak', () => {
  const study = [1, 2].map(i => ['Maths', T - i * DAY + 8 * HOUR, T - i * DAY + 9 * HOUR]);
  assert.strictEqual(FA.studySummary(FA.prepare(raw({ study })), T + 7 * HOUR).streak, 2);
});

t('answers to "why?" are counted by reason and by app', () => {
  const P = FA.prepare(raw({ rules: [
    ['intent', 'gram', T + HOUR, 3], ['intent', 'gram', T + 2 * HOUR, 4], ['intent', 'gram', T + 3 * HOUR, 1],
    ['intent', 'gram', T + 4 * HOUR, 0], ['intent', 'book', T + 5 * HOUR, 2],
    ['intent', 'gram', T - 3 * DAY, 3]                            // outside the window
  ] }));
  const st = FA.ruleStats(P, T, T + DAY);
  assert.strictEqual(st.asked, 5);
  assert.strictEqual(st.idle, 2, 'bored and habit');
  assert.strictEqual(st.closed, 1);
  assert.strictEqual(st.apps[0].n, 'Gram');
  assert.strictEqual(st.apps[0].idle, 2);
  assert.strictEqual(st.reasons.find(r => r.v === 3).name, 'Bored');
});

t('rule screens: held against gave in', () => {
  const P = FA.prepare(raw({ rules: [
    ['lock_kept', 'gram', T + HOUR, 0], ['lock_kept', 'gram', T + 2 * HOUR, 0], ['lock_snoozed', 'gram', T + 3 * HOUR, 0],
    ['lock_ended', 'gram', T + 4 * HOUR, 4], ['lock_kept', 'gram', T + 5 * HOUR, 2]
  ] }));
  const st = FA.ruleStats(P, T, T + DAY);
  const limit = st.screens.find(x => x.mode === 0);
  assert.strictEqual(limit.name, 'Daily limit');
  assert.strictEqual(limit.held, 2);
  assert.strictEqual(limit.gave, 1);
  assert.strictEqual(st.screens.find(x => x.mode === 4).gave, 1, 'ending a study session early counts as giving in');
  assert.strictEqual(st.screens[0].mode, 0, 'most frequent first');
});

t('old data without study or rules still works', () => {
  const P = FA.prepare({ apps, sessions: [['book', T + HOUR, T + 2 * HOUR]] });
  assert.deepStrictEqual(P.study, []);
  assert.strictEqual(FA.ruleStats(P, T, T + DAY).asked, 0);
  assert.strictEqual(Math.round(FA.examPlan(P, cfg, T + 20 * HOUR).today.study), 60);
});

t('the sample data includes study sessions and answers', () => {
  const P = FA.prepare(FA.sample(T + 20 * HOUR, 14));
  assert.ok(P.study.length > 3);
  assert.ok(P.rules.length > 3);
});

console.log(`\n${pass} passed`);
