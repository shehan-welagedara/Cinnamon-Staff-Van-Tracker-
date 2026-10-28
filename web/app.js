// Van Tracker – management web dashboard.
// Reads the same Firestore database as the Android app (security rules apply: only
// active GM / OM / administrator accounts can open the dashboard).
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import {
  getAuth, onAuthStateChanged, signInWithEmailAndPassword, signOut,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";
import {
  getFirestore, collection, doc, getDoc, getDocs, onSnapshot, query, where, orderBy, limit,
  updateDoc, writeBatch,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";

window.__vtReady = true;
const $ = (id) => document.getElementById(id);
const CONFIG_KEY = "vt-firebase-config";
const THEME_KEY = "vt-theme";

// ------------------------------------------------------------------ theme

function applyTheme(t) {
  if (t === "light" || t === "dark") document.documentElement.dataset.theme = t;
  else delete document.documentElement.dataset.theme;
  document.querySelectorAll("#themeSeg button").forEach((b) => b.classList.toggle("on", b.dataset.theme === (t || "system")));
}
applyTheme(localStorage.getItem(THEME_KEY) || "system");
document.querySelectorAll("#themeSeg button").forEach((b) => b.addEventListener("click", () => {
  localStorage.setItem(THEME_KEY, b.dataset.theme); applyTheme(b.dataset.theme); renderSoon();
}));

// ------------------------------------------------------------------ Firebase connection

/** Accepts a Firebase web config (object literal or JSON), google-services.json, or a VT1 setup code. */
function parseConfig(text) {
  text = (text || "").trim();
  if (!text) return null;
  try {
    if (text.startsWith("VT1:")) {
      const j = JSON.parse(atob(text.slice(4)));
      return { apiKey: j.k, appId: j.a, projectId: j.p, authDomain: `${j.p}.firebaseapp.com` };
    }
  } catch { /* fall through */ }
  try {
    const j = JSON.parse(text);
    if (j.apiKey && j.projectId) return { authDomain: `${j.projectId}.firebaseapp.com`, ...j };
    if (j.project_info) {
      const c = j.client[0];
      return { apiKey: c.api_key[0].current_key, appId: c.client_info.mobilesdk_app_id, projectId: j.project_info.project_id, authDomain: `${j.project_info.project_id}.firebaseapp.com` };
    }
  } catch { /* not JSON – try object literal */ }
  const cfg = {};
  for (const m of text.matchAll(/(\w+)\s*:\s*["']([^"']+)["']/g)) cfg[m[1]] = m[2];
  if (cfg.apiKey && cfg.projectId) { cfg.authDomain ||= `${cfg.projectId}.firebaseapp.com`; return cfg; }
  return null;
}

function storedConfig() {
  if (window.VT_FIREBASE_CONFIG && window.VT_FIREBASE_CONFIG.apiKey) return window.VT_FIREBASE_CONFIG;
  try { return JSON.parse(localStorage.getItem(CONFIG_KEY) || "null"); } catch { return null; }
}

function show(id) { ["setup", "login", "app"].forEach((s) => { $(s).hidden = s !== id; }); }

let db, auth;
const cfg = storedConfig();
if (!cfg) {
  show("setup");
  $("setupBtn").onclick = () => {
    const c = parseConfig($("setupText").value);
    if (!c) { $("setupError").textContent = "That doesn't look like a Firebase web config."; return; }
    localStorage.setItem(CONFIG_KEY, JSON.stringify(c)); location.reload();
  };
} else {
  const app = initializeApp(cfg);
  auth = getAuth(app);
  db = getFirestore(app);
  start();
}

$("resetConfig").onclick = () => { localStorage.removeItem(CONFIG_KEY); location.reload(); };

// ------------------------------------------------------------------ formatting

const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const pad2 = (n) => String(n).padStart(2, "0");
const fmtDate = (t) => t ? new Date(t).toLocaleDateString(undefined, { day: "2-digit", month: "short", year: "numeric" }) : "";
const fmtDT = (t) => t ? new Date(t).toLocaleString(undefined, { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" }) : "";
const fmtTime = (t) => t ? new Date(t).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" }) : "";
const nf1 = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1, minimumFractionDigits: 1 });
const nf2 = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2, minimumFractionDigits: 2 });
const km = (v) => `${nf1.format(v || 0)} km`;
const money = (v) => `${S.settings.currency || "LKR"} ${nf2.format(v || 0)}`;
const dur = (min) => { const m = Math.round(min || 0); return m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m`; };
const dayKey = (t) => { const d = new Date(t); return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`; };
const normDept = (d) => (d === "Management" ? "Administration" : d || "");
const travellers = (r) => r.passengerNames || r.requesterName || "";

const STATUS_CHIP = {
  COMPLETED: ["Completed", "c-brand"], IN_PROGRESS: ["On the road", "c-ok"], ASSIGNED: ["Driver assigned", "c-info"],
  READY: ["Waiting for driver", "c-warn"], CANCELLED: ["Cancelled", "c-muted"],
  PENDING: ["Pending", "c-warn"], APPROVED: ["Approved", "c-info"], REJECTED: ["Rejected", "c-muted"],
};
const chip = (status, override) => {
  const [label, cls] = STATUS_CHIP[status] || [status, "c-muted"];
  return `<span class="chip ${cls}">${esc(override || label)}</span>`;
};

