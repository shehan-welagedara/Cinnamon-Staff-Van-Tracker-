// Van Tracker – administration pages of the web dashboard (administrators only).
// Users & access, vehicles, schedules, app settings, announcements, email reports,
// audit log and backup. Firestore security rules enforce the same limits server-side.
import { initializeApp, deleteApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import {
  getAuth, createUserWithEmailAndPassword, sendPasswordResetEmail, signOut,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";
import {
  getFirestore, collection, doc, getDocs, onSnapshot, query, orderBy, limit,
  addDoc, setDoc, updateDoc, deleteDoc,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";

const ROLES = [["DRIVER", "Driver"], ["HOD", "Head of Department"], ["GM", "General Manager"], ["OM", "Operations Manager"], ["ADMIN", "Administrator"]];
const ROLE_LABEL = Object.fromEntries([["PENDING", "Waiting for approval"], ...ROLES]);
const DEPARTMENTS = ["Front Office", "Housekeeping", "F&B Service", "Kitchen", "Engineering", "Finance",
  "Human Resources", "Purchasing", "Sales & Marketing", "Security", "Administration", "Transport"];
const CATEGORIES = ["Bank", "Laundry", "Staff Transport", "Purchasing", "Guest Related", "Official Meeting",
  "Long-distance trip", "Delivery / Pickup", "Other"];
const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const REPO_API = "https://api.github.com/repos/shehan-welagedara/Cinnamon-Staff-Van-Tracker-/releases/latest";

let X; // context from app.js: { db, auth, cfg, S, esc, fmtDT, $, renderSoon, labelTables, tileLayer }
const A = { schedules: [], report: null, announcement: null, runs: [], heartbeat: null, audit: [], latest: null, dirty: {} };

export function initAdmin(ctx) {
  X = ctx;
  const { db } = X;
  const warn = (e) => console.warn(e);
  onSnapshot(collection(db, "schedules"), (s) => { A.schedules = s.docs.map((d) => ({ id: d.id, ...d.data() })); X.renderSoon(); }, warn);
  onSnapshot(doc(db, "config", "report"), (d) => { A.report = d.data() || {}; X.renderSoon(); }, warn);
  onSnapshot(doc(db, "config", "announcement"), (d) => { A.announcement = d.data() || {}; X.renderSoon(); }, warn);
  onSnapshot(query(collection(db, "reportRuns"), orderBy("at", "desc"), limit(25)), (s) => { A.runs = s.docs.map((d) => ({ id: d.id, ...d.data() })).filter((r) => r.id !== "_heartbeat"); X.renderSoon(); }, warn);
  onSnapshot(doc(db, "reportRuns", "_heartbeat"), (d) => { A.heartbeat = d.data() || null; X.renderSoon(); }, warn);
  onSnapshot(query(collection(db, "audit"), orderBy("at", "desc"), limit(300)), (s) => { A.audit = s.docs.map((d) => ({ id: d.id, ...d.data() })); X.renderSoon(); }, warn);
  fetch(REPO_API).then((r) => r.ok ? r.json() : null).then((j) => {
    const code = j && Number(String(j.tag_name || "").split(".").pop());
    if (code) { A.latest = { code, name: j.tag_name, at: j.published_at }; X.renderSoon(); }
  }).catch(() => {});
  bindStatic();
}

export const ADMIN_VIEWS = {
  users: ["Users & access", () => renderUsers()],
  vehicles: ["Vehicles", () => renderVehicles()],
  schedules: ["Scheduled runs", () => renderSchedules()],
  settings: ["App settings", () => renderSettings()],
  reports: ["Email reports", () => renderReports()],
  audit: ["Audit log & backup", () => renderAudit()],
};

// ------------------------------------------------------------------ helpers

const $ = (id) => document.getElementById(id);
const e = (v) => X.esc(v);
const errMsg = (err) => String(err?.message || err).replace("Firebase: ", "");
const num = (v, d = 0) => { const n = parseFloat(String(v).replace(",", ".")); return Number.isFinite(n) ? n : d; };
const int = (v, d = 0) => { const n = parseInt(v, 10); return Number.isFinite(n) ? n : d; };
const isMe = (u) => (u.uid || u.id) === X.S.me.uid;

async function audit(action, target, details = "") {
  await addDoc(collection(X.db, "audit"), {
    at: Date.now(), byUid: X.S.me.uid, byName: X.S.me.name || "", action, target: target || "", details: String(details || ""),
  }).catch((err) => console.warn("audit", err));
}

function toast(text, bad) {
  const t = document.createElement("div");
  t.className = `toast glass${bad ? " bad" : ""}`; t.textContent = text;
  document.body.appendChild(t);
  setTimeout(() => t.classList.add("on"), 10);
  setTimeout(() => { t.classList.remove("on"); setTimeout(() => t.remove(), 300); }, bad ? 6000 : 3200);
}

function download(name, text, type) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([text], { type }));
  a.download = name; a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 3000);
}
function csvText(header, rows) {
  const q = (v) => { const s = String(v ?? ""); return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; };
  return "﻿" + [header, ...rows].map((r) => r.map(q).join(",")).join("\n");
}

const field = (name, label, value = "", type = "text", attrs = "") =>
  `<label>${e(label)}<input name="${name}" type="${type}" value="${e(value ?? "")}" ${attrs}></label>`;
const area = (name, label, value = "", attrs = "") =>
  `<label>${e(label)}<textarea name="${name}" rows="3" ${attrs}>${e(value ?? "")}</textarea></label>`;
const select = (name, label, options, value) =>
  `<label>${e(label)}<select name="${name}">${options.map(([v, l]) => `<option value="${e(v)}"${String(v) === String(value) ? " selected" : ""}>${e(l)}</option>`).join("")}</select></label>`;
