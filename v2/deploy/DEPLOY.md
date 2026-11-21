# Deploying Footprint: IdeaPad Slim 5 + Nothing Phone (2a)

The laptop runs the API, the database and its own tracker. The phone runs the Footprint app, which collects your phone data, sends it to the laptop, and shows your dashboard. They talk directly over **your phone's hotspot**. No VPN, no cloud, and no internet needed: it even works with mobile data off, as long as the hotspot is on and the laptop is connected to it.

```
Nothing Phone (2a)  ── hotspot Wi-Fi (WPA2/WPA3) ──►  IdeaPad Slim 5
Footprint app                                         API :8000 (firewall: local network only) ──► footprint.db
 • finds the laptop on the hotspot                     ▲
 • checks the laptop's identity                        │
 • sends events, loads the dashboard                  laptop agent
```

---

## Already set up with Tailscale? Move off it (5 minutes)

1. Copy the new version onto the laptop (see **Updating** at the end).
2. Run the setup script again in **Terminal (Admin)**:
   ```powershell
   cd C:\footprint
   powershell -ExecutionPolicy Bypass -File .\deploy\windows\setup.ps1
   ```
   It keeps your tokens, database and devices, switches off `tailscale serve`, opens port 8000 for the local network only, and restarts everything.
3. Rebuild the phone app in Android Studio (**Sync Project with Gradle Files**, then **Run ▶**). The app forgets the old Tailscale address by itself; your device ID and secret stay saved.
4. On the phone: **Settings → Find my laptop**. It should say *Laptop found at 10.x.x.x:8000*.
5. Uninstall Tailscale from the laptop (**Settings → Apps**) and from the phone. If you had turned **Private DNS** off for Tailscale, you can turn it back to **Automatic**.

---

## Fresh setup

### Part 1: Laptop

**1.1 Install Python.** In **Terminal (Admin)** (press `Win + X`, then *Terminal (Admin)*):
```powershell
winget install Python.Python.3.12
```
Close the terminal afterwards.

**1.2 Put the project in `C:\footprint`.** Avoid Desktop and Documents if OneDrive syncs them; it would lock the database and upload your activity log.

**1.3 Connect the laptop to your phone's hotspot**, as you normally do.

**1.4 Run the setup script** in a new **Terminal (Admin)**:
```powershell
cd C:\footprint
powershell -ExecutionPolicy Bypass -File .\deploy\windows\setup.ps1
```
It installs everything, creates your tokens, opens port 8000 **only to devices on the same local network**, starts two hidden background tasks (*Footprint Server*, *Footprint Agent*) that launch at every login, and registers the laptop and the phone.

It ends with a box like this. **Keep the terminal open** until the phone is set up; the secret is shown only once.
```
==================== ENTER THIS IN THE PHONE APP ====================
  Laptop address : leave empty, the app finds the laptop on your hotspot
  Device ID      : phone
  Device secret  : 4mQ...long...string
=====================================================================
```

**1.5 Check it:**
```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\windows\status.ps1
```
Expect both tasks *Running*, `API: up`, and `Firewall: open to local network only`.

If Windows ever pops up *"Allow Python to access networks?"*, click **Allow**. The script already limits Python to port 8000 on your local network, so this doesn't open anything else.

### Part 2: Phone

**2.1 Build and install the app** (from the laptop):
1. `winget install Google.AndroidStudio`, open it, finish the first-run wizard (*Standard*).
2. **File → Open → `C:\footprint\android`**, click **Trust Project**, and wait for Gradle sync to finish.
3. On the phone, **Settings → About phone**, find **Build number** (on Nothing OS possibly under *Software info*) and tap it 7 times. Then **Settings → System → Developer options → USB debugging: on**.
4. Connect the phone by USB-C, tap **Allow** on the debugging prompt, pick the phone in Android Studio's device dropdown, and press **Run ▶**.

**2.2 Connect the app.** Footprint opens on Settings the first time.
1. Enter the **Device ID** and **Device secret** from the laptop's box. Leave **Laptop address** empty.
   The secret is 43 characters. Type it carefully, or save it in a Notepad file on the laptop, send it to the phone over USB (the phone is already plugged in; choose *File transfer* in the USB notification), copy it from there, and delete the file on both devices. Don't send it over WhatsApp, email or cloud notes.
