# Releasing Footprint on Google Play

Roughly three weeks from first upload to public launch, because of the 14-day closed test. Most of that is waiting, not work.

## 0. Before anything

1. **Pick the name** (see `store-listing.md`). Search Play first.
2. **Decide the package name.** It's currently `dev.farazkhan.footprint`. It can never be changed after the first upload; the app's display name can.
3. **Host the privacy policy.** Put `privacy-policy.html` on your portfolio site (e.g. `https://<your-site>/footprint/privacy`) and keep that URL; Play requires a live link.

## 1. Developer account (one-off, ~2 days)

- Sign up at play.google.com/console, pay the **$25** one-time fee, and complete identity verification (government ID). Do this early: verification is the most common delay.
- Personal accounts created after 13 November 2023 must pass a closed test before they can publish publicly (step 4).

## 2. Build a signed release

### Two editions

The project builds two editions of the same app (same package name, same data):

| Edition | What it has | Use it for |
|---|---|---|
| **full** | Everything, including Reels and Shorts blocking (Accessibility API) | Your own phone. The default when you press ▶ Run. |
| **store** | Everything except Reels and Shorts blocking | The first Play upload: no Accessibility declaration, simpler review. |

Once the store edition is live, you can switch Play to the full edition in a later update (bump `versionCode`, upload the `fullRelease` bundle, and fill in the Accessibility declaration in section 3b). Users keep their data either way.

In Android Studio:

1. **Build → Generate Signed App Bundle / APK → Android App Bundle**.
2. **Create new keystore**. Store the `.jks` file and its passwords somewhere you will not lose them (a password manager plus one offline copy). Losing the key is recoverable through Play App Signing, but the process is slow.
3. Pick the **storeRelease** variant (or **fullRelease** once you add Reels blocking). The output is `app/store/release/app-store-release.aab`.
4. Keep **Play App Signing** enabled when Play offers it (the default).

Before each release, bump `versionCode` (and usually `versionName`) in `android/app/build.gradle.kts`. Play rejects a repeat `versionCode`.

## 3. Fill in App content

Play Console → **App content**. All of these block release:

- Privacy policy URL (step 0).
- **Data safety**: use `data-safety.md`.
- **Health apps declaration**: steps and sleep; see `data-safety.md`.
- Ads: **no ads**.
- Content rating questionnaire: a utility with no objectionable content.
- Target audience: 13+ (not a children's app).
- Government apps, financial features, data deletion: no / not applicable / in-app deletion.

## 3b. Only for the full edition: Accessibility API declaration

Play Console asks for this as soon as a bundle contains an Accessibility service. Answers:

- **Is it an accessibility tool?** No. (Footprint sets `isAccessibilityTool="false"`. Claiming "yes" for a screen-time app gets rejected.)
- **What does it do?** "At the user's request, it detects when YouTube Shorts, Instagram Reels or Snapchat Spotlight is on screen and presses Back, so short-video feeds the user chose to avoid close automatically. It only receives events from those three apps (packageNames in the service config), reads view ids and the selected tab label, and never reads messages, captions or typed text."
- **Data collected or shared through it?** None. A count of closed feeds is stored on the device only; the app has no internet permission.
- **Prominent disclosure:** Settings → App rules → Reels and Shorts → **Turn on** shows the disclosure dialog with **I agree** / **No thanks** before sending the user to Android's Accessibility settings.
- **Video** (required; record the phone screen, ~60 seconds, add captions):
  1. Open Footprint → Settings → scroll to "Reels and Shorts".
  2. Tap **Turn on**, scroll through the whole disclosure, tap **No thanks** (show that nothing changes).
  3. Tap **Turn on** again, tap **I agree**, turn on "Footprint: Reels and Shorts" in Android's Accessibility settings.
  4. Open YouTube, tap Shorts: the app goes back and the "YouTube Shorts is off" message appears. Same for Instagram Reels.
  5. Show a normal YouTube video and the Instagram feed still work.
  Upload it unlisted to YouTube and paste the link.
- Expect this review to take longer than a normal update. Android 17's Advanced Protection Mode also turns off non-accessibility-tool services; users with that mode on can't use this feature, and the rest of the app still works.

## 4. Closed test: 12 testers, 14 days

- **Testing → Closed testing → Create track**, upload the `.aab`, write release notes.
- Add testers: easiest is a Google Group. Have each person join with the Gmail account on their phone, then add the group's address to the track.
- Share the **opt-in link**. Each tester opens it on their Android phone, taps *Become a tester*, then installs from Play.
- **Recruit 15–16 people** even though 12 is the minimum. If someone opts out, their continuous 14 days restart.
- Your classmates are the natural fit here: the app is aimed at students, and exam mode is worth their feedback.
- While it runs, collect real feedback and fix things. Google asks what you learned when you apply.
- After 14 continuous days with 12+ opted-in testers: **Dashboard → Apply for production access**.

## 5. Production

- Upload the same or a newer build to **Production**, complete the store listing (`store-listing.md`) and graphics, then submit.
- First review usually takes a few days; new developers sometimes wait longer.

## Release checklist

- [ ] `versionCode` bumped
- [ ] Release build installs and runs on a clean device (uninstall first, so you test onboarding)
- [ ] Disclosure screen appears before any permission request
- [ ] App works with no permissions granted (empty states, no crashes)
- [ ] Widget added and updating
- [ ] Export JSON and CSV both open and save
- [ ] "Delete everything" empties the dashboard
- [ ] Receipt shares to WhatsApp
- [ ] Answer 8+ "worth it?" questions, confirm the regret summary and a risk window appear
- [ ] A nudge actually fires: set a risk window a few minutes ahead, wait for the 15-minute pass
- [ ] "Notification settings for <app>" opens that app's settings
- [ ] Settings: change the steps goal and screen target, confirm the dashboard updates
- [ ] Settings: hide an app, confirm it disappears from the dashboard and stops being recorded
- [ ] Settings: turn nudges off, confirm no notification arrives the next day
- [ ] Settings: shorten history to 30 days, confirm older days drop off the charts
- [ ] Rotate the phone and open on a tablet or large screen (API 36 no longer lets apps lock orientation on big screens)
- [ ] Dark and light system themes both readable

## Known policy points for this app

- **Store edition: no Accessibility service.** The full edition adds one; see 3b.
- **Overlay and foreground service** (`SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE_SPECIAL_USE`) are for app rules and the study timer. The special-use reason is in the manifest; paste it into the foreground service declaration.
- **No restricted permissions.** No `QUERY_ALL_PACKAGES` (the `<queries>` block covers launcher apps), no battery-optimisation exemption request, no notification listener.
- **Usage access** is a special access permission granted in Settings, not a popup. The app must be usable, with clear empty states, before it is granted; it is.
- **Target API 36** is required for new apps from 31 August 2026, and the project is set to it.
- If Play asks why you need usage access, the answer is simply that reporting screen time is the app's entire purpose.
