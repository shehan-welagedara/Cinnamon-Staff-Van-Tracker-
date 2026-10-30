/**
 * Van Tracker – scheduled Excel report by email (Google Apps Script, free).
 *
 * Runs in ANY Google account (e.g. kandymystservices@gmail.com) – it does not need to be
 * the account that owns the Firebase project. It signs in to the app like a normal user
 * (a dedicated read-only "report" user), reads the trips and requests of the period,
 * builds a Google Sheet (Summary / Trips / Passengers / Drivers / Vehicles / Fuel log),
 * exports it as .xlsx and emails it.
 *
 * Recipients, schedule (monthly or weekly, day and hour), subject, message and sheets are
 * edited by administrators in the web dashboard (Administration → Email reports), which
 * stores them in Firestore config/report. The dashboard's "Send now" button is picked up
 * within the hour. Every run is logged back to the dashboard.
 *
 * Setup (once) – see README "Email reports":
 *   1. In the Van Tracker app/dashboard create a user for the report, role HOD.
 *   2. script.google.com (signed in as kandymystservices@gmail.com) → New project →
 *      paste this file.
 *   3. Project settings → Script properties:
 *        FIREBASE_API_KEY     apiKey from the web config
 *        FIREBASE_PROJECT_ID  projectId from the web config
 *        REPORT_EMAIL         the report user's email
 *        REPORT_PASSWORD      the report user's password
 *        RECIPIENTS           optional fallback until recipients are set in the dashboard
 *        TIMEZONE             optional, default Asia/Colombo
 *        REFERER              optional, only if the API key is restricted to websites,
 *                             e.g. https://shehan-welagedara.github.io/
 *   4. Run testNow() once (approve the permissions) – you get last month's report.
 *   5. Run setupTrigger() once – the script then checks every hour and sends on the
 *      schedule set in the dashboard.
 */

var SCRIPT_VERSION = 2;

var DEFAULTS = {
  enabled: true, frequency: 'monthly', dayOfMonth: 1, dayOfWeek: 1, hour: 7,
  recipients: '', cc: '', subject: 'Van Tracker {frequency} report – {period}', message: '',
  sheets: { trips: true, passengers: true, drivers: true, vehicles: true, fuel: true },
};

/** Installs the hourly check (replaces older triggers of this script). */
function setupTrigger() {
  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (['sendMonthlyReport', 'tick'].indexOf(t.getHandlerFunction()) >= 0) ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger('tick').timeBased().everyHours(1).create();
  tick();
}

/** Sends the previous period's report right now to the configured recipients (use it to test). */
function testNow() {
  var c = context_();
  send_(c, period_(c, 'previous'), recipientsOf_(c), 'test');
}

/** Old name kept so an existing monthly trigger keeps working. */
function sendMonthlyReport() { testNow(); }

/** Runs every hour: heartbeat, "Send now" requests from the dashboard, and the schedule. */
function tick() {
  var c = context_();
  var props = PropertiesService.getScriptProperties();
  var nowParts = parts_(new Date(), c.tz);
  writeDoc_(c, 'reportRuns/_heartbeat', {
    lastTickAt: Date.now(), scriptVersion: SCRIPT_VERSION, timezone: c.tz, account: Session.getEffectiveUser().getEmail(),
  });

  // 1. Manual "Send now" from the dashboard.
  var req = Number(c.cfg.sendNowAt || 0);
  if (req > Number(props.getProperty('lastManualAt') || 0)) {
    props.setProperty('lastManualAt', String(req));
    var to = String(c.cfg.sendNowTo || '').trim() || recipientsOf_(c);
    safeSend_(c, period_(c, c.cfg.sendNowPeriod === 'current' ? 'current' : 'previous'), to, 'manual');
  }

  // 2. Schedule.
  if (!c.cfg.enabled) return;
  var due;
  if (c.cfg.frequency === 'weekly') due = nowParts.dow === Number(c.cfg.dayOfWeek || 1);
  else due = nowParts.day === Math.min(Number(c.cfg.dayOfMonth || 1), nowParts.daysInMonth);
  if (!due || nowParts.hour < Number(c.cfg.hour || 0)) return;
  var p = period_(c, 'previous');
  var key = c.cfg.frequency + ':' + p.key;
  if (props.getProperty('lastSentKey') === key) return;
  props.setProperty('lastSentKey', key);
  safeSend_(c, p, recipientsOf_(c), 'schedule');
}

