# CPP-R: Contextual Privacy Protection — Android Prototype

A research prototype Android application that provides **real-time, in-situ privacy risk notifications** while users interact with third-party apps. The system continuously monitors the screen for privacy-sensitive information being displayed, classifies detected risks by severity, and surfaces contextual warnings through a non-intrusive floating HUD — without requiring modification to the monitored apps.

---

## Research Overview

Many Android apps expose users' personal data (names, birthdates, contact lists, financial details, etc.) in ways that contradict or exceed their stated privacy policies. Existing privacy tools require users to read lengthy policy documents offline; this system instead delivers warnings _at the moment of exposure_, anchored directly to the UI element that triggered them.

**Core research questions this artifact addresses:**

- Can real-time screen analysis surface privacy risks that users would otherwise miss?
- Is a floating HUD an effective modality for in-context privacy notifications?
- How do risk severity levels and bounding box overlays affect user comprehension?

---

## System Architecture

```
┌─────────────────────────────────────────────────────────┐
│  Android Device                                         │
│                                                         │
│  ┌─────────────┐   screenshots   ┌──────────────────┐   │
│  │PrivacyService│ ─────────────► │  Backend Server  │   │
│  │(MediaProject)│ ◄───────────── │  /analyze (POST) │   │
│  └──────┬──────┘   risk JSON     └──────────────────┘   │
│         │ broadcast                                      │
│         ▼                                               │
│  ┌──────────────────────┐    ┌───────────────────────┐  │
│  │ FloatingWindowService│    │  RiskBoundsOverlay    │  │
│  │  (floating HUD)      │───►│  (bounding boxes on   │  │
│  └──────────────────────┘    │   screen)             │  │
│                               └───────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

---

## Features

### Screen Change Detection (`PrivacyService`)

- Captures screenshots every **2 seconds** using Android's `MediaProjection` API (requires explicit user permission)
- Before uploading, performs a fast **pixel-similarity comparison** (downscaled to 1/4 resolution) to skip frames where the screen content has not meaningfully changed (threshold: 12% pixel difference)
- The floating HUD widget's own screen region is **blacked out** before upload so the overlay UI does not confuse the backend analyzer
- Screenshots are sent as multipart PNG uploads to the analysis backend

### Backend Integration

- Endpoint: `POST /analyze` — accepts a screenshot, returns a list of detected privacy risks
- Each risk item in the response contains:
  - `typeEn` / `typeZh`: data category (e.g., "Name", "Birthday", "Location")
  - `severity`: `high` | `medium` | `low`
  - `iconName`: icon key for the HUD
  - `message1`, `message2`: two-stage contextual explanation messages
  - `PPOriginal`: the relevant original privacy policy excerpt
  - `coordinates`: pixel-level bounding boxes (top-left / bottom-right) for each detected element
- The app supports switching the backend's **knowledge base** between different target apps (Guazi, iQiyi, Ctrip, UC Browser) via `POST /set-knowledge-base`

### Floating HUD Widget (`FloatingWindowService`)

The HUD runs as a foreground service and overlays all other apps via `SYSTEM_ALERT_WINDOW`.

**Expanded state** — shows a scrollable list of active risk items. Each item is color-coded by severity:

| Severity | Color  | Hex       |
| -------- | ------ | --------- |
| HIGH     | Red    | `#E57373` |
| MEDIUM   | Orange | `#FF9800` |
| LOW      | Green  | `#4CAF50` |

- Tap a risk item → fires a **two-stage notification sequence** (first notification summarizes the risk; 4 seconds later a second notification provides detail and a tap target to view the original policy text)
- Long-press a risk item → context menu to **dismiss** the item
- Toggle switch collapses the HUD to a minimal icon

**Collapsed state** — 32dp icon pinned to the screen edge:

- When new risks arrive, plays a **shake + red glow animation** (translationX oscillation + scale pulse + alpha fade) to alert the user without requiring them to expand the HUD
- Tap to re-expand

Both states support **drag-to-reposition** anywhere along the vertical axis of the screen.

### Bounding Box Overlay (`RiskBoundsOverlay`)

When the user taps a risk item, a full-screen transparent view draws **rounded-rectangle bounding boxes** directly over the privacy-sensitive UI element, with a semi-transparent fill and a corner dot marker. The overlay is non-interactive (`FLAG_NOT_TOUCHABLE`) so the user can still interact with the underlying app.

### Privacy Policy Viewer

Tapping the second notification opens an in-app overlay dialog that renders the **verbatim privacy policy excerpt** (`PPOriginal`) relevant to the detected risk, giving users direct access to the legal text without leaving their current context.

