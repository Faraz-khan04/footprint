# Footprint (Android)

Opening the app shows your **dashboard**: the same page the laptop serves, bundled inside the app. **Settings** (top right) holds the server details, permissions and sync status.

In the background it logs per-app screen time, unlocks, screen on/off and steps every 15 minutes, queues them on the phone, and sends them straight to your laptop over your phone's hotspot, with the same HMAC signing as the laptop agent.

## Build and install

1. Install **Android Studio** (Ladybug or newer).
2. **File → Open** and pick this `android/` folder. Let Gradle sync. Android Studio creates the Gradle wrapper files for you if prompted.
3. On your phone: Settings → About phone → tap *Build number* 7 times → Developer options → turn on **USB debugging**.
4. Plug the phone in and press **Run ▶**. The app installs as *Footprint*.

Command line instead: `gradle wrapper && ./gradlew installDebug`.

## Set it up on the phone

1. Register the phone on your server (same as the laptop, with `"kind":"phone"`):
   ```bash
   curl -X POST http://<server>:8000/v1/devices -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H "Content-Type: application/json" -d '{"id":"phone","name":"My phone","kind":"phone"}'
   ```
2. Open the app, enter the server address, device ID `phone`, and the secret. Tap **Save and start**.
3. Fix each permission row:
   - **Usage access** opens Settings. Find *Footprint* and turn it on.
   - **Physical activity** is a normal permission popup (step counter).
   - **Background sync** asks to skip battery optimisation. On Xiaomi / Redmi / POCO, Oppo, Realme and Vivo also set the app's battery to *No restrictions* and enable *Autostart*, or the OS will kill the 15-minute job.
4. Tap **Sync now** and check the status line says `Synced N events`.

## Finding the laptop

Leave **Laptop address** empty. The app looks for the laptop on your phone's hotspot (and on any local Wi-Fi the phone is connected to): it tries the last address that worked, and otherwise checks the other addresses on the hotspot's subnet for port 8000, which takes 1–2 seconds. It only accepts a machine that answers `GET /v1/hello` with your device secret's signature of a random challenge. **Settings → Find my laptop** runs this on demand.

To pin an address instead, enter `http://<laptop-ip>:8000`. The app then never scans.

## What gets sent

| Event | Source | Notes |
|---|---|---|
| `app` | `UsageStatsManager` | app label, category, package; the app's own icon (96px PNG) the first time each app is seen |
| `unlock` | keyguard hidden | Android 9+ |
| `screen_on` / `screen_off` | screen state | used for sleep estimation |
| `steps` | `TYPE_STEP_COUNTER` sensor | difference since the last run; handles reboots |

Launchers, System UI and the Footprint app itself are ignored. No notification content, messages, or screen contents are ever read.

## Security notes

- The dashboard runs in a locked-down WebView on a virtual origin (`https://appassets.androidplatform.net`). It can't read files, and links to other sites open in your browser instead.
- The page never gets the device secret. It asks the app for data over `FootprintBridge`, which only exists for that origin, and the app signs each read natively (`HMAC(secret, "<ts>.GET <path>")`). Only summary, icon and top-apps paths are allowed.

- The device secret is encrypted with an AES-256-GCM key held in the Android Keystore.
- App backups are disabled so the secret and queue don't end up in cloud backups.
- Events have deterministic ids, so re-sending after a timeout never double-counts.
- `QUERY_ALL_PACKAGES` is used to read app names and icons. That's fine for a sideloaded personal app; Google Play would ask you to justify it.
