/**
 * Van Tracker – monthly Excel report by email (Google Apps Script, free).
 *
 * Runs in ANY Google account (e.g. kandymystservices@gmail.com) – it does not need to be
 * the account that owns the Firebase project. It signs in to the app like a normal user
 * (a dedicated read-only "report" user), reads last month's trips and requests, builds a
 * Google Sheet with Summary / Trips / Passengers / Drivers / Vehicles tabs, exports it as
 * .xlsx and emails it.
 *
 * Setup (once) – see README "Monthly email report":
 *   1. In the Van Tracker app create a user, e.g. reports@kandymyst.local, role HOD, and
 *      approve it in Users & roles.
 *   2. script.google.com (signed in as kandymystservices@gmail.com) → New project →
 *      paste this file.
 *   3. Project settings → Script properties:
 *        FIREBASE_API_KEY     apiKey from the web config
 *        FIREBASE_PROJECT_ID  projectId from the web config
 *        REPORT_EMAIL         the report user's email
 *        REPORT_PASSWORD      the report user's password
 *        RECIPIENTS           comma-separated addresses, e.g. gm@…, om@…
 *        CURRENCY             optional, default LKR
 *        REFERER              optional, only if the API key is restricted to websites,
 *                             e.g. https://shehan-welagedara.github.io/
 *   4. Run testNow() once (approve the permissions) – you get last month's report.
 *   5. Run setupTrigger() once – from then on it is sent on the 1st of every month, 07:00.
 */

function setupTrigger() {
  ScriptApp.getProjectTriggers()
    .filter(function (t) { return t.getHandlerFunction() === 'sendMonthlyReport'; })
    .forEach(function (t) { ScriptApp.deleteTrigger(t); });
  ScriptApp.newTrigger('sendMonthlyReport').timeBased().onMonthDay(1).atHour(7).create();
}

/** Sends last month's report right now (use it to test). */
function testNow() { sendMonthlyReport(); }

