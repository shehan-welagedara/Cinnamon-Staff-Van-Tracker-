// Van Tracker – instant push notifications (Firebase Cloud Functions, 2nd gen).
//
// Watches Firestore and sends a push message (Firebase Cloud Messaging) to the right
// phones the moment something happens:
//   new request               → GM / OM / administrators
//   request approved/rejected → the requester
//   driver confirmed          → the requester
//   trip open for drivers     → every active driver
//   trip assigned to a driver → that driver
//   trip started / cancelled  → requesters / the driver
//   new alert                 → GM / OM / administrators
//   new sign-up               → administrators
//   "Test instant push"       → the person who asked
//
// Phones store their push tokens in users/{uid}.fcmTokens. Messages carry a "key" that the
// app also uses for its 15-minute background check, so a notification never shows twice.
const { onDocumentCreated, onDocumentWritten } = require("firebase-functions/v2/firestore");
const { setGlobalOptions } = require("firebase-functions/v2");
const logger = require("firebase-functions/logger");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();
setGlobalOptions({ maxInstances: 5, memory: "256MiB" });
const db = getFirestore();

const MANAGER_ROLES = ["GM", "OM", "ADMIN"];
const isManager = (u) => MANAGER_ROLES.includes(u.role) || u.isAdmin === true;
const fmtTime = (ms) => (ms ? new Date(ms).toLocaleString("en-GB", {
  timeZone: "Asia/Colombo", day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit",
}) : "");

async function activeUsers() {
  const snap = await db.collection("users").where("active", "==", true).get();
  return snap.docs.map((d) => ({ id: d.id, ...d.data() })).filter((u) => u.role !== "PENDING");
}

/**
 * Sends one notification to a set of users. [msg] = { key, title, body, open, channel }.
 * Tokens that are no longer valid (app removed, signed out) are cleaned up.
 */
async function notify(users, msg, exceptUid) {
  const targets = users.filter((u) => u.id !== exceptUid && Array.isArray(u.fcmTokens) && u.fcmTokens.length);
  const tokens = [];
  const owner = {};
  for (const u of targets) for (const t of u.fcmTokens) if (t && !owner[t]) { owner[t] = u.id; tokens.push(t); }
  if (!tokens.length) { logger.info("No phones registered for", msg.key); return; }
  const data = {
    key: String(msg.key), title: String(msg.title).slice(0, 200), body: String(msg.body || "").slice(0, 1000),
    open: String(msg.open || ""), channel: msg.channel || "requests", ...(msg.test ? { test: "1" } : {}),
  };
  for (let i = 0; i < tokens.length; i += 500) {
    const batch = tokens.slice(i, i + 500);
    // Data-only, high priority: wakes the phone and lets the app show its own notification.
    const res = await getMessaging().sendEachForMulticast({
      tokens: batch, data, android: { priority: "high", ttl: 6 * 3600 * 1000 },
    });
    const stale = [];
    res.responses.forEach((r, j) => {
      const code = r.error?.code || "";
      if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token" || code === "messaging/invalid-argument") stale.push(batch[j]);
      else if (r.error) logger.warn("Push failed", code, r.error.message);
    });
    await Promise.all(stale.map((t) => db.collection("users").doc(owner[t]).update({ fcmTokens: FieldValue.arrayRemove(t) }).catch(() => {})));
    logger.info(`Sent "${data.key}" to ${res.successCount}/${batch.length} phones`);
  }
}

// ------------------------------------------------------------------ requests

exports.onRequestWrite = onDocumentWritten("requests/{id}", async (event) => {
  const before = event.data?.before?.exists ? event.data.before.data() : null;
  const after = event.data?.after?.exists ? event.data.after.data() : null;
  if (!after) return;
  const id = event.params.id;
  const users = await activeUsers();
  const what = after.kind === "DELIVERY" ? "pickup / delivery" : "trip";

  if (!before && after.status === "PENDING") {
    await notify(users.filter(isManager), {
      key: `req-new:${id}`, channel: "requests", open: "approvals",
      title: after.kind === "DELIVERY" ? "New pickup / delivery request" : "New trip request – approval needed",
      body: `${after.requesterName} (${after.department}): ${after.category} – ${after.purpose}\n${fmtTime(after.requestedTime)}`,
    }, after.requesterUid);
    return;
  }
  if (!before) return;
  const requester = users.filter((u) => u.id === after.requesterUid);

  if (before.status === "PENDING" && (after.status === "APPROVED" || after.status === "REJECTED")) {
    if (after.approverUid && after.approverUid === after.requesterUid) return; // approved own request
    const ok = after.status === "APPROVED";
    const driver = ok && after.driverName ? `\nDriver: ${after.driverName}` : "";
    await notify(requester, {
      key: `req-dec:${id}`, channel: "requests", open: "requests",
      title: `Your ${what} request was ${ok ? "approved" : "rejected"}`,
      body: `${after.category}: ${after.purpose}${driver}${after.decisionComment ? `\n“${after.decisionComment}” – ${after.approverName}` : ""}`,
    });
    return;
  }
  if (after.driverName && after.acceptedAt && after.acceptedAt !== before.acceptedAt && before.status === after.status) {
    await notify(requester, {
      key: `req-drv:${id}`, channel: "requests", open: "requests",
      title: "Driver confirmed",
      body: `${after.driverName} will take your ${after.category} ${what} (${fmtTime(after.requestedTime)}).`,
    });
  }
});

