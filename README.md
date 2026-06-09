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

# Artifact Appendix

Paper title: **Designing Reflective Thinking-Based Contextual Privacy Policy for Mobile Applications**

Requested Badge(s):

- [x] **Available**

## Description

This artifact accompanies the following paper:

> Shuning Zhang, Sixing Tao, Eve He, Yuting Yang, Ying Ma, Ailei Wang, Xin Yi, and Hewu Li. "Designing Reflective Thinking-Based Contextual Privacy Policy for Mobile Applications." _Proceedings on Privacy Enhancing Technologies (PoPETS)_, Issue 4, 2026.

The artifact is **CPP-R**, the Android client application used in the user study for the paper. It implements **Conflect**, the high-fidelity mobile prototype described in the paper. Conflect instantiates a reflective thinking-based design space for Contextual Privacy Policies (CPPs) through three key mechanisms:

- A **floating HUD overlay** (`FloatingWindowService`) that surfaces privacy risk notifications non-intrusively while users interact with third-party apps.
- **Real-time screen capture** (`PrivacyService`, via the MediaProjection API) with pixel-level change detection that uploads screenshots to a backend analysis server and receives structured risk responses.
- A **bounding box overlay** (`RiskBoundsOverlay`) that draws highlights directly over the on-screen UI elements identified as privacy-sensitive.
- A **two-stage notification sequence** (immediate summary + 4-second delayed detail) and an in-app **Privacy Policy Viewer** that surfaces the verbatim policy excerpt relevant to each detected risk.

This artifact releases the client-side source code. The backend server — which performs LLM-based policy extraction, risk classification (GPT-4o / GPT-4o-mini), and per-app knowledge base management — is **not included** in this repository. The hardcoded backend URL (`http://8.216.41.236:5005`) points to the authors' research server used during the study; it is not guaranteed to remain active after publication.

### Security/Privacy Issues and Ethical Concerns

**Risks to the evaluator's machine:** This artifact is an Android application source code repository. Building and running it poses no risk to the host machine. The app itself, when installed on an Android device, requests the following sensitive permissions:

- `SYSTEM_ALERT_WINDOW` — to display a floating overlay over other apps.
- `MediaProjection` — to capture screenshots of the device screen (requires an explicit user-confirmed system dialog at runtime).
- `INTERNET` — to upload screenshots to the backend server.

Screenshots are transmitted over **cleartext HTTP** to the backend. Because the backend is not included and the research server may be inactive, the data upload functionality will not operate during artifact evaluation. No sensitive data from an evaluator's device will be processed.

**Ethical conduct of the user study:** The user study (N = 48) was conducted under institutional IRB approval. Participants provided informed consent, which explicitly disclosed: automatic UI scraping via screen capture, potential access to on-screen content (including sensitive data), transmission of screenshots to an institutional server, and forwarding of extracted textual descriptions to a third-party LLM API (OpenAI). Banking and password-management apps were excluded from the study to reduce exposure of sensitive credentials. Participants received 350 CNY (Chinese Yuan) compensation. No raw participant data or identifiable information is included in this artifact. The full informed consent form is included as Appendix I of the paper.

## Environment

### Accessibility

The artifact is publicly available on GitHub:

**https://github.com/sixing-tao/CPP-R/tree/main**

The repository contains the complete Android Studio project for the Conflect client application, including all Java source files, resource layouts, drawables, and build configuration.

## Notes on Reusability

This codebase provides several independently reusable components for researchers building privacy-enhancing or overlay-based Android tools:

1. **Floating HUD system** (`FloatingWindowService.java`) — A complete foreground-service implementation of a drag-repositionable, collapsible overlay window that survives app switches. Adaptable to any notification modality that must persist across contexts.

2. **Screen change detection pipeline** (`PrivacyService.java`) — A MediaProjection-based screenshot loop with a fast pixel-similarity check (1/4-resolution downscale, configurable threshold) that avoids uploading redundant frames. Useful for any study or tool that requires efficient real-time screen monitoring.

3. **Non-interactive bounding box overlay** (`RiskBoundsOverlay.java`) — A full-screen transparent canvas that draws rounded-rectangle highlights over arbitrary pixel coordinates returned by any backend, without blocking touch input to the underlying app.

4. **Backend integration pattern** — The REST contract (`POST /analyze` returning typed risk objects with coordinates, severity, and message fields) is documented in the README and can be reimplemented against any analysis backend — local on-device models, different LLM providers, or rule-based classifiers.

Researchers can extend the artifact by replacing the backend URL with their own server, modifying the knowledge base selector to target different apps, or swapping the HUD layout to explore alternative notification designs within the reflective thinking design space described in the paper.