// ------------------------------------------------------------------ state

const S = {
  me: null, settings: { currency: "LKR" }, vehicles: [], users: [], live: [], alerts: [], pending: [], open: [],
  trips: [], requests: [], duty: [], range: { from: 0, to: 0 }, rangeUnsubs: [],
};
const docs = (snap) => snap.docs.map((d) => ({ id: d.id, ...d.data() }));

function computeRange() {
  const p = $("rangePreset").value;
  const now = new Date();
  const sod = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  let from, to = Date.now() + 1;
  if (p === "today") from = sod(now);
  else if (p === "7") from = sod(now) - 6 * 864e5;
  else if (p === "90") from = sod(now) - 89 * 864e5;
  else if (p === "lastmonth") { from = new Date(now.getFullYear(), now.getMonth() - 1, 1).getTime(); to = new Date(now.getFullYear(), now.getMonth(), 1).getTime(); }
  else if (p === "custom") {
    from = $("rangeFrom").value ? new Date($("rangeFrom").value).getTime() : sod(now);
    to = $("rangeTo").value ? new Date($("rangeTo").value).getTime() + 864e5 : to;
  } else from = new Date(now.getFullYear(), now.getMonth(), 1).getTime();
  return { from, to, label: $("rangePreset").selectedOptions[0].textContent };
}

function subscribeRange() {
  S.rangeUnsubs.forEach((u) => u());
  S.range = computeRange();
  const { from } = S.range;
  S.rangeUnsubs = [
    onSnapshot(query(collection(db, "trips"), where("createdAt", ">=", from)), (s) => { S.trips = docs(s); renderSoon(); }, warn),
    onSnapshot(query(collection(db, "requests"), where("requestedTime", ">=", from)), (s) => { S.requests = docs(s); renderSoon(); }, warn),
    onSnapshot(query(collection(db, "dutyLogs"), where("at", ">=", from)), (s) => { S.duty = docs(s); renderSoon(); }, warn),
  ];
}
const warn = (e) => console.warn(e);

function subscribeStatic() {
  onSnapshot(doc(db, "config", "settings"), (d) => { S.settings = { currency: "LKR", ...(d.data() || {}) }; renderSoon(); }, warn);
  onSnapshot(collection(db, "vehicles"), (s) => { S.vehicles = docs(s); fillFilters(); renderSoon(); }, warn);
  onSnapshot(collection(db, "users"), (s) => { S.users = docs(s); fillFilters(); renderSoon(); }, warn);
  onSnapshot(collection(db, "live"), (s) => { S.live = docs(s).filter((l) => l.updatedAt); renderSoon(); }, warn);
  onSnapshot(query(collection(db, "alerts"), orderBy("createdAt", "desc"), limit(200)), (s) => { S.alerts = docs(s); renderSoon(); }, warn);
  onSnapshot(query(collection(db, "requests"), where("status", "==", "PENDING")), (s) => { S.pending = docs(s); renderSoon(); }, warn);
  onSnapshot(query(collection(db, "trips"), where("status", "in", ["READY", "ASSIGNED", "IN_PROGRESS"])), (s) => { S.open = docs(s); renderSoon(); }, warn);
}

const inRange = (t) => t >= S.range.from && t < S.range.to;
const tripTime = (t) => t.startTime || t.plannedTime || t.createdAt;
const rangeTrips = () => S.trips.filter((t) => inRange(tripTime(t)));
const rangeRequests = () => S.requests.filter((r) => inRange(r.requestedTime));
const vehicleName = (t) => t.vehicleName || (S.vehicles.find((v) => v.id === (t.vehicleId || "default"))?.name) || S.settings.vehicleName || "Van";
const drivers = () => S.users.filter((u) => u.role === "DRIVER" && u.active !== false).sort((a, b) => (a.name || "").localeCompare(b.name || ""));

// ------------------------------------------------------------------ auth

function start() {
  show("login");
  $("loginForm").onsubmit = async (e) => {
    e.preventDefault();
    $("loginError").textContent = "";
    try { await signInWithEmailAndPassword(auth, $("email").value.trim(), $("password").value); }
    catch (err) { $("loginError").textContent = err.message.replace("Firebase: ", ""); }
  };
  $("logout").onclick = () => signOut(auth);
  onAuthStateChanged(auth, async (u) => {
    if (!u) { show("login"); return; }
    const p = await getDoc(doc(db, "users", u.uid)).catch(() => null);
    const me = p && p.exists() ? { uid: u.uid, ...p.data() } : null;
    const allowed = me && me.active !== false && (["GM", "OM", "ADMIN"].includes(me.role) || me.isAdmin);
    if (!allowed) {
      $("loginError").textContent = "This dashboard is for the GM, OM and administrators.";
      await signOut(auth); return;
    }
    S.me = me;
    $("whoami").textContent = `${me.name} · ${me.role === "ADMIN" ? "Administrator" : me.role}`;
    show("app");
    subscribeStatic();
    subscribeRange();
    route();
  });
}

// ------------------------------------------------------------------ routing

