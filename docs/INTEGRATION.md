# Cortixia KYC — Cordova / OutSystems integration guide

`cordova-plugin-cortixia-kyc` brings the Cortixia identity-verification flow —
**MRZ scan → NFC chip read → liveness** — into any Cordova app, including
**OutSystems O11 Reactive** (which imports standard Cordova plugins). The plugin
owns the guided scanning screens natively, so the app calls one method and gets
back a single result object, the same way the Flutter SDK works.

> **Phase status.** Android **MRZ** and **liveness** are live and device-verified.
> Android **NFC** (jMRTD, passport + ID card) is Phase 2. **iOS** is Phase 3.
> This guide is versioned with the plugin; sections marked _(Phase 2/3)_ describe
> the target API and are not callable yet.

---

## 1. What you need

- A Cortixia **API token** (`ck_live_…`) from the client portal at
  <https://www.e-kyc.online/portal/> — its plan must include the packs you call
  (MRZ, and Liveness for the full pack).
- Android build toolchain: **cordova-android 15+**, **JDK 17+**, a Gradle install
  on `PATH` (Cordova does not bundle Gradle), Android SDK, **minSdk 26**.
- A **physical device with a camera** (and NFC for Phase 2). The plugin does not
  work on the emulator (no camera, no NFC, and often no DNS).

---

## 2. Install

### Plain Cordova

```bash
cordova plugin add cordova-plugin-cortixia-kyc
# or from a local checkout / git:
cordova plugin add /path/to/cordova-plugin-cortixia-kyc
cordova plugin add https://github.com/Zidoun-Abdou/cordova-plugin-cortixia-kyc.git
```

The plugin declares its own permissions (`INTERNET`, `CAMERA`, `NFC`), its guided
Activities, and its Android dependencies (AppCompat, ML Kit text-recognition,
CameraX). **No host `build.gradle` edits are required** — everything is applied
through the plugin's `framework` gradle file.

### OutSystems O11 (self-hosted)

1. In **Service Studio**, open your mobile app → **Extensibility Configurations**.
2. Add the plugin by its published npm id **or** its Git URL:
   ```json
   { "plugin": { "url": "https://github.com/Zidoun-Abdou/cordova-plugin-cortixia-kyc.git" } }
   ```
3. Publish. OutSystems runs the Cordova build in **MABS**; the plugin's
   `plugin.xml` supplies permissions, Activities and gradle deps, so no MABS
   overrides are needed for Phase 1.
4. Call the plugin from a **client-side JavaScript** node (see §4). The plugin
   clobbers `cordova.plugins.cortixiaKyc`, available after `deviceready`.

> **The plugin is written in Java, not Kotlin, on purpose.** A stock Cordova /
> OutSystems host build has no Kotlin toolchain, so a Kotlin plugin would fail to
> compile silently. Do not "modernise" it to Kotlin unless you also add Kotlin
> support to the host build.

---

## 3. JavaScript API

All methods return a Promise. Errors reject with `{ code, message }` where
`message` is a ready-to-display **French** string (never a raw HTTP status).

| Method | Pack | Returns |
|---|---|---|
| `initialize({ apiToken, baseUrl?, debugMode? })` | — | license + quota |
| `scanMrz(documentType, options?)` | MRZ | `{ fields, mrz_keys, … }` |
| `checkLiveness(options?)` | Liveness | `{ decision, details, faceguard }` |
| `scanIdCard(options?)` _(Phase 2)_ | full | composed `KycResult` |
| `scanPassport(options?)` _(Phase 2)_ | full | composed `KycResult` |
| `ping()` | — | plugin/native health |

`documentType` is `'idcard'` or `'passport'`.

### `initialize`

Validates the token against the licensing server and unlocks the other calls.
Call it once after `deviceready`, before any scan.

```js
const lic = await cortixiaKyc.initialize({ apiToken: 'ck_live_...' });
// lic.client, lic.plan, lic.quota { limit, used, remaining, unlimited }
```

