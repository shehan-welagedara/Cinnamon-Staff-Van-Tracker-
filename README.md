# Cinnamon Staff Van Tracker

An Android app for the hotel's staff and common-purpose van. It works a bit like PickMe or Uber, but for internal use: HODs request the van, the GM or OM approves, the driver records the trip on GPS, and management sees the route, time on the road, and fuel cost of every trip.

**Cost:** free. The app is open source. It uses OpenStreetMap for maps (no API key, no billing) and Firebase on the free *Spark* plan for login and data sync.

## Download the APK

Every push to `main` builds a new APK and publishes it under **Releases** (`VanTracker-v1.0.N.apk`). Open the Releases page on the phone, download the APK, and allow "install unknown apps" when Android asks.

## Features

| Mode | Who | What they can do |
|---|---|---|
| **Driver** | Each driver, on their own phone | Go on or off duty. Get alerted about new trip requests and **accept** one (the first driver to accept gets it). **Start ride** once passengers are on board, then **Finish ride**. Release an accepted trip, start pre-approved scheduled runs, see who has the van. Live map, distance, timer, stop check-off. Fuel fill-up log. Reasons for unapproved movement. |
| **Staff – HOD** | Heads of department | Request the van (purpose, stops on a map, time, passengers). Add a stop to a trip or daily run that is already going. Track the status of each request. Live van location. Department trip history. |
| **Staff – GM / OM** | Approvers | Approve or reject requests, with a comment. Combine a request with an existing trip (the app suggests trips leaving around the same time). Live map. Alerts. Monthly reports with CSV export. Accept or flag reasons for unapproved movement. |
| **Administrator** (role, or admin flag on any user) | | Fuel price, km/L, GPS correction %, hotel location and geofence, Bluetooth name, movement thresholds, reason deadline. Users and roles. Recurring pre-approved schedules. Fuel log (actual vs configured km/L). Share a setup code with other phones. |

**Trip flow (like PickMe / Uber)**

1. An HOD requests the van.
2. The GM or OM approves it. They can pick a driver, or leave it as **Any driver**.
3. Every driver is alerted. The first to tap **Accept** gets the trip, and the HOD is told who's driving.
4. With passengers on board, the driver taps **Start ride**. Back at the hotel, they tap **Finish ride**.

Accepting needs internet, so two drivers can't take the same trip. Everything else works offline. Managers see a **Drivers** card: on trip, on duty, or off duty, with tap-to-call. Reports break down distance, hours and trips **by driver**.

**How tracking works on drivers' own phones**

- **Off duty:** no location is recorded at all.
- **On duty, not on a ride:** the location is read only while the phone is connected to the **van's Bluetooth**, meaning the driver is in the van. It is never read at home or on the way to work.
- **During a ride:** the full route is recorded with GPS.
- **Only one driver at a time:** while one driver is on a ride, other drivers' phones never report the van's position or flag movement.

**No trip, no driving.** If a driver's phone is in the van (on its Bluetooth) and the van drives off without a started ride, the app records it as an **unapproved movement**. Management is alerted right away and the driver is asked for a reason. Any movement still without a reason at the deadline hour (default 22:00) raises a **high-severity alert**.

- **Van Bluetooth needed:** this needs the van's Bluetooth name set under Settings — the car stereo, or a cheap Bluetooth adapter on the van's 12V/USB socket. Each driver pairs their phone with it once.
- **Dedicated van phone (optional):** under *Driver phone setup* you can mark a spare phone as a **dedicated van phone**. It then watches for movement all the time, and its charger counts as the ignition signal.
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

## Firestore rules

When an update adds data, it may need new rules. Whenever `firestore.rules` changes in this repo, copy it again into **Firebase console → Firestore Database → Rules → Publish**. Version 1.0.6+ needs this for driver duty logs.

## Updating the app (keeps all data)

- The app checks GitHub Releases. When a newer version exists, a blue **Update available** banner appears on the home screen; **More → About → Check for updates** does the same on demand.
- Tap the banner, download the APK, and open it. Android installs it **over** the existing app, so the setup, sign-in, settings, and even a trip in progress are all kept.
- Every build is signed with the same key (`app/dev-signing.jks`). Don't uninstall the app before updating, and don't change the key, or phones will refuse the update.
- Trips, requests, users and settings live in Firestore, not on the phone, so updates never touch them.
- The Firebase connection and the Light/Dark choice are included in Android's own backup. On a reinstall with the same Google account, the phone reconnects without the setup code. Adding the `FIREBASE_*` secrets below removes the setup step entirely.

## Maps

OpenStreetMap is used by default. It is free and needs no key, but in some areas its place names are out of date. Anyone can correct them at <https://www.openstreetmap.org>, and fixes appear in the app within days.

For the most accurate Sri Lankan maps, switch to **Google Maps**, which adds a Map/Satellite toggle. At one hotel's usage this should stay within Google's monthly free allowance, but Google requires a billing account:

These links open the right page for the Firebase project `cinnamon-staff-van-tracker`. If the console shows **"Failed to load"**, open the link in an Incognito window signed in with only the Google account that owns the Firebase project.

1. **Billing:** <https://console.cloud.google.com/billing/linkedaccount?project=cinnamon-staff-van-tracker> → link or create a billing account. The "free trial with $300 credit" works.
2. **Enable the API:** <https://console.cloud.google.com/apis/library/maps-android-backend.googleapis.com?project=cinnamon-staff-van-tracker> → **Enable** *Maps SDK for Android*.
3. **Create a key:** <https://console.cloud.google.com/apis/credentials?project=cinnamon-staff-van-tracker> → **Create credentials → API key**. Create a new key rather than reusing the "auto created by Firebase" one.
4. Edit the key:
   - **Application restrictions:** **Android apps**, with package name `com.cinnamon.vantracker` and SHA-1 `53:89:04:29:EF:D5:46:A2:1E:27:5F:D3:61:8A:44:E1:A8:F2:CC:E3`
   - **API restrictions:** *Maps SDK for Android* only
5. GitHub repo → **Settings → Secrets and variables → Actions → New repository secret:** name `MAPS_API_KEY`, value = the key.
6. **Actions → Build APK → Run workflow.** Install the new release over the old one; the maps switch to Google automatically.

### Optional GitHub secrets

| Secret | Purpose |
|---|---|
| `FIREBASE_API_KEY`, `FIREBASE_APP_ID`, `FIREBASE_PROJECT_ID` | Bake the Firebase config into the APK, so phones skip the setup code |
| `MAPS_API_KEY` | Use Google Maps instead of OpenStreetMap (see *Maps*) |
| `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` | Sign with a private key. The repo ships a development key (`app/dev-signing.jks`) so each build can update the last one. Switching keys means uninstalling the app once. |

## Tech stack

- Kotlin and Jetpack Compose (Material 3), min Android 8.0
- Firebase Auth and Cloud Firestore, with an offline persistent cache
- Fused location in a foreground service; WorkManager for notifications checked every 15 minutes (no paid push server)
- osmdroid (OpenStreetMap), or Google Maps Compose when a key is set
- Design: Kandy Myst by Cinnamon brand kit v1.0 (Cinnamon Purple palette); font Figtree (SIL Open Font License), the kit's digital substitute for Proxima Nova

## Build locally

```bash
./gradlew assembleRelease   # needs JDK 17 + Android SDK 35
```