const check = (name, label, on) =>
  `<label class="check-row"><input type="checkbox" name="${name}"${on ? " checked" : ""}> <span>${e(label)}</span></label>`;
const withCurrent = (list, cur) => (cur && !list.includes(cur) ? [cur, ...list] : list).map((x) => [x, x]);

/** Opens the shared edit dialog. onSubmit(form) may return false to keep it open; throw to show an error. */
function openForm({ title, html, submitLabel = "Save", onSubmit, onOpen }) {
  const d = $("editDialog");
  $("edTitle").textContent = title;
  $("edBody").innerHTML = html;
  $("edError").textContent = "";
  $("edSave").textContent = submitLabel;
  $("edSave").hidden = !onSubmit;
  $("edSave").onclick = async (ev) => {
    ev.preventDefault();
    if (!$("edForm").reportValidity()) return;
    $("edSave").disabled = true; $("edError").textContent = "";
    try { if ((await onSubmit($("edForm"))) !== false) d.close(); } catch (err) { $("edError").textContent = errMsg(err); }
    finally { $("edSave").disabled = false; }
  };
  d.showModal();
  onOpen?.($("edForm"));
}

function bindStatic() {
  $("edCancel").onclick = (ev) => { ev.preventDefault(); $("editDialog").close(); };
  $("uAdd").onclick = addUser;
  $("uCsv").onclick = () => download("van-users.csv", csvText(
    ["Name", "Email", "Phone", "Department", "Role", "Administrator", "Active"],
    X.S.users.map((u) => [u.name, u.email, u.phone, u.department, ROLE_LABEL[u.role] || u.role, u.isAdmin ? "yes" : "", u.active === false ? "no" : "yes"]),
  ), "text/csv;charset=utf-8");
  ["uSearch", "uRole", "uStatus", "auSearch"].forEach((id) => $(id).addEventListener("input", X.renderSoon));
  $("vAdd").onclick = () => editVehicle(null);
  $("scAdd").onclick = () => editSchedule(null);
  $("backupBtn").onclick = backup;
  $("auCsv").onclick = () => download("van-audit-log.csv", csvText(
    ["When", "By", "Action", "Target", "Details"], A.audit.map((a) => [X.fmtDT(a.at), a.byName, a.action, a.target, a.details]),
  ), "text/csv;charset=utf-8");
  // Forms: remember unsaved edits so live updates don't overwrite what the admin is typing.
  document.querySelectorAll("form.admin-form").forEach((f) => {
    f.addEventListener("input", () => { A.dirty[f.id] = true; });
    f.addEventListener("submit", (ev) => ev.preventDefault());
  });
  $("annSave").onclick = saveAnnouncement;
  $("annClear").onclick = clearAnnouncement;
  ["setFuel", "setHotel", "setTracking"].forEach((id) => { $(`${id}Save`).onclick = () => saveSettings(id); });
  $("verRequire").onclick = () => setMinVersion(A.latest?.code || 0);
  $("verClear").onclick = () => setMinVersion(0);
  $("hotelMapBtn").onclick = pickHotelOnMap;
  $("repSave").onclick = saveReport;
  $("repSendNow").onclick = sendNow;
  $("repFreq").addEventListener("change", syncReportFreq);
}

// ------------------------------------------------------------------ users & access

function userStatus(u) {
  if (u.role === "PENDING" && u.active !== false) return ["pending", '<span class="chip c-warn">Waiting</span>'];
  if (u.active === false) return ["inactive", '<span class="chip c-muted">Deactivated</span>'];
  return ["active", '<span class="chip c-ok">Active</span>'];
}

function renderUsers() {
  const S = X.S;
  const q = $("uSearch").value.trim().toLowerCase(), role = $("uRole").value, st = $("uStatus").value;
  const users = [...S.users].sort((a, b) => (a.name || "").localeCompare(b.name || ""));
  const pending = users.filter((u) => u.role === "PENDING" && u.active !== false);
  $("uPending").innerHTML = pending.length ? `<div class="glass card"><h3>Waiting for approval (${pending.length})</h3><div class="table-wrap"><table>
      <thead><tr><th>Name</th><th>Email</th><th>Department</th><th>Asked for</th><th></th></tr></thead><tbody>${pending.map((u) => `<tr>
      <td><b>${e(u.name)}</b><div class="muted small">${e(u.phone)}</div></td><td>${e(u.email)}</td><td>${e(u.department)}</td>
      <td>${e(ROLE_LABEL[u.requestedRole] || u.requestedRole || "")}</td>
      <td class="actions"><button class="btn ok sm" data-uapprove="${e(u.id)}">Approve…</button><button class="btn danger sm" data-udecline="${e(u.id)}">Decline</button></td></tr>`).join("")}
      </tbody></table></div></div>` : "";
  const rows = users.filter((u) => {
    if (role && (role === "ADMINFLAG" ? !u.isAdmin : u.role !== role)) return false;
    if (st && userStatus(u)[0] !== st) return false;
    return !q || [u.name, u.email, u.phone, u.department].some((v) => String(v || "").toLowerCase().includes(q));
  });
  const counts = { total: users.length, active: users.filter((u) => userStatus(u)[0] === "active").length, drivers: users.filter((u) => u.role === "DRIVER" && u.active !== false).length, admins: users.filter((u) => u.isAdmin && u.active !== false).length };
  $("uTable").innerHTML = `<div class="summary"><span><b>${counts.total}</b> accounts</span><span><b>${counts.active}</b> active</span><span><b>${counts.drivers}</b> drivers</span><span><b>${counts.admins}</b> administrators</span></div>` +
    (rows.length ? `<table><thead><tr><th>Name</th><th>Email</th><th>Phone</th><th>Department</th><th>Role</th><th>Status</th><th></th></tr></thead><tbody>${rows.map((u) => `<tr>
      <td><b>${e(u.name)}</b>${isMe(u) ? ' <span class="muted small">(you)</span>' : ""}</td><td>${e(u.email)}</td><td>${e(u.phone)}</td><td>${e(u.department)}</td>
      <td>${e(ROLE_LABEL[u.role] || u.role)}${u.isAdmin && u.role !== "ADMIN" ? ' <span class="chip c-brand">Admin</span>' : ""}</td>
      <td>${userStatus(u)[1]}${u.onDuty ? ' <span class="chip c-info">On duty</span>' : ""}</td>
      <td class="actions"><button class="btn ghost sm" data-uedit="${e(u.id)}">Edit</button><button class="btn ghost sm" data-ureset="${e(u.id)}">Reset password</button>
      ${isMe(u) ? "" : u.active === false ? `<button class="btn ok sm" data-uactive="${e(u.id)}">Activate</button>` : `<button class="btn danger sm" data-uinactive="${e(u.id)}">Deactivate</button>`}</td></tr>`).join("")}</tbody></table>`
      : `<p class="empty">No users match.</p>`);
  const find = (id) => S.users.find((u) => u.id === id);
  const on = (attr, fn) => document.querySelectorAll(`#view-users [${attr}]`).forEach((b) => { b.onclick = () => fn(find(b.getAttribute(attr))); });
  on("data-uapprove", (u) => editUser(u, true));
  on("data-udecline", declineUser);
  on("data-uedit", (u) => editUser(u, false));
  on("data-ureset", resetPassword);
  on("data-uactive", (u) => setActive(u, true));
  on("data-uinactive", (u) => setActive(u, false));
}