function sendMonthlyReport() {
  var p = PropertiesService.getScriptProperties().getProperties();
  ['FIREBASE_API_KEY', 'FIREBASE_PROJECT_ID', 'REPORT_EMAIL', 'REPORT_PASSWORD', 'RECIPIENTS'].forEach(function (k) {
    if (!p[k]) throw new Error('Missing script property ' + k);
  });
  var cur = p.CURRENCY || 'LKR';
  var tz = Session.getScriptTimeZone() || 'Asia/Colombo';

  var now = new Date();
  var start = new Date(now.getFullYear(), now.getMonth() - 1, 1);
  var end = new Date(now.getFullYear(), now.getMonth(), 1);
  var label = Utilities.formatDate(start, tz, 'MMMM yyyy');

  var token = signIn_(p);
  // Query a little wider by createdAt, then keep what actually happened in the month.
  var from = start.getTime() - 40 * 86400000;
  var trips = query_(p, token, 'trips', 'createdAt', from, end.getTime()).filter(function (t) {
    var when = t.startTime || t.plannedTime || t.createdAt;
    return when >= start.getTime() && when < end.getTime() && t.status !== 'CANCELLED';
  });
  var requests = query_(p, token, 'requests', 'createdAt', from, end.getTime()).filter(function (r) {
    var when = r.requestedTime || r.createdAt;
    return when >= start.getTime() && when < end.getTime();
  });

  var fmt = function (ms) { return ms ? Utilities.formatDate(new Date(ms), tz, 'yyyy-MM-dd HH:mm') : ''; };
  var stopsOf = function (x) { return (x.stops || []).map(function (s) { return s.name; }).join(' → '); };
  var round = function (n, d) { var f = Math.pow(10, d || 1); return Math.round((n || 0) * f) / f; };

  var done = trips.filter(function (t) { return t.status === 'COMPLETED'; });
  var sum = function (list, k) { return list.reduce(function (a, t) { return a + (Number(t[k]) || 0); }, 0); };
  var totalKm = sum(done, 'distanceKm'), totalCost = sum(done, 'fuelCost'), totalL = sum(done, 'fuelLiters');
  var totalMin = sum(done, 'durationMin');
  var unauthorized = trips.filter(function (t) { return t.type === 'UNAUTHORIZED' || t.needsReason; }).length;

  var ss = SpreadsheetApp.create('Van Tracker report – ' + label);
  var summary = ss.getSheets()[0].setName('Summary');
  writeSheet_(summary, ['Item', 'Value'], [
    ['Month', label],
    ['Completed trips', done.length],
    ['All trips (incl. planned / open)', trips.length],
    ['Distance (km)', round(totalKm)],
    ['Time on road (h)', round(totalMin / 60)],
    ['Fuel (L)', round(totalL)],
    ['Fuel cost (' + cur + ')', Math.round(totalCost)],
    ['Requests made', requests.length],
    ['Requests rejected', requests.filter(function (r) { return r.status === 'REJECTED'; }).length],
    ['Pickups / deliveries', requests.filter(function (r) { return r.kind === 'DELIVERY'; }).length],
    ['Movements without a request', unauthorized],
  ]);

  writeSheet_(ss.insertSheet('Trips'),
    ['Date', 'Title', 'Category', 'Departments', 'Route', 'Vehicle', 'Driver', 'Passengers', 'Status',
      'Start', 'End', 'Km', 'Minutes', 'Fuel L', 'Fuel cost ' + cur, 'Reason'],
    trips.map(function (t) {
      return [fmt(t.startTime || t.plannedTime), t.title, t.category, (t.departments || []).join(', '), stopsOf(t),
        t.vehicleName, t.driverName, t.passengers || 0, t.status, fmt(t.startTime), fmt(t.endTime),
        round(t.distanceKm), Math.round(t.durationMin || 0), round(t.fuelLiters), Math.round(t.fuelCost || 0), t.reason || ''];
    }));

  writeSheet_(ss.insertSheet('Passengers'),
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
  writeSheet_(ss.insertSheet('Drivers'), ['Driver'].concat(head), group('driverName'));
  writeSheet_(ss.insertSheet('Vehicles'), ['Vehicle'].concat(head), group('vehicleName'));
  SpreadsheetApp.flush();

  var xlsx = UrlFetchApp.fetch('https://docs.google.com/spreadsheets/d/' + ss.getId() + '/export?format=xlsx', {
    headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
  }).getBlob().setName('VanTracker-' + Utilities.formatDate(start, tz, 'yyyy-MM') + '.xlsx');

  var html =
    '<div style="font-family:Arial,sans-serif;color:#2B2B2B">' +
    '<h2 style="color:#592C82;margin:0 0 4px">Van Tracker – ' + label + '</h2>' +
    '<p style="color:#777;margin:0 0 16px">Kandy Myst by Cinnamon · staff transport</p>' +
    '<table cellpadding="6" style="border-collapse:collapse">' +
    row_('Completed trips', done.length) + row_('Distance', round(totalKm) + ' km') +
    row_('Time on road', round(totalMin / 60) + ' h') + row_('Fuel', round(totalL) + ' L') +
    row_('Fuel cost', cur + ' ' + Math.round(totalCost).toLocaleString()) +
    row_('Requests', requests.length) + row_('Movements without a request', unauthorized) +
    '</table><p>The full report (trips, passengers, drivers, vehicles) is attached as Excel.</p>' +
    '<p style="color:#777;font-size:12px">Also saved in Google Drive: ' + ss.getUrl() + '</p></div>';

  MailApp.sendEmail({
    to: p.RECIPIENTS,
    subject: 'Van Tracker monthly report – ' + label,
    htmlBody: html,
    attachments: [xlsx],
    name: 'Kandy Myst Van Tracker',
  });
}

// ------------------------------------------------------------------ helpers

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
  var h = { 'Content-Type': 'application/json' };
  if (token) h.Authorization = 'Bearer ' + token;
  if (p.REFERER) h.Referer = p.REFERER;
  return h;
}

/** Signs in as the report user (Firebase Auth REST API) and returns an ID token. */
function signIn_(p) {
  var res = UrlFetchApp.fetch('https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=' + p.FIREBASE_API_KEY, {
    method: 'post', headers: headers_(p), muteHttpExceptions: true,
    payload: JSON.stringify({ email: p.REPORT_EMAIL, password: p.REPORT_PASSWORD, returnSecureToken: true }),
  });
  var j = JSON.parse(res.getContentText());
  if (!j.idToken) throw new Error('Sign-in failed: ' + res.getContentText());
  return j.idToken;
}

/** Documents of [collection] with from <= field < to, as plain objects. */
function query_(p, token, collection, field, from, to) {
  var url = 'https://firestore.googleapis.com/v1/projects/' + p.FIREBASE_PROJECT_ID + '/databases/(default)/documents:runQuery';
  var res = UrlFetchApp.fetch(url, {
    method: 'post', headers: headers_(p, token), muteHttpExceptions: true,
    payload: JSON.stringify({
      structuredQuery: {
        from: [{ collectionId: collection }],
        where: {
          compositeFilter: {
            op: 'AND', filters: [
              { fieldFilter: { field: { fieldPath: field }, op: 'GREATER_THAN_OR_EQUAL', value: { integerValue: String(from) } } },
              { fieldFilter: { field: { fieldPath: field }, op: 'LESS_THAN', value: { integerValue: String(to) } } },
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
