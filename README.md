# PhotoSync

Auto-tags your Android camera photos with the city/country they were taken in
and uploads them into a single flat Google Drive folder, named
`Country_City_001.jpg`, `Country_City_002.jpg`, etc.

## What changed from the original PhotoSync

- **Flat folder** instead of `PhotoSync/Country/City/` subfolders — everything
  lands in one `PhotoSync` folder on Drive.
- **Filename encodes location + index**: `France_Paris_001.jpg` rather than
  relying on folder structure.
- **No auto-sharing.** You share the folder publicly yourself, once, from the
  Drive app or drive.google.com. The app just keeps uploading into it.

## One-time setup

### 1. Google Cloud Console

1. Create (or reuse) a project at console.cloud.google.com.
2. Enable the **Google Drive API**.
3. Configure the **OAuth consent screen** (External is fine for personal use;
   add your own Google account as a test user if the app stays unpublished).
4. Create an **OAuth client ID** of type **Android**:
   - Package name: `com.johnvv.photosync`
   - SHA-1 fingerprint: get yours with
     `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`
     (for a debug build) or your release keystore's SHA-1 for a signed build.

No API key or client secret needs to go in the app itself — Android OAuth
clients are matched by package name + SHA-1, and the Drive scope requested is
`drive.file` (the app can only see files/folders it creates, not your whole
Drive).

### 2. Build and install

Open the project in Android Studio, let Gradle sync, and run it on your
phone. On first launch:

1. Tap **Sign in with Google**, pick your account, accept the Drive
   permission prompt.
2. Grant the photo/location permissions when prompted.

### 3. Share the folder

The app creates a `PhotoSync` folder in your Drive root the first time it
runs (or the first time you tap **Get folder share link**). To make it
public:

1. Open Google Drive (app or drive.google.com).
2. Find the `PhotoSync` folder.
3. Right-click → **Share** → **General access** → **Anyone with the link** →
   Viewer.
4. Copy the link and send it to your friends.

You only need to do this once — new photos the app uploads later will
already be inside that same shared folder.

## How it works

- A WorkManager job runs roughly every 15 minutes (and whenever you tap
  **Sync now**), looking for camera photos added since the last successful
  sync.
- For each new photo, it reads GPS EXIF data, reverse-geocodes it to a
  city/country using Android's on-device Geocoder, and uploads the photo to
  the `PhotoSync` folder as `Country_City_NNN.jpg`.
- Photos with no GPS data are named `Unsorted_NoGPS_NNN.jpg`.
- Per-location counters are stored locally on the phone, so numbering stays
  sequential per city even across app restarts.

## Notes / things to double-check before relying on this

- **Privacy**: a public folder with GPS-derived filenames tells anyone with
  the link roughly where and when each photo was taken. Worth a second
  thought before sharing widely.
- **Geocoder availability**: Android's built-in `Geocoder` depends on a
  backend service that isn't guaranteed on every device/ROM. If it
  consistently returns nothing on your phone, the fallback is to swap in a
  network-based geocoding API (e.g. Google's Geocoding API) instead.
- **Duplicate names across countries**: if two different cities share a name
  (e.g. "Paris, Texas" vs "Paris, France"), the country prefix already
  disambiguates them since it's now the first part of the filename.