function userFields(u, approving) {
  const role = approving ? (u.requestedRole && u.requestedRole !== "PENDING" ? u.requestedRole : "HOD") : (u.role === "PENDING" ? "HOD" : u.role);
  return field("name", "Full name", u.name, "text", "required") +
    field("phone", "Phone", u.phone, "tel") +
    select("department", "Department", withCurrent(DEPARTMENTS, u.department), u.department || DEPARTMENTS[0]) +
    select("role", "Role", ROLES, role) +
    check("isAdmin", "Administrator (can manage users, vehicles, settings and reports)", u.isAdmin || role === "ADMIN") +
    (approving ? "" : check("active", "Account active (can sign in and use the app)", u.active !== false));
}

function editUser(u, approving) {
  if (!u) return;
  openForm({
    title: approving ? `Approve ${u.name}` : `Edit ${u.name}`,
    submitLabel: approving ? "Approve" : "Save",
    html: `<p class="muted">${e(u.email)}</p>${userFields(u, approving)}`,
    onSubmit: async (f) => {
      const role = f.role.value;
      const data = {
        name: f.name.value.trim(), phone: f.phone.value.trim(), department: f.department.value, role,
        isAdmin: f.isAdmin.checked || role === "ADMIN", active: approving ? true : f.active.checked,
      };
      if (isMe(u) && (!data.isAdmin || !data.active)) throw new Error("You can't remove your own administrator access or deactivate yourself. Ask another administrator.");
      await updateDoc(doc(X.db, "users", u.id), data);
      const was = (k) => u[k] ?? (typeof data[k] === "boolean" ? (k === "active") : "");
      const changes = Object.keys(data).filter((k) => String(was(k)) !== String(data[k])).map((k) => `${k}: ${u[k] ?? "–"} → ${data[k]}`);
      await audit(approving ? "Approved user" : "Edited user", `${u.name} <${u.email}>`, changes.join("; "));
      toast(approving ? `${data.name} approved as ${ROLE_LABEL[role]}` : "Saved");
    },
  });
}

async function declineUser(u) {
  if (!u || !confirm(`Decline ${u.name} (${u.email})?\n\nTheir sign-up is removed. They could register again.`)) return;
  try { await deleteDoc(doc(X.db, "users", u.id)); await audit("Declined sign-up", `${u.name} <${u.email}>`); toast("Sign-up declined"); }
  catch (err) { toast(errMsg(err), true); }
}

async function setActive(u, active) {
  if (!u) return;
  if (!active && !confirm(`Deactivate ${u.name}?\n\nThey can no longer use the app or the dashboard. Their trips and history are kept, and you can activate them again any time.`)) return;
  try {
    await updateDoc(doc(X.db, "users", u.id), { active, ...(active ? {} : { onDuty: false }) });
    await audit(active ? "Activated user" : "Deactivated user", `${u.name} <${u.email}>`);
    toast(active ? `${u.name} activated` : `${u.name} deactivated`);
  } catch (err) { toast(errMsg(err), true); }
}

async function resetPassword(u) {
  if (!u?.email) return;
  if (!confirm(`Send a password reset email to ${u.email}?\n\nThey get a link to choose a new password (ask them to check spam).`)) return;
  try { await sendPasswordResetEmail(X.auth, u.email); await audit("Sent password reset", `${u.name} <${u.email}>`); toast(`Reset email sent to ${u.email}`); }
  catch (err) { toast(errMsg(err), true); }
}

function tempPassword() {
  const c = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
  const a = new Uint32Array(10); crypto.getRandomValues(a);
  return "Km-" + [...a].map((n) => c[n % c.length]).join("");
}