// ------------------------------------------------------------------ report

function safeSend_(c, p, to, trigger) {
  try { send_(c, p, to, trigger); } catch (e) {
    logRun_(c, { ok: false, error: String(e && e.message || e), period: p.label, to: to, trigger: trigger });
  }
}

function send_(c, p, to, trigger) {
  if (!to) throw new Error('No recipients – add them in the dashboard (Administration → Email reports).');
  var cfg = c.cfg, cur = c.settings.currency || 'LKR', tz = c.tz;
  var sheets = cfg.sheets || DEFAULTS.sheets;

  var from = p.start - 40 * 86400000;
  var trips = query_(c, 'trips', 'createdAt', from, p.end).filter(function (t) {
    var when = t.startTime || t.plannedTime || t.createdAt;
    return when >= p.start && when < p.end && t.status !== 'CANCELLED';
  });
  var requests = query_(c, 'requests', 'createdAt', from, p.end).filter(function (r) {
    var when = r.requestedTime || r.createdAt;
    return when >= p.start && when < p.end;
  });
  var fuel = sheets.fuel ? query_(c, 'fuelLogs', 'time', p.start, p.end) : [];

  var fmt = function (ms) { return ms ? Utilities.formatDate(new Date(ms), tz, 'yyyy-MM-dd HH:mm') : ''; };
  var stopsOf = function (x) { return (x.stops || []).map(function (s) { return s.name; }).join(' → '); };
  var round = function (n, d) { var f = Math.pow(10, d || 1); return Math.round((n || 0) * f) / f; };
  var sum = function (list, k) { return list.reduce(function (a, t) { return a + (Number(t[k]) || 0); }, 0); };

  var done = trips.filter(function (t) { return t.status === 'COMPLETED'; });
  var totalKm = sum(done, 'distanceKm'), totalCost = sum(done, 'fuelCost'), totalL = sum(done, 'fuelLiters');
  var totalMin = sum(done, 'durationMin');
  var unauthorized = trips.filter(function (t) { return t.type === 'UNAUTHORIZED' || t.needsReason; }).length;
  var deliveries = requests.filter(function (r) { return r.kind === 'DELIVERY'; }).length;

  var ss = SpreadsheetApp.create('Van Tracker report – ' + p.label);
  writeSheet_(ss.getSheets()[0].setName('Summary'), ['Item', 'Value'], [
    ['Period', p.label],
    ['Completed trips', done.length],
    ['All trips (incl. planned / open)', trips.length],
    ['Distance (km)', round(totalKm)],
    ['Time on road (h)', round(totalMin / 60)],
    ['Fuel (L)', round(totalL)],
    ['Fuel cost (' + cur + ')', Math.round(totalCost)],
    ['Requests made', requests.length],
    ['Requests rejected', requests.filter(function (r) { return r.status === 'REJECTED'; }).length],
    ['Pickups / deliveries', deliveries],
    ['Movements without a request', unauthorized],
    ['Fuel filled (L)', round(sum(fuel, 'liters'))],
  ]);

  if (sheets.trips !== false) writeSheet_(ss.insertSheet('Trips'),
    ['Date', 'Title', 'Category', 'Departments', 'Route', 'Vehicle', 'Driver', 'Passengers', 'Status',
      'Start', 'End', 'Km', 'Minutes', 'Fuel L', 'Fuel cost ' + cur, 'Reason'],
    trips.map(function (t) {
      return [fmt(t.startTime || t.plannedTime), t.title, t.category, (t.departments || []).join(', '), stopsOf(t),
        t.vehicleName, t.driverName, t.passengers || 0, t.status, fmt(t.startTime), fmt(t.endTime),
        round(t.distanceKm), Math.round(t.durationMin || 0), round(t.fuelLiters), Math.round(t.fuelCost || 0), t.reason || ''];
    }));

  if (sheets.passengers !== false) writeSheet_(ss.insertSheet('Passengers'),
    ['Date', 'Passenger(s)', 'Requested by', 'Department', 'Type', 'Category', 'Purpose', 'Route', 'Seats', 'Status', 'Approved by'],
    requests.map(function (r) {
      var delivery = r.kind === 'DELIVERY';
      return [fmt(r.requestedTime), delivery ? '📦 ' + (r.deliveryItems || '') : (r.passengerNames || r.requesterName),
        r.requesterName, r.department, delivery ? 'Pickup / delivery' : 'Passenger', r.category, r.purpose,
        stopsOf(r), r.passengers || 0, r.status, r.approverName || ''];
    }));

  var group = function (key) {
    var m = {};
    done.forEach(function (t) {
      var k = t[key] || '(not set)';
      var g = m[k] || (m[k] = [k, 0, 0, 0, 0, 0]);
      g[1] += 1; g[2] += Number(t.distanceKm) || 0; g[3] += (Number(t.durationMin) || 0) / 60;
      g[4] += Number(t.fuelLiters) || 0; g[5] += Number(t.fuelCost) || 0;
    });
    return Object.keys(m).map(function (k) {
      var g = m[k]; return [g[0], g[1], round(g[2]), round(g[3]), round(g[4]), Math.round(g[5])];
    }).sort(function (a, b) { return b[2] - a[2]; });
  };
  var head = ['Trips', 'Km', 'Hours', 'Fuel L', 'Fuel cost ' + cur];
  if (sheets.drivers !== false) writeSheet_(ss.insertSheet('Drivers'), ['Driver'].concat(head), group('driverName'));
  if (sheets.vehicles !== false) writeSheet_(ss.insertSheet('Vehicles'), ['Vehicle'].concat(head), group('vehicleName'));
  if (sheets.fuel) writeSheet_(ss.insertSheet('Fuel log'), ['Date', 'Vehicle', 'Litres', 'Cost ' + cur, 'Odometer km', 'By', 'Note'],
    fuel.map(function (f) { return [fmt(f.time), f.vehicleName || f.vehicleId || '', f.liters, f.cost, f.odometerKm, f.byName, f.note]; }));
  SpreadsheetApp.flush();

  var xlsx = UrlFetchApp.fetch('https://docs.google.com/spreadsheets/d/' + ss.getId() + '/export?format=xlsx', {
    headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
  }).getBlob().setName('VanTracker-' + p.key + '.xlsx');

  var subject = String(cfg.subject || DEFAULTS.subject)
    .replace('{period}', p.label).replace('{frequency}', cfg.frequency === 'weekly' ? 'weekly' : 'monthly');
  var note = String(cfg.message || '').trim();
  var html =
    '<div style="font-family:Arial,sans-serif;color:#2B2B2B">' +
    '<h2 style="color:#592C82;margin:0 0 4px">Van Tracker – ' + p.label + '</h2>' +
    '<p style="color:#777;margin:0 0 16px">Kandy Myst by Cinnamon · staff transport</p>' +
    (note ? '<p>' + escapeHtml_(note).replace(/\n/g, '<br>') + '</p>' : '') +
    '<table cellpadding="6" style="border-collapse:collapse">' +
    row_('Completed trips', done.length) + row_('Distance', round(totalKm) + ' km') +
    row_('Time on road', round(totalMin / 60) + ' h') + row_('Fuel', round(totalL) + ' L') +
    row_('Fuel cost', cur + ' ' + Math.round(totalCost).toLocaleString()) +
    row_('Requests', requests.length) + row_('Pickups / deliveries', deliveries) +
    row_('Movements without a request', unauthorized) +
    '</table><p>The full report is attached as Excel.</p>' +
    '<p style="color:#777;font-size:12px">Also saved in Google Drive: ' + ss.getUrl() + '</p></div>';

  var mail = { to: to, subject: subject, htmlBody: html, attachments: [xlsx], name: 'Kandy Myst Van Tracker' };
  // CC only on the real distribution (not when a test copy goes to one address).
  if (String(cfg.cc || '').trim() && to === recipientsOf_(c)) mail.cc = String(cfg.cc).trim();
  MailApp.sendEmail(mail);
  logRun_(c, { ok: true, period: p.label, to: to, trigger: trigger, trips: done.length, km: round(totalKm), sheetUrl: ss.getUrl() });
}

