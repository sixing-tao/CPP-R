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