function addUser() {
  openForm({
    title: "Add user",
    submitLabel: "Create account",
    html: `<p class="muted small">Creates the sign-in for the app and this dashboard. Give the person the email and temporary password, or tick the box so they set their own password by email.</p>` +
      field("name", "Full name", "", "text", "required") +
      field("email", "Email (used to sign in)", "", "email", "required autocomplete=\"off\"") +
      field("password", "Temporary password (at least 6 characters)", tempPassword(), "text", "required minlength=\"6\" autocomplete=\"off\"") +
      field("phone", "Phone", "", "tel") +
      select("department", "Department", DEPARTMENTS.map((d) => [d, d]), DEPARTMENTS[0]) +
      select("role", "Role", ROLES, "HOD") +
      check("isAdmin", "Administrator", false) +
      check("sendReset", "Also email them a link to set their own password", false),
    onSubmit: async (f) => {
      const email = f.email.value.trim().toLowerCase(), password = f.password.value, role = f.role.value;
      const profile = { name: f.name.value.trim(), email, phone: f.phone.value.trim(), department: f.department.value };
      // A second Firebase instance creates the account without signing the administrator out.
      const sec = initializeApp(X.cfg, `create-${Date.now()}`);
      let uid;
      try {
        const sAuth = getAuth(sec);
        const cred = await createUserWithEmailAndPassword(sAuth, email, password);
        uid = cred.user.uid;
        // Security rules let a new account create only its own PENDING profile…
        await setDoc(doc(getFirestore(sec), "users", uid), {
          uid, ...profile, role: "PENDING", requestedRole: role, isAdmin: false, active: true, createdAt: Date.now(),
        });
        await signOut(sAuth);
      } finally { await deleteApp(sec).catch(() => {}); }
      // …then the administrator gives it its role.
      await updateDoc(doc(X.db, "users", uid), { role, isAdmin: f.isAdmin.checked || role === "ADMIN", active: true });
      if (f.sendReset.checked) await sendPasswordResetEmail(X.auth, email).catch(() => {});
      await audit("Created user", `${profile.name} <${email}>`, `role: ${role}${f.isAdmin.checked ? ", administrator" : ""}`);
      $("edBody").innerHTML = `<p>✅ <b>${e(profile.name)}</b> can now sign in as <b>${e(ROLE_LABEL[role])}</b>.</p>
        <div class="kv"><div><span>Email</span>${e(email)}</div><div><span>Temporary password</span><code>${e(password)}</code></div></div>
        <p class="muted small">Copy the password now – it isn't shown again. They can change it with "Forgot password" on the sign-in screen.</p>`;
      $("edSave").hidden = true;
      return false;
    },
  });
}

// ------------------------------------------------------------------ vehicles

function renderVehicles() {
  const vs = [...X.S.vehicles].sort((a, b) => (a.name || "").localeCompare(b.name || ""));
  const busy = new Set(X.S.open.filter((t) => t.status === "IN_PROGRESS").map((t) => t.vehicleId));
  $("vTable").innerHTML = vs.length ? `<table><thead><tr><th>Name</th><th>Plate</th><th>Type</th><th class="num">Seats</th><th>Fuel</th><th class="num">km/L</th><th>Status</th><th>Notes</th><th></th></tr></thead><tbody>${vs.map((v) => `<tr>
    <td><b>${e(v.name)}</b></td><td>${e(v.plateNo)}</td><td>${e(v.type)}</td><td class="num">${e(v.seats || "")}</td><td>${e(v.fuelType)}</td><td class="num">${e(v.kmPerLiter)}</td>
    <td>${v.active === false ? '<span class="chip c-muted">Out of service</span>' : busy.has(v.id) ? '<span class="chip c-ok">On a trip</span>' : '<span class="chip c-info">Available</span>'}</td>
    <td class="muted small">${e(v.notes)}</td>
    <td class="actions"><button class="btn ghost sm" data-vedit="${e(v.id)}">Edit</button>
      <button class="btn ${v.active === false ? "ok" : "danger"} sm" data-vtoggle="${e(v.id)}">${v.active === false ? "Back in service" : "Out of service"}</button></td></tr>`).join("")}</tbody></table>`
    : `<p class="empty">No vehicles yet. Until you add one, the app uses the single vehicle from the old settings (${e(X.S.settings.vehicleName || "Van")}).</p>`;
  document.querySelectorAll("#view-vehicles [data-vedit]").forEach((b) => { b.onclick = () => editVehicle(X.S.vehicles.find((v) => v.id === b.dataset.vedit)); });
  document.querySelectorAll("#view-vehicles [data-vtoggle]").forEach((b) => {
    b.onclick = async () => {
      const v = X.S.vehicles.find((x) => x.id === b.dataset.vtoggle);
      try { await updateDoc(doc(X.db, "vehicles", v.id), { active: v.active === false }); await audit(v.active === false ? "Vehicle back in service" : "Vehicle out of service", v.name); }
      catch (err) { toast(errMsg(err), true); }
    };
  });
}

function editVehicle(v) {
  const x = v || { name: "", plateNo: "", type: "Van", seats: 14, fuelType: "Diesel", kmPerLiter: 9, active: true, notes: "" };
  openForm({
    title: v ? `Edit ${v.name}` : "Add vehicle",
    html: field("name", "Name (e.g. Staff Van 2)", x.name, "text", "required") + field("plateNo", "Number plate", x.plateNo) +
      select("type", "Type", withCurrent(["Van", "Car", "Cab", "Bus", "Lorry", "Three-wheeler", "Other"], x.type), x.type) +
      field("seats", "Passenger seats", x.seats, "number", 'min="0"') +
      select("fuelType", "Fuel", [["Diesel", "Diesel"], ["Petrol", "Petrol"]], x.fuelType) +
      field("kmPerLiter", "Fuel economy (km per litre)", x.kmPerLiter, "number", 'step="0.1" min="0.1" required') +
      area("notes", "Notes (service dates, insurance…)", x.notes) + check("active", "In service", x.active !== false),
    onSubmit: async (f) => {
      const data = {
        name: f.name.value.trim(), plateNo: f.plateNo.value.trim().toUpperCase(), type: f.type.value, seats: int(f.seats.value),
        fuelType: f.fuelType.value, kmPerLiter: num(f.kmPerLiter.value, 9), notes: f.notes.value.trim(), active: f.active.checked,
      };
      if (v) await updateDoc(doc(X.db, "vehicles", v.id), data); else await addDoc(collection(X.db, "vehicles"), data);
      await audit(v ? "Edited vehicle" : "Added vehicle", data.name, `${data.plateNo} · ${data.kmPerLiter} km/L`);
    },
  });
}