Rejections: `bad_config` (no token), `invalid_token` (401),
`no_active_subscription` / `quota_exceeded` / `pack_not_entitled` (402),
`network_error`.

### `scanMrz`

Opens the guided MRZ scanner (camera + on-device OCR, auto-detect — no shutter),
then validates the lines server-side.

```js
const r = await cortixiaKyc.scanMrz('idcard');
// r.fields      → parsed MRZ (surname, given_names, document_number, birth_date, …)
// r.mrz_keys    → BAC inputs consumed by the NFC read in Phase 2
```

Rejections: `cancelled` (user backed out), `no_mrz`, `invalid_mrz` (bad check
digits — unbilled), `not_initialized`.

### `checkLiveness`

Opens the guided liveness capture (front camera, face oval, short video), extracts
a face frame, and verifies it server-side (PAD + matching via FaceGuard).

```js
const r = await cortixiaKyc.checkLiveness();
const passed = r.decision === 'True';          // decision is a STRING
// r.details.spoof_ratio  → 0 when PAD passed
// r.faceguard.result, r.faceguard.timing_ms.total_ms
```

Rejections: `cancelled`, `capture_failed`, `liveness_unavailable` (502 — retryable,
unbilled; show the message, offer retry), `not_initialized`.

> In standalone mode the reference face is the selfie's own mid-frame, so
> similarity is high by construction — this call proves the pipeline (capture →
> PAD → matching → billing). In the full document flow _(Phase 2)_ the reference
> becomes the **chip portrait (DG2)**, which is the real identity check.

---

## 4. Minimal integration example

```js
document.addEventListener('deviceready', async function () {
  const kyc = cordova.plugins.cortixiaKyc;
  try {
    await kyc.initialize({ apiToken: 'ck_live_...' });

    // MRZ
    const mrz = await kyc.scanMrz('idcard');
    console.log('MRZ', mrz.fields, mrz.mrz_keys);

    // Liveness
    const live = await kyc.checkLiveness();
    if (live.decision === 'True') { /* passed */ }
  } catch (e) {
    // e.message is a display-ready French string
    alert(e.message);
  }
});
```

In **OutSystems**, put the same body inside a client-side **JavaScript** node,
read the token from a Site Property / server call, and map the resolved object
onto local variables for the UI. Wrap the call in the node's async success/error
outputs.

---

## 5. Billing & quota

Every successful `scanMrz` and `checkLiveness` consumes **one credit** and
appears on the portal dashboard (`used` / `remaining`, broken down `by_pack`).
Cancelled scans and `invalid_mrz` are **free**. `initialize` is free.

---

## 6. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Could not find Gradle` at build | Cordova doesn't bundle Gradle — install it and add `bin/` to `PATH`. |
| Build fails on Java 11 | cordova-android 15 needs **JDK 17+**. Point `JAVA_HOME` at a 17/21 JDK. |
| `ClassNotFoundException …CortixiaKycPlugin` | Host build has no Kotlin — the plugin is Java for exactly this reason; don't re-add Kotlin sources. |
| `network_error` / everything fails on emulator | Emulator has no camera/NFC and often no DNS. Test on a real device. |
| MRZ "takes forever" | The scanner runs analysis at 1280×720 for legible glyphs; ensure good lighting and fill the frame. |

---

## 7. Roadmap

- **Phase 2 — Android NFC**: `scanIdCard` / `scanPassport` add the chip read
  (jMRTD BAC + secure messaging, DG2/DG7/DG11/DG12 → server decode) between MRZ
  and liveness, returning a full `KycResult` (personal / document / biometric /
  liveness). jMRTD is **LGPL** (AAR/dynamic-link use is fine — documented on ship).
- **Phase 3 — iOS**: Vision MRZ, `NFCTagReaderSession` + NFCPassportReader,
  AVFoundation liveness, matching native UX; delivered as code + guide.
