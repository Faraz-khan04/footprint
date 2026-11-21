/*
 * Footprint analytics. Pure functions over the raw events the Android app stores.
 * Runs in the app's WebView and in Node (for tests). All "days" are local-time days.
 *
 * Raw input (from the app, or the sample generator):
 * {
 *   apps:     { pkg: {n: "Instagram", cat: "Social"} },
 *   icons:    { pkg: "<base64 png>" },
 *   sessions: [[pkg, startMs, endMs], ...]   foreground app sessions
 *   unlocks:  [ms, ...]
 *   screenOn: [ms, ...],  screenOff: [ms, ...]
 *   notifs:   [[pkg, ms], ...]               notification interruptions (not every phone reports these)
 *   steps:    [[startMs, endMs, count], ...]
 *   study:    [[subject, startMs, endMs], ...]      study sessions timed in the app
 *   rules:    [[type, pkg, ms, value], ...]         intent (value = reason) and lock_kept / lock_snoozed /
 *                                                   lock_ended (value = which screen, see REASONS / SCREENS)
 * }
 */
(function (root) {
  'use strict';

  const MIN = 60000, HOUR = 60 * MIN;
  const SOCIALISH = new Set(['Social', 'Video']);
  // Asking "was that worth it?" about a phone call, or an app the user has opted out of, is noise.
  const neverAsk = () => new Set([...(OPTS.neverAsk || []), ...(OPTS.dialer ? [OPTS.dialer] : [])]);
  /** Settings from the app; changing them clears cached day summaries. */
  const OPTS = { minSleepHours: 3, askMinMinutes: 5, stepsGoal: 8000, sleepTargetMin: 7 * 60 };
  function setOptions(o, P) {
    let changed = false;
    for (const k of Object.keys(OPTS)) {
      if (o && Number.isFinite(o[k]) && o[k] !== OPTS[k]) { OPTS[k] = o[k]; changed = true; }
    }
    // Not numbers: the packages never to ask about, and the phone's dialer.
    if (o && Array.isArray(o.neverAsk) && String(o.neverAsk) !== String(OPTS.neverAsk || [])) {
      OPTS.neverAsk = o.neverAsk.slice(); changed = true;
    }
    if (o && typeof o.dialer === 'string' && o.dialer !== OPTS.dialer) { OPTS.dialer = o.dialer; changed = true; }
    if (changed && P && P.cache) P.cache.clear();
    return changed;
  }
  const DISTRACT_CATS = new Set(['Social', 'Video', 'Games']);
  const STUDY_CATS = new Set(['Productivity', 'Education']);

  // ---------- time helpers (DST-safe: always go through Date) ----------
  const startOfDay = ms => { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime(); };
  const addDays = (ms, n) => { const d = new Date(ms); d.setDate(d.getDate() + n); return d.getTime(); };
  const atHour = (dayMs, h) => { const d = new Date(dayMs); d.setHours(h, 0, 0, 0); return d.getTime(); };
  const dateKey = ms => { const d = new Date(ms); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };
  const fromKey = key => { const [y, m, d] = key.split('-').map(Number); return new Date(y, m - 1, d).getTime(); };

  /** First index in a sorted array whose key(x) >= value. */
  function lowerBound(arr, value, key) {
    let lo = 0, hi = arr.length;
    while (lo < hi) { const mid = (lo + hi) >> 1; if ((key ? key(arr[mid]) : arr[mid]) < value) lo = mid + 1; else hi = mid; }
    return lo;
  }
  const countIn = (sorted, a, b) => lowerBound(sorted, b) - lowerBound(sorted, a);
  const inRange = (sorted, a, b) => sorted.slice(lowerBound(sorted, a), lowerBound(sorted, b));

  function merge(intervals, gap) {
    const out = [];
    for (const [s, e] of intervals.slice().sort((x, y) => x[0] - y[0])) {
      if (out.length && s - out[out.length - 1][1] <= gap) out[out.length - 1][1] = Math.max(out[out.length - 1][1], e);
      else out.push([s, e]);
    }
    return out;
  }
  const mean = xs => xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : 0;
  function pearson(xs, ys) {
    const n = xs.length; if (n < 3) return 0;
    const mx = mean(xs), my = mean(ys);
    let num = 0, dx = 0, dy = 0;
    for (let i = 0; i < n; i++) { const a = xs[i] - mx, b = ys[i] - my; num += a * b; dx += a * a; dy += b * b; }
    return dx && dy ? num / Math.sqrt(dx * dy) : 0;
  }

  // ---------- preparation ----------
  function prepare(raw) {
    raw = raw || {};
    const apps = raw.apps || {};
    const catOf = pkg => (apps[pkg] && apps[pkg].cat) || 'Other';
    const sessions = (raw.sessions || [])
      .filter(s => Array.isArray(s) && s[2] > s[1])
      .map(([pkg, s, e]) => ({ pkg, s, e, cat: catOf(pkg) }))
      .sort((a, b) => a.s - b.s);
    // longest session bounds how far back a window query must look
    const maxLen = sessions.reduce((m, x) => Math.max(m, x.e - x.s), 0);
    const nums = a => (a || []).filter(Number.isFinite).slice().sort((x, y) => x - y);
    const P = {
      apps, icons: raw.icons || {}, sessions, maxLen,
      unlocks: nums(raw.unlocks), screenOn: nums(raw.screenOn), screenOff: nums(raw.screenOff),
      notifs: (raw.notifs || []).map(([pkg, t]) => ({ pkg, t })).filter(n => Number.isFinite(n.t)).sort((a, b) => a.t - b.t),
    };
    // real phone use, so a long step reading is not spread over the hours you slept (spreadSteps).
    // Screen-ons alone and a few seconds in an app are left out: an alarm or a call lights the screen
    // at night without you getting up.
    const used = sessions.filter(x => x.e - x.s >= 30000);
    const activity = nums([...used.map(x => x.s), ...used.map(x => x.e), ...P.unlocks]);
    P.steps = spreadSteps((raw.steps || []).map(([s, e, c]) => ({ s, e, c })).filter(x => Number.isFinite(x.c) && x.c > 0), activity);
    P.study = (raw.study || []).filter(x => Array.isArray(x) && x[2] > x[1])
      .map(([subj, s, e]) => ({ subj: String(subj || 'Study'), s, e })).sort((a, b) => a.s - b.s);
    P.rules = (raw.rules || []).filter(x => Array.isArray(x) && Number.isFinite(x[2]))
      .map(([type, pkg, t, v]) => ({ type, pkg, t, v: v | 0 })).sort((a, b) => a.t - b.t);
    P.hasNotifs = P.notifs.length > 0;
    const firsts = [sessions[0] && sessions[0].s, P.unlocks[0], P.steps[0] && P.steps[0].s, P.study[0] && P.study[0].s].filter(Number.isFinite);
    P.firstMs = firsts.length ? Math.min(...firsts) : null;
    P.name = pkg => (apps[pkg] && apps[pkg].n) || pkg;
    P.cat = catOf;
    P.cache = new Map();
    return P;
  }

  /**
   * The phone's step chip only gives a running total, and Android runs the background job when it
   * likes, so one reading can cover hours or days. A reading longer than SPREAD_AFTER is split over
   * the clock hours it covers (marked est), instead of landing on the day it started. Hours you were
   * asleep get a small share: a night-time hour inside a long stretch with no phone use at all
   * (QUIET_GAP or more) weighs ASLEEP_WEIGHT, every other hour 1. With no phone use recorded it is even.
   */
  const SPREAD_AFTER = 75 * MIN, QUIET_GAP = 3 * HOUR, ASLEEP_WEIGHT = 0.1;
  function spreadSteps(list, activity) {
    activity = activity || [];
    const asleep = (a, b) => {
      if (!activity.length) return false;
      const i = lowerBound(activity, a);
      if (i < activity.length && activity[i] < b) return false;            // used the phone this hour
      const prev = i > 0 ? activity[i - 1] : null, next = i < activity.length ? activity[i] : null;
      if (prev === null || next === null || next - prev < QUIET_GAP) return false;
      const h = new Date(a).getHours();
      return h >= 21 || h < 11;                                              // a quiet afternoon is not sleep
    };
    const out = [];
    for (const x of list) {
      if (!(x.e > x.s) || x.e - x.s <= SPREAD_AFTER) { out.push(x); continue; }
      const pieces = [];
      let a = x.s, total = 0;
      while (a < x.e) {
        const h = new Date(a); h.setMinutes(0, 0, 0); h.setHours(h.getHours() + 1);
        const b = Math.min(h.getTime(), x.e);
        const w = (b - a) * (asleep(a, b) ? ASLEEP_WEIGHT : 1);
        pieces.push({ s: a, e: b, w }); total += w;
        a = b;
      }
      for (const p of pieces) out.push({ s: p.s, e: p.e, c: x.c * p.w / total, est: true });
    }
    return out.sort((p, q) => p.s - q.s);
  }

  /** Sessions overlapping [a, b), clipped to it. */
  function sessionsIn(P, a, b) {
    const out = [];
    for (let i = lowerBound(P.sessions, a - P.maxLen, x => x.s); i < P.sessions.length; i++) {
      const x = P.sessions[i];
      if (x.s >= b) break;
      if (x.e <= a) continue;
      out.push({ pkg: x.pkg, cat: x.cat, s: Math.max(x.s, a), e: Math.min(x.e, b), rs: x.s, re: x.e });
    }
    return out;
  }
  const minutesIn = (P, a, b, filter) => sessionsIn(P, a, b).filter(filter || (() => true)).reduce((m, x) => m + (x.e - x.s) / MIN, 0);

  // ---------- sleep ----------
  /**
   * Sleep ending on the morning of `ds`: the longest stretch of at least 3 hours between 18:00 the day before
   * and 14:00 on the day with no app use and no unlocks. Screen-on alone doesn't count as awake, because many
   * phones light the screen for notifications. Needs real activity on both sides of the gap.
   */
  const WAKE_MAX = 15 * MIN, WAKE_QUIET = 45 * MIN;
  function sleepFor(P, ds) {
    const lo = atHour(addDays(ds, -1), 18), hi = atHour(ds, 14);
    const busy = [];
    for (const x of sessionsIn(P, lo, hi)) busy.push([x.s, x.e]);
    for (const u of inRange(P.unlocks, lo, hi)) busy.push([u, u + MIN]);
    const all = merge(busy, 0);
    // A short look at the phone in the night (a notification, the time) splits one night into two
    // shorter gaps, and then neither may count. Between 21:00 and 11:00, a burst of up to
    // WAKE_MAX with at least WAKE_QUIET of quiet on both sides is a brief wake-up, not getting up.
    const nightLo = atHour(addDays(ds, -1), 21), nightHi = atHour(ds, 11);
    const m = [], woke = [];
    for (let i = 0; i < all.length; i++) {
      const [s, e] = all[i];
      const before = i > 0 ? s - all[i - 1][1] : 0, after = i < all.length - 1 ? all[i + 1][0] - e : 0;
      if (e - s <= WAKE_MAX && before >= WAKE_QUIET && after >= WAKE_QUIET && s >= nightLo && e <= nightHi) woke.push([s, e]);
      else m.push([s, e]);
    }
    let best = null;
    for (let i = 1; i < m.length; i++) {
      const gap = m[i][0] - m[i - 1][1];
      if (gap >= OPTS.minSleepHours * HOUR && (!best || gap > best.wakeMs - best.bedMs)) best = { bedMs: m[i - 1][1], wakeMs: m[i][0] };
    }
    if (!best) return null;
    const inside = woke.filter(([s, e]) => s >= best.bedMs && e <= best.wakeMs);
    const awake = inside.reduce((t, [s, e]) => t + (e - s), 0);
    const prevMidnight = addDays(ds, -1);
    return {
      bedMs: best.bedMs, wakeMs: best.wakeMs, woke: inside.length,
      min: Math.round((best.wakeMs - best.bedMs - awake) / MIN),
      bed: Math.round((best.bedMs - prevMidnight) / MIN),   // minutes after the previous midnight
      wake: Math.round((best.wakeMs - ds) / MIN),           // minutes after this midnight
    };
  }


  // ---------- sleep, across the week ----------
  /** The last [n] nights, with the summary that makes a single number mean something. */
  function sleepWeek(P, ds, n) {
    n = n || 7;
    const nights = [];
    for (let i = n - 1; i >= 0; i--) {
      const key = addDays(startOfDay(ds), -i);
      const s = sleepFor(P, key);
      nights.push({ ds: key, ...(s || { min: 0, bed: null, wake: null }) });
    }
    const have = nights.filter(x => x.min > 0);
    // Nights shorter than the nap threshold are never detected, so report it: an empty week
    // usually means the threshold is set above how long this person actually sleeps.
    const thresholdH = OPTS.minSleepHours;
    if (!have.length) return { nights, have: 0, missing: nights.length, thresholdH };
    const avg = Math.round(have.reduce((t, x) => t + x.min, 0) / have.length);
    const beds = have.map(x => x.bed > 12 * 60 ? x.bed - 24 * 60 : x.bed);   // after-midnight bedtimes go negative
    const bedAvg = Math.round(beds.reduce((a, b) => a + b, 0) / beds.length);
    const spread = beds.length < 2 ? 0
      : Math.round(Math.sqrt(beds.reduce((t, b) => t + (b - bedAvg) ** 2, 0) / beds.length));
    const target = OPTS.sleepTargetMin || 7 * 60;
    const short = have.filter(x => x.min < target);
    return {
      nights, have: have.length, missing: nights.length - have.length, thresholdH, avgMin: avg,
      shortest: have.reduce((a, b) => (b.min < a.min ? b : a)),
      longest: have.reduce((a, b) => (b.min > a.min ? b : a)),
      bedAvgMin: bedAvg,                       // minutes past midnight; negative means before midnight
      bedSpreadMin: spread,                    // how much your bedtime wanders
      shortNights: short.length,
      debtMin: short.reduce((t, x) => t + (target - x.min), 0),
      targetMin: target
    };
  }

  /**
   * The thing only this app can answer: does screen time right before bed cost you sleep?
   * Compares nights with a lot of screen time in the hour before sleep against the rest.
   */
  function screenBeforeBed(P, ds, n) {
    n = n || 14;
    const rows = [];
    for (let i = n - 1; i >= 0; i--) {
      const key = addDays(startOfDay(ds), -i);
      const s = sleepFor(P, key);
      if (!s) continue;
      let before = 0;
      for (const x of P.sessions) {
        const from = Math.max(x.s, s.bedMs - HOUR), to = Math.min(x.e, s.bedMs);
        if (to > from) before += (to - from) / MIN;
      }
      rows.push({ ds: key, before, min: s.min, bedMs: s.bedMs });
    }
    if (rows.length < 4) return null;
    const cut = 15;                                   // a quarter of an hour on the phone counts as "on it"
    const busy = rows.filter(r => r.before >= cut), calm = rows.filter(r => r.before < cut);
    if (!busy.length || !calm.length) return null;
    const avg = list => Math.round(list.reduce((t, r) => t + r.min, 0) / list.length);
    return {
      nights: rows.length, busy: busy.length, calm: calm.length,
      busyAvg: avg(busy), calmAvg: avg(calm), diff: avg(calm) - avg(busy),
      r: pearson(rows.map(r => r.before), rows.map(r => r.min))
    };
  }

  // ---------- steps, with something to compare against ----------
  function stepsSummary(P, ds, n) {
    n = n || 7;
    const days = [];
    for (let i = n - 1; i >= 0; i--) {
      const key = addDays(startOfDay(ds), -i);
      days.push({ ds: key, steps: day(P, key).steps });
    }
    const prev = [];
    for (let i = 2 * n - 1; i >= n; i--) prev.push(day(P, addDays(startOfDay(ds), -i)).steps);
    const total = days.reduce((t, d) => t + d.steps, 0);
    const counted = days.filter(d => d.steps > 0);
    const goal = OPTS.stepsGoal || 8000;
    const hits = days.filter(d => d.steps >= goal);
    // a streak counted back from today
    let streak = 0;
    for (let i = days.length - 1; i >= 0; i--) { if (days[i].steps >= goal) streak++; else break; }
    const prevTotal = prev.reduce((t, v) => t + v, 0);
    return {
      days, total, goal,
      avg: counted.length ? Math.round(total / counted.length) : 0,
      best: days.reduce((a, b) => (b.steps > a.steps ? b : a), days[0]),
      goalDays: hits.length, streak,
      prevTotal, change: prevTotal ? Math.round(((total - prevTotal) / prevTotal) * 100) : null
    };
  }

  /** When you actually move, in three plain windows. */
  function stepsByPartOfDay(P, ds, n) {
    n = n || 7;
    const from = addDays(startOfDay(ds), -(n - 1)), to = addDays(startOfDay(ds), 1);
    const part = { morning: 0, afternoon: 0, evening: 0 };
    let guessed = 0;
    for (const x of P.steps) {
      if (x.s < from || x.s >= to) continue;
      if (x.est) { guessed += x.c; continue; }             // spread readings don't know the hour
      const h = new Date(x.s).getHours();
      if (h < 12) part.morning += x.c; else if (h < 18) part.afternoon += x.c; else part.evening += x.c;
    }
    const total = part.morning + part.afternoon + part.evening;
    // only say when you move if most of the week's steps were timed, not guessed
    if (!total || total < guessed) return null;
    const top = Object.keys(part).reduce((a, b) => (part[b] > part[a] ? b : a));
    return { ...part, total, top, topShare: Math.round((part[top] / total) * 100) };
  }

  /** The longest stretch today you were on the phone and not moving at all. */
  function stillestStretch(P, ds) {
    const start = startOfDay(ds), end = addDays(start, 1);
    const moving = new Set(), unknown = new Set();
    for (const x of P.steps) {
      if (x.s < start || x.s >= end) continue;
      const h = Math.floor((x.s - start) / HOUR);
      if (x.est) unknown.add(h); else if (x.c > 50) moving.add(h);
    }
    let best = null, run = null;
    for (let h = 0; h < 24; h++) {
      const screen = P.sessions.some(x => x.e > start + h * HOUR && x.s < start + (h + 1) * HOUR);
      if (screen && !moving.has(h) && !unknown.has(h)) { run = run || h; if (!best || h - run >= best.hours - 1) best = { from: run, hours: h - run + 1 }; }
      else run = null;
    }
    return best && best.hours >= 2 ? best : null;
  }

  // ---------- one day ----------
  function day(P, ds) {
    ds = startOfDay(ds);
    if (P.cache.has(ds)) return P.cache.get(ds);
    const de = addDays(ds, 1);
    const per = new Map(), blocks = [];
    for (const x of sessionsIn(P, ds, de)) {
      const m = (x.e - x.s) / MIN;
      per.set(x.pkg, (per.get(x.pkg) || 0) + m);
      blocks.push([(x.s - ds) / MIN, (x.e - ds) / MIN]);
    }
    const apps = [...per].map(([pkg, m]) => ({ pkg, n: P.name(pkg), cat: P.cat(pkg), m }))
      .filter(a => a.m >= 0.5).sort((a, b) => b.m - a.m);
    const stepRows = P.steps.filter(x => x.s >= ds && x.s < de);
    const steps = Math.round(stepRows.reduce((s, x) => s + x.c, 0));
    const stepsEst = stepRows.some(x => x.est);
    const unlocks = countIn(P.unlocks, ds, de);
    const sleep = sleepFor(P, ds);
    const res = {
      ds, key: dateKey(ds), apps,
      phone: merge(blocks, 2).map(([s, e]) => [s, e]),
      screenMin: apps.reduce((s, a) => s + a.m, 0),
      socialMin: apps.filter(a => SOCIALISH.has(a.cat)).reduce((s, a) => s + a.m, 0),
      steps, stepsEst, unlocks, sleep,
      hasData: apps.length > 0 || unlocks > 0 || steps > 0,
      hourly: hourly(P, ds, de),
    };
    res.why = why(P, ds, de);
    res.night = night(P, res);
    P.cache.set(ds, res);
    return res;
  }

  function hourly(P, ds, de) {
    const h = new Array(24).fill(0);
    for (const x of sessionsIn(P, ds, de)) {
      for (let t = x.s; t < x.e;) {
        const hour = new Date(t).getHours();
        const next = Math.min(x.e, atHour(ds, hour + 1));
        h[hour] += (next - t) / MIN;
        if (next <= t) break;
        t = next;
      }
    }
    return h.map(v => Math.min(60, v));
  }

  // ---------- why you picked it up ----------
  function why(P, ds, de) {
    const unlocks = inRange(P.unlocks, ds, de);
    const firstCounts = new Map();
    let zombies = 0;
    for (const u of unlocks) {
      // the app shown right after unlocking (the previous app can resume a moment before the unlock event)
      const i = lowerBound(P.sessions, u - 2000, x => x.s);
      const first = P.sessions[i];
      if (first && first.s <= u + 60000) firstCounts.set(first.pkg, (firstCounts.get(first.pkg) || 0) + 1);
      // zombie: unlocked, nothing used for 5s+, screen off within 15s
      const off = P.screenOff[lowerBound(P.screenOff, u + 1)];
      if (off !== undefined && off - u <= 15000) {
        const engaged = sessionsIn(P, u, off).some(x => x.e - x.s >= 5000);
        if (!engaged) zombies++;
      }
    }
    const firstApp = [...firstCounts].map(([pkg, c]) => ({ pkg, n: P.name(pkg), c })).sort((a, b) => b.c - a.c);

    // notification pulls: a notification, then that app opened within 2 minutes (and you weren't already in it)
    const pulls = new Map();
    for (let i = lowerBound(P.notifs, ds, n => n.t); i < P.notifs.length && P.notifs[i].t < de; i++) {
      const n = P.notifs[i];
      const rec = pulls.get(n.pkg) || { pkg: n.pkg, n: P.name(n.pkg), notifs: 0, pulled: 0 };
      rec.notifs++;
      const already = sessionsIn(P, n.t, n.t + 1).some(x => x.pkg === n.pkg);
      if (!already) {
        for (let j = lowerBound(P.sessions, n.t, x => x.s); j < P.sessions.length && P.sessions[j].s <= n.t + 2 * MIN; j++) {
          if (P.sessions[j].pkg === n.pkg) { rec.pulled++; break; }
        }
      }
      pulls.set(n.pkg, rec);
    }
    const notifPulls = [...pulls.values()].filter(r => r.pulled > 0).sort((a, b) => b.pulled - a.pulled || b.notifs - a.notifs);

    // doom-scroll: same social/video app, gaps under a minute stitched together, over 20 minutes
    const doom = [];
    const byPkg = new Map();
    for (const x of sessionsIn(P, ds, de)) {
      if (!SOCIALISH.has(x.cat)) continue;
      const list = byPkg.get(x.pkg) || []; list.push([x.s, x.e]); byPkg.set(x.pkg, list);
    }
    for (const [pkg, list] of byPkg) {
      for (const [s, e] of merge(list, MIN)) if (e - s > 20 * MIN) doom.push({ pkg, n: P.name(pkg), s, e, m: (e - s) / MIN });
    }
    doom.sort((a, b) => b.m - a.m);

    return { unlocks: unlocks.length, firstApp, zombies, notifPulls, hasNotifs: P.hasNotifs, doom };
  }

  // ---------- night and morning ----------
  function night(P, d) {
    const s = d.sleep;
    if (!s) return null;
    const inBed = minutesIn(P, s.bedMs - HOUR, s.bedMs);
    const lateNight = minutesIn(P, atHour(addDays(d.ds, -1), 22), Math.max(s.bedMs, atHour(addDays(d.ds, -1), 22)));
    const i = lowerBound(P.sessions, s.wakeMs, x => x.s);
    const firstApp = P.sessions[i] && P.sessions[i].s < s.wakeMs + 6 * HOUR ? { pkg: P.sessions[i].pkg, n: P.name(P.sessions[i].pkg) } : null;
    let reach = null;
    for (let j = i; j < P.sessions.length && P.sessions[j].s < s.wakeMs + 6 * HOUR; j++) {
      if (SOCIALISH.has(P.sessions[j].cat)) {
        reach = { min: (P.sessions[j].s - s.wakeMs) / MIN, pkg: P.sessions[j].pkg, n: P.name(P.sessions[j].pkg) };
        break;
      }
    }
    return { inBed, lateNight, firstApp, reach };
  }

  // ---------- week / range ----------
  function lastDays(P, n, endMs) {
    const end = startOfDay(endMs == null ? Date.now() : endMs);
    const out = [];
    for (let i = n - 1; i >= 0; i--) out.push(day(P, addDays(end, -i)));
    return out;
  }

  // ---------- patterns (correlations) ----------
  const MIN_DAYS = 7, MIN_GROUP = 3;
  function split(days, metric, test, outcome) {
    const yes = [], no = [], xs = [], ys = [];
    for (const d of days) {
      const m = metric(d), o = outcome(d);
      if (m == null || o == null) continue;
      (test(m) ? yes : no).push(o);
      xs.push(m); ys.push(o);
    }
    if (yes.length < MIN_GROUP || no.length < MIN_GROUP) return null;
    return { yes: mean(yes), no: mean(no), nYes: yes.length, nNo: no.length, n: xs.length, r: pearson(xs, ys) };
  }
  const strength = r => Math.abs(r) >= 0.5 ? 'strong' : Math.abs(r) >= 0.3 ? 'clear' : 'weak';

  function patterns(P, endMs) {
    // complete days only (today isn't finished), with real data, last 30
    const today = startOfDay(endMs == null ? Date.now() : endMs);
    const days = lastDays(P, 31, today).filter(d => d.ds < today && d.hasData);
    if (days.length < MIN_DAYS) return { ready: false, have: days.length, need: MIN_DAYS };
    const found = [];
    const add = (id, res, minDiff, text) => {
      if (!res) return;
      const diff = res.no - res.yes;          // positive = "yes" group is lower
      if (Math.abs(diff) < minDiff) return;
      found.push({ id, diff, text: text(diff), n: res.n, nYes: res.nYes, nNo: res.nNo, r: res.r, strength: strength(res.r), early: res.n < 14 });
    };
    const mins = x => `${Math.round(Math.abs(x))} min`;

    add('steps-social', split(days, d => d.steps, v => v >= 6000, d => d.socialMin), 10,
      diff => `On days you walked 6,000+ steps, you spent ${mins(diff)} ${diff > 0 ? 'less' : 'more'} on social and video apps.`);
    add('late-sleep', split(days, d => d.night ? d.night.lateNight : null, v => v >= 30, d => d.sleep ? d.sleep.min : null), 15,
      diff => `Nights with 30+ minutes on your phone after 10 pm gave you ${mins(diff)} ${diff > 0 ? 'less' : 'more'} sleep.`);
    add('reach-social', split(days, d => d.night && d.night.reach ? d.night.reach.min : null, v => v <= 15, d => d.socialMin), 10,
      diff => `When a social app came within 15 minutes of waking, you spent ${mins(diff)} ${diff > 0 ? 'less' : 'more'} on social apps that day.`);
    add('sleep-screen', split(days, d => d.sleep ? d.sleep.min : null, v => v < 360, d => d.screenMin), 15,
      diff => `After nights under 6 hours of sleep, your screen time was ${mins(diff)} ${diff > 0 ? 'lower' : 'higher'}.`);
    add('zombie-screen', split(days, d => d.why.zombies, v => v >= 10, d => d.screenMin), 20,
      diff => `On days with 10+ zombie unlocks, your screen time was ${mins(diff)} ${diff > 0 ? 'lower' : 'higher'}.`);

    found.sort((a, b) => Math.abs(b.r) - Math.abs(a.r));
    return { ready: true, days: days.length, items: found };
  }

  // ---------- exam mode ----------
  function classify(P, pkg, cfg) {
    const o = cfg && cfg.labels && cfg.labels[pkg];
    if (o === 'study' || o === 'distract' || o === 'neutral') return o;
    const c = P.cat(pkg);
    return STUDY_CATS.has(c) ? 'study' : DISTRACT_CATS.has(c) ? 'distract' : 'neutral';
  }
  function exam(P, cfg, endMs) {
    if (!cfg || !cfg.date) return null;
    const today = startOfDay(endMs == null ? Date.now() : endMs);
    const examDay = fromKey(cfg.date);
    const daysLeft = Math.round((examDay - today) / 86400000);
    const week = lastDays(P, 7, today).map(d => {
      let study = 0, distract = 0;
      for (const a of d.apps) {
        const k = classify(P, a.pkg, cfg);
        if (k === 'study') study += a.m; else if (k === 'distract') distract += a.m;
      }
      return { key: d.key, ds: d.ds, study, distract };
    });
    const t = week[week.length - 1];
    const sum = k => week.reduce((s, d) => s + d[k], 0);
    return { name: cfg.name || 'Exam', date: cfg.date, daysLeft, today: t, week, studyWeek: sum('study'), distractWeek: sum('distract') };
  }

  /**
   * Exam mode with more than a countdown: several exams, a daily study target, how long the
   * habit has held, and where the week is heading.
   *
   * cfg: { exams: [{name, date}], targetMin, labels, startedMs } — a single {name,date} is
   * still accepted, so plans made by the older version keep working.
   */
  function examPlan(P, cfg, endMs) {
    if (!cfg) return null;
    const list = (cfg.exams && cfg.exams.length ? cfg.exams : (cfg.date ? [{ name: cfg.name, date: cfg.date }] : []))
      .filter(x => x && x.date)
      .map(x => ({ name: x.name || 'Exam', date: x.date, ds: fromKey(x.date) }))
      .sort((a, b) => a.ds - b.ds);
    if (!list.length) return null;

    const today = startOfDay(endMs == null ? Date.now() : endMs);
    const dayOf = d => {
      let study = 0, distract = 0;
      for (const a of d.apps) {
        const k = classify(P, a.pkg, cfg);
        if (k === 'study') study += a.m; else if (k === 'distract') distract += a.m;
      }
      // Timed sessions count as study too. A study app used during a timed session is only counted once.
      const de = addDays(d.ds, 1);
      const timer = timedIn(P, d.ds, de);
      let overlap = 0;
      if (timer.length) {
        for (const x of sessionsIn(P, d.ds, de)) {
          if (classify(P, x.pkg, cfg) !== 'study') continue;
          for (const [a, b] of timer) overlap += Math.max(0, Math.min(x.e, b) - Math.max(x.s, a)) / MIN;
        }
      }
      const timed = timer.reduce((m, [a, b]) => m + (b - a) / MIN, 0);
      return { key: d.key, ds: d.ds, study: Math.max(0, study - overlap) + timed, distract, timed };
    };

    const upcoming = list.filter(x => x.ds >= today);
    const next = upcoming[0] || list[list.length - 1];
    const daysLeft = Math.round((next.ds - today) / 86400000);
    const target = cfg.targetMin || 0;

    const week = lastDays(P, 7, today).map(dayOf);
    const t = week[week.length - 1];

    // how long the target has been met, counting back from today
    let streak = 0;
    if (target > 0) {
      const back = lastDays(P, 30, today).map(dayOf);
      for (let i = back.length - 1; i >= 0; i--) { if (back[i].study >= target) streak++; else break; }
    }

    // since exam mode was turned on
    const startedDs = cfg.startedMs ? startOfDay(cfg.startedMs) : week[0].ds;
    const spanDays = Math.max(1, Math.round((today - startedDs) / 86400000) + 1);
    const since = lastDays(P, Math.min(spanDays, 60), today).map(dayOf).filter(d => d.ds >= startedDs);
    const sum = (list, k) => list.reduce((a, d) => a + d[k], 0);
    const studySince = sum(since, 'study'), distractSince = sum(since, 'distract');
    const perDay = since.length ? studySince / since.length : 0;

    return {
      exams: list, next, name: next.name, date: next.date, daysLeft,
      today: t, week, target, streak,
      studyWeek: sum(week, 'study'), distractWeek: sum(week, 'distract'),
      days: since.length, studySince, distractSince,
      perDay: Math.round(perDay),
      // at the current daily rate, what is left before the exam
      projectedMin: daysLeft > 0 ? Math.round(perDay * daysLeft) : 0,
      bestDay: since.length ? since.reduce((a, b) => (b.study > a.study ? b : a)) : null
    };
  }

  // ---------- study timer ----------
  /** Timed study intervals overlapping [a, b), clipped and merged, as [[s, e], ...]. */
  function timedIn(P, a, b) {
    const out = [];
    for (const x of P.study) {
      if (x.s >= b) break;
      if (x.e <= a) continue;
      out.push([Math.max(x.s, a), Math.min(x.e, b)]);
    }
    return merge(out, 0);
  }

  /**
   * What the study timer has recorded: today, the last 7 days, and time per subject this week.
   * [running] is a session in progress ({start, subject}), shown in today's number as it goes.
   */
  function studySummary(P, endMs, running) {
    const now = endMs == null ? Date.now() : endMs;
    const ds = startOfDay(now), de = addDays(ds, 1), weekStart = addDays(ds, -6);
    const list = P.study.slice();
    if (running && running.start && running.start < now) list.push({ subj: running.subject || 'Study', s: running.start, e: now });
    const clip = (x, a, b) => Math.max(0, Math.min(x.e, b) - Math.max(x.s, a)) / MIN;
    let today = 0, sessionsToday = 0, week = 0;
    const subj = new Map();
    const perDay = new Array(7).fill(0);
    for (const x of list) {
      const t = clip(x, ds, de);
      if (t > 0) { today += t; sessionsToday++; }
      const w = clip(x, weekStart, de);
      if (w > 0) {
        week += w;
        subj.set(x.subj, (subj.get(x.subj) || 0) + w);
        for (let i = 0; i < 7; i++) { const a = addDays(weekStart, i); perDay[i] += clip(x, a, addDays(a, 1)); }
      }
    }
    let streak = 0;
    for (let i = 6; i >= 0; i--) { if (perDay[i] >= 1) streak++; else if (i === 6) continue; else break; }
    return {
      today: Math.round(today), sessionsToday, week: Math.round(week),
      perDay: perDay.map(Math.round), streak,
      subjects: [...subj].map(([name, m]) => ({ name, m: Math.round(m) })).sort((a, b) => b.m - a.m),
    };
  }

  // ---------- what you told the rule screens ----------
  const REASONS = { 0: 'Changed my mind', 1: 'Reply to someone', 2: 'Something specific', 3: 'Bored', 4: 'Just habit' };
  const SCREENS = { 0: 'Daily limit', 1: 'Too many opens', 2: 'Break', 3: 'Blocked time', 4: 'Study session' };

  /**
   * Answers to "why are you opening this?" and what happened on each kind of rule screen,
   * over [a, b). "Held" means you closed the app (or stayed studying).
   */
  function ruleStats(P, a, b) {
    const reasons = new Map(), apps = new Map(), screens = new Map(), reels = new Map();
    let asked = 0, idle = 0, reelsTotal = 0;
    for (const x of P.rules) {
      if (x.t < a) continue;
      if (x.t >= b) break;
      if (x.type === 'intent') {
        asked++;
        reasons.set(x.v, (reasons.get(x.v) || 0) + 1);
        const r = apps.get(x.pkg) || { pkg: x.pkg, n: P.name(x.pkg), asked: 0, idle: 0, closed: 0 };
        r.asked++;
        if (x.v === 3 || x.v === 4) { r.idle++; idle++; }
        if (x.v === 0) r.closed++;
        apps.set(x.pkg, r);
      } else if (x.type === 'lock_kept' || x.type === 'lock_snoozed' || x.type === 'lock_ended' || x.type === 'lock_walked') {
        const r = screens.get(x.v) || { mode: x.v, name: SCREENS[x.v] || 'Rule', held: 0, gave: 0, walked: 0 };
        if (x.type === 'lock_kept') r.held++; else if (x.type === 'lock_walked') r.walked++; else r.gave++;
        screens.set(x.v, r);
      } else if (x.type === 'reels_blocked') {
        reelsTotal++;
        const name = REELS[x.v] || 'Short videos';
        reels.set(name, (reels.get(name) || 0) + 1);
      }
    }
    return {
      asked, idle,
      closed: reasons.get(0) || 0,
      reasons: [...reasons].map(([v, c]) => ({ v, name: REASONS[v] || 'Other', c })).sort((x, y) => y.c - x.c),
      apps: [...apps.values()].sort((x, y) => y.asked - x.asked),
      screens: [...screens.values()].sort((x, y) => (y.held + y.gave + y.walked) - (x.held + x.gave + x.walked)),
      reels: [...reels].map(([name, c]) => ({ name, c })).sort((x, y) => y.c - x.c),
      reelsTotal,
    };
  }

  // ---------- weekly review ----------
  const REELS = { 0: 'YouTube Shorts', 1: 'Instagram Reels', 2: 'Snapchat Spotlight' };

  /** One number over the days [a, b): what an experiment is judged on. */
  function metric(P, name, a, b, app) {
    const days = [];
    for (let d = startOfDay(a); d < b; d = addDays(d, 1)) days.push(day(P, d));
    const sum = f => days.reduce((t, d) => t + f(d), 0);
    switch (name) {
      case 'screen': return sum(d => d.screenMin);
      case 'social': return sum(d => d.socialMin);
      case 'late': return sum(d => d.hourly[23] + d.hourly[0] + d.hourly[1] + d.hourly[2] + d.hourly[3] + d.hourly[4]);
      case 'unlocks': return sum(d => d.unlocks);
      case 'zombies': return sum(d => d.why.zombies);
      case 'timed': return timedIn(P, a, b).reduce((m, [x, y]) => m + (y - x) / MIN, 0);
      case 'app': return sum(d => { const r = d.apps.find(x => x.pkg === app); return r ? r.m : 0; });
      default: return 0;
    }
  }

  /**
   * This week (the 7 days ending on endMs's day) against the 7 before it.
   * opts: { model, labels } for regretted minutes, when the worth-it model is ready.
   */
  function weekReview(P, endMs, opts) {
    const o = opts || {};
    const end = addDays(startOfDay(endMs == null ? Date.now() : endMs), 1);
    const mid = addDays(end, -7), start = addDays(end, -14);
    const side = (a, b) => {
      const days = [];
      for (let d = a; d < b; d = addDays(d, 1)) days.push(day(P, d));
      const withData = days.filter(d => d.hasData);
      const sleeps = days.map(d => d.sleep).filter(Boolean);
      const steps = days.filter(d => d.steps > 0);
      const rs = ruleStats(P, a, b);
      const held = rs.screens.reduce((t, x) => t + x.held, 0), shown = rs.screens.reduce((t, x) => t + x.held + x.gave + x.walked, 0);
      let regret = null;
      if (o.model && o.model.ready) regret = days.reduce((t, d) => t + dayRegret(P, o.model, o.labels, d.ds).min, 0);
      const apps = new Map();
      for (const d of days) for (const x of d.apps) apps.set(x.pkg, (apps.get(x.pkg) || 0) + x.m);
      return {
        days: withData.length,
        screen: metric(P, 'screen', a, b), social: metric(P, 'social', a, b), late: metric(P, 'late', a, b),
        unlocks: metric(P, 'unlocks', a, b), zombies: metric(P, 'zombies', a, b), timed: metric(P, 'timed', a, b),
        steps: steps.length ? steps.reduce((t, d) => t + d.steps, 0) / steps.length : null,
        sleep: sleeps.length ? sleeps.reduce((t, x) => t + x.min, 0) / sleeps.length : null,
        held, shown, reels: rs.reelsTotal, asked: rs.asked, idle: rs.idle, regret, apps,
      };
    };
    const cur = side(mid, end), prev = side(start, mid);
    const top = [...cur.apps].sort((x, y) => y[1] - x[1]).slice(0, 3)
      .map(([pkg, m]) => ({ pkg, n: P.name(pkg), m, prev: prev.apps.get(pkg) || 0 }));
    delete cur.apps; delete prev.apps;
    return { from: mid, to: end, cur, prev, top, enough: cur.days >= 3 };
  }

  /**
   * Small things to try next week, best fit first, picked from what the week shows. Each comes with the number
   * it will be judged on, so next week's review can say whether it worked.
   */
  function pickExperiment(P, review, endMs) {
    const c = review.cur, days = Math.max(1, c.days);
    const perDay = x => x / days;
    const heavy = review.top.find(t => SOCIALISH.has(P.cat(t.pkg)) && perDay(t.m) >= 45);
    const options = [];
    if (perDay(c.late) >= 20) options.push({ id: 'night', metric: 'late', dir: -1, weight: perDay(c.late),
      title: 'A phone-free night', how: `Add a night blocked time from 23:30 in Settings. You spent about ${Math.round(perDay(c.late))} minutes a night on your phone after 11 pm.` });
    if (heavy) options.push({ id: 'limit', metric: 'app', app: heavy.pkg, dir: -1, weight: perDay(heavy.m) * 0.8,
      title: `A limit on ${heavy.n}`, how: `Set a daily limit of ${Math.max(15, Math.round(perDay(heavy.m) * 0.7 / 5) * 5)} minutes on ${heavy.n}, about 30% under your average of ${Math.round(perDay(heavy.m))}.` });
    if (perDay(c.zombies) >= 12) options.push({ id: 'why', metric: 'zombies', dir: -1, weight: perDay(c.zombies) * 1.5,
      title: 'Ask why before opening', how: `You unlocked and put the phone down ${Math.round(perDay(c.zombies))} times a day. Turn on "Ask why before opening" for your most-opened app.` });
    if (c.timed < 60) options.push({ id: 'study', metric: 'timed', dir: 1, weight: 20,
      title: 'One timed study session a day', how: 'Start a 25-minute session in the Exam tab once a day. Your distracting apps lock until it ends.' });
    options.push({ id: 'social', metric: 'social', dir: -1, weight: 1,
      title: 'Social apps after lunch only', how: 'Add a blocked time for your distracting apps from waking up until 13:00.' });
    options.sort((x, y) => y.weight - x.weight);
    // Best fit first. Each carries its starting number (per day this week) and starts tomorrow.
    return options.map(o => Object.assign({}, o, { baseline: metric(P, o.metric, review.from, review.to, o.app) / days, start: review.to }));
  }

  /** How an experiment went: its number per day since it started, against the baseline. */
  function experimentResult(P, exp, endMs) {
    if (!exp || !exp.start) return null;
    const end = Math.min(addDays(startOfDay(endMs == null ? Date.now() : endMs), 1), addDays(exp.start, 7));
    const days = Math.round((end - exp.start) / 86400000);
    if (days < 1) return { days: 0, done: false };
    const now = metric(P, exp.metric, exp.start, end, exp.app) / days;
    const change = now - exp.baseline;
    const better = exp.dir < 0 ? change < 0 : change > 0;
    return { days, done: days >= 7, now, baseline: exp.baseline, change, better,
             same: Math.abs(change) < Math.max(1, Math.abs(exp.baseline) * 0.05) };
  }

  // ---------- weekly receipt ----------
  function receipt(P, endMs) {
    const days = lastDays(P, 7, endMs);
    const withData = days.filter(d => d.hasData);
    const apps = new Map();
    const firsts = new Map();
    let unlocks = 0, zombies = 0, steps = 0, longestDoom = null;
    for (const d of days) {
      for (const a of d.apps) apps.set(a.pkg, (apps.get(a.pkg) || 0) + a.m);
      for (const f of d.why.firstApp) firsts.set(f.pkg, (firsts.get(f.pkg) || 0) + f.c);
      unlocks += d.unlocks; zombies += d.why.zombies; steps += d.steps;
      for (const x of d.why.doom) if (!longestDoom || x.m > longestDoom.m) longestDoom = { ...x, key: d.key };
    }
    const top = [...apps].map(([pkg, m]) => ({ pkg, n: P.name(pkg), m })).sort((a, b) => b.m - a.m).slice(0, 5);
    const firstTop = [...firsts].map(([pkg, c]) => ({ pkg, n: P.name(pkg), c })).sort((a, b) => b.c - a.c)[0] || null;
    const sleeps = days.map(d => d.sleep && d.sleep.min).filter(v => v != null);
    const screen = days.reduce((s, d) => s + d.screenMin, 0);
    const calmest = withData.length ? withData.reduce((a, b) => (b.screenMin < a.screenMin ? b : a)) : null;
    return {
      from: days[0].key, to: days[days.length - 1].key, daysWithData: withData.length,
      screen, avgScreen: withData.length ? screen / withData.length : 0,
      top, unlocks, zombies, steps, firstTop, longestDoom,
      avgSleep: sleeps.length ? mean(sleeps) : null,
      calmest: calmest ? { key: calmest.key, ds: calmest.ds, m: calmest.screenMin } : null,
    };
  }


  // ---------- "Worth it?": learning which sessions you regret ----------
  /*
   * Research on smartphone overuse finds that regret, not duration, is what people actually want less
   * of: a long call with a friend isn't the problem, a 40-minute 11pm scroll is. So we ask about a
   * few sessions, learn the contexts you regret, and warn before the next one like them.
   *
   * labels: { "<pkg>|<startMs>": 1 (not worth it) | 0 (worth it) }
   */
  const LABEL_KEY = s => `${s.pkg}|${s.s}`;

  /** Context of one session: the things that might explain regret. */
  function contextOf(P, s, sleepByDay) {
    const d = new Date(s.s), hour = d.getHours();
    const late = hour >= 22 || hour < 2;
    let notif = false;
    for (let i = lowerBound(P.notifs, s.s - 2 * MIN, n => n.t); i < P.notifs.length && P.notifs[i].t <= s.s; i++) {
      if (P.notifs[i].pkg === s.pkg) { notif = true; break; }
    }
    const sleep = sleepByDay ? sleepByDay(startOfDay(s.s)) : day(P, s.s).sleep;
    const morning = !!(sleep && s.s >= sleep.wakeMs && s.s - sleep.wakeMs <= 30 * MIN);
    return { pkg: s.pkg, cat: s.cat, hour, late, notif, morning, min: (s.e - s.s) / MIN };
  }

  /** Sessions long enough to be worth asking about, newest first, unlabelled only. */
  function askCandidates(P, labels, opts) {
    const o = Object.assign({ days: 7, max: 12, endMs: null }, opts || {});
    const end = o.endMs == null ? Date.now() : o.endMs;
    const from = addDays(startOfDay(end), -(o.days - 1));
    const out = [];
    const skip = neverAsk();
    for (let i = P.sessions.length - 1; i >= 0; i--) {
      const s = P.sessions[i];
      if (s.s < from) break;
      if (s.e > end - 2 * MIN) continue;                       // let a session finish before asking
      const len = s.e - s.s;
      if (!OPTS.askMinMinutes) return [];                      // asking turned off in Settings
      if (len < OPTS.askMinMinutes * MIN) continue;
      if (!SOCIALISH.has(s.cat) && len < Math.max(8, OPTS.askMinMinutes) * MIN) continue;   // chat/work only if long
      if (labels && LABEL_KEY(s) in labels) continue;
      if (skip.has(s.pkg)) continue;                           // calls, and apps you opted out of
      const hour = new Date(s.s).getHours();
      const late = hour >= 22 || hour < 2;
      // the answers worth asking for: long ones, social ones, late ones, recent ones
      const score = len / MIN + (late ? 20 : 0) + (SOCIALISH.has(s.cat) ? 10 : 0) +
        (s.s >= addDays(startOfDay(end), -1) ? 10 : 0);
      out.push({ key: LABEL_KEY(s), pkg: s.pkg, n: P.name(s.pkg), cat: s.cat, s: s.s, e: s.e,
                 min: len / MIN, late, score });
    }
    // An unusual session teaches more than a typical one, so say how it compares with that app's norm.
    const typical = typicalMinutes(P);
    for (const c of out) {
      const t = typical.get(c.pkg);
      c.typicalMin = t || 0;
      if (t && c.min >= t * 1.5) { c.reason = `longer than your usual ${Math.round(t)}m on ${c.n}`; c.score += 15; }
      else if (c.late) c.reason = 'late at night';
      else c.reason = null;
    }
    out.sort((a, b) => b.score - a.score);
    return spreadApps(out).slice(0, o.max);
  }


  /**
   * How a day's zombie unlocks compare with the days around it. A number alone means little;
   * "2 today, you usually have 5" is the part worth reading.
   */
  function zombieCompare(days, ds) {
    const i = days.findIndex(d => d.ds === ds);
    if (i < 0) return null;
    const past = days.slice(Math.max(0, i - 7), i).filter(d => d.unlocks > 0);
    if (past.length < 3) return null;                      // not enough history to compare against
    const avg = past.reduce((t, d) => t + d.why.zombies, 0) / past.length;
    const today = days[i].why.zombies;
    const diff = today - avg;
    const near = Math.abs(diff) < Math.max(1, avg * 0.2);
    return { avg: Math.round(avg * 10) / 10, today, diff, level: near ? 'same' : diff < 0 ? 'better' : 'worse' };
  }

  /** The middle session length for each app, used to spot a session that is unusual for it. */
  function typicalMinutes(P) {
    const byApp = new Map();
    for (const s of P.sessions) {
      if (!byApp.has(s.pkg)) byApp.set(s.pkg, []);
      byApp.get(s.pkg).push((s.e - s.s) / MIN);
    }
    const out = new Map();
    for (const [pkg, list] of byApp) {
      if (list.length < 4) continue;                  // too few to call anything typical
      list.sort((a, b) => a - b);
      out.set(pkg, list[Math.floor(list.length / 2)]);
    }
    return out;
  }

  /**
   * Reorder so the same app is not asked about twice in a row: answers spread over apps teach more.
   * Keeps every item, and keeps each app's own order (best score first).
   */
  function spreadApps(list) {
    const groups = new Map();
    for (const c of list) {
      if (!groups.has(c.pkg)) groups.set(c.pkg, []);
      groups.get(c.pkg).push(c);
    }
    const out = [];
    let last = null;
    while (out.length < list.length) {
      // the app with the most left to ask about, skipping the one just used
      let pick = null;
      for (const [pkg, items] of groups) {
        if (!items.length || pkg === last) continue;
        if (!pick || items.length > groups.get(pick).length) pick = pkg;
      }
      if (pick === null) pick = [...groups].find(([, items]) => items.length)[0];   // only one app left
      out.push(groups.get(pick).shift());
      last = pick;
    }
    return out;
  }

  const KEYS_FOR = ctx => [
    ctx.notif ? `${ctx.pkg}|notif` : null,
    ctx.late ? `${ctx.pkg}|late` : null,
    ctx.morning ? `${ctx.pkg}|morning` : null,
    `${ctx.pkg}`,
    ctx.late ? `cat:${ctx.cat}|late` : `cat:${ctx.cat}`,
    'all',
  ].filter(Boolean);

  const WHY = {
    notif: n => `${n} opened from a notification`,
    late: n => `${n} after 10 pm`,
    morning: n => `${n} right after waking`,
  };

  /** Counts of regretted vs worth-it sessions per context key. */
  function regretModel(P, labels) {
    const keys = new Map();
    let labelled = 0, regretted = 0;
    const sleepCache = new Map();
    const sleepByDay = ds => { if (!sleepCache.has(ds)) sleepCache.set(ds, day(P, ds).sleep); return sleepCache.get(ds); };
    for (const s of P.sessions) {
      const key = LABEL_KEY(s);
      if (!labels || !(key in labels)) continue;
      const bad = labels[key] ? 1 : 0;
      labelled++; regretted += bad;
      for (const k of KEYS_FOR(contextOf(P, s, sleepByDay))) {
        const rec = keys.get(k) || { n: 0, bad: 0 };
        rec.n++; rec.bad += bad; keys.set(k, rec);
      }
    }
    return { keys, labelled, regretted, ready: labelled >= 8, sleepByDay };
  }

  const MIN_KEY_N = 3;
  /** Chance this session is one you'd regret, with the reason, or null when we can't say yet. */
  function predictRegret(P, model, session) {
    if (!model || !model.ready) return null;
    const ctx = session.hour != null ? session : contextOf(P, session, model.sleepByDay);
    for (const k of KEYS_FOR(ctx)) {
      const rec = model.keys.get(k);
      if (!rec || rec.n < MIN_KEY_N) continue;
      const p = (rec.bad + 0.5) / (rec.n + 1);
      const flavour = k.split('|')[1];
      const name = P.name(ctx.pkg);
      const what = k === 'all' ? 'Sessions like this'
        : k.startsWith('cat:') ? `${ctx.cat} apps${ctx.late ? ' after 10 pm' : ''}`
          : WHY[flavour] ? WHY[flavour](name) : name;
      return { p, n: rec.n, bad: rec.bad, key: k, ctx, why: `${what}: you marked ${rec.bad} of ${rec.n} not worth it.` };
    }
    return null;
  }

  const REGRET_CUT = 0.6;
  /** Minutes of a day you marked as regret, plus minutes the model expects you'd regret. */
  function dayRegret(P, model, labels, ds) {
    ds = startOfDay(ds);
    let labelledMin = 0, predictedMin = 0, total = 0;
    for (const s of sessionsIn(P, ds, addDays(ds, 1))) {
      const len = (s.e - s.s) / MIN;
      total += len;
      const key = `${s.pkg}|${s.rs}`;
      if (labels && key in labels) { if (labels[key]) labelledMin += len; continue; }
      const pred = predictRegret(P, model, { pkg: s.pkg, cat: s.cat, s: s.rs, e: s.re });
      if (pred && pred.p >= REGRET_CUT) predictedMin += len;
    }
    return { labelledMin, predictedMin, total, min: labelledMin + predictedMin };
  }

  /** Times of day your regretted sessions start, so the app can warn you just before. */
  function riskWindows(P, labels, endMs) {
    if (!labels) return [];
    const buckets = new Map();
    for (const s of P.sessions) {
      const key = LABEL_KEY(s);
      if (!labels[key]) continue;                              // only the ones marked "not worth it"
      const d = new Date(s.s);
      const slot = d.getHours();
      const rec = buckets.get(slot) || { slot, count: 0, min: 0, startMin: 0, apps: new Map() };
      rec.count++; rec.min += (s.e - s.s) / MIN; rec.startMin += d.getMinutes();
      rec.apps.set(s.pkg, (rec.apps.get(s.pkg) || 0) + 1);
      buckets.set(slot, rec);
    }
    return [...buckets.values()]
      .filter(b => b.count >= 2)
      .sort((a, b) => b.count - a.count || b.min - a.min)
      .slice(0, 3)
      .map(b => {
        const [pkg] = [...b.apps].sort((x, y) => y[1] - x[1])[0];
        const minute = Math.floor(b.startMin / b.count / 10) * 10;      // typical start, to ten minutes
        return {
          minuteOfDay: b.slot * 60 + minute, hour: b.slot, minute,
          count: b.count, avgMin: b.min / b.count, pkg, n: P.name(pkg),
        };
      });
  }

  /** Which apps' notifications are actually costing you time. */
  function notifTriage(P, days, endMs) {
    days = days || 7;
    const end = endMs == null ? Date.now() : endMs;
    const from = addDays(startOfDay(end), -(days - 1));
    const per = new Map();
    for (let i = lowerBound(P.notifs, from, n => n.t); i < P.notifs.length; i++) {
      const n = P.notifs[i];
      if (n.t > end) break;
      const rec = per.get(n.pkg) || { pkg: n.pkg, n: P.name(n.pkg), cat: P.cat(n.pkg), notifs: 0, pulls: 0, min: 0 };
      rec.notifs++;
      const already = sessionsIn(P, n.t, n.t + 1).some(x => x.pkg === n.pkg);
      if (!already) {
        for (let j = lowerBound(P.sessions, n.t, x => x.s); j < P.sessions.length && P.sessions[j].s <= n.t + 2 * MIN; j++) {
          if (P.sessions[j].pkg === n.pkg) { rec.pulls++; rec.min += (P.sessions[j].e - P.sessions[j].s) / MIN; break; }
        }
      }
      per.set(n.pkg, rec);
    }
    return [...per.values()].map(r => ({ ...r, rate: r.notifs ? r.pulls / r.notifs : 0 }))
      .sort((a, b) => b.min - a.min || b.pulls - a.pulls);
  }

  // ---------- sample data (for previews without the app) ----------
  function sample(endMs, nDays) {
    nDays = nDays || 30;
    let seed = 1234567;
    const r = () => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed / 4294967296; };
    const apps = {
      'com.instagram.android': { n: 'Instagram', cat: 'Social' }, 'com.google.android.youtube': { n: 'YouTube', cat: 'Video' },
      'com.whatsapp': { n: 'WhatsApp', cat: 'Chat' }, 'com.reddit.frontpage': { n: 'Reddit', cat: 'Social' },
      'com.spotify.music': { n: 'Spotify', cat: 'Music' }, 'org.telegram.messenger': { n: 'Telegram', cat: 'Chat' },
      'com.brave.browser': { n: 'Brave', cat: 'Browser' }, 'com.google.android.apps.classroom': { n: 'Classroom', cat: 'Education' },
      'com.duolingo': { n: 'Duolingo', cat: 'Education' }, 'com.google.android.apps.maps': { n: 'Maps', cat: 'Maps' },
    };
    const weights = [['com.instagram.android', 8], ['com.google.android.youtube', 5], ['com.whatsapp', 7], ['com.reddit.frontpage', 3],
      ['com.spotify.music', 2], ['org.telegram.messenger', 2], ['com.brave.browser', 3], ['com.google.android.apps.classroom', 2],
      ['com.duolingo', 1], ['com.google.android.apps.maps', 1]];
    const total = weights.reduce((s, w) => s + w[1], 0);
    const pick = () => { let x = r() * total; for (const [p, w] of weights) { if ((x -= w) <= 0) return p; } return weights[0][0]; };
    const out = { apps, icons: {}, sessions: [], unlocks: [], screenOn: [], screenOff: [], notifs: [], steps: [], study: [], rules: [] };
    const end = startOfDay(endMs == null ? Date.now() : endMs);
    const now = endMs == null ? Date.now() : endMs;
    for (let i = nDays - 1; i >= 0; i--) {
      const ds = addDays(end, -i);
      const active = r() < 0.5;                                        // some days you walk more
      const wake = ds + (6.5 + r() * 2) * HOUR;
      const bed = addDays(ds, 1) + (-1 + r() * 2.5) * HOUR;            // 23:00 to 01:30
      let t = wake;
      while (t < bed && t < now) {
        const on = t;
        out.screenOn.push(on);
        const zombie = r() < 0.18;
        out.unlocks.push(on + 800);
        let u = on + 1500;
        if (!zombie) {
          const n = 1 + Math.floor(r() * 3);
          for (let k = 0; k < n; k++) {
            const pkg = pick();
            const doom = SOCIALISH.has(apps[pkg].cat) && r() < (bed - u < 2 * HOUR ? 0.35 : 0.12);
            const len = doom ? (18 + r() * 35) * MIN : (0.4 + r() * 9) * MIN;
            const e = Math.min(u + len, now);
            if (e > u) out.sessions.push([pkg, u, e]);
            u = e + 2000;
          }
        } else {
          u = on + 6000 + r() * 6000;
        }
        out.screenOff.push(u);
        // notifications while the screen is off; some pull you back in
        const gap = (active ? 14 : 8) * MIN + r() * 40 * MIN;
        if (r() < 0.55) {
          const pkg = r() < 0.5 ? 'com.whatsapp' : r() < 0.6 ? 'com.instagram.android' : 'com.reddit.frontpage';
          const nt = u + gap * r();
          out.notifs.push([pkg, nt]);
          if (r() < 0.45 && nt + 60000 < bed) { t = nt + 20000 + r() * 50000; out.sessions.push([pkg, t + 1500, t + 1500 + (0.5 + r() * 4) * MIN]); out.unlocks.push(t + 800); out.screenOn.push(t); u = t + 1500 + 5 * MIN; out.screenOff.push(u); }
        }
        t = u + gap;
      }
      // an evening study session on most days, and a few answers to the rule screens
      if (r() < 0.7) {
        const s0 = ds + (17 + r() * 3) * HOUR, len = (r() < 0.5 ? 25 : 50) * MIN;
        if (s0 + len < now) out.study.push([r() < 0.5 ? 'DBMS' : r() < 0.5 ? 'Operating Systems' : 'Maths', s0, s0 + len]);
      }
      for (let k = 0; k < 4; k++) {
        const at = ds + (9 + r() * 13) * HOUR;
        if (at >= now) continue;
        if (r() < 0.6) out.rules.push(['intent', 'com.instagram.android', at, Math.floor(r() * 5)]);
        else if (r() < 0.25) out.rules.push(['reels_blocked', r() < 0.5 ? 'com.instagram.android' : 'com.google.android.youtube', at, Math.floor(r() * 2)]);
        else out.rules.push([r() < 0.5 ? 'lock_kept' : r() < 0.6 ? 'lock_snoozed' : 'lock_walked', r() < 0.5 ? 'com.instagram.android' : 'com.google.android.youtube', at, Math.floor(r() * 4)]);
      }
      for (let h = 7; h < 22; h++) {
        const s = ds + h * HOUR;
        if (s > now) break;
        out.steps.push([s, s + HOUR, Math.round((active ? 700 : 250) * r() * (h > 9 && h < 20 ? 1.4 : 0.6))]);
      }
    }
    return out;
  }


  // ---------- collection health ----------
  // Android keeps usage events for about a week. A short gap backfills on the next run;
  // a gap longer than that means some of the time can never be recovered.
  const RECOVERABLE_MS = 6 * 24 * 3600 * 1000;   // stay inside the system's window
  function collectionHealth(now, lastCollect, opts) {
    const o = opts || {};
    if (!o.ready) return { level: 'ok' };                       // setup isn't finished; other banners cover it
    if (!lastCollect) return { level: 'ok' };                   // nothing read yet: first run
    const gap = now - lastCollect;
    if (gap < 3 * 3600 * 1000) return { level: 'ok', gap };     // a couple of missed runs is normal
    const lost = gap > RECOVERABLE_MS;
    return { level: lost ? 'lost' : 'stale', gap, hours: Math.floor(gap / 3600000), lost,
             unrestricted: o.unrestricted !== false };
  }


  // ---------- small apps ----------
  // A day has a long tail of apps opened for a minute. They belong in the total, but listing
  // each one buries the apps that matter, so the tail is folded into one "Others" row.
  function splitOthers(apps, opts) {
    const o = opts || {};
    const minMin = o.minMin == null ? 2 : o.minMin;     // below this a row is not worth its space
    const maxRows = o.maxRows == null ? 12 : o.maxRows; // and never more rows than this
    const sorted = apps.slice().sort((a, b) => b.m - a.m);
    let cut = sorted.findIndex(a => a.m < minMin);
    if (cut < 0) cut = sorted.length;
    if (cut > maxRows) cut = maxRows;
    const rest = sorted.slice(cut);
    if (rest.length < 2) return { shown: sorted, others: null };   // one small app: just show it
    return {
      shown: sorted.slice(0, cut),
      others: { count: rest.length, m: rest.reduce((t, a) => t + a.m, 0), apps: rest }
    };
  }

  // ---------- Future You (mirrors FutureYou.kt, which the lock screens use) ----------
  const FY_PARTS = [['NIGHT', 'late at night'], ['MORNING', 'in the morning'], ['AFTERNOON', 'in the afternoon'], ['EVENING', 'in the evening']];
  const fyPart = h => (h >= 5 && h <= 11 ? 1 : h >= 12 && h <= 16 ? 2 : h >= 17 && h <= 21 ? 3 : 0);
  const fyHm = min => { const h = Math.floor(min / 60), m = min % 60; return h && m ? `${h}h ${m}m` : h ? `${h}h` : `${m}m`; };
  function fyPlural(unit, n) {
    if (n > 0.999 && n < 1.001) return unit;
    if (/s$/.test(unit)) return unit;
    if (/[^aeiou]y$/.test(unit)) return unit.slice(0, -1) + 'ies';
    return unit + 's';
  }
  function fyAmount(units, unit) {
    if (units < 0.2) return null;
    const art = /^[aeiou]/i.test(unit) ? 'an' : 'a';
    if (units < 0.35) return `about a quarter of ${art} ${unit}`;
    if (units < 0.65) return `about half ${art} ${unit}`;
    if (units < 0.9) return `about three quarters of ${art} ${unit}`;
    if (units < 1.15) return `about one ${unit}`;
    if (units < 10) { const r = Math.round(units * 2) / 2; return `about ${r} ${fyPlural(unit, r)}`; }
    return `about ${Math.round(units)} ${fyPlural(unit, units)}`;
  }
  /** Pace from study minutes, same rule as the app: your own after 2 units, your estimate before. */
  function fyPace(goal, studyMin) {
    const done = Math.max(0, goal.total - goal.left);
    if (done >= 2 && studyMin >= 10) return { pace: Math.min(1000, Math.max(5, studyMin / done)), learned: true };
    return { pace: Math.min(1000, Math.max(5, goal.estimate || 60)), learned: false };
  }
  function fyCost(minutes, goal) {
    if (minutes < 5 || !goal) return null;
    const a = fyAmount(minutes / goal.pace, goal.unit);
    if (!a) return null;
    return `That's ${a} of ${goal.name}, ${goal.learned ? 'at your pace' : `at your estimate of ${Math.round(goal.pace)} minutes each`}.`;
  }
  function fyDeadline(goal, todayDay) {
    if (goal.left <= 0) return `All ${goal.total} ${fyPlural(goal.unit, goal.total)} done.`;
    if (!goal.deadline) return null;
    const days = goal.deadline - todayDay;
    const left = `${goal.left} ${fyPlural(goal.unit, goal.left)} left`;
    if (days < 0) return `${left}. The date has passed.`;
    if (days === 0) return `${left}, and the day is today.`;
    return `${left}, ${days} day${days === 1 ? '' : 's'} to go: about ${fyHm(Math.round(goal.left * goal.pace / days))} a day.`;
  }
  const epochDay = ms => { const d = new Date(ms); return Math.round(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / (24 * HOUR)); };
  const fyQuoteOfDay = (quotes, day) => (quotes && quotes.length ? quotes[(((day * 37 + 11) % quotes.length) + quotes.length) % quotes.length] : null);
  /** Minutes today on social, video and games: what the dashboard converts into your goal. */
  const fyDistract = (P, ds) => day(P, ds).apps.filter(a => a.cat === 'Social' || a.cat === 'Video' || a.cat === 'Games').reduce((t, a) => t + a.m, 0);
  /**
   * Your own verdicts: app + time of day where at least 3 recent answers exist and half or more said
   * "not worth it". Worst first. Labels are { "pkg|start": 1 regretted / 0 worth it }.
   */
  function fyVerdicts(P, labels, limit) {
    const groups = new Map();
    for (const [k, v] of Object.entries(labels || {})) {
      const i = k.lastIndexOf('|'); const pkg = k.slice(0, i), s = +k.slice(i + 1);
      if (!pkg || !s) continue;
      const part = fyPart(new Date(s).getHours());
      const g = groups.get(pkg + '#' + part) || { pkg, part, list: [] };
      g.list.push([s, v === 1 || v === true]); groups.set(pkg + '#' + part, g);
    }
    const out = [];
    for (const g of groups.values()) {
      const recent = g.list.sort((a, b) => b[0] - a[0]).slice(0, 10);
      const bad = recent.filter(x => x[1]).length;
      if (recent.length < 3 || bad * 2 < recent.length) continue;
      out.push({ pkg: g.pkg, n: P.name(g.pkg), part: FY_PARTS[g.part][1], asked: recent.length, regretted: bad, share: bad / recent.length });
    }
    return out.sort((a, b) => b.share - a.share || b.asked - a.asked).slice(0, limit || 3);
  }

  const api = { fyPart, fyHm, fyPlural, fyAmount, fyPace, fyCost, fyDeadline, fyQuoteOfDay, fyDistract, fyVerdicts, epochDay,
    weekReview, pickExperiment, experimentResult, metric, REELS_NAMES: REELS, timedIn, studySummary, ruleStats, REASONS, SCREENS, examPlan, sleepWeek, screenBeforeBed, stepsSummary, stepsByPartOfDay, stillestStretch, zombieCompare, typicalMinutes, spreadApps, splitOthers, collectionHealth, setOptions, OPTS, prepare, day, lastDays, sleepFor, why, patterns, exam, classify, receipt, sample, startOfDay, addDays, dateKey, fromKey, merge, pearson,
    askCandidates, regretModel, predictRegret, dayRegret, riskWindows, notifTriage, contextOf, LABEL_KEY: s => `${s.pkg}|${s.s}` };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.FA = api;
})(typeof window !== 'undefined' ? window : this);