// ------------------------------------------------------------------ scheduled runs

const daysText = (d) => (d || []).length === 7 ? "Every day" : (d || []).slice().sort().map((n) => DAYS[n - 1]).join(", ");

function renderSchedules() {
  const list = [...A.schedules].sort((a, b) => String(a.time).localeCompare(String(b.time)));
  $("scTable").innerHTML = list.length ? `<table><thead><tr><th>Run</th><th>Time</th><th>Days</th><th>Goes to</th><th>Stops</th><th>Status</th><th></th></tr></thead><tbody>${list.map((s) => `<tr>
    <td><b>${e(s.title)}</b><div class="muted small">${e(s.category)}${s.department ? " · " + e(s.department) : ""}</div></td><td>${e(s.time)}</td><td>${e(daysText(s.days))}</td>
    <td>${e(s.destination?.name || "")}</td><td class="num">${(s.stops || []).length}</td>
    <td>${s.active === false ? '<span class="chip c-muted">Paused</span>' : '<span class="chip c-ok">Running</span>'}</td>
    <td class="actions"><button class="btn ghost sm" data-scedit="${e(s.id)}">Edit</button>
      <button class="btn ghost sm" data-sctoggle="${e(s.id)}">${s.active === false ? "Resume" : "Pause"}</button>
      <button class="btn danger sm" data-scdel="${e(s.id)}">Delete</button></td></tr>`).join("")}</tbody></table>`
    : `<p class="empty">No scheduled runs yet.</p>`;
  const find = (id) => A.schedules.find((s) => s.id === id);
  document.querySelectorAll("#view-schedules [data-scedit]").forEach((b) => { b.onclick = () => editSchedule(find(b.dataset.scedit)); });
  document.querySelectorAll("#view-schedules [data-sctoggle]").forEach((b) => {
    b.onclick = async () => {
      const s = find(b.dataset.sctoggle);
      try { await updateDoc(doc(X.db, "schedules", s.id), { active: s.active === false }); await audit(s.active === false ? "Resumed run" : "Paused run", s.title); }
      catch (err) { toast(errMsg(err), true); }
    };
  });
  document.querySelectorAll("#view-schedules [data-scdel]").forEach((b) => {
    b.onclick = async () => {
      const s = find(b.dataset.scdel);
      if (!confirm(`Delete the run "${s.title}"?\n\nPast trips of this run are kept. To stop it temporarily use Pause instead.`)) return;
      try { await deleteDoc(doc(X.db, "schedules", s.id)); await audit("Deleted run", s.title); } catch (err) { toast(errMsg(err), true); }
    };
  });
}

const pinIcon = () => L.divIcon({ className: "", html: '<div class="pick-pin"></div>', iconSize: [26, 26], iconAnchor: [13, 26] });

let pickMap;
function editSchedule(s) {
  const x = s || { title: "", time: "21:30", days: [1, 2, 3, 4, 5, 6, 7], category: "Staff Transport", purpose: "", department: "", active: true, destination: null, stops: [] };
  const dest = x.destination || null;
  openForm({
    title: s ? `Edit ${s.title}` : "New scheduled run",
    html: field("title", "Name (e.g. Night staff transport)", x.title, "text", "required") +
      `<div class="grid-2f">${field("time", "Departure time", x.time, "time", "required")}${select("category", "Category", withCurrent(CATEGORIES, x.category), x.category)}</div>` +
      `<label>Days</label><div class="days">${DAYS.map((d, i) => `<label class="day"><input type="checkbox" name="day" value="${i + 1}"${(x.days || []).includes(i + 1) ? " checked" : ""}><span>${d}</span></label>`).join("")}</div>` +
      field("purpose", "Purpose", x.purpose) +
      select("department", "Department (optional)", [["", "–"], ...withCurrent(DEPARTMENTS, x.department)], x.department || "") +
      field("destName", "Destination (e.g. Staff Accommodation)", dest?.name || "") +
      `<input type="hidden" name="destLat" value="${dest?.lat ?? ""}"><input type="hidden" name="destLng" value="${dest?.lng ?? ""}">
       <p class="muted small">Tap the map to set the destination. Stops along the way are edited in the app (Recurring schedules).</p>
       <div id="pickMap" class="map"></div>` +
      check("active", "Running (staff can book seats)", x.active !== false),
    onOpen: (f) => {
      if (typeof L === "undefined") return;
      if (pickMap) { pickMap.remove(); pickMap = null; }
      const h = X.S.settings.hotelLat ? [X.S.settings.hotelLat, X.S.settings.hotelLng] : [7.2906, 80.6337];
      pickMap = L.map("pickMap").setView(dest?.lat ? [dest.lat, dest.lng] : h, 13);
      X.tileLayer().addTo(pickMap);
      let m = dest?.lat ? L.marker([dest.lat, dest.lng], { icon: pinIcon() }).addTo(pickMap) : null;
      pickMap.on("click", (ev) => {
        f.destLat.value = ev.latlng.lat.toFixed(6); f.destLng.value = ev.latlng.lng.toFixed(6);
        if (m) m.setLatLng(ev.latlng); else m = L.marker(ev.latlng, { icon: pinIcon() }).addTo(pickMap);
      });
      setTimeout(() => pickMap.invalidateSize(), 120);
    },
    onSubmit: async (f) => {
      const days = [...f.querySelectorAll('input[name="day"]:checked')].map((c) => int(c.value));
      if (!days.length) throw new Error("Pick at least one day.");
      const lat = num(f.destLat.value, NaN), lng = num(f.destLng.value, NaN), name = f.destName.value.trim();
      const destination = name ? { name, lat: Number.isFinite(lat) ? lat : 0, lng: Number.isFinite(lng) ? lng : 0, requestId: "", department: "", arrivedAt: null } : null;
      const data = {
        title: f.title.value.trim(), time: f.time.value, days, category: f.category.value, purpose: f.purpose.value.trim(),
        department: f.department.value, active: f.active.checked, destination,
      };
      if (s) await updateDoc(doc(X.db, "schedules", s.id), data);
      else await addDoc(collection(X.db, "schedules"), { ...data, stops: [] });
      await audit(s ? "Edited run" : "Created run", data.title, `${data.time} · ${daysText(days)}${name ? " → " + name : ""}`);
    },
  });
}

