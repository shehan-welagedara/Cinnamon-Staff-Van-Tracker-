// Demo data for the screenshot tour – written into the LOCAL Firebase emulator only
// (FIRESTORE_EMULATOR_HOST / FIREBASE_AUTH_EMULATOR_HOST must be set). Names are made up.
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getAuth } = require("firebase-admin/auth");

if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST) {
  console.error("Refusing to run: emulator hosts are not set."); process.exit(1);
}
initializeApp({ projectId: "demo-vantracker" });
const db = getFirestore();
const auth = getAuth();

const MIN = 60e3, H = 60 * MIN, D = 24 * H;
const now = Date.now();
// Local (Asia/Colombo, UTC+5:30) wall-clock helpers.
const TZ = 5.5 * H;
const dayStart = (t) => Math.floor((t + TZ) / D) * D - TZ;
const at = (dayOffset, hh, mm = 0) => dayStart(now) + dayOffset * D + hh * H + mm * MIN;
const isoDow = (t) => { const d = new Date(t + TZ).getUTCDay(); return d === 0 ? 7 : d; };
// "Next Friday" = the Friday of next week (the trip the calendar slide shows).
const nextFridayOffset = (() => { const dow = isoDow(now); return (5 - dow + 7) % 7 + 7; })();

const HOTEL = { name: "Kandy Myst by Cinnamon", lat: 7.2965, lng: 80.6369 };
const P = {
  bank: { name: "People's Bank, Kandy", lat: 7.2936, lng: 80.6343 },
  market: { name: "Kandy Municipal Market", lat: 7.2920, lng: 80.6310 },
  laundry: { name: "Peradeniya Laundry Services", lat: 7.2690, lng: 80.5940 },
  accom: { name: "Staff Accommodation, Katugastota", lat: 7.3290, lng: 80.6250 },
  colombo: { name: "Cinnamon Grand, Colombo", lat: 6.9175, lng: 79.8487 },
  airport: { name: "Bandaranaike Airport (CMB)", lat: 7.1808, lng: 79.8841 },
  tea: { name: "Ceylon Tea Museum", lat: 7.2585, lng: 80.6440 },
};
const stop = (p, extra = {}) => ({ name: p.name, lat: p.lat, lng: p.lng, requestId: "", department: "", arrivedAt: null, ...extra });

const USERS = [
  { uid: "gm", email: "gm@kandymyst.demo", name: "Ruwan Jayasinghe", role: "GM", department: "Administration", isAdmin: true, phone: "0771000001" },
  { uid: "om", email: "om@kandymyst.demo", name: "Dilini Perera", role: "OM", department: "Administration", phone: "0771000002" },
  { uid: "hod", email: "hod@kandymyst.demo", name: "Kamala Fernando", role: "HOD", department: "Housekeeping", phone: "0771000003" },
  { uid: "fb", email: "fb@kandymyst.demo", name: "Ashan Silva", role: "HOD", department: "F&B Service", phone: "0771000004" },
  { uid: "fo", email: "fo@kandymyst.demo", name: "Nirosha Wickramasinghe", role: "HOD", department: "Front Office", phone: "0771000005" },
  { uid: "sm", email: "sales@kandymyst.demo", name: "Tharindu Rajapaksha", role: "HOD", department: "Sales & Marketing", phone: "0771000006" },
  { uid: "d1", email: "driver@kandymyst.demo", name: "Nimal Perera", role: "DRIVER", department: "Transport", phone: "0771000007", onDuty: true },
  { uid: "d2", email: "driver2@kandymyst.demo", name: "Sunil Bandara", role: "DRIVER", department: "Transport", phone: "0771000008", onDuty: false },
];