const VIEWS = {
  overview: ["Overview", renderOverview], live: ["Live fleet", renderLive], trips: ["Trips", renderTrips],
  passengers: ["Passengers", renderPassengers], drivers: ["Drivers", renderDrivers],
  approvals: ["Approvals", renderApprovals], alerts: ["Alerts", renderAlerts],
};
let current = "overview";
function route() {
  current = (location.hash || "#overview").slice(1);
  if (!VIEWS[current]) current = "overview";
  Object.keys(VIEWS).forEach((v) => { $(`view-${v}`).hidden = v !== current; });
  document.querySelectorAll("#nav a").forEach((a) => a.classList.toggle("active", a.dataset.view === current));
  $("viewEyebrow").textContent = VIEWS[current][0];
  renderSoon();
  if (current === "live" && liveMap) setTimeout(() => liveMap.invalidateSize(), 50);
}
window.addEventListener("hashchange", route);
$("rangePreset").addEventListener("change", () => {
  const custom = $("rangePreset").value === "custom";
  $("rangeFrom").hidden = !custom; $("rangeTo").hidden = !custom;
  subscribeRange();
});
["rangeFrom", "rangeTo"].forEach((id) => $(id).addEventListener("change", subscribeRange));
["tripSearch", "fVehicle", "fDriver", "fDept", "fType", "fStatus", "paxSearch", "paxDept", "paxRun", "paxIncludeCancelled"]
  .forEach((id) => $(id).addEventListener("input", renderSoon));

let raf = 0;
function renderSoon() { cancelAnimationFrame(raf); raf = requestAnimationFrame(render); }
function render() {
  if (!S.me) return;
  $("viewTitle").textContent = ["live", "approvals", "alerts"].includes(current) ? "Now" : S.range.label;
  const pend = S.pending.length, al = S.alerts.filter((a) => !a.acknowledged).length;
  $("pendingBadge").hidden = !pend; $("pendingBadge").textContent = pend;
  $("alertBadge").hidden = !al; $("alertBadge").textContent = al;
  VIEWS[current][1]();
}

function fillFilters() {
  const fill = (sel, values) => {
    const cur = sel.value; const first = sel.options[0].outerHTML;
    sel.innerHTML = first + values.map((v) => `<option value="${esc(v[0])}">${esc(v[1])}</option>`).join("");
    sel.value = cur;
  };
  fill($("fVehicle"), S.vehicles.map((v) => [v.id, `${v.name}${v.plateNo ? " · " + v.plateNo : ""}`]));
  fill($("fDriver"), drivers().map((d) => [d.uid || d.id, d.name]));
  const depts = [...new Set(S.users.map((u) => normDept(u.department)).filter(Boolean))].sort();
  fill($("fDept"), depts.map((d) => [d, d]));
  fill($("paxDept"), depts.map((d) => [d, d]));
}

// ------------------------------------------------------------------ charts

const charts = {};
function chart(id, type, labels, data, opts = {}) {
  charts[id]?.destroy();
  const css = getComputedStyle(document.documentElement);
  const text = css.getPropertyValue("--muted").trim() || "#6F5590";
  const palette = ["#592C82", "#9A2580", "#B993E6", "#4A2271", "#CDB7E0", "#6F5590", "#DB6BC2", "#2F6FDB"];
  charts[id] = new Chart($(id), {
    type,
    data: { labels, datasets: [{ data, backgroundColor: type === "doughnut" ? palette : "#592C82cc", borderRadius: 8, borderWidth: 0 }] },
    options: {
      plugins: { legend: { display: type === "doughnut", position: "right", labels: { color: text } } },
      scales: type === "doughnut" ? {} : { x: { ticks: { color: text }, grid: { display: false } }, y: { ticks: { color: text }, grid: { color: "rgba(128,100,160,.15)" } } },
      ...opts,
    },
  });
}
const sumBy = (rows, key, val) => rows.reduce((m, r) => { const k = key(r); m[k] = (m[k] || 0) + val(r); return m; }, {});
const sorted = (m) => Object.entries(m).sort((a, b) => b[1] - a[1]);

// ------------------------------------------------------------------ overview