// ------------------------------------------------------------------ context & periods

function context_() {
  var p = PropertiesService.getScriptProperties().getProperties();
  ['FIREBASE_API_KEY', 'FIREBASE_PROJECT_ID', 'REPORT_EMAIL', 'REPORT_PASSWORD'].forEach(function (k) {
    if (!p[k]) throw new Error('Missing script property ' + k);
  });
  var c = { p: p, tz: p.TIMEZONE || 'Asia/Colombo' };
  c.token = signIn_(p);
  var cfg = readDoc_(c, 'config/report') || {};
  Object.keys(DEFAULTS).forEach(function (k) { if (cfg[k] === undefined || cfg[k] === null || cfg[k] === '') cfg[k] = DEFAULTS[k]; });
  c.cfg = cfg;
  c.settings = readDoc_(c, 'config/settings') || {};
  return c;
}

function recipientsOf_(c) { return String(c.cfg.recipients || c.p.RECIPIENTS || '').trim(); }

function parts_(d, tz) {
  var f = Utilities.formatDate(d, tz, 'yyyy,MM,dd,HH,u').split(',').map(Number);
  var dim = new Date(f[0], f[1], 0).getDate();
  return { year: f[0], month: f[1], day: f[2], hour: f[3], dow: f[4], daysInMonth: dim };
}