async function main() {
  for (const u of USERS) {
    await auth.createUser({ uid: u.uid, email: u.email, password: "demo1234", displayName: u.name }).catch(() => {});
    await db.doc(`users/${u.uid}`).set({
      uid: u.uid, name: u.name, email: u.email, phone: u.phone, department: u.department,
      role: u.role, requestedRole: u.role, isAdmin: !!u.isAdmin, active: true, onDuty: !!u.onDuty,
    });
  }

  await db.doc("config/settings").set({
    vehicleName: "Staff Van", plateNo: "PH-4521", currency: "LKR", fuelPricePerLiter: 340, petrolPricePerLiter: 371,
    kmPerLiter: 9, distanceCorrectionPct: 3, hotelName: HOTEL.name, hotelLat: HOTEL.lat, hotelLng: HOTEL.lng,
    geofenceRadiusM: 150, movementSpeedKmh: 12, minGpsAccuracyM: 50, usePowerTrigger: true,
    reasonDeadlineHour: 20, autoEndUnauthorizedMin: 10, autoApproveScheduled: true, minVersionCode: 0,
  });
  await db.doc("config/announcement").set({
    message: "Welcome to Van Tracker! One-week pilot with all HODs – please send every vehicle request through the app.",
    level: "info", active: true, until: 0, at: now - H, byName: "Ruwan Jayasinghe",
  });

  await db.doc("vehicles/v1").set({ name: "Staff Van", plateNo: "PH-4521", type: "Van", seats: 14, fuelType: "Diesel", kmPerLiter: 9, active: true, notes: "" });
  await db.doc("vehicles/v2").set({ name: "Hotel Car", plateNo: "CAB-7788", type: "Car", seats: 4, fuelType: "Petrol", kmPerLiter: 12, active: true, notes: "" });

  await db.doc("schedules/night").set({
    title: "Night staff transport", category: "Staff Transport", purpose: "Staff going home after the late shift",
    department: "", stops: [], days: [1, 2, 3, 4, 5, 6, 7], time: "22:30", active: true, destination: stop(P.accom),
  });
  await db.doc("schedules/morning").set({
    title: "Morning staff pickup", category: "Staff Transport", purpose: "Early shift pickup", department: "",
    stops: [stop(P.accom)], days: [1, 2, 3, 4, 5, 6], time: "06:00", active: true, destination: stop(HOTEL),
  });

  // ---- requests
  const req = (id, r) => db.doc(`requests/${id}`).set({
    kind: "PASSENGER", deliveryItems: "", deliveryContact: "", awayUntil: null, notes: "", joinTarget: "", joinTargetLabel: "",
    approverName: "", decidedAt: null, decisionComment: "", tripId: "", driverName: "", acceptedAt: null, passengerNames: "",
    passengers: 1, ...r,
  });
  await req("p1", {
    requesterUid: "fb", requesterName: "Ashan Silva", department: "F&B Service", category: "Purchasing",
    purpose: "Collect fresh produce for the dinner buffet", stops: [stop(P.market)], requestedTime: now + 90 * MIN,
    status: "PENDING", createdAt: now - 12 * MIN, passengers: 1, passengerNames: "Ashan Silva",
  });
  await req("p2", {
    requesterUid: "fo", requesterName: "Nirosha Wickramasinghe", department: "Front Office", category: "Guest Related",
    purpose: "Guest airport drop – late check-out", stops: [stop(P.airport)], requestedTime: at(1, 15, 0),
    status: "PENDING", createdAt: now - 40 * MIN, passengers: 2, passengerNames: "Mr & Mrs Andersson",
  });
  await req("k1", {
    requesterUid: "hod", requesterName: "Kamala Fernando", department: "Housekeeping", category: "Laundry",
    purpose: "Deliver linen to the laundry", stops: [stop(P.laundry)], requestedTime: now + 2 * H,
    status: "APPROVED", approverName: "Ruwan Jayasinghe", approverUid: "gm", decidedAt: now - 20 * MIN,
    decisionComment: "OK – combine with any other Peradeniya run", tripId: "t-laundry", createdAt: now - 35 * MIN,
    passengerNames: "Kamala Fernando",
  });
  await req("k2", {
    requesterUid: "hod", requesterName: "Kamala Fernando", department: "Housekeeping", category: "Staff Transport",
    purpose: "Night staff transport", stops: [stop(P.accom)], requestedTime: at(0, 22, 30), status: "APPROVED",
    joinTarget: "sched:night:x", joinTargetLabel: "Night staff transport (22:30)", approverName: "Auto (scheduled run)",
    createdAt: now - 3 * H, passengers: 3, passengerNames: "Chathura, Ishara, Malini",
  });
  await req("k3", {
    requesterUid: "hod", requesterName: "Kamala Fernando", department: "Housekeeping", category: "Purchasing",
    purpose: "Cleaning supplies from the wholesaler", stops: [stop(P.market)], requestedTime: now - 3 * D,
    status: "COMPLETED", approverName: "Dilini Perera", tripId: "c3", driverName: "Nimal Perera", acceptedAt: now - 3 * D,
    createdAt: now - 3 * D - 2 * H,
  });
  await req("s1", {
    requesterUid: "sm", requesterName: "Tharindu Rajapaksha", department: "Sales & Marketing", category: "Official Meeting",
    purpose: "Cinnamon sales meeting in Colombo", stops: [stop(P.colombo)], requestedTime: at(nextFridayOffset, 6, 30),
    awayUntil: at(nextFridayOffset, 19, 0), status: "APPROVED", approverName: "Ruwan Jayasinghe", tripId: "t-colombo",
    driverName: "Sunil Bandara", acceptedAt: now - D, createdAt: now - 2 * D, passengers: 3,
    passengerNames: "Tharindu, Sanduni, Kasun",
  });

  // ---- trips
  const trip = (id, t) => db.doc(`trips/${id}`).set({
    type: "REQUEST", category: "", purpose: "", departments: [], requestIds: [], stops: [], passengers: 1,
    driverUid: "", driverName: "", startTime: null, endTime: null, distanceKm: 0, gpsDistanceKm: 0, durationMin: 0,
    movingMin: 0, stoppedMin: 0, maxSpeedKmh: 0, fuelLiters: 0, fuelCost: 0, fuelPrice: 340, kmPerLiter: 9,
    needsReason: false, reason: "", reviewStatus: "", createdBy: "Ruwan Jayasinghe", vehicleId: "", vehicleName: "",
    destination: null, awayUntil: null, ...t,
  });
  await trip("t-bank", {
    status: "IN_PROGRESS", title: "Bank – Finance", category: "Bank", purpose: "Daily cash deposit", departments: ["Finance"],
    stops: [stop(P.bank)], plannedTime: now - 30 * MIN, createdAt: now - H, openedAt: now - H, assignedAt: now - 50 * MIN,
    startTime: now - 22 * MIN, driverUid: "d1", driverName: "Nimal Perera", vehicleId: "v1", vehicleName: "Staff Van · PH-4521",
  });
  await trip("t-laundry", {
    status: "READY", title: "Laundry – Housekeeping", category: "Laundry", purpose: "Deliver linen to the laundry",
    departments: ["Housekeeping"], requestIds: ["k1"], stops: [stop(P.laundry, { requestId: "k1", department: "Housekeeping" })],
    plannedTime: now + 2 * H, createdAt: now - 20 * MIN, openedAt: now - 20 * MIN,
  });
  await trip("t-colombo", {
    status: "ASSIGNED", title: "Official Meeting – Sales & Marketing", category: "Official Meeting",
    purpose: "Cinnamon sales meeting in Colombo", departments: ["Sales & Marketing"], requestIds: ["s1"],
    stops: [stop(P.colombo, { requestId: "s1", department: "Sales & Marketing" })], passengers: 3,
    plannedTime: at(nextFridayOffset, 6, 30), awayUntil: at(nextFridayOffset, 19, 0), createdAt: now - D, openedAt: now - D,
    assignedAt: now - D, driverUid: "d2", driverName: "Sunil Bandara", vehicleId: "v1", vehicleName: "Staff Van · PH-4521",
  });
  await trip("t-tea", {
    status: "READY", title: "Guest Related – Front Office", category: "Guest Related", purpose: "Guest tour: tea museum",
    departments: ["Front Office"], stops: [stop(P.tea)], plannedTime: at(3, 9, 30), createdAt: now - 2 * H, openedAt: now - 2 * H,
    passengers: 4,
  });

  // Completed trips over the last three weeks (for reports and the calendar).
  const done = [
    ["Bank", "Finance", P.bank, 6.2, 41, "d1", "v1"], ["Laundry", "Housekeeping", P.laundry, 18.4, 66, "d1", "v1"],
    ["Purchasing", "Kitchen", P.market, 5.1, 38, "d2", "v2"], ["Guest Related", "Front Office", P.airport, 214.6, 365, "d2", "v1"],
    ["Purchasing", "Housekeeping", P.market, 5.8, 47, "d1", "v1"], ["Official Meeting", "Administration", P.colombo, 232.9, 540, "d2", "v1"],
    ["Bank", "Finance", P.bank, 6.0, 35, "d1", "v2"], ["Laundry", "Housekeeping", P.laundry, 17.9, 58, "d2", "v1"],
    ["Staff Transport", "Kitchen", P.accom, 9.4, 28, "d1", "v1"], ["Guest Related", "Front Office", P.tea, 11.3, 95, "d1", "v2"],
    ["Purchasing", "F&B Service", P.market, 5.5, 44, "d2", "v2"], ["Bank", "Finance", P.bank, 6.4, 39, "d1", "v1"],
  ];
  for (let i = 0; i < done.length; i++) {
    const [cat, dept, place, km, min, d, v] = done[i];
    const start = at(-(i * 1.6 + 1) | 0, 9 + (i % 6), (i * 13) % 60);
    const veh = v === "v1" ? "Staff Van · PH-4521" : "Hotel Car · CAB-7788";
    const kmpl = v === "v1" ? 9 : 12, price = v === "v1" ? 340 : 371;
    const liters = km / kmpl;
    await trip(`c${i}`, {
      status: "COMPLETED", title: `${cat} – ${dept}`, category: cat, purpose: cat, departments: [dept], stops: [stop(place)],
      plannedTime: start, createdAt: start - H, openedAt: start - H, assignedAt: start - 40 * MIN, startTime: start,
      endTime: start + min * MIN, distanceKm: km, gpsDistanceKm: km / 1.03, durationMin: min, movingMin: min * 0.7,
      stoppedMin: min * 0.3, maxSpeedKmh: km > 100 ? 92 : 48, fuelLiters: liters, fuelCost: liters * price, fuelPrice: price,
      kmPerLiter: kmpl, passengers: 1 + (i % 3), driverUid: d, driverName: d === "d1" ? "Nimal Perera" : "Sunil Bandara",
      vehicleId: v, vehicleName: veh,
    });
  }

  // GPS route of the bank run (hotel → bank) for the trip detail map.
  const route = [[7.2965, 80.6369], [7.2961, 80.6362], [7.2956, 80.6357], [7.2951, 80.6352], [7.2946, 80.6349], [7.2941, 80.6346], [7.2938, 80.6344]];
  await db.doc("trips/t-bank/track/0").set({ seq: 0, p: route.map(([a, o], i) => ({ a, o, t: now - 22 * MIN + i * 2 * MIN, s: 18 })) });

  await db.doc("live/v1").set({
    vehicleId: "v1", vehicleName: "Staff Van · PH-4521", lat: 7.2938, lng: 80.6344, bearing: 215, speedKmh: 14, accuracy: 6,
    mode: "TRIP", tripId: "t-bank", tripTitle: "Bank – Finance", driverUid: "d1", driverName: "Nimal Perera", updatedAt: now - 20e3,
  });
  await db.doc("live/v2").set({
    vehicleId: "v2", vehicleName: "Hotel Car · CAB-7788", lat: HOTEL.lat, lng: HOTEL.lng, bearing: 90, speedKmh: 0, accuracy: 8,
    mode: "PARKED", tripId: "", tripTitle: "", driverUid: "d2", driverName: "Sunil Bandara", updatedAt: now - 2 * H,
  });

  await db.doc("alerts/a1").set({
    type: "MISSING_REASON", severity: "HIGH", title: "Van moved without an approved trip",
    message: "Hotel Car moved 3.2 km yesterday at 18:40 without an approved trip. No reason given yet.",
    tripId: "c6", createdAt: now - 5 * H, acknowledged: false,
  });
  await db.doc("dutyLogs/l1").set({ driverUid: "d1", driverName: "Nimal Perera", onDuty: true, at: at(0, 7, 2) });
  await db.doc("dutyLogs/l2").set({ driverUid: "d2", driverName: "Sunil Bandara", onDuty: false, at: at(-1, 18, 5) });

  console.log(`Seeded demo data (next Friday offset ${nextFridayOffset} days)`);
  process.exit(0);
}
main().catch((e) => { console.error(e); process.exit(1); });