// ------------------------------------------------------------------ settings & announcement

function fillForm(id, values) {
  if (A.dirty[id]) return;
  const f = $(id);
  Object.entries(values).forEach(([k, v]) => {
    const el = f.elements[k];
    if (!el) return;
    if (el.type === "checkbox") el.checked = !!v; else el.value = v ?? "";
  });
}

function renderSettings() {
  const st = X.S.settings;
  const a = A.announcement || {};
  fillForm("annForm", { message: a.message || "", level: a.level || "info", until: a.until ? new Date(a.until - new Date().getTimezoneOffset() * 6e4).toISOString().slice(0, 10) : "", active: a.active !== false && !!a.message });
  const live = a.message && a.active !== false && !(a.until && a.until < Date.now());
  $("annStatus").innerHTML = live ? `<span class="chip c-ok">Showing in the app</span> <span class="muted small">by ${e(a.byName || "")} · ${e(X.fmtDT(a.at))}</span>` : '<span class="chip c-muted">No announcement</span>';
  fillForm("setFuelForm", { currency: st.currency || "LKR", fuelPricePerLiter: st.fuelPricePerLiter ?? "", petrolPricePerLiter: st.petrolPricePerLiter ?? "", kmPerLiter: st.kmPerLiter ?? "", distanceCorrectionPct: st.distanceCorrectionPct ?? 0 });
  fillForm("setHotelForm", { hotelName: st.hotelName || "", hotelLat: st.hotelLat ?? "", hotelLng: st.hotelLng ?? "", geofenceRadiusM: st.geofenceRadiusM ?? 150 });
  fillForm("setTrackingForm", {
    autoApproveScheduled: st.autoApproveScheduled !== false, reasonDeadlineHour: st.reasonDeadlineHour ?? 20, autoEndUnauthorizedMin: st.autoEndUnauthorizedMin ?? 10,
    movementSpeedKmh: st.movementSpeedKmh ?? 12, minGpsAccuracyM: st.minGpsAccuracyM ?? 50, usePowerTrigger: st.usePowerTrigger !== false,
  });
  const min = st.minVersionCode || 0;
  $("verInfo").innerHTML = `<div class="kv"><div><span>Latest published</span>${e(A.latest?.name || "checking…")}</div>
    <div><span>Required on every phone</span>${min ? `v1.0.${min} or newer` : "Not required"}</div></div>`;
  $("verRequire").hidden = !(A.latest && A.latest.code > min);
  $("verRequire").textContent = `Require every phone to update to ${A.latest?.name || ""}`;
  $("verClear").hidden = !min;
}

const SETTING_FIELDS = {
  setFuel: { currency: "text", fuelPricePerLiter: "num", petrolPricePerLiter: "num", kmPerLiter: "num", distanceCorrectionPct: "num" },
  setHotel: { hotelName: "text", hotelLat: "num", hotelLng: "num", geofenceRadiusM: "num" },
  setTracking: { autoApproveScheduled: "bool", reasonDeadlineHour: "int", autoEndUnauthorizedMin: "int", movementSpeedKmh: "num", minGpsAccuracyM: "num", usePowerTrigger: "bool" },
};

async function saveSettings(id) {
  const f = $(`${id}Form`);
  if (!f.reportValidity()) return;
  const data = {};
  Object.entries(SETTING_FIELDS[id]).forEach(([k, t]) => {
    const el = f.elements[k];
    if (t !== "bool" && t !== "text" && String(el.value).trim() === "") return; // leave blank numbers unchanged
    data[k] = t === "bool" ? el.checked : t === "num" ? num(el.value) : t === "int" ? int(el.value) : el.value.trim();
  });
  if (data.currency === "") data.currency = "LKR";
  if ("kmPerLiter" in data && data.kmPerLiter <= 0) { toast("km per litre must be more than 0", true); return; }
  if ("reasonDeadlineHour" in data) data.reasonDeadlineHour = Math.min(23, Math.max(0, data.reasonDeadlineHour));
  if ("autoEndUnauthorizedMin" in data) data.autoEndUnauthorizedMin = Math.min(120, Math.max(1, data.autoEndUnauthorizedMin));
  const st = X.S.settings;
  const changes = Object.keys(data).filter((k) => String(st[k] ?? "") !== String(data[k])).map((k) => `${k}: ${st[k] ?? "–"} → ${data[k]}`);
  try {
    await setDoc(doc(X.db, "config", "settings"), data, { merge: true });
    A.dirty[`${id}Form`] = false;
    if (changes.length) await audit("Changed settings", id.replace("set", ""), changes.join("; "));
    toast("Settings saved – phones pick them up straight away");
  } catch (err) { toast(errMsg(err), true); }
}

