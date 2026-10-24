# Cinnamon Staff Van Tracker

An Android app for the hotel's staff and common-purpose van. It works a bit like PickMe or Uber, but for internal use: HODs request the van, the GM or OM approves, the driver records the trip on GPS, and management sees the route, time on the road, and fuel cost of every trip.

**Cost:** free. The app is open source. It uses OpenStreetMap for maps (no API key, no billing) and Firebase on the free *Spark* plan for login and data sync.

## Download the APK

Every push to `main` builds a new APK and publishes it under **Releases** (`VanTracker-v1.0.N.apk`). Open the Releases page on the phone, download the APK, and allow "install unknown apps" when Android asks.

## Features

| Mode | Who | What they can do |
|---|---|---|
| **Driver** | Dedicated van phone | Approved and scheduled trips list. Start a trip once passengers are on board and end it back at the hotel. Live map, distance, timer, stop check-off. Fuel fill-up log. Reasons for unapproved movement. |
| **Staff – HOD** | Heads of department | Request the van (purpose, stops on a map, time, passengers). Add a stop to a trip or daily run that is already going. Track the status of each request. Live van location. Department trip history. |
| **Staff – GM / OM** | Approvers | Approve or reject requests, with a comment. Combine a request with an existing trip (the app suggests trips leaving around the same time). Live map. Alerts. Monthly reports with CSV export. Accept or flag reasons for unapproved movement. |
| **Admin** (flag on any user) | | Fuel price, km/L, GPS correction %, hotel location and geofence, Bluetooth name, movement thresholds, reason deadline. Users and roles. Recurring pre-approved schedules. Fuel log (actual vs configured km/L). Share a setup code with other phones. |

**How trips are tracked**

- **No trip, no driving.** The van phone runs an always-on *van guard*. If the van moves without a started trip, the app records the route as an **unapproved movement**, alerts management right away, and asks the driver for a reason. Any movement still without a reason at the deadline hour (default 22:00) raises a **high-severity alert** for management.
- **Charger and Bluetooth trigger.** When the phone's charger gets power (ignition on) or the phone connects to the van's Bluetooth, GPS switches to high precision and the driver is reminded to start a trip.
- **Fuel cost** is calculated from GPS distance only:
  - km = GPS km × (1 + correction %)
  - litres = km ÷ km per litre
  - cost = litres × price
  
  Each trip stores the rates in force when it ended. An admin can recalculate any trip.
- **Time on the road**: total, moving and stopped time, max speed, and the arrival time at each stop.
- **Combined trips**: several requests can share one trip, and the cost is split between the departments on board.
- **Recurring schedules**: pre-approved daily runs, such as staff transport or laundry pickup. The driver starts them without waiting for approval.
- **Offline**: GPS recording never needs a signal. Every screen reads from the phone's local copy of the data, and changes sync automatically once the phone is back online. Map tiles you have already viewed are cached too.

## One-time setup (about 15 minutes)

1. Go to <https://console.firebase.google.com> → **Add project** (Google Analytics is not needed). The project stays on the free Spark plan.
2. **Build → Authentication → Get started → Email/Password → Enable.**
3. **Build → Firestore Database → Create database.** Choose the region `asia-south1` (Mumbai) and start in production mode.
4. **Firestore → Rules:** replace the contents with [`firestore.rules`](firestore.rules) from this repo → **Publish**.
5. **Project settings → Your apps → Android:** use the package name `com.cinnamon.vantracker` → **Register** → download **google-services.json**.
6. Install the APK on the administrator's phone. Open it, paste the whole content of `google-services.json`, and tap **Connect**.
7. **Create an account.** Tick *"make me the system administrator"*. This only works for the very first account.
8. **More → Van, fuel & tracking settings:**
   - Set the hotel location and the diesel price and km/L.
   - Set the van's Bluetooth name (optional).
9. **More → Share setup code.** Send the code to the van phone and to each HOD and manager phone. They paste it on first launch, then create their accounts.
10. **More → Users & roles:** approve each person and set their role (Driver, HOD, GM, OM).
11. On the van phone, sign in as the driver and complete **Van phone setup**:
    - Location set to *Allow all the time*
    - Notifications allowed
    - Nearby devices allowed
    - Battery optimisation ignored

    Keep the phone on the van charger.

### Optional GitHub secrets

| Secret | Purpose |
|---|---|
| `FIREBASE_API_KEY`, `FIREBASE_APP_ID`, `FIREBASE_PROJECT_ID` | Bake the Firebase config into the APK, so phones skip the setup code |
| `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` | Sign with a private key. The repo ships a development key (`app/dev-signing.jks`) so each build can update the last one. Switching keys means uninstalling the app once. |

## Tech stack

- Kotlin and Jetpack Compose (Material 3), min Android 8.0
- Firebase Auth and Cloud Firestore, with an offline persistent cache
- Fused location in a foreground service; WorkManager for notifications checked every 15 minutes (no paid push server)
- osmdroid (OpenStreetMap)

## Build locally

```bash
./gradlew assembleRelease   # needs JDK 17 + Android SDK 35
```