function midnight_(y, m, d, tz) {
  var dt = new Date(y, m - 1, d); // normalises overflow (e.g. day 0, month 13)
  return Utilities.parseDate(Utilities.formatDate(dt, 'UTC', 'yyyy-MM-dd') + ' 00:00', tz, 'yyyy-MM-dd HH:mm').getTime();
}

/** 'previous' = last full month / week; 'current' = this month / week so far. */
function period_(c, which) {
  var n = parts_(new Date(), c.tz), tz = c.tz;
  if (c.cfg.frequency === 'weekly') {
    var thisMon = midnight_(n.year, n.month, n.day - (n.dow - 1), tz);
    var start = which === 'current' ? thisMon : thisMon - 7 * 86400000;
    var end = which === 'current' ? Date.now() : thisMon;
    var label = 'week of ' + Utilities.formatDate(new Date(start), tz, 'd MMM yyyy');
    return { start: start, end: end, label: label, key: Utilities.formatDate(new Date(start), tz, "yyyy-'W'ww") };
  }
  var ms = which === 'current' ? midnight_(n.year, n.month, 1, tz) : midnight_(n.year, n.month - 1, 1, tz);
  var me = which === 'current' ? Date.now() : midnight_(n.year, n.month, 1, tz);
  var lab = Utilities.formatDate(new Date(ms), tz, 'MMMM yyyy') + (which === 'current' ? ' (to date)' : '');
  return { start: ms, end: me, label: lab, key: Utilities.formatDate(new Date(ms), tz, 'yyyy-MM') };
}

// ------------------------------------------------------------------ helpers