2. Tap **Save and start**, then **Find my laptop**. It should say *Laptop found at …*.

**2.3 Grant the three permissions** listed in Settings:

| Row | On the Nothing Phone (2a) |
|---|---|
| **Screen time** | Opens *Usage access*: tap **Footprint** and turn it on. If it's greyed out ("Restricted setting"): **Settings → Apps → Footprint → ⋮ → Allow restricted settings**, then try again. |
| **Steps** | Tap **Allow** on the *Physical activity* popup. |
| **Background sync** | Tap **Allow**, then also **Settings → Apps → Footprint → Battery → Unrestricted**. |

**2.4 First sync.** Tap **Sync now**, then **Back to dashboard**. Within a few seconds your day appears. The first sync backfills the last 24 hours of screen time; steps start from the next 15-minute sync (the first reading is only a baseline).

---

## How syncing behaves

- Every 15 minutes the app looks for the laptop. If the hotspot is off, or the laptop is asleep or not connected, nothing is lost: events stay queued on the phone and send the next time the laptop is reachable.
- The dashboard shows the last data it loaded and says how old it is when the laptop can't be reached.
- The laptop's hotspot IP can change between sessions. The app notices and rescans (about 1–2 seconds).

If you want near-real-time updates while the laptop is plugged in: **Settings → System → Power & battery → Screen, sleep & hibernate timeouts → When plugged in, put my device to sleep after: Never**.

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| *Laptop not found* | Check the laptop is connected to **this phone's** hotspot and awake. On the laptop run `status.ps1`: both tasks should be *Running* and the firewall rule present. Rerunning `setup.ps1` fixes a missing rule. |
| Found once, then not found | Some Windows updates reset the network to *Public* and third-party antivirus firewalls can block port 8000. Rerun `setup.ps1`; if you use another antivirus firewall, allow TCP 8000 from the local subnet in it. |
| *The server rejected this phone* | Wrong device ID or secret, or the phone's clock is more than 5 minutes off (turn on automatic date and time). Lost the secret? Run `setup.ps1 -NewPhoneSecret` on the laptop and enter the new ID and secret. |
| Want to force an address | Put `http://<laptop-ip>:8000` in **Laptop address** in Settings. `status.ps1` shows the laptop's IP. Leave it empty to go back to automatic. |
| Steps say *no step sensor* | No hardware step counter was found; screen time and sleep still work. |
| `API: DOWN` in status | Read `logs\server.log`. Rerunning `setup.ps1` restarts both tasks. |

---

## Security, in one paragraph

Only devices that know your hotspot password can be on the network, and the hotspot encrypts traffic with WPA2/WPA3. The laptop's firewall accepts connections on port 8000 only from that local network. Every request from the phone is HMAC-signed with its own device secret (stored encrypted in the Android Keystore). Before the app sends anything, the laptop must prove it knows that secret by signing a random challenge (`/v1/hello`), so another device on the hotspot can't pose as your laptop. The web dashboard inside the app never sees the secret; the app makes the signed requests natively.

---

## Updating to a new version

Don't unzip a new version straight over `C:\footprint`; that would overwrite your tokens, data and settings. Instead:

1. Unzip the new zip to `C:\footprint-new`.
2. In PowerShell:
   ```powershell
   robocopy C:\footprint-new\footprint C:\footprint /E /XF .env footprint.db config.json queue.db local.properties gradle-wrapper.properties /XD .venv logs .gradle build .idea
   Stop-ScheduledTask "Footprint Server"; Start-ScheduledTask "Footprint Server"
   Stop-ScheduledTask "Footprint Agent";  Start-ScheduledTask "Footprint Agent"
   ```
   The excluded files are your secrets, database, agent queue, and the Gradle download fix.
3. If the Android app changed: in Android Studio, **File → Sync Project with Gradle Files**, then **Run ▶** with the phone connected. The app updates in place and keeps its settings and queued events.
4. Delete `C:\footprint-new`.

## Remove it

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\windows\uninstall.ps1
```
Stops the tasks and removes the firewall rules. Your data stays in `server\footprint.db`. On the phone, uninstall the Footprint app.
