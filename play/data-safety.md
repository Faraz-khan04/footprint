# Data safety form answers (Play Console → App content → Data safety)

Answer honestly; Google cross-checks these against the APK's permissions and behaviour.

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **No** | Google defines "collect" as transmitting data off the device. Footprint has no INTERNET permission, so it cannot. Data that only stays on the device is not "collected". |
| Is all of the user data encrypted in transit? | n/a | Nothing is transmitted. |
| Do you provide a way for users to request that their data is deleted? | **Yes** | "Delete everything" in the app, and uninstalling. |

If Play's form asks about on-device processing, say so plainly in the optional notes:
"All processing happens on the device. The app has no internet permission and transmits nothing."

# Health apps declaration (App content → Health apps)
Footprint shows step counts and estimated sleep, so declare it as a health app:
- Health data used: fitness/wellness (steps, sleep estimate).
- Source: the phone's own step-counter sensor and usage statistics; no health APIs, no Health Connect.
- Not for medical use; state this plainly. The app makes no diagnosis or treatment claims.

# Permissions declaration
- `PACKAGE_USAGE_STATS`: core function. If asked, explain that screen-time reporting is the entire purpose of the app.
- `ACTIVITY_RECOGNITION`: reading the hardware step counter, optional.
- `POST_NOTIFICATIONS`: the nudge before a risky moment, the review reminder, the weekly review and the end of a study session, optional.
- `SYSTEM_ALERT_WINDOW`: shows the rule screens (limit, opens, break, blocked time, study session, "why?") over another app. Optional; only for app rules.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`: keeps app rules and the study timer working while they're in use. Paste the reason from the manifest's `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` into the foreground service declaration.
- `RECEIVE_BOOT_COMPLETED`: restarts app rules after the phone restarts.
- Full edition only: the Accessibility service for Reels and Shorts. See `RELEASE.md` section 3b. Data safety answers don't change: nothing it sees leaves the phone.
- No restricted permissions are used: no QUERY_ALL_PACKAGES, no battery-optimisation exemption request, no notification listener.