async function setMinVersion(code) {
  if (code && !confirm(`Require every phone to use v1.0.${code} or newer?\n\nPhones on an older version will only show an "Update required" screen with an install button until they update.`)) return;
  try {
    await setDoc(doc(X.db, "config", "settings"), { minVersionCode: code }, { merge: true });
    await audit(code ? "Required app version" : "Removed required app version", code ? `v1.0.${code}` : "");
    toast(code ? `Every phone must now update to v1.0.${code}` : "Requirement removed");
  } catch (err) { toast(errMsg(err), true); }
}

let hotelMap;
function pickHotelOnMap() {
  const f = $("setHotelForm");
  const cur = [num(f.hotelLat.value, 7.2906), num(f.hotelLng.value, 80.6337)];
  openForm({
    title: "Tap the hotel's location",
    submitLabel: "Use this location",
    html: `<div id="hotelPick" class="map tall"></div><input type="hidden" name="lat" value="${cur[0]}"><input type="hidden" name="lng" value="${cur[1]}">`,
    onOpen: (form) => {
      if (hotelMap) { hotelMap.remove(); hotelMap = null; }
      hotelMap = L.map("hotelPick").setView(cur, 16);
      X.tileLayer().addTo(hotelMap);
      const m = L.marker(cur, { icon: pinIcon() }).addTo(hotelMap);
      hotelMap.on("click", (ev) => { m.setLatLng(ev.latlng); form.lat.value = ev.latlng.lat.toFixed(6); form.lng.value = ev.latlng.lng.toFixed(6); });
      setTimeout(() => hotelMap.invalidateSize(), 120);
    },
    onSubmit: (form) => {
      f.hotelLat.value = form.lat.value; f.hotelLng.value = form.lng.value; A.dirty.setHotelForm = true;
      toast("Location set – press Save to keep it");
    },
  });
}

async function saveAnnouncement() {
  const f = $("annForm");
  const message = f.message.value.trim();
  if (!message) { toast("Write the message first", true); return; }
  const until = f.until.value ? new Date(`${f.until.value}T23:59:59`).getTime() : 0;
  try {
    await setDoc(doc(X.db, "config", "announcement"), { message, level: f.level.value, until, active: f.active.checked, at: Date.now(), byName: X.S.me.name || "" });
    A.dirty.annForm = false;
    await audit("Posted announcement", f.level.value, message);
    toast(f.active.checked ? "Announcement is now showing in the app" : "Announcement saved (hidden)");
  } catch (err) { toast(errMsg(err), true); }
}

async function clearAnnouncement() {
  try {
    await setDoc(doc(X.db, "config", "announcement"), { message: "", active: false, at: Date.now(), byName: X.S.me.name || "" });
    A.dirty.annForm = false; $("annForm").reset();
    await audit("Removed announcement", "");
    toast("Announcement removed");
  } catch (err) { toast(errMsg(err), true); }
}

// ------------------------------------------------------------------ email reports

const REPORT_DEFAULTS = {
  enabled: true, frequency: "monthly", dayOfMonth: 1, dayOfWeek: 1, hour: 7, recipients: "", cc: "",
  subject: "Van Tracker {frequency} report – {period}", message: "",
  sheets: { trips: true, passengers: true, drivers: true, vehicles: true, fuel: true },
};
const reportCfg = () => ({ ...REPORT_DEFAULTS, ...(A.report || {}), sheets: { ...REPORT_DEFAULTS.sheets, ...((A.report || {}).sheets || {}) } });

function syncReportFreq() {
  const weekly = $("repFreq").value === "weekly";
  $("repDomWrap").hidden = weekly; $("repDowWrap").hidden = !weekly;
}

function nextSend(c) {
  if (!c.enabled) return null;
  const now = new Date();
  for (let i = 0; i < 400; i++) {
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() + i, c.hour, 0, 0);
    const dow = d.getDay() === 0 ? 7 : d.getDay();
    const last = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate();
    const ok = c.frequency === "weekly" ? dow === Number(c.dayOfWeek) : d.getDate() === Math.min(Number(c.dayOfMonth), last);
    if (ok && d > now) return d;
  }
  return null;
}

function renderReports() {
  const c = reportCfg();
  fillForm("repForm", {
    enabled: c.enabled, frequency: c.frequency, dayOfMonth: c.dayOfMonth, dayOfWeek: c.dayOfWeek, hour: c.hour,
    recipients: c.recipients, cc: c.cc, subject: c.subject, message: c.message,
    sh_trips: c.sheets.trips, sh_passengers: c.sheets.passengers, sh_drivers: c.sheets.drivers, sh_vehicles: c.sheets.vehicles, sh_fuel: c.sheets.fuel,
  });
  syncReportFreq();
  const hb = A.heartbeat;
  const fresh = hb?.lastTickAt && Date.now() - hb.lastTickAt < 2.5 * 3600e3;
  const n = nextSend(c);
  $("repStatus").innerHTML = `<div class="kv">
      <div><span>Report script</span>${hb?.lastTickAt ? (fresh ? '<span class="chip c-ok">Connected</span>' : '<span class="chip c-warn">Not running</span>') : '<span class="chip c-muted">Not set up yet</span>'}</div>
      <div><span>Last check</span>${hb?.lastTickAt ? e(X.fmtDT(hb.lastTickAt)) : "–"}</div>
      <div><span>Sending account</span>${e(hb?.account || "–")}</div>
      <div><span>Next report</span>${n ? e(n.toLocaleString(undefined, { weekday: "short", day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" })) : "Paused"}</div>
    </div>${hb?.lastTickAt && !fresh ? '<p class="muted small">The script has not checked in for a while. In Apps Script, run <b>setupTrigger</b> again (see the setup steps below).</p>' : ""}
    ${hb?.scriptVersion && hb.scriptVersion < 2 ? '<p class="muted small">An older script version is installed – paste the latest MonthlyReport.gs.</p>' : ""}
    ${c.sendNowAt && (!A.runs[0] || A.runs[0].at < c.sendNowAt) ? `<p><span class="chip c-info">Send-now requested ${e(X.fmtDT(c.sendNowAt))}</span> <span class="muted small">The script picks it up within the hour.</span></p>` : ""}`;
  $("repRuns").innerHTML = A.runs.length ? `<table><thead><tr><th>When</th><th>Type</th><th>Period</th><th>Sent to</th><th>Result</th></tr></thead><tbody>${A.runs.map((r) => `<tr>
      <td>${e(X.fmtDT(r.at))}</td><td>${e({ schedule: "Scheduled", manual: "Send now", test: "Test" }[r.trigger] || r.trigger)}</td><td>${e(r.period)}</td><td class="small">${e(r.to)}</td>
      <td>${r.ok ? `<span class="chip c-ok">Sent</span> <span class="muted small">${e(r.trips ?? "")} trips${r.sheetUrl ? ` · <a href="${e(r.sheetUrl)}" target="_blank" rel="noopener">sheet</a>` : ""}</span>` : `<span class="chip c-danger">Failed</span><div class="small">${e(r.error)}</div>`}</td></tr>`).join("")}</tbody></table>`
    : `<p class="empty">No reports sent yet.</p>`;
}

