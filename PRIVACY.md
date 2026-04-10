# Privacy Policy

**Effective date:** 2026-04-10

## Overview

PhotoSlide is a local live wallpaper application for Android. It displays photos from folders you select on your device. This policy explains what data the app accesses and how it is handled.

## 1. Data Collection & Usage

PhotoSlide does **not** collect, transmit, or share any personally identifiable information.

All data the app stores remains exclusively on your device:

- **Selected folders** — the folder paths you choose are saved locally in the app's private storage.
- **Settings & preferences** — your configured options (grid layout, slide interval, sort order, etc.) are stored locally using Android's SharedPreferences.
- **Image URI cache** — a local cache of photo file references is stored in the app's private files directory to speed up wallpaper loading.

None of this data ever leaves your device.

## 2. Photo Access

PhotoSlide accesses photos only from folders you explicitly select using Android's built-in folder picker (Storage Access Framework). The app does not request broad storage permissions and cannot access files outside the folders you choose.

Photos are read locally for display as a live wallpaper. They are not copied, uploaded, or processed in any way beyond rendering them on screen.

## 3. Third-Party Services

PhotoSlide does **not** integrate any third-party services. The following are explicitly not used:

- No analytics or crash reporting (e.g. Firebase, Crashlytics)
- No advertising networks
- No social media SDKs
- No tracking or profiling libraries

The app has no internet permission and makes no network requests.

## 4. Server Access

PhotoSlide has no backend server. No data is transmitted to any server, and no account or registration is required to use the app.

## 5. Children's Privacy

PhotoSlide does not collect any data from any user, including children. The app is safe for use by all age groups.

## 6. Changes to This Policy

If this policy changes in a future version, the updated policy will be published in this repository and the effective date above will be updated.

## 7. Contact

If you have questions about this privacy policy, please open an issue at:  
https://github.com/gomaed/PhotoSlide/issues