function renderOverview() {
  const trips = rangeTrips().filter((t) => t.status !== "CANCELLED");
  const done = trips.filter((t) => t.status === "COMPLETED");
  const unapproved = trips.filter((t) => t.type === "UNAUTHORIZED");
  const seats = rangeRequests().filter((r) => !["CANCELLED", "REJECTED"].includes(r.status)).reduce((s, r) => s + (r.passengers || 1), 0);
  const k = [
    ["Trips", trips.length], ["Distance", km(done.reduce((s, t) => s + (t.distanceKm || 0), 0))],
    ["Time on road", dur(done.reduce((s, t) => s + (t.durationMin || 0), 0))],
    ["Fuel (est.)", `${nf1.format(done.reduce((s, t) => s + (t.fuelLiters || 0), 0))} L`],
    ["Fuel cost", money(done.reduce((s, t) => s + (t.fuelCost || 0), 0))],
    ["Seats booked", seats], ["Unapproved", unapproved.length], ["Pending approvals", S.pending.length],
  ];
  $("kpis").innerHTML = k.map(([l, v]) => `<div class="glass kpi"><div class="v">${esc(v)}</div><div class="l">${esc(l)}</div></div>`).join("");

  const daily = sumBy(done, (t) => dayKey(tripTime(t)), (t) => t.distanceKm || 0);
  const days = Object.keys(daily).sort();
  chart("chartDaily", "bar", days.map((d) => d.slice(5)), days.map((d) => +daily[d].toFixed(1)));
  const byDept = {};
  done.forEach((t) => { const ds = (t.departments || []).map(normDept); const list = ds.length ? ds : [t.type === "UNAUTHORIZED" ? "Unapproved" : "Unassigned"]; list.forEach((d) => { byDept[d] = (byDept[d] || 0) + (t.fuelCost || 0) / list.length; }); });
  const dept = sorted(byDept);
  chart("chartDept", "doughnut", dept.map((e) => e[0]), dept.map((e) => +e[1].toFixed(2)));
  const veh = sorted(sumBy(done, vehicleName, (t) => t.distanceKm || 0));
  chart("chartVehicle", "bar", veh.map((e) => e[0]), veh.map((e) => +e[1].toFixed(1)), { indexAxis: "y" });
  const drv = sorted(sumBy(trips, (t) => t.driverName || "Unassigned", () => 1));
  chart("chartDriver", "bar", drv.map((e) => e[0]), drv.map((e) => e[1]), { indexAxis: "y" });

  $("recentTrips").innerHTML = tripTable(trips.sort((a, b) => tripTime(b) - tripTime(a)).slice(0, 10));
  bindTripRows($("recentTrips"));
}

// ------------------------------------------------------------------ trips

function filteredTrips() {
  const q = $("tripSearch").value.trim().toLowerCase();
  const v = $("fVehicle").value, d = $("fDriver").value, dp = $("fDept").value, ty = $("fType").value, st = $("fStatus").value;
  return rangeTrips().filter((t) => (!v || (t.vehicleId || "default") === v) && (!d || t.driverUid === d)
    && (!dp || (t.departments || []).map(normDept).includes(dp)) && (!ty || t.type === ty) && (!st || t.status === st)
    && (!q || [t.title, t.purpose, t.reason, t.driverName, vehicleName(t), ...(t.stops || []).map((s) => s.name), t.destination?.name]
      .some((f) => (f || "").toLowerCase().includes(q))))
    .sort((a, b) => tripTime(b) - tripTime(a));
}

function tripTable(rows) {
  if (!rows.length) return `<p class="empty">No trips in this period.</p>`;
  return `<table><thead><tr><th>When</th><th>Trip</th><th>Driver</th><th>Vehicle</th><th>Departments</th><th class="num">Pax</th><th class="num">Distance</th><th class="num">Time</th><th class="num">Fuel cost</th><th>Status</th></tr></thead><tbody>${
    rows.map((t) => `<tr class="click" data-trip="${esc(t.id)}"><td>${esc(fmtDT(tripTime(t)))}</td><td><b>${esc(t.title)}</b>${t.destination?.name ? `<br><span class="muted small">→ ${esc(t.destination.name)}</span>` : ""}</td>
      <td>${esc(t.driverName || "—")}</td><td>${esc(vehicleName(t))}</td><td>${esc((t.departments || []).map(normDept).join(", "))}</td>
      <td class="num">${t.passengers || 0}</td><td class="num">${km(t.distanceKm)}</td><td class="num">${dur(t.durationMin)}</td><td class="num">${money(t.fuelCost)}</td>
      <td>${t.type === "UNAUTHORIZED" ? chip("x", t.reason ? "Unapproved" : "Unapproved – no reason").replace("c-muted", "c-danger") : chip(t.status)}</td></tr>`).join("")
  }</tbody></table>`;
}

function bindTripRows(root) { root.querySelectorAll("tr[data-trip]").forEach((tr) => { tr.onclick = () => openTrip(tr.dataset.trip); }); }

function renderTrips() {
  const rows = filteredTrips();
  const done = rows.filter((t) => t.status === "COMPLETED");
  $("tripsSummary").innerHTML = `<span><b>${rows.length}</b> trips</span><span><b>${km(done.reduce((s, t) => s + (t.distanceKm || 0), 0))}</b></span>
    <span><b>${dur(done.reduce((s, t) => s + (t.durationMin || 0), 0))}</b> on road</span><span><b>${money(done.reduce((s, t) => s + (t.fuelCost || 0), 0))}</b> fuel</span>`;
  $("tripsTable").innerHTML = tripTable(rows);
  bindTripRows($("tripsTable"));
}
$("tripsCsv").onclick = () => csv("trips", ["Date", "Start", "End", "Type", "Title", "Purpose / reason", "Destination", "Stops", "Departments", "Driver", "Vehicle", "Passengers", "Distance km", "Duration min", "Moving min", "Stopped min", "Fuel L", "Fuel cost", "Status"],
  filteredTrips().map((t) => [fmtDate(tripTime(t)), fmtTime(t.startTime), fmtTime(t.endTime), t.type, t.title, t.purpose || t.reason, t.destination?.name, (t.stops || []).map((s) => s.name).join(" > "),
    (t.departments || []).map(normDept).join("; "), t.driverName, vehicleName(t), t.passengers, t.distanceKm, t.durationMin, t.movingMin, t.stoppedMin, t.fuelLiters, t.fuelCost, t.status]));

// ------------------------------------------------------------------ trip detail

