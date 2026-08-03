# PhotoSync

Auto-tags your Android camera photos with the city/country they were taken in
and uploads them into a single flat Google Drive folder, named
`Country_City_001.jpg`, `Country_City_002.jpg`, etc.

Photos are uploaded and renumbered in **capture order**, so within a location
the index is the chronological order. Anything that hands out sequence numbers
has to sort ascending first — `PhotoScanner` returns newest-first for the
picker grid, and uploading in that order numbers a library backwards.

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

1. Grant the photo/location permissions when prompted.
2. Open the **Settings** tab, tap **Sign in with Google**, pick your account,
   and accept the Drive permission prompt.

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

## Tabs

The app opens on a three-tab bar; tabs can be tapped or swiped between.

- **Sync** — what's backed up, live progress of a running sync, sync options,
  browse synced photos, and **Stop Sync** (enabled only while one is running;
  cancels between photos, so whatever already uploaded stays put and the rest
  resume on the next sync).
- **Settings** — sign in with Google, share the public browsing link, point
  this phone at a Drive folder shared from another account, or **Re-upload
  Everything** (forgets which photos have already been uploaded so the whole
  library syncs again; delete the Drive copies first or you'll get duplicates).
- **Edit** — fix what's already on Drive:
  - **Fix Locations** walks the folder in capture order, gives every
    `NoGPS, Unsorted` photo the location of the last photo that had one,
    renumbers each city's sequence chronologically, and writes that order back
    to Drive as per-file `appProperties` (Drive has no ordering of its own).
    Each photo's location is then editable by hand — type `City, Country` and
    tap **Save** to rename it.
  - **Find Redundant** hashes every photo on-device to cluster near-identical
    shots, then asks Gemini which of each cluster are genuinely redundant.
    Its picks come back pre-ticked but fully editable.
  - **Delete Selected** moves the ticked photos to Drive's trash, so a
    mis-flagged photo is still recoverable for 30 days.

The Edit screen can only rename or delete photos this app uploaded — the
`drive.file` scope grants write access to the app's own files only, so photos
added to the folder some other way are listed but reported as skipped.

## How it works

- Syncing is started from **Sync Options** (all / by city / individual photos),
  which enqueues a WorkManager job. Note there is currently **no periodic
  background sync** — nothing in the app enqueues a `PeriodicWorkRequest`, so
  photos are uploaded only when a sync is started by hand.
- For each new photo, it reads GPS EXIF data, reverse-geocodes it to a
  city/country using Android's on-device Geocoder, and uploads the photo to
  the `PhotoSync` folder as `Country_City_NNN.jpg`.
- Reads go through `OriginalMedia`, which calls `MediaStore.setRequireOriginal()`.
  Without it Android 10+ hands back a copy with the GPS tags stripped — even
  with `ACCESS_MEDIA_LOCATION` granted — so every photo would name itself
  `Unsorted_NoGPS`, and the stripped bytes would be what reached Drive.
- Each Drive file is backdated to the photo's EXIF capture time. Drive
  otherwise stamps `createdTime` with the moment of upload, so a library synced
  in one go shows up dated today everywhere that reads file dates instead of
  EXIF — the Drive UI, and the public browsing page, which sorts on
  `createdTime`. Only `modifiedTime` can be rewritten afterwards, so photos
  uploaded by an older build are fully corrected only by re-uploading.
- A photo with no GPS of its own inherits the location of the nearest photo in
  capture order that has one (backwards first, then forwards), and those
  coordinates are written into the uploaded copy's EXIF so the location travels
  with the file rather than living only in its name. The chain carries across
  sync runs, so `Unsorted_NoGPS_NNN.jpg` now only happens when no photo in
  range — and no earlier run — had a fix at all. The sync completion message
  says how many, if any, ended up that way.
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