// ------------------------------------------------------------------ trips

exports.onTripWrite = onDocumentWritten("trips/{id}", async (event) => {
  const before = event.data?.before?.exists ? event.data.before.data() : null;
  const after = event.data?.after?.exists ? event.data.after.data() : null;
  if (!after) return;
  const id = event.params.id;
  const when = fmtTime(after.plannedTime);
  const stops = (after.stops || []).map((s) => s.name).filter(Boolean).join(" → ");
  let users;
  const load = async () => (users ||= await activeUsers());

  // Open for drivers: first to accept takes it (new trip, or released by a driver).
  const openNow = after.status === "READY" && !after.driverUid;
  const wasOpen = before && before.status === "READY" && !before.driverUid && before.openedAt === after.openedAt;
  if (openNow && !wasOpen && after.type !== "UNAUTHORIZED") {
    await notify((await load()).filter((u) => u.role === "DRIVER"), {
      key: `trip-open:${id}:${after.openedAt || 0}`, channel: "alerts", open: "",
      title: "New trip – tap to accept",
      body: `${after.title || after.category}${when ? " · " + when : ""}${stops ? "\n" + stops : ""}`,
    });
  }

  // Assigned by a manager (a driver accepting it themselves needs no message).
  const assignedNow = after.status === "ASSIGNED" && after.driverUid;
  const selfAccepted = before && before.status === "READY";
  if (assignedNow && (!before || (!selfAccepted && before.driverUid !== after.driverUid))) {
    await notify((await load()).filter((u) => u.id === after.driverUid), {
      key: `trip-asg:${id}:${after.driverUid}`, channel: "alerts", open: "",
      title: "Trip assigned to you",
      body: `${after.title || after.category}${when ? " · " + when : ""}${stops ? "\n" + stops : ""}`,
    });
  }

  // Ride started: tell the people who booked it.
  if (before && before.status !== "IN_PROGRESS" && after.status === "IN_PROGRESS" && after.type !== "UNAUTHORIZED") {
    const reqIds = after.requestIds || [];
    if (reqIds.length) {
      const reqs = await Promise.all(reqIds.slice(0, 30).map((r) => db.collection("requests").doc(r).get()));
      const uids = new Set(reqs.filter((r) => r.exists).map((r) => r.data().requesterUid));
      await notify((await load()).filter((u) => uids.has(u.id)), {
        key: `trip-go:${id}`, channel: "requests", open: "requests",
        title: "Your vehicle is on the way",
        body: `${after.driverName || "The driver"} started ${after.title || "the trip"}${after.vehicleName ? " · " + after.vehicleName : ""}.`,
      }, after.driverUid);
    }
  }

  // Cancelled after a driver took it.
  if (before && before.status !== "CANCELLED" && after.status === "CANCELLED" && before.driverUid) {
    await notify((await load()).filter((u) => u.id === before.driverUid), {
      key: `trip-cxl:${id}`, channel: "alerts", open: "",
      title: "Trip cancelled", body: `${after.title || after.category}${when ? " · " + when : ""} was cancelled.`,
    });
  }
});

// ------------------------------------------------------------------ alerts, sign-ups, tests

exports.onAlertCreate = onDocumentCreated("alerts/{id}", async (event) => {
  const a = event.data?.data();
  if (!a || a.acknowledged) return;
  await notify((await activeUsers()).filter(isManager), {
    key: `alert:${event.params.id}`, channel: "alerts", open: "alerts",
    title: a.title || "Van alert", body: a.message || "",
  });
});

exports.onUserCreate = onDocumentCreated("users/{uid}", async (event) => {
  const u = event.data?.data();
  if (!u || u.role !== "PENDING") return;
  await notify((await activeUsers()).filter((x) => x.isAdmin === true), {
    key: `user-new:${event.params.uid}`, channel: "requests", open: "",
    title: "New user waiting for approval",
    body: `${u.name || u.email} (${u.department || "–"}) asked to join as ${u.requestedRole || "user"}.`,
  });
});

exports.onPushTest = onDocumentCreated("pushTests/{id}", async (event) => {
  const t = event.data?.data();
  if (t?.uid) {
    const u = await db.collection("users").doc(t.uid).get();
    if (u.exists) {
      await notify([{ id: u.id, ...u.data() }], {
        key: `test:${event.params.id}`, test: true, channel: "alerts",
        title: "Instant push works ✔", body: `Sent by the server at ${fmtTime(Date.now())}.`,
      });
    }
  }
  await event.data?.ref.delete().catch(() => {});
});