### Debug Interceptor (`BackendJsonInterceptor`)

In debug builds only, an OkHttp interceptor logs all backend responses to Logcat with pretty-printed JSON, while automatically redacting sensitive fields (`authorization`, `token`, `password`, etc.).

---

## App-Specific Knowledge Bases

The backend supports per-app knowledge bases for targeted analysis. The main activity exposes a selector for:

- **Default** — generic analysis
- **Guazi** (瓜子二手车) — used-car marketplace
- **iQiyi** (爱奇艺) — video streaming
- **Ctrip** (携程) — travel booking
- **UC Browser** (UC浏览器) — mobile browser

---

## Permissions

| Permission                                                   | Purpose                                                   |
| ------------------------------------------------------------ | --------------------------------------------------------- |
| `SYSTEM_ALERT_WINDOW`                                        | Display the floating HUD over other apps                  |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Keep capture service alive                                |
| `POST_NOTIFICATIONS`                                         | Two-stage risk notifications                              |
| `INTERNET`                                                   | Upload screenshots and receive risk analysis from backend |

---

## Setup & Build

### Requirements

- Android Studio (Hedgehog or later recommended)
- Android device or emulator running **Android 8.0+ (API 26+)**
- Access to the analysis backend server

### Build

```bash
./gradlew assembleDebug
```

Install on a connected device:

```bash
./gradlew installDebug
```

### Configuration

The backend URL is hardcoded in two places:

| File                     | Variable       | Default                            |
| ------------------------ | -------------- | ---------------------------------- |
| `PrivacyService.java:60` | `BACKEND_URL`  | `http://8.216.41.236:5005/analyze` |
| `MainActivity.java:46`   | `API_BASE_URL` | `http://8.216.41.236:5005`         |

The app uses cleartext HTTP (`android:usesCleartextTraffic="true"`) for local/research network access. See `app/src/main/res/xml/network_security_config.xml` to restrict this for deployment.

### First Launch

1. Grant **overlay permission** when prompted (`Settings > Apps > Display over other apps`)
2. Grant **screen capture permission** when the system dialog appears
3. Select a knowledge base (optional) via the "Select App" button
4. Tap **Start Privacy Monitor**

---

## Project Structure

```
app/src/main/
├── java/com/example/cppr/
│   ├── MainActivity.java            # Launch UI; permission flow; KB selector
│   ├── PrivacyService.java          # Screen capture, change detection, backend upload
│   ├── FloatingWindowService.java   # Floating HUD, notifications, overlay management
│   ├── RiskBoundsOverlay.java       # Full-screen bounding-box canvas view
│   ├── RiskData.java                # Risk item data model (Parcelable) + resource mappings
│   ├── BoundingBox.java             # Coordinate + risk level model (Parcelable)
│   ├── OverlayMaskState.java        # Thread-safe HUD position state for screenshot masking
│   └── net/
│       └── BackendJsonInterceptor.java  # Debug-only response logger with field masking
├── res/
│   ├── layout/
│   │   ├── activity_main.xml             # Main activity
│   │   ├── floating_widget_layout.xml    # Expanded HUD
│   │   ├── floating_widget_collapsed.xml # Collapsed HUD icon (referenced but not listed)
│   │   ├── warning_item_layout.xml       # Single risk row in expanded HUD
│   │   ├── risk_context_menu.xml         # Long-press dismiss menu
│   │   └── pp_original_dialog.xml        # Privacy policy text overlay
│   └── drawable/
│       └── ic_*.xml                      # Data-type icons (address, birthday, contacts, …)
└── AndroidManifest.xml
```

---

## Technical Notes

- **Minimum SDK**: API 26 (Android 8.0 Oreo); **Target SDK**: API 34 (Android 14)
- Language: Java 8; Build system: Gradle 8.5
- Networking: OkHttp 4.9.3 (30s connect/write/read timeouts)
- The `OverlayMaskState` singleton uses a lock-protected list to safely pass the HUD's screen rect from `FloatingWindowService` (UI thread) to `PrivacyService` (background thread) without a binder or broadcast round-trip
- Screenshot comparison is intentionally lossy (1/4 downscale, 50/765 color tolerance) to reduce CPU overhead; a full-resolution capture is only sent when a change is detected

---

## License

This project is released for academic research and non-commercial use only.

---

## Artifact Appendix

The PETS artifact appendix for this submission is maintained as a separate file: [ARTIFACT-APPENDIX.md](ARTIFACT-APPENDIX.md).
