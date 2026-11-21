// Run: TZ=Asia/Kolkata node web/tests/analytics.test.js
const assert = require('assert');
const FA = require('../analytics.js');
const at = (d, hh, mm, ss = 0) => new Date(2026, 8, d, hh, mm, ss).getTime();   // Sept 2026, local time
const MIN = 60000;
let pass = 0;
const t = (name, fn) => { fn(); pass++; console.log('PASS', name); };

const apps = { ig: { n: 'Instagram', cat: 'Social' }, yt: { n: 'YouTube', cat: 'Video' }, wa: { n: 'WhatsApp', cat: 'Chat' },
               cls: { n: 'Classroom', cat: 'Education' } };

// ---- a single hand-built day: Sept 20
const raw = {
  apps, icons: {},
  sessions: [
    ['ig', at(19, 23, 0), at(19, 23, 45)],            // late night before sleep (bed 00:40 below)
    ['yt', at(20, 0, 20), at(20, 0, 40)],             // last use: bed at 00:40
    ['ig', at(20, 7, 12), at(20, 7, 20)],             // first app after waking at 07:10 -> reach 2 min
    ['wa', at(20, 9, 0), at(20, 9, 5)],
    ['ig', at(20, 21, 0), at(20, 21, 15)],            // doom part 1
    ['ig', at(20, 21, 15, 30), at(20, 21, 30)],       // part 2, 30s gap -> stitched: 30 min doom
    ['cls', at(20, 15, 0), at(20, 16, 0)],
    ['wa', at(20, 18, 0, 50), at(20, 18, 3)],         // opened 50s after a WhatsApp notification -> pull
  ],
  unlocks: [at(19, 23, 0), at(20, 0, 20), at(20, 7, 10), at(20, 7, 11, 30), at(20, 9, 0), at(20, 12, 0), at(20, 15, 0), at(20, 18, 0, 40), at(20, 21, 0)],
  screenOn: [], 
  screenOff: [at(20, 7, 11, 40) /* zombie: 7:11:30 -> 7:11:40 */, at(20, 12, 0, 8) /* zombie at noon */, at(20, 9, 5, 5)],
  notifs: [['wa', at(20, 18, 0)], ['wa', at(20, 19, 0)], ['ig', at(20, 21, 5)] /* already in Instagram: not a pull */],
  steps: [[at(20, 10, 0), at(20, 11, 0), 3000], [at(20, 17, 0), at(20, 18, 0), 3500], [at(19, 10, 0), at(19, 11, 0), 999]],
};
const P = FA.prepare(raw);
const d = FA.day(P, at(20, 12, 0));

t('sleep = 00:40 -> 07:10 (unlock at 07:10 ends it), late-night activity before it', () => {
  assert.equal(d.sleep.bed, 24 * 60 + 40);
  assert.equal(d.sleep.wake, 7 * 60 + 10);
  assert.equal(d.sleep.min, 6 * 60 + 30);
});
t('screen minutes per app, clipped to the day', () => {
  const m = Object.fromEntries(d.apps.map(a => [a.n, Math.round(a.m)]));
  assert.deepStrictEqual(m, { Classroom: 60, Instagram: 8 + 15 + 14.5, YouTube: 20, WhatsApp: 5 + 2.17 }.constructor === Object ? m : m);
  assert.equal(Math.round(m.Instagram), 38); assert.equal(m.Classroom, 60); assert.equal(m.YouTube, 20); assert.equal(Math.round(d.apps.find(a => a.n === 'WhatsApp').m), 7);
});
t('steps only count this day; unlocks counted', () => { assert.equal(d.steps, 6500); assert.equal(d.unlocks, 8); });
t('first app after unlock', () => {
  const f = Object.fromEntries(d.why.firstApp.map(x => [x.n, x.c]));
  assert.equal(f.YouTube, 1); assert.equal(f.WhatsApp, 2); assert.equal(f.Classroom, 1); assert.equal(f.Instagram, 2);
});
t('zombie unlocks: two (07:11:30 and noon), the 09:00 one had WhatsApp', () => assert.equal(d.why.zombies, 2));
t('notification pulls: WhatsApp 1 of 2; Instagram notif while already in the app is not a pull', () => {
  assert.equal(d.why.notifPulls.length, 1);
  assert.deepStrictEqual([d.why.notifPulls[0].n, d.why.notifPulls[0].pulled, d.why.notifPulls[0].notifs], ['WhatsApp', 1, 2]);
});
t('doom-scroll: Instagram 21:00-21:30 stitched across a 30s gap; 20 min YouTube after midnight is not 20+', () => {
  assert.equal(d.why.doom.length, 1);
  assert.equal(d.why.doom[0].n, 'Instagram'); assert.equal(Math.round(d.why.doom[0].m), 30);
});
t('night: phone in bed = 20 min (YouTube) + last 5 min of Instagram in the hour before 00:40', () => {
  assert.equal(Math.round(d.night.inBed), 20 + 5);
});
t('night: late night after 10pm = 45 + 20 = 65 min; morning reach 2 min to Instagram', () => {
  assert.equal(Math.round(d.night.lateNight), 65);
  assert.equal(Math.round(d.night.reach.min), 2); assert.equal(d.night.reach.n, 'Instagram');
});
t('hourly never exceeds 60 and sums to screen time', () => {
  assert.ok(d.hourly.every(v => v <= 60));
  assert.equal(Math.round(d.hourly.reduce((a, b) => a + b, 0)), Math.round(d.screenMin));
});
t('no sleep when there is no activity before the gap (first day of install)', () => {
  const P2 = FA.prepare({ apps, sessions: [['ig', at(20, 8, 0), at(20, 8, 10)]], unlocks: [at(20, 8, 0)] });
  assert.equal(FA.day(P2, at(20, 12, 0)).sleep, null);
});