let tdMap;
async function openTrip(id) {
  const snap = await getDoc(doc(db, "trips", id));
  if (!snap.exists()) return;
  const t = { id, ...snap.data() };
  $("tdTitle").textContent = t.title || "Trip";
  const reqs = (await getDocs(query(collection(db, "requests"), where("tripId", "==", id)))).docs.map((d) => ({ id: d.id, ...d.data() }));
  const stopRows = (t.stops || []).map((s, i) => {
    const r = reqs.find((x) => x.id === s.requestId);
    return `<tr><td>${i + 1}</td><td>${esc(s.name)}</td><td>${esc(r ? travellers(r) : "")}</td><td>${esc(normDept(s.department))}</td><td>${s.arrivedAt ? esc(fmtTime(s.arrivedAt)) : "—"}</td></tr>`;
  }).join("");
  $("tdBody").innerHTML = `
    <div class="kv">
      <div><span>Status</span>${chip(t.status)}</div><div><span>Type</span>${esc(t.type)}</div>
      <div><span>Driver</span>${esc(t.driverName || "—")}</div><div><span>Vehicle</span>${esc(vehicleName(t))}</div>
      <div><span>Started</span>${esc(fmtDT(t.startTime) || "—")}</div><div><span>Ended</span>${esc(fmtDT(t.endTime) || "—")}</div>
      <div><span>Distance</span>${km(t.distanceKm)}</div><div><span>Time on road</span>${dur(t.durationMin)} (moving ${dur(t.movingMin)})</div>
      <div><span>Fuel</span>${nf2.format(t.fuelLiters || 0)} L · ${money(t.fuelCost)}</div><div><span>Max speed</span>${Math.round(t.maxSpeedKmh || 0)} km/h</div>
      ${t.destination?.name ? `<div><span>Destination</span>${esc(t.destination.name)}</div>` : ""}
      ${t.type === "UNAUTHORIZED" ? `<div><span>Reason</span>${esc(t.reason || "NOT GIVEN")}</div>` : ""}
    </div>
    ${stopRows ? `<h3>Stops & drop-offs</h3><div class="table-wrap"><table><thead><tr><th>#</th><th>Stop</th><th>Passenger</th><th>Department</th><th>Arrived</th></tr></thead><tbody>${stopRows}</tbody></table></div>` : ""}
    ${reqs.length ? `<h3 style="margin-top:14px">Passengers</h3><div class="table-wrap"><table><thead><tr><th>Passenger(s)</th><th>Requested by</th><th>Department</th><th class="num">Seats</th><th>Status</th></tr></thead><tbody>${
      reqs.map((r) => `<tr><td>${esc(travellers(r))}</td><td>${esc(r.requesterName)}</td><td>${esc(normDept(r.department))}</td><td class="num">${r.passengers || 1}</td><td>${chip(r.status)}</td></tr>`).join("")}</tbody></table></div>` : ""}`;
  $("tripDialog").showModal();

  const track = (await getDocs(collection(db, "trips", id, "track"))).docs.map((d) => d.data()).sort((a, b) => a.seq - b.seq)
    .flatMap((c) => (c.p || []).map((p) => [p.a, p.o]));
  if (!tdMap) { tdMap = L.map("tdMap"); tileLayer().addTo(tdMap); }
  tdMap.eachLayer((l) => { if (!(l instanceof L.TileLayer)) tdMap.removeLayer(l); });
  const pts = [...track];
  if (track.length > 1) L.polyline(track, { color: "#592C82", weight: 5 }).addTo(tdMap);
  [...(t.stops || []), t.destination].filter((s) => s && s.lat != null).forEach((s) => { L.marker([s.lat, s.lng]).bindPopup(esc(s.name)).addTo(tdMap); pts.push([s.lat, s.lng]); });
  setTimeout(() => {
    tdMap.invalidateSize();
    if (pts.length > 1) tdMap.fitBounds(pts, { padding: [30, 30] });
    else if (pts.length === 1) tdMap.setView(pts[0], 15);
    else tdMap.setView(hotel() || [7.2906, 80.6337], 13);
  }, 60);
}
$("tdClose").onclick = () => $("tripDialog").close();

// ------------------------------------------------------------------ live fleet

const tileLayer = () => L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "© OpenStreetMap" });
const hotel = () => (S.settings.hotelLat ? [S.settings.hotelLat, S.settings.hotelLng] : null);
const carSvg = (bearing) => `<div class="car" style="transform:rotate(${bearing || 0}deg);width:30px;height:54px"><svg viewBox="0 0 30 54" width="30" height="54">
  <rect x="1" y="1" width="28" height="52" rx="7" fill="#fff"/><rect x="2.5" y="2.5" width="25" height="49" rx="6" fill="#592C82"/>
  <path fill="#CDB7E0" d="M6.5 13.5q8.5-4 17 0l-1.8 6.5q-6.7-2-13.4 0z"/><rect x="8.3" y="21.5" width="13.4" height="15" fill="#4A2271"/>
  <path fill="#CDB7E0" d="M8.3 38.5h13.4l1.3 5q-8 2.5-16 0z"/><rect x="6" y="3.5" width="4" height="2" fill="#FFF3C4"/><rect x="20" y="3.5" width="4" height="2" fill="#FFF3C4"/>
  <rect x="5" y="47" width="4" height="2" fill="#9A2580"/><rect x="21" y="47" width="4" height="2" fill="#9A2580"/></svg></div>`;
let liveMap; const liveMarkers = {}; let liveFitted = false;

function liveLabel(l) {
  if (l.mode === "TRIP") return ["On ride", "c-ok"];
  if (l.mode === "UNAUTHORIZED") return ["Unapproved", "c-danger"];
  return ["Parked", "c-info"];
}

function renderLive() {
  if (!liveMap) {
    liveMap = L.map("liveMap").setView(hotel() || [7.2906, 80.6337], 13);
    tileLayer().addTo(liveMap);
    if (hotel()) L.marker(hotel()).bindPopup(esc(S.settings.hotelName || "Hotel")).addTo(liveMap);
  }
  const vehicles = S.vehicles.length ? S.vehicles : [{ id: "default", name: S.settings.vehicleName || "Staff Van" }];
  const pts = [];
  S.live.forEach((l) => {
    const v = vehicles.find((x) => x.id === l.id);
    const name = v ? `${v.name}${v.plateNo ? " · " + v.plateNo : ""}` : (l.vehicleName || l.id);
    const pos = [l.lat, l.lng]; pts.push(pos);
    const popup = `<b>${esc(name)}</b><br>${esc(liveLabel(l)[0])}${l.driverName ? " · " + esc(l.driverName) : ""}<br>${esc(l.tripTitle || "")}<br>${Math.round(l.speedKmh || 0)} km/h · ${esc(fmtDT(l.updatedAt))}`;
    const icon = L.divIcon({ className: "", html: carSvg(l.bearing), iconSize: [30, 54], iconAnchor: [15, 27] });
    if (liveMarkers[l.id]) { liveMarkers[l.id].setLatLng(pos).setIcon(icon).setPopupContent(popup); }
    else liveMarkers[l.id] = L.marker(pos, { icon }).bindPopup(popup).addTo(liveMap);
  });
  if (!liveFitted && pts.length) { liveFitted = true; pts.length > 1 ? liveMap.fitBounds(pts, { padding: [40, 40] }) : liveMap.setView(pts[0], 15); }
  setTimeout(() => liveMap.invalidateSize(), 30);

  $("liveList").innerHTML = vehicles.map((v) => {
    const l = S.live.find((x) => x.id === v.id);
    const [label, cls] = l ? liveLabel(l) : ["No position yet", "c-muted"];
    return `<div class="glass card"><div class="row"><b>${esc(v.name)}${v.plateNo ? " · " + esc(v.plateNo) : ""}</b><span class="chip ${cls}">${esc(label)}</span></div>
      ${l ? `<p class="muted small">${l.driverName ? esc(l.driverName) + " · " : ""}${Math.round(l.speedKmh || 0)} km/h · ${esc(fmtDT(l.updatedAt))}</p>${l.tripTitle ? `<p>${esc(l.tripTitle)}</p>` : ""}
      ${l.tripId ? `<button class="btn ghost" data-trip="${esc(l.tripId)}">Open ride</button>` : ""}` : ""}</div>`;
  }).join("");
  $("liveList").querySelectorAll("button[data-trip]").forEach((b) => { b.onclick = () => openTrip(b.dataset.trip); });
}

// ------------------------------------------------------------------ passengers

function filteredPax() {
  const q = $("paxSearch").value.trim().toLowerCase();
  const dp = $("paxDept").value, run = $("paxRun").value, all = $("paxIncludeCancelled").checked;
  return rangeRequests().filter((r) => (all || !["CANCELLED", "REJECTED"].includes(r.status))
    && (!dp || normDept(r.department) === dp) && (!run || (r.joinTargetLabel || r.category) === run)
    && (!q || [travellers(r), r.requesterName, r.department, r.joinTargetLabel, r.category, r.purpose, ...(r.stops || []).map((s) => s.name)]
      .some((f) => (f || "").toLowerCase().includes(q))))
    .sort((a, b) => b.requestedTime - a.requestedTime);
}

function renderPassengers() {
  const runs = [...new Set(rangeRequests().map((r) => r.joinTargetLabel || r.category).filter(Boolean))].sort();
  const sel = $("paxRun"); const cur = sel.value;
  sel.innerHTML = `<option value="">All runs & requests</option>` + runs.map((r) => `<option>${esc(r)}</option>`).join(""); sel.value = cur;

  const rows = filteredPax();
  const per = {};
  rows.forEach((r) => travellers(r).split(/[,&/]/).map((n) => n.trim()).filter(Boolean).forEach((n) => {
    const p = per[n] ||= { trips: 0, seats: 0, last: 0, dept: normDept(r.department), drops: new Set() };
    p.trips++; p.seats += r.passengers || 1; p.last = Math.max(p.last, r.requestedTime);
    (r.stops || []).forEach((s) => p.drops.add(s.name));
  }));
  const perRows = Object.entries(per).sort((a, b) => b[1].trips - a[1].trips);
  $("paxSummary").innerHTML = perRows.length ? `<table><thead><tr><th>Passenger</th><th>Department</th><th class="num">Trips</th><th>Usual drop-off</th><th>Last</th></tr></thead><tbody>${
    perRows.map(([n, p]) => `<tr class="click" data-pax="${esc(n)}"><td><b>${esc(n)}</b></td><td>${esc(p.dept)}</td><td class="num">${p.trips}</td><td>${esc([...p.drops].slice(0, 2).join(", "))}</td><td>${esc(fmtDate(p.last))}</td></tr>`).join("")}</tbody></table>`
    : `<p class="empty">No passengers in this period.</p>`;
  $("paxSummary").querySelectorAll("tr[data-pax]").forEach((tr) => { tr.onclick = () => { $("paxSearch").value = tr.dataset.pax; renderSoon(); }; });

  const perDay = sumBy(rows, (r) => dayKey(r.requestedTime), (r) => r.passengers || 1);
  const days = Object.keys(perDay).sort();
  chart("chartPax", "bar", days.map((d) => d.slice(5)), days.map((d) => perDay[d]));

  $("paxTable").innerHTML = rows.length ? `<table><thead><tr><th>When</th><th>Passenger(s)</th><th>Requested by</th><th>Department</th><th>Run / purpose</th><th>Drop-off</th><th class="num">Seats</th><th>Driver</th><th>Status</th></tr></thead><tbody>${
    rows.map((r) => `<tr class="${r.tripId ? "click" : ""}" data-trip="${esc(r.tripId || "")}"><td>${esc(fmtDT(r.requestedTime))}</td><td><b>${esc(travellers(r))}</b></td><td>${esc(r.requesterName)}</td>
      <td>${esc(normDept(r.department))}</td><td>${esc(r.joinTargetLabel || `${r.category}: ${r.purpose}`)}</td><td>${esc((r.stops || []).map((s) => s.name).join(" → "))}</td>
      <td class="num">${r.passengers || 1}</td><td>${esc(r.driverName || "—")}</td><td>${chip(r.status)}</td></tr>`).join("")}</tbody></table>`
    : `<p class="empty">No bookings or requests match.</p>`;
  $("paxTable").querySelectorAll("tr[data-trip]").forEach((tr) => { if (tr.dataset.trip) tr.onclick = () => openTrip(tr.dataset.trip); });
}
$("paxCsv").onclick = () => csv("passengers", ["Date", "Time", "Passenger(s)", "Requested by", "Department", "Run / category", "Purpose", "Drop-off", "Seats", "Driver", "Status"],
  filteredPax().map((r) => [fmtDate(r.requestedTime), fmtTime(r.requestedTime), travellers(r), r.requesterName, normDept(r.department), r.joinTargetLabel || r.category, r.purpose,
    (r.stops || []).map((s) => s.name).join(" > "), r.passengers || 1, r.driverName, r.status]));

// ------------------------------------------------------------------ drivers

function dutyMinutes(uid, onDutyNow) {
  const logs = S.duty.filter((d) => d.driverUid === uid).sort((a, b) => a.at - b.at);
  let total = 0, since = null;
  logs.forEach((l) => {
    if (l.onDuty && since == null) since = Math.max(l.at, S.range.from);
    if (!l.onDuty && since != null) { total += Math.min(l.at, S.range.to) - since; since = null; }
  });
  if (since != null && onDutyNow) total += Math.min(Date.now(), S.range.to) - since;
  return total / 60000;
}

function renderDrivers() {
  const trips = rangeTrips().filter((t) => t.status === "COMPLETED");
  $("driverCards").innerHTML = drivers().map((d) => {
    const uid = d.uid || d.id;
    const onTrip = S.open.find((t) => t.status === "IN_PROGRESS" && t.driverUid === uid);
    const mine = trips.filter((t) => t.driverUid === uid);
    const [label, cls] = onTrip ? ["On ride", "c-ok"] : d.onDuty ? ["On duty", "c-info"] : ["Off duty", "c-muted"];
    return `<div class="glass card"><div class="row"><div><b>${esc(d.name)}</b><br><span class="muted small">${esc(d.phone || d.email || "")}</span></div><span class="chip ${cls}">${label}</span></div>
      ${onTrip ? `<p>${esc(onTrip.title)} · ${esc(onTrip.vehicleName || "")}</p>` : ""}
      <div class="kv"><div><span>Trips</span>${mine.length}</div><div><span>Distance</span>${km(mine.reduce((s, t) => s + (t.distanceKm || 0), 0))}</div>
      <div><span>Driving time</span>${dur(mine.reduce((s, t) => s + (t.durationMin || 0), 0))}</div><div><span>On duty</span>${dur(dutyMinutes(uid, d.onDuty))}</div></div></div>`;
  }).join("") || `<p class="empty">No drivers yet.</p>`;
  const logs = [...S.duty].filter((l) => inRange(l.at)).sort((a, b) => b.at - a.at);
  $("dutyTable").innerHTML = logs.length ? `<table><thead><tr><th>When</th><th>Driver</th><th>Change</th></tr></thead><tbody>${
    logs.map((l) => `<tr><td>${esc(fmtDT(l.at))}</td><td>${esc(l.driverName)}</td><td>${l.onDuty ? '<span class="chip c-ok">On duty</span>' : '<span class="chip c-muted">Off duty</span>'}</td></tr>`).join("")}</tbody></table>`
    : `<p class="empty">No duty changes in this period.</p>`;
}

// ------------------------------------------------------------------ approvals

function renderApprovals() {
  const list = [...S.pending].sort((a, b) => a.requestedTime - b.requestedTime);
  $("approvalList").innerHTML = list.map((r) => `<div class="glass card">
      <div class="row"><b>${esc(r.category)} – ${esc(normDept(r.department))}</b>${chip("PENDING")}</div>
      <p>${esc(r.purpose)}</p>
      <div class="kv"><div><span>Passenger(s)</span>${esc(travellers(r))}</div><div><span>When</span>${esc(fmtDT(r.requestedTime))}</div>
      <div><span>Seats</span>${r.passengers || 1}</div><div><span>Requested by</span>${esc(r.requesterName)}</div></div>
      ${(r.stops || []).length ? `<p class="muted">Stops: ${esc(r.stops.map((s) => s.name).join(" → "))}</p>` : ""}
      ${r.joinTargetLabel ? `<p class="muted">Wants to join: ${esc(r.joinTargetLabel)}</p>` : ""}
      ${r.notes ? `<p class="muted">Notes: ${esc(r.notes)}</p>` : ""}
      <div class="row" style="justify-content:flex-start;gap:8px"><button class="btn ok" data-approve="${esc(r.id)}">Approve</button><button class="btn danger" data-reject="${esc(r.id)}">Reject</button></div>
      <p class="muted small">Approving here creates a separate trip for all drivers. To combine with another trip or pick a driver, use the app.</p>
    </div>`).join("") || `<p class="empty">Nothing waiting for approval 👍</p>`;
  $("approvalList").querySelectorAll("[data-approve]").forEach((b) => { b.onclick = () => approve(S.pending.find((r) => r.id === b.dataset.approve)); });
  $("approvalList").querySelectorAll("[data-reject]").forEach((b) => { b.onclick = () => reject(S.pending.find((r) => r.id === b.dataset.reject)); });
}

async function approve(r) {
  if (!r) return;
  const comment = prompt("Comment for the requester (optional):", "") ?? null;
  if (comment === null) return;
  const now = Date.now();
  const tripRef = doc(collection(db, "trips"));
  const stops = (r.stops || []).map((s) => ({ ...s, requestId: r.id, department: r.department }));
  const b = writeBatch(db);
  b.set(tripRef, {
    type: "REQUEST", status: "READY", title: `${r.category} – ${r.department}`, category: r.category, purpose: r.purpose,
    departments: [r.department], requestIds: [r.id], stops, plannedTime: r.requestedTime, passengers: r.passengers || 1,
    createdAt: now, openedAt: now, createdBy: S.me.name, driverUid: "", driverName: "",
  });
  b.update(doc(db, "requests", r.id), {
    status: "APPROVED", approverName: S.me.name, approverUid: S.me.uid, decidedAt: now, decisionComment: comment.trim(), tripId: tripRef.id,
  });
  await b.commit().catch((e) => alert(e.message));
}

async function reject(r) {
  if (!r) return;
  const comment = prompt("Reason for rejection:", "");
  if (!comment) return;
  await updateDoc(doc(db, "requests", r.id), {
    status: "REJECTED", approverName: S.me.name, approverUid: S.me.uid, decidedAt: Date.now(), decisionComment: comment.trim(),
  }).catch((e) => alert(e.message));
}

// ------------------------------------------------------------------ alerts

function renderAlerts() {
  $("alertList").innerHTML = S.alerts.map((a) => {
    const cls = a.acknowledged ? "c-muted" : a.severity === "HIGH" ? "c-danger" : a.severity === "WARN" ? "c-warn" : "c-info";
    return `<div class="glass card"><div class="row"><b>${esc(a.title)}</b><span class="chip ${cls}">${a.acknowledged ? "Acknowledged" : esc(a.severity)}</span></div>
      <p>${esc(a.message)}</p><p class="muted small">${esc(fmtDT(a.createdAt))}${a.acknowledgedBy ? " · ✔ " + esc(a.acknowledgedBy) : ""}</p>
      <div class="row" style="justify-content:flex-start;gap:8px">${a.tripId ? `<button class="btn ghost" data-trip="${esc(a.tripId)}">Open trip</button>` : ""}
      ${a.acknowledged ? "" : `<button class="btn ghost" data-ack="${esc(a.id)}">Acknowledge</button>`}</div></div>`;
  }).join("") || `<p class="empty">No alerts.</p>`;
  $("alertList").querySelectorAll("[data-trip]").forEach((b) => { b.onclick = () => openTrip(b.dataset.trip); });
  $("alertList").querySelectorAll("[data-ack]").forEach((b) => {
    b.onclick = () => updateDoc(doc(db, "alerts", b.dataset.ack), { acknowledged: true, acknowledgedBy: S.me.name }).catch((e) => alert(e.message));
  });
}

// ------------------------------------------------------------------ CSV

function csv(name, header, rows) {
  const q = (v) => { const s = String(v ?? ""); return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; };
  const text = [header, ...rows].map((r) => r.map(q).join(",")).join("\n");
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob(["﻿" + text], { type: "text/csv;charset=utf-8" }));
  a.download = `van-${name}-${dayKey(S.range.from)}_${dayKey(S.range.to - 1)}.csv`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
}

