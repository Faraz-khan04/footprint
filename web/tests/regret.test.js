// Run: TZ=Asia/Kolkata node web/tests/regret.test.js
const assert = require('assert');
const FA = require('../analytics.js');
const at = (d, hh, mm, ss = 0) => new Date(2026, 8, d, hh, mm, ss).getTime();
const MIN = 60000;
let pass = 0; const t = (name, fn) => { fn(); pass++; console.log('PASS', name); };
const apps = { ig: { n: 'Instagram', cat: 'Social' }, yt: { n: 'YouTube', cat: 'Video' },
               wa: { n: 'WhatsApp', cat: 'Chat' }, cls: { n: 'Classroom', cat: 'Education' } };

// 10 days: late-night YouTube (always regretted), daytime WhatsApp (never), morning Instagram (mixed)
const sessions = [], unlocks = [], notifs = [], labels = {};
for (let i = 1; i <= 10; i++) {
  const night = [at(i, 23, 10), at(i, 23, 50)];                        // YouTube 40 min, late
  sessions.push(['yt', ...night]);
  labels[`yt|${night[0]}`] = 1;                                        // not worth it, every time
  const chat = [at(i, 13, 0), at(i, 13, 12)];                          // WhatsApp 12 min, daytime
  sessions.push(['wa', ...chat]); labels[`wa|${chat[0]}`] = 0;
  notifs.push(['wa', at(i, 12, 59)]);
  sessions.push(['cls', at(i, 16, 0), at(i, 17, 0)]);                  // Classroom, never asked about
  unlocks.push(at(i, 7, 0), at(i, 13, 0), at(i, 23, 10));
  sessions.push(['ig', at(i, 7, 2), at(i, 7, 2) + 9 * MIN]);           // morning Instagram, unlabelled
}
const P = FA.prepare({ apps, sessions, unlocks, notifs });
const model = FA.regretModel(P, labels);

t('model is ready after 20 labels and counts them', () => {
  assert.equal(model.labelled, 20); assert.equal(model.regretted, 10); assert.equal(model.ready, true);
});
t('predicts high regret for a new late-night YouTube session, with the reason', () => {
  const p = FA.predictRegret(P, model, { pkg: 'yt', cat: 'Video', s: at(11, 23, 30), e: at(11, 23, 55) });
  assert.ok(p.p > 0.85, `p=${p.p}`);
  assert.equal(p.why, 'YouTube after 10 pm: you marked 10 of 10 not worth it.');
});
t('predicts low regret for daytime WhatsApp opened from a notification', () => {
  const p = FA.predictRegret(P, model, { pkg: 'wa', cat: 'Chat', s: at(11, 13, 0), e: at(11, 13, 10) });
  assert.ok(p.p < 0.2, `p=${p.p}`);
});
t('says nothing at all before 8 labels', () => {
  const cold = FA.regretModel(P, { [`yt|${at(1, 23, 10)}`]: 1 });
  assert.equal(cold.ready, false);
  assert.equal(FA.predictRegret(P, cold, { pkg: 'yt', cat: 'Video', s: at(11, 23, 30), e: at(11, 23, 55) }), null);
});
t('falls back to a category rule for an app it has never seen', () => {
  const p = FA.predictRegret(P, model, { pkg: 'newvid', cat: 'Video', s: at(11, 23, 30), e: at(11, 23, 50) });
  assert.ok(p, 'some prediction'); assert.ok(p.key.startsWith('cat:Video'), p.key);
});
t('asks about long or social sessions only, newest first, skipping labelled ones', () => {
  const c = FA.askCandidates(P, labels, { endMs: at(10, 23, 59), days: 7 });
  assert.ok(c.length > 0);
  assert.ok(c.every(x => !(x.key in labels)), 'no labelled sessions');
  assert.ok(c.every(x => x.min >= 5), 'nothing under 5 minutes');
  assert.ok(c.some(x => x.pkg === 'ig'), 'the 9-minute Instagram sessions are asked about');
  assert.ok(c.every(x => x.pkg !== 'wa'), 'already-labelled WhatsApp is skipped');
  assert.equal(c[0].pkg, 'cls', 'the longest session is asked about first');
});
t("day regret: labelled minutes plus predicted minutes, and doesn't double count", () => {
  const r = FA.dayRegret(P, model, labels, at(5, 12, 0));
  assert.equal(Math.round(r.labelledMin), 40);        // the YouTube session only; WhatsApp was "worth it"
  assert.ok(r.total > r.min, 'regret is a part of the day, not all of it');
});
t('risk windows point at 23:00–23:30 with YouTube', () => {
  const w = FA.riskWindows(P, labels);
  assert.equal(w[0].hour, 23); assert.equal(w[0].minute, 10);   // they typically start at 23:10
  assert.equal(w[0].n, 'YouTube'); assert.equal(w[0].count, 10);
  assert.equal(Math.round(w[0].avgMin), 40);
});
t('no risk windows from a single regretted session', () => {
  assert.deepStrictEqual(FA.riskWindows(P, { [`yt|${at(1, 23, 10)}`]: 1 }), []);
});
t('notification triage ranks by minutes pulled in, not by count', () => {
  const tri = FA.notifTriage(P, 7, at(10, 23, 59));
  const wa = tri.find(x => x.pkg === 'wa');
  assert.equal(wa.notifs, 7); assert.equal(wa.pulls, 7);
  assert.equal(Math.round(wa.min), 7 * 12);
  assert.equal(Math.round(wa.rate * 100), 100);
});
t('sample data produces candidates and a usable model once labelled', () => {
  const S = FA.prepare(FA.sample(at(20, 22, 0), 30));
  const cands = FA.askCandidates(S, {}, { endMs: at(20, 22, 0) });
  assert.ok(cands.length >= 5, `got ${cands.length}`);
  const lab = {}; cands.forEach((c, i) => lab[c.key] = i % 3 === 0 ? 0 : 1);
  const m = FA.regretModel(S, lab);
  assert.equal(m.ready, cands.length >= 8);
  console.log(`      sample: ${cands.length} candidates, ${Math.round(FA.dayRegret(S, m, lab, at(20, 12, 0)).min)} regret min today`);
});
console.log(`\n${pass} passed`);
