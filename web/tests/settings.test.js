// Run: TZ=Asia/Kolkata node web/tests/settings.test.js
const assert = require('assert');
const FA = require('../analytics.js');
const at = (d, hh, mm) => new Date(2026, 8, d, hh, mm).getTime();
const MIN = 60000;
let pass = 0; const t = (name, fn) => { fn(); pass++; console.log('PASS', name); };
const apps = { ig: { n: 'Instagram', cat: 'Social' }, wa: { n: 'WhatsApp', cat: 'Chat' } };

t('"ignore naps shorter than" changes what counts as sleep', () => {
  // a 2h30m quiet stretch: sleep at the 2h setting, not at 3h
  const raw = { apps, sessions: [['ig', at(19, 23, 0), at(19, 23, 30)], ['ig', at(20, 2, 0), at(20, 2, 30)],
                                 ['ig', at(20, 9, 0), at(20, 9, 30)]],
                unlocks: [at(19, 23, 0), at(20, 2, 0), at(20, 9, 0)] };
  const P = FA.prepare(raw);
  FA.setOptions({ minSleepHours: 3 }, P);
  assert.equal(Math.round(FA.day(P, at(20, 12, 0)).sleep.min), 390, '3h setting finds the 02:30-09:00 stretch');
  FA.setOptions({ minSleepHours: 2 }, P);
  assert.equal(Math.round(FA.day(P, at(20, 12, 0)).sleep.min), 390, 'still picks the longest gap');
  const P2 = FA.prepare({ apps, sessions: [['ig', at(19, 23, 0), at(19, 23, 30)], ['ig', at(20, 2, 0), at(20, 2, 30)]],
                          unlocks: [at(19, 23, 0), at(20, 2, 0)] });
  FA.setOptions({ minSleepHours: 3 }, P2);
  assert.equal(FA.day(P2, at(20, 12, 0)).sleep, null, 'a 2h30m gap is not sleep at the 3h setting');
  FA.setOptions({ minSleepHours: 2 }, P2);
  assert.equal(Math.round(FA.day(P2, at(20, 12, 0)).sleep.min), 150, 'the same gap is sleep at the 2h setting');
});

t('"ask about sessions longer than" changes which sessions are offered, and Off asks nothing', () => {
  const P = FA.prepare({ apps, sessions: [['ig', at(20, 10, 0), at(20, 10, 4)], ['ig', at(20, 12, 0), at(20, 12, 7)],
                                          ['ig', at(20, 14, 0), at(20, 14, 20)]] });
  const end = at(20, 23, 0);
  FA.setOptions({ askMinMinutes: 15 }, P);
  assert.equal(FA.askCandidates(P, {}, { endMs: end }).length, 1, 'only the 20-minute session');
  FA.setOptions({ askMinMinutes: 5 }, P);
  assert.equal(FA.askCandidates(P, {}, { endMs: end }).length, 2, 'the 7- and 20-minute ones');
  FA.setOptions({ askMinMinutes: 3 }, P);
  assert.equal(FA.askCandidates(P, {}, { endMs: end }).length, 3, 'all three');
  FA.setOptions({ askMinMinutes: 0 }, P);
  assert.equal(FA.askCandidates(P, {}, { endMs: end }).length, 0, 'Off means never ask');
  FA.setOptions({ askMinMinutes: 5 }, P);
});

t('changing a setting clears cached days so numbers are recomputed', () => {
  const P = FA.prepare({ apps, sessions: [['ig', at(20, 10, 0), at(20, 10, 30)]] });
  FA.day(P, at(20, 12, 0));
  assert.ok(P.cache.size > 0);
  FA.setOptions({ minSleepHours: 4 }, P);
  assert.equal(P.cache.size, 0, 'cache cleared');
  FA.setOptions({ minSleepHours: 3 }, P);
});
console.log(`\n${pass} passed`);