async function saveReport() {
  const f = $("repForm");
  if (!f.reportValidity()) return;
  const emails = (s) => s.split(/[,;\s]+/).map((x) => x.trim()).filter(Boolean);
  const bad = [...emails(f.recipients.value), ...emails(f.cc.value)].filter((x) => !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(x));
  if (bad.length) { toast(`Check these addresses: ${bad.join(", ")}`, true); return; }
  if (f.enabled.checked && !emails(f.recipients.value).length) { toast("Add at least one recipient", true); return; }
  const data = {
    enabled: f.enabled.checked, frequency: f.frequency.value,
    dayOfMonth: Math.min(31, Math.max(1, int(f.dayOfMonth.value, 1))), dayOfWeek: int(f.dayOfWeek.value, 1),
    hour: Math.min(23, Math.max(0, int(f.hour.value, 7))),
    recipients: emails(f.recipients.value).join(", "), cc: emails(f.cc.value).join(", "),
    subject: f.subject.value.trim() || REPORT_DEFAULTS.subject, message: f.message.value.trim(),
    sheets: { trips: f.sh_trips.checked, passengers: f.sh_passengers.checked, drivers: f.sh_drivers.checked, vehicles: f.sh_vehicles.checked, fuel: f.sh_fuel.checked },
    updatedAt: Date.now(), updatedBy: X.S.me.name || "",
  };
  try {
    await setDoc(doc(X.db, "config", "report"), data, { merge: true });
    A.dirty.repForm = false;
    await audit("Changed email report", data.frequency, `${data.enabled ? "on" : "off"} · day ${data.frequency === "weekly" ? DAYS[data.dayOfWeek - 1] : data.dayOfMonth} ${data.hour}:00 · to ${data.recipients}`);
    toast("Report settings saved");
  } catch (err) { toast(errMsg(err), true); }
}

async function sendNow() {
  const period = $("repNowPeriod").value, to = $("repNowTo").value.trim();
  const c = reportCfg();
  if (!to && !c.recipients) { toast("Add recipients first, or type an address to send a test to", true); return; }
  try {
    await setDoc(doc(X.db, "config", "report"), { sendNowAt: Date.now(), sendNowPeriod: period, sendNowTo: to }, { merge: true });
    await audit("Requested report now", period, to || c.recipients);
    toast("Requested – the report is sent within the hour");
  } catch (err) { toast(errMsg(err), true); }
}

// ------------------------------------------------------------------ audit log & backup

function renderAudit() {
  const q = $("auSearch").value.trim().toLowerCase();
  const rows = A.audit.filter((a) => !q || [a.byName, a.action, a.target, a.details].some((v) => String(v || "").toLowerCase().includes(q)));
  $("auTable").innerHTML = rows.length ? `<table><thead><tr><th>When</th><th>By</th><th>Action</th><th>Target</th><th>Details</th></tr></thead><tbody>${rows.map((a) => `<tr>
    <td>${e(X.fmtDT(a.at))}</td><td>${e(a.byName)}</td><td><b>${e(a.action)}</b></td><td>${e(a.target)}</td><td class="small muted">${e(a.details)}</td></tr>`).join("")}</tbody></table>`
    : `<p class="empty">No administrator actions recorded yet. Changes made in the dashboard appear here.</p>`;
}

async function backup() {
  const btn = $("backupBtn");
  btn.disabled = true; btn.textContent = "Preparing…";
  const names = ["users", "vehicles", "schedules", "config", "trips", "requests", "alerts", "dutyLogs", "fuelLogs", "audit", "reportRuns"];
  const out = { exportedAt: new Date().toISOString(), exportedBy: X.S.me.name, project: X.cfg.projectId, collections: {} };
  try {
    for (const n of names) {
      const snap = await getDocs(collection(X.db, n));
      out.collections[n] = Object.fromEntries(snap.docs.map((d) => [d.id, d.data()]));
    }
    const stamp = new Date().toISOString().slice(0, 10);
    download(`van-tracker-backup-${stamp}.json`, JSON.stringify(out, null, 1), "application/json");
    const total = Object.values(out.collections).reduce((a, c) => a + Object.keys(c).length, 0);
    await audit("Downloaded backup", "", `${total} records`);
    toast(`Backup downloaded (${total} records)`);
  } catch (err) { toast(errMsg(err), true); }
  finally { btn.disabled = false; btn.textContent = "⬇ Download full backup (JSON)"; }
}