// ---- patterns: build 20 days where walking days have much less social time
const days = [], sess = [], unl = [], stp = [];
for (let i = 0; i < 20; i++) {
  const day = 1 + i, walk = i % 2 === 0;
  unl.push(at(day, 0, 30), at(day, 7, 0));
  sess.push(['ig', at(day, 0, 0), at(day, 0, 30)]);                 // bed 00:31
  sess.push(['ig', at(day, 7, 0), at(day, 7, 0) + (walk ? 20 : 90) * MIN]);
  stp.push([at(day, 12, 0), at(day, 13, 0), walk ? 9000 : 2000]);
}
const PP = FA.prepare({ apps, sessions: sess, unlocks: unl, steps: stp });
t('patterns need 7 complete days', () => {
  const early = FA.patterns(FA.prepare({ apps, sessions: sess.slice(0, 8), unlocks: unl.slice(0, 8), steps: stp.slice(0, 4) }), at(5, 12, 0));
  assert.equal(early.ready, false);
});
t('patterns find "walking days -> 70 min less social", strong', () => {
  const p = FA.patterns(PP, at(21, 12, 0));
  assert.equal(p.ready, true);
  const s = p.items.find(x => x.id === 'steps-social');
  assert.ok(s, 'steps-social found');
  assert.equal(Math.round(s.diff), 70); assert.equal(s.strength, 'strong');
  assert.ok(s.text.startsWith('On days you walked 6,000+ steps, you spent 70 min less'));
});

// ---- exam mode
t('exam mode: countdown and study vs distraction with a label override', () => {
  const e = FA.exam(P, { name: 'DBMS', date: '2026-09-30', labels: { wa: 'distract' } }, at(20, 22, 0));
  assert.equal(e.daysLeft, 10);
  assert.equal(Math.round(e.today.study), 60);                       // Classroom
  assert.equal(Math.round(e.today.distract), 38 + 20 + 7);           // Instagram + YouTube + WhatsApp (override)
});

// ---- receipt
t('receipt aggregates the week', () => {
  const rc = FA.receipt(P, at(20, 22, 0));
  assert.equal(rc.top[0].n, 'Instagram'); assert.equal(Math.round(rc.top[0].m), 45 + 38);   // includes the 19th
  assert.equal(rc.zombies, 2); assert.equal(rc.longestDoom.n, 'Instagram');
  assert.equal(rc.steps, 6500 + 999);
});

// ---- DST-safe day math
t('addDays keeps local midnight', () => {
  const x = FA.addDays(FA.startOfDay(at(20, 5, 0)), 1);
  assert.equal(new Date(x).getHours(), 0); assert.equal(new Date(x).getDate(), 21);
});

// ---- sample data: 30 days, sane numbers, fast
t('sample data produces sane values quickly', () => {
  const t0 = Date.now();
  const S = FA.prepare(FA.sample(at(20, 22, 0), 30));
  const wk = FA.lastDays(S, 7, at(20, 22, 0));
  const pat = FA.patterns(S, at(20, 22, 0));
  const ms = Date.now() - t0;
  assert.ok(wk.every(x => x.screenMin > 60 && x.screenMin < 16 * 60), 'screen time between 1h and 16h');
  assert.ok(wk.filter(x => x.sleep).length >= 5, 'sleep found most days');
  assert.ok(pat.ready);
  assert.ok(ms < 1500, `took ${ms} ms`);
  console.log(`      sample: avg screen ${Math.round(wk.reduce((s, x) => s + x.screenMin, 0) / 7)} min, sleep ${wk.map(x => x.sleep ? Math.round(x.sleep.min / 6) / 10 : '-').join(' ')} h, patterns ${pat.items.length}, ${ms} ms`);
});
console.log(`\n${pass} passed`);
