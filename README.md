# QDate — Android WebView app

A Google Play-ready Android wrapper around the QDate dating web app
(`https://test.dreamsparkgroups.com/qdate/`). Everything runs in a single
`MainActivity` WebView; the app has **no separate features of its own** — it
loads the web app and adds native conveniences:

- **Splash screen** on launch (androidx core-splashscreen).
- **Google sign-in uses the system browser and returns automatically.**
  Google blocks sign-in in embedded WebViews, and a WebView has no Google
  cookies anyway — so "Continue with Google" now opens the device's default
  browser (via `app=1` on `/auth/google/start`), where the user picks the Google
  account already signed into that browser. When the browser finishes, the app's
  callback page hands the result back to the app with a short-lived signed
  `qdate://resume` deep link; `MainActivity` then loads `/auth/app-resume` so the
  WebView's own (separate) session cookie jar gets logged in. No OAuth happens
  inside the WebView. All other links open in the system browser.
- **File uploads** (profile photo, chat image/video) open the system chooser
  with gallery **and** camera options (capture goes through a FileProvider).
- The back button navigates WebView history, downloads go to the Downloads
  folder, HTTPS-only (network security config). (No pull-to-refresh: the web app
  scrolls inside per-page containers, so a SwipeRefresh wrapper would swallow
  upward scroll gestures — the page could scroll down but never back up.)
- **AdMob ads** — an adaptive banner sits above the web app's bottom nav
  (hidden on chat and login pages), and a full-screen interstitial shows after
  every **10** profile browses, at most once per 60 seconds.