function escapeHtml_(s) { return s.replace(/[&<>"]/g, function (ch) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[ch]; }); }

function row_(k, v) {
  return '<tr><td style="border-bottom:1px solid #eee">' + k + '</td><td style="border-bottom:1px solid #eee"><b>' + v + '</b></td></tr>';
}

function writeSheet_(sh, header, rows) {
  var all = [header].concat(rows);
  sh.getRange(1, 1, all.length, header.length).setValues(all.map(function (r) {
    return header.map(function (_, i) { return r[i] === undefined || r[i] === null ? '' : r[i]; });
  }));
  sh.getRange(1, 1, 1, header.length).setFontWeight('bold').setBackground('#592C82').setFontColor('#FFFFFF');
  sh.setFrozenRows(1);
  sh.autoResizeColumns(1, header.length);
}

function headers_(p, token) {
  var h = {};
  if (token) h.Authorization = 'Bearer ' + token;
  if (p.REFERER) h.Referer = p.REFERER;
  return h;
}

function base_(c) { return 'https://firestore.googleapis.com/v1/projects/' + c.p.FIREBASE_PROJECT_ID + '/databases/(default)/documents'; }

/** Signs in as the report user (Firebase Auth REST API) and returns an ID token. */
function signIn_(p) {
  var res = UrlFetchApp.fetch('https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=' + p.FIREBASE_API_KEY, {
    method: 'post', contentType: 'application/json', headers: headers_(p), muteHttpExceptions: true,
    payload: JSON.stringify({ email: p.REPORT_EMAIL, password: p.REPORT_PASSWORD, returnSecureToken: true }),
  });
  var j = JSON.parse(res.getContentText());
  if (!j.idToken) throw new Error('Sign-in failed: ' + res.getContentText());
  return j.idToken;
}

function readDoc_(c, path) {
  var res = UrlFetchApp.fetch(base_(c) + '/' + path, { headers: headers_(c.p, c.token), muteHttpExceptions: true });
  if (res.getResponseCode() === 404) return null;
  if (res.getResponseCode() !== 200) throw new Error('Reading ' + path + ' failed: ' + res.getContentText());
  return decodeFields_(JSON.parse(res.getContentText()).fields || {});
}

/** Creates or overwrites a document (used for the heartbeat). Failures are ignored. */
function writeDoc_(c, path, obj) {
  UrlFetchApp.fetch(base_(c) + '/' + path, {
    method: 'patch', contentType: 'application/json', headers: headers_(c.p, c.token), muteHttpExceptions: true,
    payload: JSON.stringify({ fields: encodeFields_(obj) }),
  });
}

function logRun_(c, run) {
  run.at = Date.now();
  UrlFetchApp.fetch(base_(c) + '/reportRuns', {
    method: 'post', contentType: 'application/json', headers: headers_(c.p, c.token), muteHttpExceptions: true,
    payload: JSON.stringify({ fields: encodeFields_(run) }),
  });
}

/** Documents of [collection] with from <= field < to, as plain objects. */
function query_(c, collection, field, from, to) {
  var res = UrlFetchApp.fetch(base_(c) + ':runQuery', {
    method: 'post', contentType: 'application/json', headers: headers_(c.p, c.token), muteHttpExceptions: true,
    payload: JSON.stringify({
      structuredQuery: {
        from: [{ collectionId: collection }],
        where: {
          compositeFilter: {
            op: 'AND', filters: [
              { fieldFilter: { field: { fieldPath: field }, op: 'GREATER_THAN_OR_EQUAL', value: { integerValue: String(Math.floor(from)) } } },
              { fieldFilter: { field: { fieldPath: field }, op: 'LESS_THAN', value: { integerValue: String(Math.floor(to)) } } },
            ],
          },
        },
      },
    }),
  });
  if (res.getResponseCode() !== 200) throw new Error('Reading ' + collection + ' failed: ' + res.getContentText());
  return JSON.parse(res.getContentText())
    .filter(function (r) { return r.document; })
    .map(function (r) {
      var o = decodeFields_(r.document.fields || {});
      o.id = r.document.name.split('/').pop();
      return o;
    });
}

function decodeFields_(f) {
  var o = {};
  Object.keys(f).forEach(function (k) { o[k] = decode_(f[k]); });
  return o;
}

function decode_(v) {
  if ('stringValue' in v) return v.stringValue;
  if ('integerValue' in v) return Number(v.integerValue);
  if ('doubleValue' in v) return v.doubleValue;
  if ('booleanValue' in v) return v.booleanValue;
  if ('timestampValue' in v) return new Date(v.timestampValue).getTime();
  if ('nullValue' in v) return null;
  if ('arrayValue' in v) return (v.arrayValue.values || []).map(decode_);
  if ('mapValue' in v) return decodeFields_(v.mapValue.fields || {});
  return null;
}

function encodeFields_(o) {
  var f = {};
  Object.keys(o).forEach(function (k) { if (o[k] !== undefined) f[k] = encode_(o[k]); });
  return f;
}

function encode_(v) {
  if (v === null) return { nullValue: null };
  if (typeof v === 'boolean') return { booleanValue: v };
  if (typeof v === 'number') return Number.isInteger(v) ? { integerValue: String(v) } : { doubleValue: v };
  if (Array.isArray(v)) return { arrayValue: { values: v.map(encode_) } };
  if (typeof v === 'object') return { mapValue: { fields: encodeFields_(v) } };
  return { stringValue: String(v) };
}
