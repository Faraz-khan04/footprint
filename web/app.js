(function () {
  'use strict';
  const $ = id => document.getElementById(id);
  const root = document.documentElement;
  const FA = window.FA;

  // ---------- storage ----------
  const store = {
    get: k => { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set: (k, v) => { try { localStorage.setItem(k, v); } catch (e) {} },
    del: k => { try { localStorage.removeItem(k); } catch (e) {} },
  };

  // ---------- theme ----------
  const savedTheme = store.get('fp-theme');
  if (savedTheme) root.dataset.theme = savedTheme;
  $('theme').addEventListener('click', () => {
    const dark = root.dataset.theme ? root.dataset.theme === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
    root.dataset.theme = dark ? 'light' : 'dark';
    store.set('fp-theme', root.dataset.theme);
  });

  // ---------- helpers ----------
  const LIMIT = 60;
  let PREFS = { stepsGoal: 8000, screenGoalMin: 0, askMinMinutes: 5, minSleepHours: 3,
                nudgesEnabled: true, nudgeLeadMin: 5, reviewHour: 21, quietFrom: 30, quietTo: 420 };
  const inQuiet = m => PREFS.quietFrom <= PREFS.quietTo
    ? (m >= PREFS.quietFrom && m < PREFS.quietTo)
    : (m >= PREFS.quietFrom || m < PREFS.quietTo);
  const PALETTE = ['#D46FA8', '#D9583E', '#3FB27F', '#E88A3A', '#9BC53D', '#3FB6C9', '#3F6FE0', '#E3B23C', '#8C6CE0', '#B8B2A5'];
  const CAT_COLORS = { Social: '#D46FA8', Video: '#D9583E', Chat: '#3FB27F', Music: '#9BC53D', Games: '#E88A3A', Browser: '#3FB6C9',
    Education: '#3F6FE0', Productivity: '#8C6CE0', Maps: '#E3B23C', News: '#B8B2A5', Photos: '#E3B23C', Other: '#8d897f' };
  const esc = v => String(v).replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
  const b64ok = v => typeof v === 'string' && v.length < 200000 && /^[A-Za-z0-9+/=]+$/.test(v);
  const monogram = n => {
    const caps = n.match(/[A-Z0-9]/g) || [];
    const mg = caps.length >= 2 ? caps[0] + caps[1] : n.replace(/[^A-Za-z0-9]/g, '').slice(0, 2);
    return (mg || '??').toUpperCase();
  };
  const colorFor = n => { let h = 0; for (const ch of n) h = (h * 31 + ch.charCodeAt(0)) >>> 0; return PALETTE[h % PALETTE.length]; };
  const fmt = m => { m = Math.round(m); const h = Math.floor(m / 60), mm = m % 60; return h && mm ? `${h}h ${mm}m` : h ? `${h}h` : `${mm}m`; };
  const hmHtml = m => { m = Math.round(m); return `${Math.floor(m / 60)}<small>h</small>${String(m % 60).padStart(2, '0')}<small>m</small>`; };
  const clock = ms => new Date(ms).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
  const clockMin = m => { m = ((Math.round(m) % 1440) + 1440) % 1440; return String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0'); };
  const dayLabelLong = ds => new Date(ds).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });

  // ---------- data source ----------
  function bridgeSource() {
    const bridge = window.FootprintBridge;
    let seq = 0; const pending = new Map();
    bridge.onmessage = ev => {
      let msg; try { msg = JSON.parse(ev.data); } catch (e) { return; }
      const done = pending.get(msg.id);
      if (done) { pending.delete(msg.id); done(msg); }
    };
    const call = (msg, timeout) => new Promise((resolve, reject) => {
      const id = String(++seq);
      pending.set(id, resolve);
      bridge.postMessage(JSON.stringify(Object.assign({ id }, msg)));
      setTimeout(() => { if (pending.delete(id)) reject(new Error('The app did not respond')); }, timeout || 30000);
    });
    return {
      kind: 'app',
      async data() {
        const r = await call({ op: 'data', days: 35, haveIcons: Object.keys(ICONS) });
        if (r.status !== 200) throw new Error(r.error || 'Could not read your data');
        return JSON.parse(r.body);
      },
      async status() { return JSON.parse((await call({ op: 'status' })).body); },
      async battery() { return call({ op: 'batterySettings' }); },
      async labels() { return JSON.parse((await call({ op: 'labels' })).body); },
      async setLabel(key, regret, pkg, start) { return call({ op: 'setLabel', key, regret, pkg, start }); },
      async setPref(key, value) { return call({ op: 'setPref', key, value }); },
      async delLabel(key) { return call({ op: 'delLabel', key }); },
      async importLabels(labels) { return call({ op: 'importLabels', labels }); },
      collect: () => call({ op: 'collect' }, 60000),
      settings: () => call({ op: 'settings' }),
      exportData: format => call({ op: 'export', format }, 120000),
      share: png => call({ op: 'share', png }, 60000),
      wipe: () => call({ op: 'wipe' }, 120000),
      prefs: () => call({ op: 'prefs' }),
      nudges: list => call({ op: 'nudges', list }),
      notifSettings: pkg => call({ op: 'notifSettings', pkg }),
      async focus() { return JSON.parse((await call({ op: 'focus' })).body); },
      async focusStart(subject, minutes, lock) { return JSON.parse((await call({ op: 'focusStart', subject, minutes, lock })).body); },
      async focusStop() { return JSON.parse((await call({ op: 'focusStop' })).body); },
      bridgeCall: msg => call(msg),
    };
  }
  const src = (window.FootprintBridge && typeof window.FootprintBridge.postMessage === 'function') ? bridgeSource() : { kind: 'demo' };
  window.fpRefresh = () => refresh();

  // While the dashboard is open, keep today's numbers current without the user pulling to refresh.
  // Icons are already cached, so each pass is a small payload.
  let ticking = null;
  function startTicking() {
    stopTicking();
    ticking = setInterval(() => { if (!document.hidden && src.kind === 'app') refresh(); }, 60000);
  }
  function stopTicking() { if (ticking) { clearInterval(ticking); ticking = null; } }
  document.addEventListener('visibilitychange', () => { if (document.hidden) stopTicking(); else { refresh(); startTicking(); } });
  startTicking();

  // ---------- state ----------
  let P = FA.prepare({});
  let DAYS = FA.lastDays(P, 7);
  let cur = 6, selApp = 0, othersOpen = false, lastAnswer = null, ICONS = {};
  const neverAskList = () => { try { return JSON.parse(store.get('fp-neverask') || '[]'); } catch (e) { return []; } };
  let model = null, candidates = [], askIndex = 0;

  // Answers live in the app's own store so the widget can add to them and a browser-storage
  // clear can't lose them. LABELS is the copy this screen works from.
  let LABELS = {};
  const labels = () => LABELS;
  const setLabel = (key, regret, pkg, start) => {
    LABELS[key] = regret ? 1 : 0;
    if (src.kind === 'app') src.setLabel(key, !!regret, pkg || '', start || 0).catch(() => {});
    else store.set('fp-labels', JSON.stringify(LABELS));      // sample mode keeps working
  };
  const dropLabel = key => {
    delete LABELS[key];
    if (src.kind === 'app') src.delLabel(key).catch(() => {});
    else store.set('fp-labels', JSON.stringify(LABELS));
  };

  /** Load answers from the app, handing over anything still in browser storage the first time. */
  async function loadLabels() {
    if (src.kind !== 'app') {
      try { LABELS = JSON.parse(store.get('fp-labels') || '{}'); } catch (e) { LABELS = {}; }
      return;
    }
    try {
      LABELS = await src.labels();
      let old = {};
      try { old = JSON.parse(store.get('fp-labels') || '{}'); } catch (e) {}
      if (Object.keys(old).length) {
        await src.importLabels(old);
        LABELS = await src.labels();
        store.del('fp-labels');                                // handed over; don't do it twice
      }
    } catch (e) { LABELS = LABELS || {}; }
  }

  const iconOf = pkg => (b64ok(ICONS[pkg]) ? ICONS[pkg] : null);
  const decorate = a => ({ ...a, mg: monogram(a.n), c: colorFor(a.n), social: a.cat === 'Social' || a.cat === 'Video', icon: iconOf(a.pkg) });
  const iconHtml = (pkg, name, cls) => {
    const icon = iconOf(pkg), c = colorFor(name);
    return icon
      ? `<div class="${cls}" style="border-color:${c}"><img src="data:image/png;base64,${icon}" alt=""></div>`
      : `<div class="${cls} mono" style="border-color:${c};color:${c}">${esc(monogram(name))}</div>`;
  };
  function setBanner(text, action) {
    const b = $('banner');
    b.textContent = text || ''; b.hidden = !text;
    b.dataset.settings = action ? (action === true ? 'settings' : action) : '';
    b.style.cursor = action ? 'pointer' : '';
  }
  $('banner').addEventListener('click', () => {
    const a = $('banner').dataset.settings;
    if (!a || src.kind !== 'app') return;
    if (a === 'battery') src.battery(); else src.settings();
  });

  async function refresh() {
    let raw;
    if (src.kind === 'app') {
      try { Object.assign(PREFS, JSON.parse((await src.prefs()).body)); } catch (e) {}
      FA.setOptions({ ...PREFS, neverAsk: neverAskList() }, P);
      try { raw = await src.data(); setBanner(''); }
      catch (e) { setBanner(e.message + '.'); raw = {}; }
    } else {
      raw = FA.sample(Date.now(), 30);
      setBanner('Sample data. Install the app to see your own.');
    }
    await loadLabels();                     // the widget can answer too, so re-read every time
    Object.assign(ICONS, raw.icons || {});   // icons arrive once, then are kept
    P = FA.prepare(raw);
    FA.setOptions({ ...PREFS, neverAsk: neverAskList() }, P);
    DAYS = FA.lastDays(P, 7);
    if (cur >= DAYS.length) cur = DAYS.length - 1;
    select(cur);
    await loadFocus();
    renderWorth(); renderTriage(); renderPatterns(); renderTimer(); renderExam(); renderReview(); drawReceipt();
    await loadFuture();
    showStatus();
    pushNudges();
  }

  async function showStatus() {
    if (src.kind !== 'app') { $('foot').textContent = 'Showing sample data.'; return; }
    try {
      const st = await src.status();
      if ($('banner').hidden || $('banner').dataset.settings) {
        // each reads naturally on its own: "Nudges are off", "Step counting is off"
        const off = [st.steps === false && { n: 'Step counting', v: 'is', it: 'it' },
                     st.nudges === false && { n: 'Nudges', v: 'are', it: 'them' }].filter(Boolean);
        if (st.usage === false) setBanner('Usage access is off, so nothing new is being recorded. Tap here to fix it.', true);
        else if (off.length) setBanner(`${off.map(o => o.n).join(' and ')} ${off.length > 1 ? 'are' : off[0].v} off.`
          + ` Tap here to turn ${off.length > 1 ? 'them' : off[0].it} on.`, true);
        else {
          const h = FA.collectionHealth(Date.now(), st.last_collect, { ready: st.ready, unrestricted: st.battery_unrestricted });
          if (h.level === 'lost')
            setBanner('Nothing has been recorded for ' + Math.floor(h.hours / 24) + ' days, so some of that time could not be recovered. Tap here to allow background use.', 'battery');
          else if (h.level === 'stale')
            setBanner('Nothing new in ' + h.hours + ' hours. Android may have put the app to sleep; the missed time is still recoverable. Tap here to allow background use.', 'battery');
          else if ($('banner').dataset.settings) setBanner('');
        }
      }
      $('cellSync').textContent = st.last_collect ? `Updated ${new Date(st.last_collect).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })}` : 'Every 15 min';
      $('foot').textContent = st.ready ? `${st.events.toLocaleString('en-IN')} events stored on this phone.` : 'Finish setup in Settings to start collecting.';
    } catch (e) { $('foot').textContent = ''; }
  }

  // ---------- day chips ----------
  const label = i => i === DAYS.length - 1 ? 'Today' : i === DAYS.length - 2 ? 'Yesterday'
    : new Date(DAYS[i].ds).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric' });
  function chips() {
    $('days').innerHTML = DAYS.map((_, i) => `<button class="day mono" type="button" data-i="${i}" aria-pressed="${i === cur}">${label(i)}</button>`).join('');
    const on = $('days').querySelector('[aria-pressed="true"]');
    if (on) $('days').scrollLeft = on.offsetLeft - $('days').clientWidth + on.offsetWidth + 18;
  }
  $('days').addEventListener('click', e => { const b = e.target.closest('.day'); if (b) select(+b.dataset.i); });

  // ---------- orbit ----------
  const C = { x: 180, y: 252 }, RADII = [92, 142, 192];
  const RANGE = [[200, 340], [196, 344], [204, 336]];
  let badges = [], raf = null;
  const still = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const DUST = (() => {
    let seed = 42; const r = () => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed / 4294967296; };
    let s = ''; const cols = ['var(--fg)', '#C7503A', '#3F6FE0', 'var(--fg)'];
    for (let i = 0; i < 190; i++) {
      const a = Math.PI + r() * Math.PI, rad = 66 * Math.sqrt(r());
      s += `<circle cx="${(C.x + Math.cos(a) * rad).toFixed(1)}" cy="${(C.y + Math.sin(a) * rad).toFixed(1)}" r="${(0.7 + r() * 0.9).toFixed(2)}" fill="${cols[i % 4]}" opacity="${(0.35 + r() * 0.6).toFixed(2)}"/>`;
    }
    return s;
  })();
  function drawOrbit(apps) {
    apps = apps.slice(0, 10);
    const max = apps.length ? apps[0].m : 1;
    let html = RADII.map(r => `<path class="arc" d="M${C.x - r} ${C.y} A${r} ${r} 0 0 1 ${C.x + r} ${C.y}"/>`).join('') + DUST;
    const perRing = [Math.min(3, apps.length), Math.min(3, Math.max(apps.length - 3, 0)), Math.max(apps.length - 6, 0)];
    badges = apps.map((a, i) => {
      const ring = i < 3 ? 0 : i < 6 ? 1 : 2, idx = ring === 0 ? i : ring === 1 ? i - 3 : i - 6;
      const [a0, a1] = RANGE[ring];
      const deg = a0 + (a1 - a0) * ((idx + 0.5) / perRing[ring]) + (ring === 1 ? 8 : 0);
      return { a, i, ring, base: deg * Math.PI / 180, size: 13 + Math.sqrt(Math.max(a.m, 0) / Math.max(max, 1)) * 10, seed: i * 1.7 };
    });
    html += badges.map(b => {
      const r = b.size, ir = r - 2.5;
      const inner = b.a.icon
        ? `<clipPath id="cp${b.i}"><circle r="${ir.toFixed(1)}"/></clipPath><image href="data:image/png;base64,${b.a.icon}" x="${(-ir).toFixed(1)}" y="${(-ir).toFixed(1)}" width="${(2 * ir).toFixed(1)}" height="${(2 * ir).toFixed(1)}" clip-path="url(#cp${b.i})"/>`
        : `<text font-size="${(r * 0.72).toFixed(1)}" fill="${b.a.c}">${esc(b.a.mg)}</text>`;
      return `<g class="badge${b.i === selApp ? ' sel' : ''}" data-i="${b.i}" tabindex="0" role="button" aria-label="${esc(b.a.n)}, ${fmt(b.a.m)}"><circle class="b" r="${r.toFixed(1)}" stroke="${b.a.c}"/>${inner}</g>`;
    }).join('');
    $('orbit').innerHTML = html;
    badges.forEach(b => b.el = $('orbit').querySelector(`[data-i="${b.i}"]`));
    place(performance.now());
    if (!still && !raf) raf = requestAnimationFrame(loop);
  }
  function place(t) {
    badges.forEach(b => {
      const ang = b.base + Math.sin(t / 4200 + b.seed) * 0.035, r = RADII[b.ring];
      b.el.setAttribute('transform', `translate(${(C.x + Math.cos(ang) * r).toFixed(1)},${(C.y + Math.sin(ang) * r).toFixed(1)})`);
    });
  }
  function loop(t) { place(t); raf = requestAnimationFrame(loop); }
  function pick() {
    const d = DAYS[cur], apps = d.apps.map(decorate), a = apps[selApp];
    if (!a) { $('pick').innerHTML = `<div class="mono">Nothing yet</div><div class="d mono">No app use recorded for this day.</div>`; return; }
    const total = d.screenMin || 1;
    $('pick').innerHTML = `<div class="mono">${String(selApp + 1).padStart(2, '0')} / ${esc(a.cat)}</div><div class="n">${esc(a.n)}</div><div class="d mono">${fmt(a.m)} · ${Math.round(a.m / total * 100)}% of screen time</div>`;
    badges.forEach(b => b.el.classList.toggle('sel', b.i === selApp));
  }
  const choose = e => { const g = e.target.closest('.badge'); if (!g) return; selApp = +g.dataset.i; pick(); };
  $('orbit').addEventListener('click', choose);
  $('orbit').addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); choose(e); } });

  // ---------- render: selected day ----------
  const lane = (el, blocks, cls) => el.innerHTML = blocks.map(([s, e]) =>
    `<span class="seg ${cls}" style="left:${(s / 1440 * 100).toFixed(2)}%;width:${Math.max((e - s) / 1440 * 100, .5).toFixed(2)}%"></span>`).join('');

  function renderDay() {
    const d = DAYS[cur];
    const apps = d.apps.map(decorate);
    $('heroDate').textContent = dayLabelLong(d.ds);
    $('stickerTime').textContent = `${Math.floor(d.screenMin / 60)}:${String(Math.round(d.screenMin % 60)).padStart(2, '0')}`;
    $('cScreen').innerHTML = hmHtml(d.screenMin) +
      (PREFS.screenGoalMin ? `<small style="display:block;font-size:.34em;letter-spacing:.1em">${d.screenMin <= PREFS.screenGoalMin ? 'UNDER' : 'OVER'} ${(PREFS.screenGoalMin % 60 ? fmt(PREFS.screenGoalMin) : Math.round(PREFS.screenGoalMin / 60) + 'h').toUpperCase()} TARGET</small>` : '');
    $('cSteps').textContent = (d.stepsEst && d.steps ? '~' : '') + d.steps.toLocaleString('en-IN');
    $('cSteps').title = d.stepsEst ? 'Estimated: Android read the step counter late, so steps were spread over the hours since the last reading.' : '';
    $('cSleep').innerHTML = d.sleep ? hmHtml(d.sleep.min) : '<small>no data</small>';
    $('cUnlocks').textContent = d.unlocks;

    const top = apps.slice(0, 6);
    const items = top.length ? top.map(a => `<span>${esc(a.n)}<em>${fmt(a.m)}</em></span><span class="star">✦</span>`).join('')
      : `<span>No screen time yet<em>for this day</em></span><span class="star">✦</span>`;
    $('track').innerHTML = items + items;

    drawOrbit(apps); pick();

    // categories
    const cats = new Map();
    for (const a of apps) cats.set(a.cat, (cats.get(a.cat) || 0) + a.m);
    const catList = [...cats].sort((x, y) => y[1] - x[1]);
    const total = d.screenMin || 1;
    $('cats').innerHTML = catList.map(([c, m]) => `<i style="width:${(m / total * 100).toFixed(2)}%;background:${CAT_COLORS[c] || CAT_COLORS.Other}" title="${esc(c)}"></i>`).join('');
    $('catlegend').innerHTML = catList.map(([c, m]) => `<span><b style="background:${CAT_COLORS[c] || CAT_COLORS.Other}"></b>${esc(c)} ${fmt(m)}</span>`).join('');

    if (!apps.length) $('rows').innerHTML = `<p class="empty mono">No app use recorded for this day yet.</p>`;
    else {
      const max = apps[0].m || 1, N = 34;
      const split = FA.splitOthers(apps, { minMin: 2, maxRows: 12 });
      const list = othersOpen && split.others ? apps : split.shown;
      $('rows').innerHTML = list.map((a, i) => {
        const over = a.social && a.m > LIMIT, on = Math.round(a.m / max * N), lim = Math.round(LIMIT / max * N);
        const ticks = Array.from({ length: N }, (_, k) => `<i class="${k < on ? (over && k >= lim ? 'hot' : 'on') : ''}"></i>`).join('');
        const ic = a.icon ? `<div class="ic has-img" style="border-color:${a.c}"><img src="data:image/png;base64,${a.icon}" alt=""></div>`
          : `<div class="ic mono" style="border-color:${a.c};color:${a.c}">${esc(a.mg)}</div>`;
        return `<div class="row">${ic}<div class="nm">${esc(a.n)}</div>
          <div class="t">${fmt(a.m)}${over ? `<span class="over">+${fmt(a.m - LIMIT)} over</span>` : ''}</div>
          <div class="meta mono">${String(i + 1).padStart(2, '0')} / ${esc(a.cat)}</div><div class="ticks">${ticks}</div></div>`;
      }).join('');
      if (split.others) {
        const o = split.others;
        $('rows').innerHTML += `<button class="row others" type="button" id="othersRow" aria-expanded="${othersOpen}">
          <div class="ic mono" style="border-color:var(--mute);color:var(--mute)">+${o.count}</div>
          <div class="nm">Others</div>
          <div class="t">${fmt(o.m)}</div>
          <div class="meta mono">${othersOpen ? 'TAP TO FOLD' : `${o.count} APPS UNDER ${fmt(2)}`}</div></button>`;
        $('othersRow').addEventListener('click', () => { othersOpen = !othersOpen; renderDay(); });
      }
    }

    renderWhy(d);

    if (d.sleep) { lane($('laneSleep'), [[Math.max(d.sleep.bed - 1440, 0), d.sleep.wake]], 'sleep'); $('lSleep').textContent = `${clockMin(d.sleep.bed)}–${clockMin(d.sleep.wake)}`; }
    else { lane($('laneSleep'), [], 'sleep'); $('lSleep').textContent = 'not enough data'; }
    lane($('lanePhone'), d.phone, ''); $('lPhone').textContent = fmt(d.screenMin);

    const n = d.night;
    $('nInBed').innerHTML = n ? hmHtml(n.inBed) : '<small>—</small>';
    $('nLate').innerHTML = n ? hmHtml(n.lateNight) : '<small>—</small>';
    $('nReach').innerHTML = n && n.reach ? `${Math.round(n.reach.min)}<small>min</small>` : '<small>—</small>';
    $('nFirst').textContent = n && n.firstApp ? n.firstApp.n : '—';
    $('nightNote').textContent = !n ? 'Needs a night with the phone tracking: bedtime is your last use at night, waking is your first unlock.'
      : n.reach ? `${n.reach.n} was your first social app, ${Math.round(n.reach.min)} minutes after you woke at ${clock(d.sleep.wakeMs)}.`
        : `No social or video apps in the 6 hours after you woke at ${clock(d.sleep.wakeMs)}.`;

    const ramp = ' .:-=+*#%@';
    $('ascii').innerHTML = '     0     6     12    18   \n' + DAYS.map((x, i) => {
      const row = x.hourly.map(v => ramp[Math.min(9, Math.round(v / 60 * 9))]).join('');
      const name = new Date(x.ds).toLocaleDateString('en-GB', { weekday: 'short' }).toUpperCase();
      return i === cur ? `<b>${name}  ${row}</b>` : `${name}  ${row}`;
    }).join('\n');

    const goal = PREFS.stepsGoal || 8000;
    const SCALE = Math.max(goal * 1.35, ...DAYS.map(x => x.steps * 1.1)), STEP = SCALE / 27;
    $('week').innerHTML = `<div class="goal mono" style="bottom:${goal / SCALE * 100}%"><span>${goal.toLocaleString('en-IN')} goal</span></div>` +
      DAYS.map((x, i) => `<button class="col${x.steps >= goal ? ' hit' : ''}" type="button" data-i="${i}" aria-pressed="${i === cur}" aria-label="${label(i)}: ${x.steps} steps">
        <b class="cnt mono">${x.steps ? (x.stepsEst ? '~' : '') + x.steps.toLocaleString('en-IN') : ''}</b>${'<i></i>'.repeat(Math.round(x.steps / STEP))}</button>`).join('');
    $('wk').innerHTML = DAYS.map((x, i) => `<span class="${i === cur ? 'on' : ''}">${new Date(x.ds).toLocaleDateString('en-GB', { weekday: 'narrow' })}</span>`).join('');
    renderGoalControl(goal);

    if (d.sleep) {
      $('sleepBig').textContent = `${Math.floor(d.sleep.min / 60)}h${String(d.sleep.min % 60).padStart(2, '0')}m`;
      $('sleepNote').textContent = `Asleep around ${clockMin(d.sleep.bed)}, up at ${clockMin(d.sleep.wake)}. Estimated from your last phone use at night and your first unlock in the morning.`;
    } else {
      $('sleepBig').textContent = '—';
      $('sleepNote').textContent = 'Not enough data yet. Sleep shows up the morning after a night with the app collecting.';
    }
    renderBody(d);
  }

  /** The goal line is worth changing from here, not only from Settings. */
  function renderGoalControl(goal) {
    const box = $('goalCtl');
    if (!box) return;
    box.innerHTML = `<span class="mono lbl">Daily goal</span>
      <button class="stepbtn mono" type="button" data-d="-1000" aria-label="Lower the goal">−</button>
      <b class="mono val">${goal.toLocaleString('en-IN')}</b>
      <button class="stepbtn mono" type="button" data-d="1000" aria-label="Raise the goal">+</button>
      <span class="mono hint">${src.kind === 'app' ? 'steps' : 'steps (sample)'}</span>`;
    box.querySelectorAll('[data-d]').forEach(b => b.onclick = async () => {
      const next = Math.min(30000, Math.max(1000, (PREFS.stepsGoal || 8000) + (+b.dataset.d)));
      PREFS.stepsGoal = next;
      FA.setOptions({ stepsGoal: next }, P);
      renderDay();                                   // chart, goal line and the facts all follow
      if (src.kind === 'app') {
        try { Object.assign(PREFS, JSON.parse((await src.setPref('stepsGoal', next)).body)); }
        catch (e) { setBanner('Could not save the goal.'); }
      }
    });
  }

  const hm = fmt;   // same formatting, kept as a name that reads well in the sleep and steps copy
  const clockOf = min => {
    const m = ((min % 1440) + 1440) % 1440;
    return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
  };
  const fact = (label, text) => `<div class="fact"><b>${esc(label)}</b><span>${esc(text)}</span></div>`;

  /** The numbers under the steps chart and the nights under last night's total. */
  function renderBody(d) {
    // ---- steps
    const s = FA.stepsSummary(P, d.ds, 7);
    const part = FA.stepsByPartOfDay(P, d.ds, 7);
    const still = FA.stillestStretch(P, d.ds);
    let out = '';
    if (s.total > 0) {
      out += fact('THIS WEEK', `${s.total.toLocaleString()} steps, about ${s.avg.toLocaleString()} on the days it counted.`);
      // a tiny wobble is not news, and there is nothing to compare against in week one
      if (s.change !== null && Math.abs(s.change) >= 5) out += fact(s.change > 0 ? 'UP' : 'DOWN',
        `${Math.abs(s.change)}% ${s.change > 0 ? 'more' : 'less'} than the week before.`);
      else if (s.change === null) out += fact('FIRST WEEK', 'Next week this compares against this one.');
      out += fact('GOAL', s.goalDays
        ? `You passed ${s.goal.toLocaleString()} on ${s.goalDays} day${s.goalDays > 1 ? 's' : ''}` +
          (s.streak > 1 ? `, the last ${s.streak} in a row.` : '.')
        : `No day reached ${s.goal.toLocaleString()} yet this week.`);
      if (part) out += fact('WHEN', `Most of your steps happen in the ${part.top}: ${part.topShare}% of them.`);
      if (still) out += fact('STILL', `Today you were on the phone and not moving for about ${still.hours} hours straight, from ${String(still.from).padStart(2, '0')}:00.`);
    } else {
      out = fact('STEPS', 'Nothing counted yet. Steps need the activity permission and the phone on you.');
    }
    $('stepsFacts').innerHTML = out;

    // ---- sleep
    const w = FA.sleepWeek(P, d.ds, 7);
    const bb = FA.screenBeforeBed(P, d.ds, 14);
    const max = Math.max(...w.nights.map(n => n.min), 1);
    $('sleepWeek').innerHTML = w.nights.map(n => {
      const h = n.min ? Math.max(8, Math.round((n.min / max) * 58)) : 4;
      const short = n.min && n.min < (w.targetMin || 420);
      const date = new Date(n.ds).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
      return `<div class="night" title="${date}: ${n.min ? hm(n.min) : 'no reading'}">
        <i class="${n.min ? (short ? 'short' : '') : 'none'}" style="height:${h}px"></i>
        <u>${new Date(n.ds).toLocaleDateString('en-GB', { weekday: 'narrow' })}</u></div>`;
    }).join('');

    let sf = '';
    if (w.have >= 2) {
      sf += fact('AVERAGE', `${hm(w.avgMin)} a night across ${w.have} night${w.have > 1 ? 's' : ''}, from ${hm(w.shortest.min)} to ${hm(w.longest.min)}.`);
      sf += fact('BEDTIME', w.bedSpreadMin <= 30
        ? `Around ${clockOf(w.bedAvgMin)}, give or take ${w.bedSpreadMin} minutes. That is steady.`
        : `Around ${clockOf(w.bedAvgMin)}, but it wanders by about ${hm(w.bedSpreadMin)} either way.`);
      if (w.shortNights) sf += fact('SHORT', `${w.shortNights} night${w.shortNights > 1 ? 's' : ''} under ${hm(w.targetMin)}, ${hm(w.debtMin)} short in total.`);
    }
    if (bb && Math.abs(bb.diff) >= 15) sf += fact('BEFORE BED', bb.diff > 0
      ? `On nights you were on your phone in the hour before sleep, you slept ${hm(bb.diff)} less.`
      : `Phone use right before sleep does not seem to shorten your nights.`);
    if (w.missing) sf += fact('NOT READ', `${w.missing} of the last 7 nights had no break longer than ${w.thresholdH}h, so they were treated as naps. Change "Ignore naps shorter than" in Settings if you sleep less than that.`);
    if (!sf) sf = fact('SLEEP', 'A few more nights and this fills in with your average, your bedtime and what shortens it.');
    $('sleepFacts').innerHTML = sf;
  }
  $('week').addEventListener('click', e => { const b = e.target.closest('.col'); if (b) select(+b.dataset.i); });

  function renderWhy(d) {
    const w = d.why;
    $('zombieN').textContent = w.zombies;
    const zc = FA.zombieCompare(DAYS, d.ds);
    const zcTxt = !zc ? ''
      : zc.level === 'same' ? ` About the same as your usual ${zc.avg}.`
      : zc.level === 'better' ? ` Fewer than your usual ${zc.avg}.`
      : ` More than your usual ${zc.avg}.`;
    $('zombieTxt').textContent = w.unlocks
      ? `${w.zombies} of your ${w.unlocks} unlocks ended within 15 seconds without opening anything.${zcTxt || " That's a habit, not a need."}`
      : 'No unlocks recorded for this day.';

    const firstMax = w.firstApp.length ? w.firstApp[0].c : 1;
    $('firstList').innerHTML = w.firstApp.length ? w.firstApp.slice(0, 5).map(f =>
      `<div class="li">${iconHtml(f.pkg, f.n, 'ic')}<div class="nm">${esc(f.n)}</div><div class="val">${f.c}×</div>
       <div class="sub mono">first after ${f.c} of ${w.unlocks} unlocks</div><div class="bar"><i style="width:${(f.c / firstMax * 100).toFixed(1)}%"></i></div></div>`).join('')
      : `<p class="empty-state mono">Nothing yet for this day.</p>`;

    $('pullList').innerHTML = !w.hasNotifs
      ? `<p class="empty-state mono">Your phone doesn't report notification events to apps, so this can't be measured here.</p>`
      : w.notifPulls.length ? w.notifPulls.slice(0, 5).map(p =>
        `<div class="li">${iconHtml(p.pkg, p.n, 'ic')}<div class="nm">${esc(p.n)}</div><div class="val">${p.pulled}×</div>
         <div class="sub mono">opened within 2 min of ${p.pulled} of ${p.notifs} notifications</div></div>`).join('')
        : `<p class="empty-state mono">No notification pulled you in on this day.</p>`;

    renderSaid(d.ds);

    $('doomList').innerHTML = w.doom.length ? w.doom.slice(0, 5).map(x =>
      `<div class="li">${iconHtml(x.pkg, x.n, 'ic')}<div class="nm">${esc(x.n)}</div><div class="val">${fmt(x.m)}</div>
       <div class="sub mono">${clock(x.s)}–${clock(x.e)}</div></div>`).join('')
      : `<p class="empty-state mono">No doom-scrolls on this day.</p>`;
  }

  function select(i) { cur = i; selApp = 0; othersOpen = false; chips(); renderDay(); }


  // ---------- worth it? ----------
  function renderWorth() {
    const lab = labels();
    model = FA.regretModel(P, lab);
    candidates = FA.askCandidates(P, lab, { days: 7, max: 12 });
    if (askIndex >= candidates.length) askIndex = 0;
    // Don't follow an answer with the same app again: a spread of apps teaches the model more.
    if (lastAnswer && candidates[askIndex] && candidates[askIndex].pkg === lastAnswer.pkg) {
      const other = candidates.findIndex(c => c.pkg !== lastAnswer.pkg);
      if (other >= 0) askIndex = other;
    }
    renderAsk();
    renderRegret();
  }

  function renderAsk() {
    const box = $('ask'), lab = labels(), answered = Object.keys(lab).length;
    const c = candidates[askIndex];
    if (!c) {
      const why = !PREFS.askMinMinutes
        ? 'Questions are turned off. Switch them back on in Settings.'
        : answered ? `Nothing new to review. ${answered} sessions answered so far.`
          : 'Once you have used your phone for a while, sessions to review will show up here.';
      box.innerHTML = `<div class="empty-state mono">${why}</div>`;
      return;
    }
    const when = `${new Date(c.s).toLocaleDateString('en-GB', { weekday: 'short' })} ${clock(c.s)}–${clock(c.e)}`;
    box.innerHTML = `<div class="askcard">
        <div class="when mono">${esc(when.toUpperCase())}</div>
        <div class="what">${iconHtml(c.pkg, c.n, 'ic')}<div><div class="nm">${esc(c.n)}</div><div class="dur mono">${fmt(c.min)} in one go</div></div></div>
        ${c.reason ? `<div class="askwhy mono">${esc(c.reason.toUpperCase())}</div>` : ''}
        <div class="askrow">
          <button class="btn yes" type="button" data-worth="1">Worth it</button>
          <button class="btn" type="button" data-worth="0">Not worth it</button>
        </div>
        <div class="asknote mono"><span>${answered} answered</span><button class="linkbtn mono" type="button" id="skipAsk">Skip →</button></div>
        <div class="asknote mono">
          <button class="linkbtn mono" type="button" id="neverAsk">Never ask about ${esc(c.n)}</button>
          ${lastAnswer ? `<button class="linkbtn mono" type="button" id="undoAsk">↩ Undo last</button>` : ''}
        </div>
      </div>`;
    box.querySelectorAll('[data-worth]').forEach(b => b.onclick = () => {
      lastAnswer = { key: c.key, n: c.n, pkg: c.pkg };
      setLabel(c.key, b.dataset.worth === '0', c.pkg, c.s);
      askIndex = 0;
      renderWorth();
      pushNudges();
    });
    $('skipAsk').onclick = () => { askIndex = (askIndex + 1) % candidates.length; renderAsk(); };
    $('neverAsk').onclick = () => {
      const list = neverAskList();
      if (!list.includes(c.pkg)) list.push(c.pkg);
      store.set('fp-neverask', JSON.stringify(list));
      FA.setOptions({ neverAsk: list });
      askIndex = 0;
      renderWorth();
    };
    if (lastAnswer) $('undoAsk').onclick = () => {
      dropLabel(lastAnswer.key);
      lastAnswer = null;
      askIndex = 0;
      renderWorth();
      pushNudges();
    };
  }

  function renderRegret() {
    const lab = labels(), box = $('regretBox');
    const answered = Object.keys(lab).length;
    if (!model.ready) {
      const left = Math.max(0, 8 - answered);
      box.innerHTML = `<div class="empty-state mono">Answer ${left} more and the app starts spotting the moments you regret.
        <div class="meter"><i style="width:${Math.min(100, answered / 8 * 100)}%"></i></div></div>`;
      return;
    }
    const d = DAYS[cur];
    const r = FA.dayRegret(P, model, lab, d.ds);
    const windows = FA.riskWindows(P, lab);
    const share = r.total ? Math.round(r.min / r.total * 100) : 0;
    let html = `<div class="regret">
        <div class="mono k">${esc(label(cur).toUpperCase())} · TIME YOU'D TAKE BACK</div>
        <div class="big">${fmt(r.min)}</div>
        <p class="note">of ${fmt(r.total)} on screen (${share}%). ${Math.round(r.labelledMin)} min you marked yourself, ${Math.round(r.predictedMin)} min the app expects you would.</p>
      </div>`;
    if (windows.length) {
      html += `<div class="kicker mono" style="margin-top:22px">Your risky moments</div>`;
      html += windows.map(w => `<div class="window">
          <div class="time">${String(w.hour).padStart(2, '0')}:${String(w.minute).padStart(2, '0')}</div>
          <div class="nm mono">${esc(w.n)}</div>
          <div class="sub mono">${w.count} sessions you regretted · ${fmt(w.avgMin)} on average</div></div>`).join('');
      const next = windows[0];
      html += `<div class="pred mono">The app will nudge you ${PREFS.nudgeLeadMin || 5} minutes before ${String(next.hour).padStart(2, '0')}:${String(next.minute).padStart(2, '0')}${src.kind === 'app' ? '' : ' (in the app)'}.</div>`;
    }
    const worst = candidates.map(c => ({ c, p: FA.predictRegret(P, model, { pkg: c.pkg, cat: c.cat, s: c.s, e: c.e }) }))
      .filter(x => x.p && x.p.p >= 0.6).sort((a, b) => b.p.p - a.p.p)[0];
    if (worst) html += `<div class="pred mono">Why: ${esc(worst.p.why)}</div>`;
    box.innerHTML = html;
  }

  // ---------- notification triage ----------
  function renderTriage() {
    const list = FA.notifTriage(P, 7).filter(r => r.notifs >= 3).slice(0, 6);
    if (!list.length) {
      $('triageList').innerHTML = `<div class="empty-state mono">No notification events recorded yet. Some phones don't report these to apps.</div>`;
      return;
    }
    const max = list[0].min || 1;
    $('triageList').innerHTML = list.map(r => `<div class="li">${iconHtml(r.pkg, r.n, 'ic')}
        <div class="nm">${esc(r.n)}</div><div class="val">${fmt(r.min)}</div>
        <div class="sub mono">${r.notifs} notifications · pulled you in ${r.pulls} times (${Math.round(r.rate * 100)}%)</div>
        <div class="bar"><i style="width:${(r.min / max * 100).toFixed(1)}%"></i></div>
        ${src.kind === 'app' ? `<button class="btn ghost" type="button" data-notif="${esc(r.pkg)}" style="grid-column:1/4;margin-top:10px">Notification settings for ${esc(r.n)}</button>` : ''}
      </div>`).join('');
    $('triageList').querySelectorAll('[data-notif]').forEach(b => b.onclick = () => src.notifSettings(b.dataset.notif));
  }

  // ---------- nudges (scheduled by the app, fired as plain notifications) ----------
  function pushNudges() {
    if (src.kind !== 'app') return;
    const lab = labels(), list = [];
    if (!PREFS.nudgesEnabled) { src.nudges([]).catch(() => {}); return; }
    for (const w of FA.riskWindows(P, lab)) {
      const total = w.hour * 60 + w.minute - (PREFS.nudgeLeadMin || 5);   // a few minutes before it usually starts
      if (inQuiet(((total % 1440) + 1440) % 1440)) continue;              // your quiet hours
      list.push({
        id: `risk-${w.hour}-${w.minute}`,
        hour: Math.floor(((total % 1440) + 1440) % 1440 / 60),
        minute: ((total % 60) + 60) % 60,
        title: 'About that time',
        text: `Last ${w.count} times around now, ${w.n} took ${fmt(w.avgMin)} you said wasn't worth it.`,
        pkg: w.pkg, app: w.n,                                            // the app can add your verdict and notes
      });
    }
    if (PREFS.reviewHour >= 0 && !inQuiet(PREFS.reviewHour * 60) &&
        FA.askCandidates(P, lab, { days: 7, max: 3 }).length >= 3) {
      list.push({ id: 'review', hour: PREFS.reviewHour, minute: 0, title: 'Worth it?',
        text: 'A few sessions are waiting to be reviewed. Two taps each.' });
    }
    src.nudges(list).catch(() => {});
  }

  // ---------- patterns ----------
  function renderPatterns() {
    const p = FA.patterns(P);
    if (!p.ready) {
      $('patternList').innerHTML = `<div class="empty-state mono">Patterns appear after ${p.need} full days of data. You have ${p.have}.
        <div class="meter"><i style="width:${Math.min(100, p.have / p.need * 100)}%"></i></div></div>`;
      return;
    }
    $('patternList').innerHTML = p.items.length ? p.items.map(x => `<div class="pat">
        ${x.early ? '<span class="badge mono">Early pattern</span>' : ''}
        <p>${esc(x.text)}</p>
        <div class="meta mono">${x.n} days · ${x.nYes} vs ${x.nNo} · ${x.strength} link (r = ${x.r.toFixed(2)})</div></div>`).join('')
      : `<div class="empty-state mono">No clear patterns in your last ${p.days} days yet. That can be good news: your days are consistent.</div>`;
  }



  // ---------- week in review ----------
  let REVIEW = null, expIdx = 0;
  const loadExp = () => { try { return JSON.parse(store.get('fp-experiment') || 'null'); } catch (e) { return null; } };
  const saveExp = x => { if (x) store.set('fp-experiment', JSON.stringify(x)); else store.del('fp-experiment'); };
  const signed = (m, unit) => (m > 0 ? '+' : m < 0 ? '−' : '±') + (unit === 'min' ? fmt(Math.abs(m)) : Math.round(Math.abs(m)));

  function renderReview() {
    const box = $('reviewBox');
    if (!box) return;
    const r = REVIEW = FA.weekReview(P, Date.now(), { model, labels: LABELS });
    $('shareReview').hidden = !r.enough;
    if (!r.enough) {
      box.innerHTML = `<p class="empty-state mono">A review needs at least 3 days of data this week. So far: ${r.cur.days}.</p>`;
      return;
    }
    const c = r.cur, p = r.prev;
    const cd = Math.max(1, c.days), pd = Math.max(1, p.days);
    const avg = (x, d) => x / d;
    const change = avg(c.screen, cd) - (p.days ? avg(p.screen, pd) : avg(c.screen, cd));
    const deltaCls = !p.days || Math.abs(change) < 10 ? '' : change < 0 ? 'good' : 'bad';
    const deltaTxt = !p.days ? 'first week' : Math.abs(change) < 10 ? 'about the same as last week' : `${fmt(Math.abs(change))} a day ${change < 0 ? 'less' : 'more'} than last week`;

    // [label, this week, last week, unit, lower is better?]
    const rows = [
      ['Screen time a day', avg(c.screen, cd), p.days ? avg(p.screen, pd) : null, 'min', true],
      ['Social and video a day', avg(c.social, cd), p.days ? avg(p.social, pd) : null, 'min', true],
      ['After 11 pm, a night', avg(c.late, cd), p.days ? avg(p.late, pd) : null, 'min', true],
      ['Unlocks a day', avg(c.unlocks, cd), p.days ? avg(p.unlocks, pd) : null, 'n', true],
      ['Zombie unlocks a day', avg(c.zombies, cd), p.days ? avg(p.zombies, pd) : null, 'n', true],
      ['Studied with the timer', c.timed, p.days ? p.timed : null, 'min', false],
      ...(c.regret != null ? [['Time you\'d take back', c.regret, p.regret, 'min', true]] : []),
      ...(c.sleep != null ? [['Sleep a night', c.sleep, p.sleep, 'min', false]] : []),
      ...(c.steps != null ? [['Steps a day', c.steps, p.steps, 'n', false]] : []),
      ...(c.shown ? [['Rule screens you closed on', c.held / c.shown * 100, p.shown ? p.held / p.shown * 100 : null, '%', false]] : []),
      ...(c.reels ? [['Reels and Shorts closed', c.reels, p.days ? p.reels : null, 'n', null]] : []),
    ].filter(x => x[1] > 0 || (x[2] || 0) > 0);
    const show = (v, u) => v == null ? '–' : u === 'min' ? fmt(v) : u === '%' ? Math.round(v) + '%' : Math.round(v).toLocaleString('en-IN');

    const exp = loadExp();
    const res = exp ? FA.experimentResult(P, exp, Date.now()) : null;
    const options = FA.pickExperiment(P, r, Date.now());
    const pick = options[expIdx % options.length];
    let expHtml;
    if (exp && res && !res.done) {
      expHtml = `<div class="exp"><div class="kicker mono">Trying this week</div><div class="t">${esc(exp.title)}</div>
        <p class="note">${esc(exp.how)}</p>
        <p class="note" style="margin-top:8px">${res.days ? `After ${res.days} day${res.days === 1 ? '' : 's'}: ${expNum(exp, res.now)} a day, against ${expNum(exp, res.baseline)} before. ${res.same ? 'No change yet.' : res.better ? 'Going the right way.' : 'Not yet.'}` : 'It starts tomorrow. The review will tell you how it went.'}</p>
        <button class="btn ghost" id="expStop" type="button">Stop this experiment</button></div>`;
    } else {
      const done = exp && res && res.done
        ? `<p class="note" style="margin-bottom:10px"><b>Last experiment, ${esc(exp.title)}:</b> ${expNum(exp, res.baseline)} a day before, ${expNum(exp, res.now)} a day during it. ${res.same ? 'About the same.' : res.better ? 'It worked.' : 'It didn\'t move the number.'}</p>` : '';
      expHtml = `<div class="exp">${done}<div class="kicker mono">Try this next week</div><div class="t">${esc(pick.title)}</div>
        <p class="note">${esc(pick.how)}</p>
        <div class="btnrow"><button class="btn" id="expTry" type="button">I'll try it</button><button class="btn ghost" id="expNext" type="button">Another idea</button></div></div>`;
    }

    box.innerHTML = `<div class="rvbig"><span class="v">${hmHtml(avg(c.screen, cd))}</span><span class="mono note">a day</span>
        <span class="delta ${deltaCls}">${deltaTxt}</span></div>
      <div class="rvtable">${rows.map(([label, now, was, unit, lower]) => {
        const d = was == null ? null : now - was;
        const good = d == null || lower == null || Math.abs(d) < (unit === 'min' ? 5 : 1) ? null : (lower ? d < 0 : d > 0);
        return `<div class="rvrow"><span>${esc(label)}</span><b>${show(now, unit)}</b><span class="was">${was == null ? '' : 'was ' + show(was, unit)}</span>
          ${good == null ? '' : `<span class="ch" style="color:${good ? '#3FB27F' : 'var(--rust)'}">${good ? '▼ better' : '▲ worse'} by ${unit === '%' ? Math.round(Math.abs(d)) + ' points' : unit === 'min' ? fmt(Math.abs(d)) : Math.round(Math.abs(d)).toLocaleString('en-IN')}</span>`}</div>`;
      }).join('')}</div>
      ${r.top.length ? `<div class="block"><div class="kicker mono">Most used this week</div>${r.top.map(t =>
        `<div class="li">${iconHtml(t.pkg, t.n, 'ic')}<div class="nm">${esc(t.n)}</div><div class="val">${fmt(t.m)}</div>
         <div class="sub mono">${t.prev ? `${t.m < t.prev ? 'down' : 'up'} ${fmt(Math.abs(t.m - t.prev))} on last week` : 'new this week'}</div></div>`).join('')}</div>` : ''}
      ${expHtml}`;

    if ($('expTry')) $('expTry').onclick = () => { saveExp(pick); expIdx = 0; renderReview(); };
    if ($('expNext')) $('expNext').onclick = () => { expIdx++; renderReview(); };
    if ($('expStop')) $('expStop').onclick = () => { saveExp(null); renderReview(); };
  }
  const expNum = (exp, v) => exp.metric === 'zombies' || exp.metric === 'unlocks' ? String(Math.round(v * 10) / 10) : fmt(v);

  /** The review as a shareable picture: the headline numbers only. */
  async function drawReviewCard() {
    const r = REVIEW; if (!r || !r.enough) return null;
    const cv = $('reviewCard'), g = cv.getContext('2d');
    try { await Promise.all(['900 60px Montserrat', '700 30px "Courier Prime"', '700 40px Caveat'].map(f => document.fonts.load(f))); } catch (e) {}
    const INK = '#151515', BONE = '#EFEBE2', RUST = '#C7503A', MUTE = '#8D897F';
    const W = cv.width, H = cv.height;
    g.fillStyle = INK; g.fillRect(0, 0, W, H);
    g.strokeStyle = 'rgba(239,235,226,.06)'; g.lineWidth = 1;
    for (let x = 0; x < W; x += 36) { g.beginPath(); g.moveTo(x, 0); g.lineTo(x, H); g.stroke(); }
    for (let y = 0; y < H; y += 36) { g.beginPath(); g.moveTo(0, y); g.lineTo(W, y); g.stroke(); }
    const c = r.cur, p = r.prev, cd = Math.max(1, c.days), pd = Math.max(1, p.days);
    const d0 = new Date(r.from), d1 = new Date(r.to - 1);
    const fd = d => d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
    g.fillStyle = RUST; g.font = '700 30px "Courier Prime"'; g.textAlign = 'left';
    g.fillText(`FOOTPRINT · WEEK OF ${fd(d0).toUpperCase()} – ${fd(d1).toUpperCase()}`, 80, 130);
    g.fillStyle = BONE; g.font = '900 200px Montserrat';
    const m = Math.round(c.screen / cd);
    g.fillText(`${Math.floor(m / 60)}h${String(m % 60).padStart(2, '0')}`, 70, 340);
    g.fillStyle = MUTE; g.font = '700 40px Caveat'; g.fillText('on my phone, a day', 84, 400);
    if (p.days) {
      const ch = m - Math.round(p.screen / pd);
      g.fillStyle = Math.abs(ch) < 10 ? MUTE : ch < 0 ? '#3FB27F' : RUST; g.font = '700 34px "Courier Prime"';
      g.fillText(Math.abs(ch) < 10 ? 'ABOUT THE SAME AS LAST WEEK' : `${ch < 0 ? '▼' : '▲'} ${fmt(Math.abs(ch)).toUpperCase()} A DAY ${ch < 0 ? 'LESS' : 'MORE'} THAN LAST WEEK`, 84, 470);
    }
    const lines = [
      ['UNLOCKS A DAY', String(Math.round(c.unlocks / cd))],
      ['AFTER 11 PM, A NIGHT', fmt(c.late / cd)],
      ['STUDIED WITH THE TIMER', fmt(c.timed)],
      ...(c.shown ? [['RULE SCREENS HELD', Math.round(c.held / c.shown * 100) + '%']] : []),
      ...(c.reels ? [['REELS AND SHORTS CLOSED', String(c.reels)]] : []),
    ].slice(0, 5);
    let y = 600;
    for (const [k, v] of lines) {
      g.strokeStyle = 'rgba(239,235,226,.25)'; g.beginPath(); g.moveTo(80, y - 60); g.lineTo(W - 80, y - 60); g.stroke();
      g.fillStyle = MUTE; g.font = '700 30px "Courier Prime"'; g.textAlign = 'left'; g.fillText(k, 80, y);
      g.fillStyle = BONE; g.font = '900 56px Montserrat'; g.textAlign = 'right'; g.fillText(v, W - 80, y + 6);
      y += 110;
    }
    const exp = loadExp();
    g.textAlign = 'left'; g.fillStyle = RUST; g.font = '700 44px Caveat';
    if (exp) g.fillText(`Trying next: ${exp.title}`.slice(0, 48), 80, H - 170);
    g.fillStyle = MUTE; g.font = '700 26px "Courier Prime"'; g.fillText('NO DATA LEFT THIS PHONE', 80, H - 90);
    return cv.toDataURL('image/png').split(',')[1];
  }
  $('shareReview').addEventListener('click', async () => {
    if (src.kind !== 'app') { setBanner('Sharing works in the app.'); return; }
    const png = await drawReviewCard();
    if (!png) return;
    try { await src.share(png); } catch (e) { setBanner(e.message + '.'); }
  });

  // ---------- study timer ----------
  // FOCUS mirrors the app's session: {active, start, end (0 = stopwatch), subject, lock, plan, canLock, distract, subjects}
  let FOCUS = { active: false, subjects: [], canLock: false, distract: 0, plan: 25, lock: true };
  const MIN_MS = 60000;
  let timerBusy = false, stopArmed = false;
  const draft = (() => { try { return JSON.parse(store.get('fp-timer') || '{}'); } catch (e) { return {}; } })();
  const saveDraft = () => store.set('fp-timer', JSON.stringify(draft));

  async function loadFocus() {
    if (src.kind !== 'app') return;                         // sample mode keeps its own in-memory session
    try { FOCUS = await src.focus(); } catch (e) {}
  }

  const mmss = ms => {
    const t = Math.max(0, Math.round(ms / 1000)), h = Math.floor(t / 3600), m = Math.floor(t / 60) % 60, sec = t % 60;
    return (h ? h + ':' + String(m).padStart(2, '0') : String(m)) + ':' + String(sec).padStart(2, '0');
  };
  const running = () => (FOCUS.active ? { start: FOCUS.start, subject: FOCUS.subject } : null);

  function renderTimer() {
    const box = $('timer');
    if (!box) return;
    const sum = FA.studySummary(P, Date.now(), running());
    const facts = `<div class="facts">
        ${factLine('TODAY', sum.today ? `${fmt(sum.today)} timed over ${sum.sessionsToday} session${sum.sessionsToday === 1 ? '' : 's'}.` : 'Nothing timed yet today.')}
        ${sum.week ? factLine('THIS WEEK', `${fmt(sum.week)} timed${sum.streak > 1 ? `, ${sum.streak} days in a row` : ''}.`) : ''}
      </div>
      ${sum.subjects.some(x => x.m >= 1) ? `<div class="block"><div class="kicker mono">By subject, last 7 days</div>${sum.subjects.filter(x => x.m >= 1).slice(0, 6).map(x =>
        `<div class="subj"><span>${esc(x.name)}</span><b class="mono">${fmt(x.m)}</b><div class="bar"><i style="width:${(x.m / sum.subjects[0].m * 100).toFixed(1)}%"></i></div></div>`).join('')}</div>` : ''}`;

    if (FOCUS.active) {
      const countdown = FOCUS.end > 0;
      box.innerHTML = `<div class="timerhead"><span class="kicker mono">${countdown ? 'Left' : 'Studying for'}</span><span class="mono hint">${esc(FOCUS.subject)}</span></div>
        <div class="clockbig" id="clockNow">${mmss(countdown ? FOCUS.end - Date.now() : Date.now() - FOCUS.start)}</div>
        <p class="note">${FOCUS.lock ? (FOCUS.canLock ? `${FOCUS.distract} distracting app${FOCUS.distract === 1 ? ' is' : 's are'} locked until it ends.` : 'Allow "show over other apps" in Settings so apps can be locked.') : 'Nothing is locked.'}
          ${countdown ? ` Ends at ${clock(FOCUS.end)}.` : ' Stops by itself after 4 hours.'}</p>
        <button class="btn ${stopArmed ? '' : 'ghost'}" id="tStop" type="button">${stopArmed ? 'Tap again to stop' : 'Stop and save'}</button>
        ${facts}`;
      $('tStop').onclick = async () => {
        if (!stopArmed) { stopArmed = true; renderTimer(); setTimeout(() => { if (stopArmed) { stopArmed = false; renderTimer(); } }, 4000); return; }
        stopArmed = false;
        await stopTimer();
      };
      return;
    }

    const minutes = Number.isFinite(draft.minutes) ? draft.minutes : (FOCUS.plan || 25);
    const lock = draft.lock !== false;
    const subjects = [...new Set([...(FOCUS.subjects || []), ...(draft.recent || [])])].slice(0, 6);
    box.innerHTML = `<label class="field"><span class="mono">Subject</span><input id="tSubj" maxlength="40" placeholder="e.g. DBMS" value="${esc(draft.subject || '')}"></label>
      ${subjects.length ? `<div class="chips">${subjects.map(x => `<button type="button" class="mono" data-subj="${esc(x)}">${esc(x)}</button>`).join('')}</div>` : ''}
      <div class="segctl lenctl" role="group" aria-label="Length">${[[25, '25 min'], [50, '50 min'], [90, '90 min'], [0, 'Stopwatch']].map(([v, l]) =>
        `<button type="button" class="mono" data-len="${v}" aria-pressed="${v === minutes}">${l}</button>`).join('')}</div>
      <label class="check"><input type="checkbox" id="tLock" ${lock ? 'checked' : ''}>
        <span>Lock my distracting apps until it ends${src.kind === 'app' ? ` (${FOCUS.distract} app${FOCUS.distract === 1 ? '' : 's'}, chosen in Settings)` : ''}${src.kind === 'app' && !FOCUS.canLock ? '. Needs "show over other apps" in Settings.' : ''}</span></label>
      <button class="btn" id="tStart" type="button"${timerBusy ? ' disabled' : ''}>Start ${minutes ? minutes + '-minute session' : 'stopwatch'}</button>
      ${facts}`;
    $('tSubj').oninput = e => { draft.subject = e.target.value; saveDraft(); };
    box.querySelectorAll('[data-subj]').forEach(b => b.onclick = () => { draft.subject = b.dataset.subj; saveDraft(); renderTimer(); });
    box.querySelectorAll('[data-len]').forEach(b => b.onclick = () => { draft.minutes = +b.dataset.len; saveDraft(); renderTimer(); });
    $('tLock').onchange = e => { draft.lock = e.target.checked; saveDraft(); };
    $('tStart').onclick = startTimer;
  }

  async function startTimer() {
    if (timerBusy) return;
    const subject = ($('tSubj').value || '').trim() || 'Study';
    const minutes = Number.isFinite(draft.minutes) ? draft.minutes : (FOCUS.plan || 25);
    const lock = draft.lock !== false;
    draft.subject = subject;
    draft.recent = [subject, ...(draft.recent || []).filter(x => x !== subject)].slice(0, 6);
    saveDraft();
    timerBusy = true;
    try {
      if (src.kind === 'app') FOCUS = await src.focusStart(subject, minutes, lock);
      else {
        const now = Date.now();
        FOCUS = Object.assign({}, FOCUS, { active: true, start: now, end: minutes ? now + minutes * MIN_MS : 0, subject, lock, canLock: true, distract: 3 });
      }
    } catch (e) { setBanner(e.message + '.'); }
    timerBusy = false;
    renderTimer(); renderExam();
  }

  async function stopTimer() {
    try {
      if (src.kind === 'app') { FOCUS = await src.focusStop(); await refresh(); return; }
      FOCUS = Object.assign({}, FOCUS, { active: false });
    } catch (e) { setBanner(e.message + '.'); }
    renderTimer(); renderExam();
  }

  // The clock ticks once a second while it's on screen; when a countdown runs out, ask the app.
  setInterval(() => {
    if (!FOCUS.active || document.hidden || screen !== 'exam') return;
    const el = $('clockNow');
    const left = FOCUS.end > 0 ? FOCUS.end - Date.now() : null;
    if (el) el.textContent = mmss(left != null ? left : Date.now() - FOCUS.start);
    if (left != null && left <= 0) {
      if (src.kind === 'app') refresh();
      else { FOCUS = Object.assign({}, FOCUS, { active: false }); renderTimer(); }
    }
  }, 1000);

  // ---------- what you said ----------
  function renderSaid(ds) {
    const st = FA.ruleStats(P, FA.addDays(ds, -6), FA.addDays(ds, 1));
    const top = st.reasons.length ? st.reasons[0].c : 1;
    $('intentList').innerHTML = st.asked
      ? `<p class="note" style="margin-bottom:6px">${st.asked} time${st.asked === 1 ? '' : 's'} asked.${st.idle ? ` ${st.idle} of them were boredom or habit.` : ''}${st.closed ? ` You closed the app ${st.closed} time${st.closed === 1 ? '' : 's'}.` : ''}</p>` +
        st.reasons.map(r => `<div class="subj"><span>${esc(r.name)}</span><b class="mono">${r.c}×</b><div class="bar"><i style="width:${(r.c / top * 100).toFixed(1)}%"></i></div></div>`).join('') +
        st.apps.slice(0, 4).map(a => `<div class="li">${iconHtml(a.pkg, a.n, 'ic')}<div class="nm">${esc(a.n)}</div><div class="val">${a.asked}×</div>
          <div class="sub mono">${a.idle ? `${a.idle} bored or habit` : 'always for a reason'}${a.closed ? ` · closed ${a.closed}` : ''}</div></div>`).join('')
      : `<p class="empty-state mono">Turn on "Ask why before opening" for an app in Settings, and your answers show up here.</p>`;
    $('ruleList').innerHTML = st.screens.length || st.reelsTotal
      ? `<p class="note" style="margin-bottom:6px">How often you closed the app when a rule screen appeared.</p>` +
        st.screens.map(x => { const n = x.held + x.gave + x.walked, pct = Math.round(x.held / Math.max(1, n) * 100);
          return `<div class="subj"><span>${esc(x.name)}</span><b class="mono">held ${x.held} of ${n}${x.walked ? ` · walked ${x.walked}` : ''}</b><div class="bar"><i style="width:${pct}%"></i></div></div>`; }).join('') +
        (st.reelsTotal ? `<p class="note" style="margin-top:10px">Reels and Shorts closed ${st.reelsTotal} time${st.reelsTotal === 1 ? '' : 's'}: ${st.reels.map(r => `${esc(r.name)} ${r.c}`).join(', ')}.</p>` : '')
      : `<p class="empty-state mono">No rule screens this week. Set app rules in Settings.</p>`;
  }

  // ---------- future you ----------
  // Your goal, notes to yourself and lock-screen choices live in the app (Motivation.kt), so the
  // screens shown over other apps can use them. Sample mode keeps a copy in browser storage.
  const QUOTES = Array.isArray(window.FQ) ? window.FQ : [];
  const WHEN = [['ANY', 'Any time'], ['NIGHT', 'Late at night'], ['BLOCKED', 'Blocked or study time'], ['LIMIT', "When time's up"]];
  const UNITS = ['chapter', 'topic', 'lecture', 'unit', 'page', 'problem', 'module'];
  let FUT = { options: { verdict: true, cost: true, notes: true, quotes: true }, notes: [] };
  let fyEdit = false, fyWhen = 'ANY', fyDraft = '', quoteShift = 0, noteFocus = false;

  const demoFuture = () => { try { return JSON.parse(store.get('fp-future') || 'null') || {}; } catch (e) { return {}; } };
  /** Sample mode: apply the same changes the app would, then work out pace from sample study time. */
  function demoFutureSet(msg) {
    const d = Object.assign({ options: { verdict: true, cost: true, notes: true, quotes: true }, notes: [] }, demoFuture());
    if ('goal' in msg) d.goal = msg.goal && msg.goal.name ? Object.assign({ start: Date.now() }, d.goal && d.goal.name === msg.goal.name ? { start: d.goal.start } : {}, msg.goal) : undefined;
    if (msg.step && d.goal) d.goal.left = Math.max(0, Math.min(d.goal.total, d.goal.left - msg.step));
    if (msg.options) Object.assign(d.options, msg.options);
    if (msg.note && msg.note.trim()) { d.notes.unshift({ id: Date.now(), text: msg.note.trim().slice(0, 200), when: msg.when || 'ANY', at: Date.now() }); d.notes = d.notes.slice(0, 30); delete d.ask; }
    if (msg.delNote) d.notes = d.notes.filter(n => n.id !== msg.delNote);
    if (msg.dismissAsk) delete d.ask;
    store.set('fp-future', JSON.stringify(d));
    return demoFutureState(d);
  }
  function demoFutureState(d) {
    d = Object.assign({ options: { verdict: true, cost: true, notes: true, quotes: true }, notes: [] }, d || demoFuture());
    const out = { options: d.options, notes: d.notes, today: FA.epochDay(Date.now()), ask: d.ask };
    if (d.goal) {
      const subj = (d.goal.subject || d.goal.name).toLowerCase();
      const studyMin = Math.round((P.study || []).filter(x => x.subj.toLowerCase() === subj && x.s >= (d.goal.start || 0))
        .reduce((t, x) => t + (x.e - x.s) / MIN_MS, 0));
      const pc = FA.fyPace(d.goal, studyMin);
      out.goal = Object.assign({}, d.goal, { studyMin, pace: pc.pace, learned: pc.learned });
    }
    return out;
  }
  async function futureCall(msg) {
    if (src.kind === 'app') {
      const r = await src.bridgeCall(Object.assign({ op: msg ? 'futureSet' : 'future' }, msg || {}));
      return JSON.parse(r.body || '{}');
    }
    return msg ? demoFutureSet(msg) : demoFutureState();
  }
  async function loadFuture() { try { FUT = await futureCall(null); } catch (e) {} renderFuture(); renderFyToday(); }
  async function setFuture(msg) { try { FUT = await futureCall(msg); } catch (e) { setBanner(e.message + '.'); } renderFuture(); renderFyToday(); }

  function renderFyToday() {
    const box = $('fyToday');
    if (!box) return;
    const q = QUOTES.length ? QUOTES[(((FA.epochDay(Date.now()) * 37 + 11 + quoteShift) % QUOTES.length) + QUOTES.length) % QUOTES.length] : null;
    const g = FUT.goal;
    const d = DAYS[DAYS.length - 1];
    const dm = d ? Math.round(FA.fyDistract(P, d.ds)) : 0;
    const cost = g && g.left > 0 ? FA.fyCost(dm, g) : null;
    const v = FA.fyVerdicts(P, labels(), 1)[0];
    box.innerHTML = `<div class="kicker mono">Future you</div>
      ${q ? `<p class="qd">“${esc(q.t)}”</p>${q.by ? `<div class="by mono">— ${esc(q.by.toUpperCase())}</div>` : ''}` : ''}
      ${cost ? `<div class="cost">${fmt(dm)} on social, video and games today. ${esc(cost)}</div>` : ''}
      ${v ? `<div class="cost">You said ${esc(v.n)} ${esc(v.part)} wasn't worth it <b>${v.regretted} of the last ${v.asked}</b> times.</div>` : ''}
      <div class="row"><button class="btn ghost" id="fyNext" type="button">Another quote</button>
        <button class="btn ghost" id="fyGo" type="button">${g ? 'Your goal' : 'Set a goal'}</button></div>`;
    $('fyNext').onclick = () => { quoteShift = (quoteShift + 1 + Math.floor(Math.random() * 5)) % Math.max(1, QUOTES.length); renderFyToday(); };
    $('fyGo').onclick = () => { showScreen('exam'); setTimeout(() => { const el = $('future-section'); if (el) el.scrollIntoView(); }, 50); };
  }

  function goalForm(g) {
    const unit = (g && g.unit) || 'chapter';
    const dl = g && g.deadline ? new Date(g.deadline * 86400000).toISOString().slice(0, 10) : '';
    const exam = (examCfg() || {}).exams || [];
    const hintName = exam[0] ? exam[0].name : 'e.g. DBMS end-sem';
    return `<label class="field"><span class="mono">What are you working towards?</span><input id="gName" maxlength="40" placeholder="${esc(hintName)}" value="${esc(g ? g.name : '')}"></label>
      <div class="fields2">
        <label class="field"><span class="mono">Counted in</span><select id="gUnit">${UNITS.map(u => `<option value="${u}"${u === unit ? ' selected' : ''}>${u}s</option>`).join('')}</select></label>
        <label class="field"><span class="mono">How many in all</span><input id="gTotal" type="number" min="1" max="500" inputmode="numeric" value="${g ? g.total : 10}"></label>
        <label class="field"><span class="mono">How many left</span><input id="gLeft" type="number" min="0" max="500" inputmode="numeric" value="${g ? g.left : 10}"></label>
        <label class="field"><span class="mono">Minutes each (guess)</span><input id="gEst" type="number" min="5" max="1000" inputmode="numeric" value="${g ? g.estimate : 60}"></label>
      </div>
      <label class="field"><span class="mono">By when (optional)</span><input id="gDate" type="date" value="${dl}"></label>
      <label class="field"><span class="mono">Study timer subject that counts</span><input id="gSubj" maxlength="40" placeholder="Same as the goal" value="${esc(g && g.subject && g.subject !== g.name ? g.subject : '')}"></label>
      <p class="note">Your guess is used until you've finished 2. After that, Footprint uses your real pace from timed study.</p>
      <button class="btn" id="gSave" type="button">Save goal</button>
      ${g ? '<button class="btn ghost" id="gCancel" type="button">Cancel</button>' : ''}`;
  }

  function renderFuture() {
    const box = $('future');
    if (!box) return;
    const g = FUT.goal, o = FUT.options || {};
    let html = '';
    if (!g || fyEdit) html += `<div class="kicker mono">Your goal</div>${goalForm(g)}`;
    else {
      const done = g.total - g.left;
      const d = DAYS[DAYS.length - 1];
      const dm = d ? Math.round(FA.fyDistract(P, d.ds)) : 0;
      const cost = g.left > 0 ? FA.fyCost(dm, g) : null;
      const dead = FA.fyDeadline(g, FUT.today || FA.epochDay(Date.now()));
      html += `<div class="goalcard">
        <div class="kicker mono">Your goal</div>
        <h3>${esc(g.name)}</h3>
        <div class="mono">${done} of ${g.total} ${esc(FA.fyPlural(g.unit, g.total))} done</div>
        <div class="bar"><i style="width:${(done / g.total * 100).toFixed(1)}%"></i></div>
        ${dead ? `<p>${esc(dead)}</p>` : ''}
        <p class="note">${g.learned ? `Your pace: ${FA.fyHm(Math.round(g.pace))} per ${esc(g.unit)}, from ${fmt(g.studyMin)} of timed ${esc(g.subject || g.name)}.`
          : `Using your guess of ${Math.round(g.pace)} minutes per ${esc(g.unit)}. Finish 2 with the study timer on "${esc(g.subject || g.name)}" and your real pace takes over.`}</p>
        ${cost ? `<p><b>${fmt(dm)}</b> on social, video and games today. ${esc(cost)}</p>` : ''}
        <div class="btnrow"><button class="btn" id="gDone" type="button"${g.left <= 0 ? ' disabled' : ''}>I finished a ${esc(g.unit)}</button>
          <button class="btn ghost" id="gUndo" type="button"${done <= 0 ? ' disabled' : ''}>Undo</button></div>
        <div class="btnrow"><button class="btn ghost" id="gEdit" type="button">Edit</button><button class="btn danger" id="gDel" type="button">Remove</button></div>
      </div>`;
    }

    const vs = FA.fyVerdicts(P, labels(), 5);
    html += `<div class="block"><div class="kicker mono">In your own words</div>${vs.length
      ? `<div class="verdicts">${vs.map(v => `<div class="verdict">${esc(v.n)} ${esc(v.part)}: <b>${v.regretted} of ${v.asked}</b> times you said it wasn't worth it.</div>`).join('')}</div>
         <p class="note" style="margin-top:8px">These appear when you open that app at that time of day.</p>`
      : '<p class="note">Answer "Worth it?" a few times. When you keep saying an app wasn\'t worth it at some time of day, it shows here, and on the screen when you open it then.</p>'}</div>`;

    const ask = FUT.ask;
    html += `<div class="block"><div class="kicker mono">Notes to later you</div>
      <div class="compose${ask ? ' ask' : ''}" id="compose">
        ${ask ? `<p class="hand" style="font-size:24px;margin:0 0 8px">You just studied ${fmt(ask.minutes)} of ${esc(ask.subject)}. Write one line for the you who'll want to scroll later.</p>` : '<p class="note" style="margin-bottom:8px">Write it when you feel strong. It shows up when you need it.</p>'}
        <textarea id="nText" maxlength="200" placeholder="You said you'd finish OS before Sunday. Put it down.">${esc(fyDraft)}</textarea>
        <div class="segctl" role="group" aria-label="When to show it">${WHEN.map(([k, l]) => `<button type="button" class="mono" data-when="${k}" aria-pressed="${k === fyWhen}">${l}</button>`).join('')}</div>
        <button class="btn" id="nSave" type="button">Save note</button>
        ${ask ? '<button class="btn ghost" id="nSkip" type="button">Not now</button>' : ''}
      </div>
      ${(FUT.notes || []).map(n => `<div class="notecard"><div class="k mono">${esc((WHEN.find(w => w[0] === n.when) || WHEN[0])[1].toUpperCase())} · ${new Date(n.at).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })}</div>
        <div class="t">${esc(n.text)}</div><button class="x" type="button" data-del="${n.id}" aria-label="Delete note">×</button></div>`).join('')}
    </div>`;

    html += `<div class="block"><div class="kicker mono">On the screens over your apps, show</div>
      ${[['verdict', 'What you said about that app before'], ['cost', 'What the time costs your goal'], ['notes', 'Your notes to yourself'], ['quotes', 'A quote']].map(([k, l]) =>
        `<label class="check"><input type="checkbox" data-opt="${k}" ${o[k] !== false ? 'checked' : ''}><span>${l}</span></label>`).join('')}
      <p class="note" style="margin-top:10px">${QUOTES.length} quotes, picked for the moment: late-night ones at night, focus ones in study time.</p></div>`;

    box.innerHTML = html;

    if (!g || fyEdit) {
      $('gSave').onclick = () => {
        const name = ($('gName').value || '').trim();
        if (!name) { $('gName').focus(); return; }
        const total = Math.max(1, Math.min(500, parseInt($('gTotal').value, 10) || 10));
        const left = Math.max(0, Math.min(total, parseInt($('gLeft').value, 10)));
        const date = $('gDate').value;
        const deadline = date ? Math.round(Date.UTC(+date.slice(0, 4), +date.slice(5, 7) - 1, +date.slice(8, 10)) / 86400000) : 0;
        fyEdit = false;
        setFuture({ goal: { name, unit: $('gUnit').value, total, left: Number.isFinite(left) ? left : total,
          estimate: Math.max(5, Math.min(1000, parseInt($('gEst').value, 10) || 60)), deadline, subject: ($('gSubj').value || '').trim() || name } });
      };
      if ($('gCancel')) $('gCancel').onclick = () => { fyEdit = false; renderFuture(); };
    } else {
      $('gDone').onclick = () => setFuture({ step: 1 });
      $('gUndo').onclick = () => setFuture({ step: -1 });
      $('gEdit').onclick = () => { fyEdit = true; renderFuture(); };
      let armed = false;
      $('gDel').onclick = e => {
        if (!armed) { armed = true; e.target.textContent = 'Tap again'; setTimeout(() => { armed = false; if (e.target.isConnected) e.target.textContent = 'Remove'; }, 4000); return; }
        setFuture({ goal: null });
      };
    }
    $('nText').oninput = e => { fyDraft = e.target.value; };
    box.querySelectorAll('[data-when]').forEach(b => b.onclick = () => { fyWhen = b.dataset.when; box.querySelectorAll('[data-when]').forEach(x => x.setAttribute('aria-pressed', String(x === b))); });
    $('nSave').onclick = () => {
      const t = ($('nText').value || '').trim();
      if (!t) { $('nText').focus(); return; }
      fyDraft = '';
      setFuture({ note: t, when: fyWhen });
    };
    if ($('nSkip')) $('nSkip').onclick = () => setFuture({ dismissAsk: true });
    box.querySelectorAll('[data-del]').forEach(b => b.onclick = () => setFuture({ delNote: +b.dataset.del }));
    box.querySelectorAll('[data-opt]').forEach(c => c.onchange = () => setFuture({ options: { [c.dataset.opt]: c.checked } }));
    if (noteFocus) { noteFocus = false; const c = $('compose'); if (c) { c.scrollIntoView(); $('nText').focus(); } }
  }

  // ---------- exam mode ----------
  const examCfg = () => { try { return JSON.parse(store.get('fp-exam') || 'null'); } catch (e) { return null; } };
  const saveExam = cfg => { if (cfg) store.set('fp-exam', JSON.stringify(cfg)); else store.del('fp-exam'); renderExam(); };
  function renderExam() {
    const cfg = examCfg();
    const box = $('exam');
    const e = cfg ? FA.examPlan(P, cfg) : null;

    if (!e) {
      box.innerHTML = `<label class="field"><span class="mono">Exam</span><input id="exName" maxlength="40" placeholder="e.g. DBMS end-sem"></label>
        <label class="field"><span class="mono">Date</span><input id="exDate" type="date"></label>
        <button class="btn" id="exStart" type="button">Start exam mode</button>`;
      $('exStart').onclick = () => {
        const date = $('exDate').value;
        if (!date) { $('exDate').focus(); return; }
        saveExam({ exams: [{ name: $('exName').value.trim() || 'Exam', date }], targetMin: 60,
                   labels: (cfg && cfg.labels) || {}, startedMs: Date.now() });
      };
      return;
    }

    const t = e.today, tot = t.study + t.distract;
    const left = e.daysLeft;
    const weekMax = Math.max(1, ...e.week.map(d => Math.max(d.study, d.distract)));
    const recent = new Map();
    for (const d of DAYS) for (const a2 of d.apps) recent.set(a2.pkg, (recent.get(a2.pkg) || 0) + a2.m);
    const list = [...recent].sort((x, y) => y[1] - x[1]).slice(0, 12);
    const pct = e.target ? Math.min(100, Math.round((t.study / e.target) * 100)) : 0;

    box.innerHTML = `<div class="count"><span class="heavy">${left >= 0 ? left : 0}</span>
        <span class="hand" style="font-size:28px">${left > 1 ? 'days to' : left === 1 ? 'day to' : left === 0 ? 'today:' : 'days since'} ${esc(e.name)}</span></div>

      ${e.target ? `<div class="kicker mono">Today's target</div>
        <div class="target">
          <button class="stepbtn mono" type="button" data-t="-15" aria-label="Lower the target">−</button>
          <b class="mono">${fmt(e.target)}</b>
          <button class="stepbtn mono" type="button" data-t="15" aria-label="Raise the target">+</button>
          <span class="mono hint">${t.study >= e.target ? 'done today' : `${fmt(Math.max(0, e.target - t.study))} to go`}</span>
        </div>
        <div class="tbar"><i style="width:${pct}%"></i></div>
        ${e.streak > 1 ? `<p class="note">${e.streak} days in a row at or above target.</p>` : ''}` : ''}

      <div class="kicker mono">Today: study vs distraction</div>
      <div class="ratio">${tot ? `<div class="st" style="width:${t.study / tot * 100}%">${t.study >= 1 ? 'Study ' + fmt(t.study) : ''}</div><div class="ds" style="width:${t.distract / tot * 100}%">${t.distract >= 1 ? 'Scroll ' + fmt(t.distract) : ''}</div>` : '<div class="none mono">Nothing yet today</div>'}</div>

      <div class="exweek" aria-label="Last 7 days, study and distraction">${e.week.map(d => `<div class="d" title="${d.key}"><i class="s" style="height:${d.study / weekMax * 100}%"></i><i class="x" style="height:${d.distract / weekMax * 100}%"></i></div>`).join('')}</div>
      <div class="wk mono">${e.week.map(d => `<span>${new Date(d.ds).toLocaleDateString('en-GB', { weekday: 'narrow' })}</span>`).join('')}</div>

      <div class="facts">
        ${factLine('SO FAR', `${fmt(e.studySince)} studying over ${e.days} day${e.days > 1 ? 's' : ''}, about ${fmt(e.perDay)} a day.`)}
        ${e.bestDay && e.bestDay.study > 0 ? factLine('BEST DAY', `${fmt(e.bestDay.study)} on ${new Date(e.bestDay.ds).toLocaleDateString('en-GB', { weekday: 'long' })}.`) : ''}
        ${left > 0 && e.perDay > 0 ? factLine('AT THIS RATE', `About ${fmt(e.projectedMin)} more before ${esc(e.name)}.`) : ''}
        ${factLine('THIS WEEK', `${fmt(e.studyWeek)} studying against ${fmt(e.distractWeek)} on distractions.`)}
        ${t.timed >= 1 ? factLine('TIMED', `${fmt(t.timed)} of today's study came from the timer.`) : ''}
      </div>

      <div class="kicker mono">Exams</div>
      <div class="exams">${e.exams.map((x, i) => `<div class="exrow"><span class="d">${new Date(x.ds).toLocaleDateString('en-GB', { day: '2-digit', month: 'short' })}</span>
        <span class="n">${esc(x.name)}</span><button type="button" data-rm="${i}">remove</button></div>`).join('')}</div>
      <label class="field"><span class="mono">Add another exam</span><input id="exName2" maxlength="40" placeholder="e.g. OS unit test"></label>
      <label class="field"><span class="mono">Date</span><input id="exDate2" type="date"></label>
      <button class="btn" id="exAdd" type="button">Add exam</button>

      <details class="edit"><summary class="mono">Choose which apps count</summary><div class="labels">${list.map(([pkg]) => {
        const k = FA.classify(P, pkg, cfg), n = P.name(pkg);
        return `<div class="li">${iconHtml(pkg, n, 'ic')}<div class="nm">${esc(n)}</div>
          <div class="segctl" role="group" aria-label="${esc(n)}">${['study', 'neutral', 'distract'].map(v => `<button type="button" class="mono" data-pkg="${esc(pkg)}" data-v="${v}" aria-pressed="${k === v}">${v === 'distract' ? 'Distraction' : v[0].toUpperCase() + v.slice(1)}</button>`).join('')}</div></div>`;
      }).join('')}</div></details>
      <button class="btn danger" id="exEnd" type="button">End exam mode</button>`;

    box.querySelectorAll('.segctl button').forEach(b => b.onclick = () => {
      const c = examCfg(); c.labels = c.labels || {}; c.labels[b.dataset.pkg] = b.dataset.v; saveExam(c);
      const det = $('exam').querySelector('details'); if (det) det.open = true;
    });
    box.querySelectorAll('[data-t]').forEach(b => b.onclick = () => {
      const c = examCfg();
      c.targetMin = Math.min(600, Math.max(15, (c.targetMin || 60) + (+b.dataset.t)));
      saveExam(c);
    });
    box.querySelectorAll('[data-rm]').forEach(b => b.onclick = () => {
      const c = examCfg();
      const all = (c.exams && c.exams.length ? c.exams : [{ name: c.name, date: c.date }]).slice();
      all.sort((x, y) => FA.fromKey(x.date) - FA.fromKey(y.date));
      all.splice(+b.dataset.rm, 1);
      if (!all.length) { saveExam(null); return; }          // the last one out ends exam mode
      c.exams = all; delete c.date; delete c.name;
      saveExam(c);
    });
    $('exAdd').onclick = () => {
      const date = $('exDate2').value;
      if (!date) { $('exDate2').focus(); return; }
      const c = examCfg();
      const all = (c.exams && c.exams.length ? c.exams : [{ name: c.name, date: c.date }]).slice();
      all.push({ name: $('exName2').value.trim() || 'Exam', date });
      c.exams = all; delete c.date; delete c.name;
      saveExam(c);
    };
    $('exEnd').onclick = () => saveExam(null);
  }

  const factLine = (label, text) => `<div class="fact"><b>${esc(label)}</b><span>${text}</span></div>`;

  // ---------- weekly receipt ----------
  let receiptBusy = false;
  async function drawReceipt() {
    if (receiptBusy) return; receiptBusy = true;
    try {
      const rc = FA.receipt(P);
      const cv = $('receipt'), g = cv.getContext('2d');
      try { await Promise.all(['900 60px Montserrat', '700 30px "Courier Prime"', '400 30px "Courier Prime"', '700 40px Caveat'].map(f => document.fonts.load(f))); } catch (e) {}
      const W = 1080, H = 1350, INK = '#151515', PAPER = '#EDE7DA', RUST = '#C7503A', BLUE = '#3F6FE0';
      g.clearRect(0, 0, W, H);
      g.fillStyle = INK; g.fillRect(0, 0, W, H);
      g.strokeStyle = 'rgba(239,235,226,.05)'; g.lineWidth = 1;
      for (let x = 0; x < W; x += 54) { g.beginPath(); g.moveTo(x + .5, 0); g.lineTo(x + .5, H); g.stroke(); }
      for (let y = 0; y < H; y += 54) { g.beginPath(); g.moveTo(0, y + .5); g.lineTo(W, y + .5); g.stroke(); }

      g.save(); g.translate(W / 2, H / 2); g.rotate(-1.5 * Math.PI / 180); g.translate(-W / 2, -H / 2);
      const X = 110, Y = 70, RW = 860, RH = 1210, T = 14;
      const paperPath = off => {
        g.beginPath(); g.moveTo(X + off, Y + off);
        for (let x = X; x < X + RW; x += T * 2) { g.lineTo(x + T + off, Y + T + off); g.lineTo(x + 2 * T + off, Y + off); }
        g.lineTo(X + RW + off, Y + RH + off);
        for (let x = X + RW; x > X; x -= T * 2) { g.lineTo(x - T + off, Y + RH - T + off); g.lineTo(x - 2 * T + off, Y + RH + off); }
        g.closePath();
      };
      paperPath(14); g.fillStyle = BLUE; g.fill();
      paperPath(0); g.fillStyle = PAPER; g.fill();

      const cx = X + RW / 2, L = X + 60, R = X + RW - 60;
      g.fillStyle = INK; g.textAlign = 'center'; g.textBaseline = 'alphabetic';
      g.font = '900 78px Montserrat'; g.fillText('FOOTPRINT', cx, Y + 120);
      g.font = '700 26px "Courier Prime"'; g.fillText('W E E K L Y   R E C E I P T', cx, Y + 162);
      const range = `${new Date(FA.fromKey(rc.from)).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })} – ${new Date(FA.fromKey(rc.to)).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })}`.toUpperCase();
      g.font = '400 26px "Courier Prime"'; g.fillText(range, cx, Y + 198);
      const dash = y => { g.save(); g.setLineDash([10, 8]); g.strokeStyle = INK; g.lineWidth = 2; g.beginPath(); g.moveTo(L, y); g.lineTo(R, y); g.stroke(); g.restore(); };
      const line = (y, left, right, bold) => {
        g.font = `${bold ? 700 : 400} 30px "Courier Prime"`;
        g.textAlign = 'left'; g.fillText(left, L, y);
        g.textAlign = 'right'; g.fillText(right, R, y);
        const lw = g.measureText(left).width, rw = g.measureText(right).width;
        g.textAlign = 'left'; g.font = '400 30px "Courier Prime"';
        const dotW = g.measureText('.').width; let dots = '';
        const room = R - L - lw - rw - 24; for (let k = 0; k < Math.floor(room / dotW); k++) dots += '.';
        g.fillStyle = 'rgba(21,21,21,.45)'; g.fillText(dots, L + lw + 12, y); g.fillStyle = INK;
      };
      dash(Y + 230);
      let y = Y + 282;
      if (!rc.top.length) { g.textAlign = 'center'; g.font = '400 30px "Courier Prime"'; g.fillText('NO SCREEN TIME RECORDED YET', cx, y + 40); y += 200; }
      rc.top.forEach(a => { line(y, a.n.toUpperCase().slice(0, 22), fmt(a.m), true); y += 46; });
      y = Math.max(y, Y + 500);
      dash(y); y += 52;
      g.textAlign = 'left'; g.font = '700 26px "Courier Prime"'; g.fillText('TOTAL SCREEN TIME', L, y);
      g.textAlign = 'right'; g.font = '400 26px "Courier Prime"'; g.fillText(`AVG ${fmt(rc.avgScreen).toUpperCase()}/DAY`, R, y);
      y += 104; g.textAlign = 'left'; g.fillStyle = RUST; g.font = '900 112px Montserrat'; g.fillText(fmt(rc.screen).toUpperCase(), L - 6, y); g.fillStyle = INK;
      y += 38; dash(y); y += 50;
      line(y, 'UNLOCKS', rc.unlocks.toLocaleString('en-IN')); y += 44;
      line(y, 'ZOMBIE UNLOCKS', String(rc.zombies)); y += 44;
      line(y, 'STEPS', rc.steps.toLocaleString('en-IN')); y += 44;
      line(y, 'AVG SLEEP', rc.avgSleep != null ? fmt(rc.avgSleep).toUpperCase() : '—'); y += 44;
      line(y, 'LONGEST DOOM-SCROLL', rc.longestDoom ? `${fmt(rc.longestDoom.m).toUpperCase()} ${rc.longestDoom.n.toUpperCase().slice(0, 10)}` : 'NONE'); y += 44;
      line(y, 'FIRST APP AFTER UNLOCK', rc.firstTop ? rc.firstTop.n.toUpperCase().slice(0, 14) : '—'); y += 26;
      dash(y + 12);
      g.textAlign = 'center'; g.fillStyle = RUST; g.font = '700 46px Caveat'; g.fillText('thank you for looking up.', cx, y + 70);
      g.fillStyle = INK;
      let bx = cx - 250, h = rc.screen + rc.unlocks * 7 + rc.steps;
      while (bx < cx + 250) { const w = 2 + (h % 5); h = Math.floor(h / 3) + 7919 * (bx | 0) % 997; g.fillRect(bx, y + 88, w, 52); bx += w + 3 + (h % 4); }
      g.font = '400 20px "Courier Prime"'; g.fillText('NO DATA LEFT THIS PHONE', cx, y + 170);
      window.__receiptBottom = y + 170;
      g.restore();
    } finally { receiptBusy = false; }
  }
  $('shareBtn').addEventListener('click', async () => {
    if (src.kind !== 'app') { setBanner('Sharing works in the app.'); return; }
    const png = $('receipt').toDataURL('image/png').split(',')[1];
    try { await src.share(png); } catch (e) { setBanner(e.message + '.'); }
  });

  // ---------- data buttons ----------
  const needApp = () => { if (src.kind !== 'app') { setBanner('This works in the app.'); return true; } return false; };
  $('exportJson').onclick = async () => { if (!needApp()) { try { await src.exportData('json'); } catch (e) { setBanner(e.message + '.'); } } };
  $('exportCsv').onclick = async () => { if (!needApp()) { try { await src.exportData('csv'); } catch (e) { setBanner(e.message + '.'); } } };
  $('wipeBtn').onclick = async () => {
    if (needApp()) return;
    try { const r = await src.wipe(); if (r.status === 200 && JSON.parse(r.body).wiped) { store.del('fp-exam'); refresh(); } } catch (e) { setBanner(e.message + '.'); }
  };

  // ---------- top buttons ----------
  $('settingsBtn').hidden = src.kind !== 'app';
  $('syncBtn').hidden = src.kind !== 'app';
  $('settingsBtn').addEventListener('click', () => src.settings());
  $('syncBtn').addEventListener('click', async () => {
    setBanner('Reading the latest from your phone…');
    try { await src.collect(); } catch (e) {}
    refresh();
  });

  // ---------- screens ----------
  const SCREENS = ['today', 'worth', 'why', 'trends', 'exam', 'you'];
  const tabs = [...document.querySelectorAll('.nav button[data-s]')];
  const scrollMemory = {};
  let screen = 'today';

  function showScreen(name, push) {
    if (name === 'note') {                          // the "study session done" notification opens the note box
      noteFocus = true;
      showScreen('exam', push);
      loadFuture();                                 // fetch the "you just studied" prompt fresh
      return;
    }
    if (name === 'review') {                        // the weekly notification opens the review itself
      showScreen('you', push);
      setTimeout(() => { const el = $('review'); if (el) el.scrollIntoView(); }, 50);
      return;
    }
    if (!SCREENS.includes(name)) name = 'today';
    if (name !== screen) scrollMemory[screen] = scrollY;
    screen = name;
    SCREENS.forEach(k => $('s-' + k).classList.toggle('on', k === name));
    tabs.forEach(b => b.setAttribute('aria-current', b.dataset.s === name ? 'page' : 'false'));
    $('daybar').classList.toggle('on', !!$('s-' + name).dataset.daybar);
    scrollTo({ top: scrollMemory[name] || 0, behavior: 'instant' in document.documentElement.style ? 'instant' : 'auto' });
    if (push !== false && location.hash !== '#' + name) history.pushState({ screen: name }, '', '#' + name);
    onScroll();
  }
  tabs.forEach(b => b.addEventListener('click', () => {
    if (b.dataset.s === screen) scrollTo({ top: 0, behavior: 'smooth' });   // tapping the current tab goes up
    else showScreen(b.dataset.s);
  }));
  // Android back button: the app calls webView.goBack(), which pops one of these entries
  addEventListener('popstate', e => showScreen((e.state && e.state.screen) || (location.hash || '#today').slice(1), false));

  window.fpShow = name => showScreen(String(name));

  // ---------- scroll progress ----------
  const NT = 44;
  $('prog').innerHTML = '<i></i>'.repeat(NT);
  const ticks = [...$('prog').children];
  function onScroll() {
    const h = document.documentElement.scrollHeight - innerHeight;
    const f = h > 0 ? Math.min(1, scrollY / h) : 0, on = Math.round(f * NT);
    ticks.forEach((t, k) => t.classList.toggle('on', k < on));
  }
  addEventListener('scroll', onScroll, { passive: true });

  // ---------- start ----------
  showScreen((location.hash || '#today').slice(1), false);
  select(cur); onScroll(); renderWorth(); renderTriage(); renderPatterns(); renderTimer(); renderExam(); renderReview();
  renderFyToday(); renderFuture();
  refresh();
})();