- **FCM push notifications** for likes, matches and new messages — shown only
  when the app is backgrounded (the web app's polling handles the foreground).

## Requirements

- **Android Studio** (Ladybug or newer) with **JDK 17** configured
  (Settings → Build Tools → Gradle → Gradle JDK → 17).
- Android **SDK Platform 35** (Android Studio offers to install it on first sync).
- A physical Android device (Android 8.0+, API 26) or an emulator.

## Build the APK

1. Open **Android Studio** → **Open** → select the `Android_APK` folder.
2. Let Gradle sync finish (it downloads Gradle 8.9 the first time — internet
   needed).
3. **Build → Build APK(s)** — the debug-signed APK is written to
   `app/build/outputs/apk/debug/app-debug.apk`. Install it on a device and test.
4. For a **release** build: **Build → Generate Signed APK / Bundle** (see below).

> **If Gradle asks to use its bundled version (e.g. 9.x) instead of the wrapper:**
> accept the wrapper at 8.9. This project pins Gradle 8.9 because the Android
> Gradle Plugin 8.7.3 is tested against it. Using Gradle 9.x would break the
> sync.

## Change the app URL

Edit `app/build.gradle`:

```groovy
buildConfigField "String", "APP_URL", "\"https://your-domain.com/qdate/\""
```

Then **Sync** and rebuild. (The URL is compiled into `BuildConfig.APP_URL`.)

## Change the app name / icon

- Name: `app/src/main/res/values/strings.xml` → `app_name`.
- Icon: the launcher uses a vector heart on a purple background. To use your
  own image (`app_icon.jpg` / `app_launcher.png` already sit in this folder),
  use Android Studio's **Image Asset** wizard:
  `app/src/main/res/` → right-click `mipmap-anydpi-v26` → **New → Image Asset**,
  set the foreground to your image. That generates the required mipmaps.

## Release signing (for Google Play)

The `release` build type currently signs with the **debug keystore** so the
first APK installs on your phone. **You must use your own keystore before
publishing.** Generate one once:

```bash
keytool -genkey -v -keystore qdate-release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias qdate
```

Then in `app/build.gradle` add (and **do not commit** the `.jks` file or its
password — it is gitignored):

```groovy
android {
    signingConfigs {
        release {
            storeFile file("../qdate-release.jks")
            storePassword "your-pass"
            keyAlias "qdate"
            keyPassword "your-pass"
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
        }
    }
}
```

## Upload to Google Play

1. **App Bundle (AAB)**: `./gradlew bundleRelease` →
   `app/build/outputs/bundle/release/app-release.aab` — Play prefers AABs.
2. Play Console: create an app, complete the **Data safety** form (this app
   collects only what the web app stores; it itself requests only the
   `INTERNET` permission and accesses no device data), upload the AAB, and use
   **Play App Signing** (Google keeps the key; upload your `upload` key).
3. **Privacy policy** is required — the web app's privacy policy URL must be
   added to the Play listing.
4. Test on a real device before review: login (email/password **and** Google
   sign-in), Discover/Explore + filters, profile photo upload, chat image/video
   upload (gallery + camera), back button, rotation (page must not reload).

## AdMob ads

Ad unit IDs live in **`app/src/main/res/values/admob.xml`** (real banner +
interstitial IDs) and **`app/src/debug/res/values/admob.xml`** (Google's test
IDs). Debug builds automatically serve **test ads**; release builds
automatically serve the real ones — there is no flag to flip before release.
The App ID is in `AndroidManifest.xml`.

> The AdView's unit ID comes straight from the layout XML
> (`@string/banner_ad_unit_id`). Never also call `adView.adUnitId = ...` in
> code — the AdMob SDK allows a unit ID to be set only once and throws
> `IllegalStateException` ("The ad unit ID can only be set once on AdView"),
> which crashes the app on launch.

Behaviour:

- **Banner**: adaptive anchored banner placed directly above the web app's
  bottom nav. A small injected script (`app/src/main/assets/qdate_bridge.js`)
  measures the nav and reserves matching space in the page, so the banner
  never covers content or the nav. On chat pages and logged-out pages the nav
  (and banner) hide automatically.
- **Interstitial**: shown after every **10** profile browses (a swipe in
  Discover or opening a full profile), never more than once per 60 seconds
  (AdMob policy). Counting happens in the injected script; showing happens in
  `MainActivity.onProfileBrowsedInternal()` — change
  `INTERSTITIAL_EVERY_N_BROWSES` there if you want a different frequency.

## FCM push notifications — one-time setup

The app code is ready; push activates after this setup:

1. **Android side**: Firebase Console → project `qdate-12da2` →
   **Project settings → Your apps → Add app → Android**, package name
   `com.dreamsparkgroups.qdate`. Download **`google-services.json`** and put
   it in **`Android_APK/app/`**. Rebuild. (The Gradle plugin is applied
   conditionally, so the project also builds *without* the file — push is just
   off.)
2. **Server side**: Firebase Console → **Project settings → Service accounts
   → Generate new private key**. Save the JSON as
   **`config/fcm-service-account.json`** in the web app's `config/` dir on the
   server (that dir is web-denied). Then set `fcm.enabled = true` in
   `config/config.php` (or env `FCM_ENABLED=true`).
3. **Database**: run `config/migrations/003_push_tokens.sql` once (creates the
   `push_tokens` table).

How it works: the app registers its FCM token with `/api/push/register` on
each page load (and unregisters when logged out). When a like/match/message
notification is created, `modules/PushService.php` sends a data-only FCM
message (`NotificationService::push()` → deferred
`PushService::notify()`). `QDateMessagingService` displays it only when the
app is backgrounded; tapping it opens the relevant page. All push code is
wrapped in try/catch — a push failure never breaks a swipe or message.

> The OAuth access token is cached in `config/fcm-token-cache.json`
> (auto-created) and refreshed roughly hourly.

### Push troubleshooting

If a device receives no push:

1. Confirm `config/migrations/003_push_tokens.sql` has been run on the
   production database. The Android app can obtain a token even when that
   table is missing, but the server cannot bind the token to a user.
2. Sign in on the APK, open the app once, then put it in the background. The
   current FCM token is registered on page load and again when the app resumes.
3. Check `config/fcm-debug.log` on the server. It records registration errors,
   missing device tokens, OAuth failures and FCM rejection statuses without
   exposing credentials to the browser.
4. Android 13+ requires the notification permission to be allowed in the
   system settings. Data-only pushes are intentionally suppressed while the
   app is in the foreground because the web app's live polling shows the same
   event there.
5. A notification tap now loads the target page directly, including on a cold
   app start. Rebuild the APK after pulling the Android source changes.

## Release checklist (before Play submission)

- [ ] Real ad units confirmed in a **release** build (debug always serves test ads).
- [ ] Own release keystore configured (see below) — not the debug keystore.
- [ ] `google-services.json` present in `Android_APK/app/` (push works).
- [ ] Push tested end-to-end (like/match/message → background → notification
      → tap opens the right page; foreground shows no duplicate).
- [ ] Interstitial verified: after 10 browses, then not again for 60 s.

## Google OAuth — one-time setup

The web app's OAuth redirect URI must be registered in the
Google Cloud Console (it already is for the web app):
`https://test.dreamsparkgroups.com/qdate/auth/google/callback`.
"Continue with Google" runs entirely in the device browser using the same web
OAuth flow, so **no Android client ID or App Links/universal-link verification
is needed**. The browser finishes the OAuth and returns to the app via the
`qdate://resume` custom scheme (declared as an intent-filter on
`MainActivity`); a new account then completes its one-time sign-up form on
`/google-signup` inside the app.
