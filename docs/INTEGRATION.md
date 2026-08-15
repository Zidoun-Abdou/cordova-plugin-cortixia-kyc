# Cortixia KYC — Cordova / OutSystems integration guide

`cordova-plugin-cortixia-kyc` brings the Cortixia identity-verification flow —
**MRZ scan → NFC chip read → liveness** — into any Cordova app, including
**OutSystems O11 Reactive** (which imports standard Cordova plugins). The plugin
owns the guided scanning screens natively, so the app calls one method and gets
back a single result object, the same way the Flutter SDK works.

> **Phase status.** **Android** (MRZ + NFC via jMRTD + liveness) and **iOS**
> (MRZ via Vision + NFC via CoreNFC + liveness via AVFoundation) are both
> implemented and device-verified, including the composed **`scanIdCard`** /
> **`scanPassport`** flow (MRZ → chip → liveness → one result). The JS API is
> identical on both platforms. iOS setup has extra signing requirements — see
> **§8 iOS setup**.

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
| `scanIdCard(options?)` | MRZ+NFC+Liveness | composed `KycResult` |
| `scanPassport(options?)` | MRZ+NFC+Liveness | composed `KycResult` |
| `scanMrz(documentType, options?)` | MRZ | `{ fields, mrz_keys, … }` |
| `readChip({ documentType, mrzKeys })` | NFC | `{ decoded: { dg2, … } }` |
| `checkLiveness(options?)` | Liveness | `{ decision, details, faceguard }` |
| `ping()` | — | plugin/native health |

`documentType` is `'idcard'` or `'passport'`. The composed `scanIdCard` /
`scanPassport` calls are the normal entry point; the single-pack methods
(`scanMrz` / `readChip` / `checkLiveness`) exist for clients who drive the steps
themselves.

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
> PAD → matching → billing). In the full `scanIdCard` flow the reference becomes
> the **chip portrait (DG2)**, so it is a real "live face == document photo"
> check.

### `readChip`

Reads the eMRTD chip using the BAC keys from a prior `scanMrz`, then decodes the
datagroups server-side. The screen guides the user to hold the document against
the back of the phone; DG2/DG7/DG11/DG12 are read over ~10–15 s.

```js
const mrz = await cortixiaKyc.scanMrz('idcard');
const chip = await cortixiaKyc.readChip({ documentType: 'idcard', mrzKeys: mrz.mrz_keys });
// chip.decoded.dg2.face  → portrait JPEG (base64)
// chip.decoded.dg11 / dg12 → server-decoded identity fields
```

Rejections: `cancelled`, `nfc_unavailable` / `nfc_disabled`, `bac_failed` (wrong
MRZ keys — redo the MRZ scan), `nfc_read_failed`, `not_initialized`. **Only the
composed flow or advanced callers need this** — most apps call `scanIdCard`.

### `scanIdCard` / `scanPassport`

The full guided flow — **MRZ scan → NFC chip read → liveness → one result**. This
is what most integrations call. Each step's screen is shown in turn; a cancel or
failure at any step rejects with a typed French error.

```js
const r = await cortixiaKyc.scanIdCard();
// r.status === 'success'
// r.document_type        → 'idcard'
// r.mrz                  → parsed MRZ fields (surname, given_names, document_number, …)
// r.decoded.decoded      → server-decoded chip identity (dg2 portrait, dg11/dg12 fields)
// r.liveness.decision    → 'True' | 'False'  (live face vs chip portrait)
// r.liveness.faceguard   → { result, timing_ms, … }
```

**`KycResult` shape:**

```json
{
  "status": "success",
  "document_type": "idcard",
  "mrz":      { "surname": "...", "given_names": "...", "document_number": "...", "birth_date": "...", "expiry_date": "..." },
  "decoded":  { "status": "ok", "decoded": { "dg2": { "face": "<base64 jpeg>" }, "dg11": {...}, "dg12": {...} } },
  "liveness": { "decision": "True", "details": { "spoof_ratio": 0 }, "faceguard": { "result": "verified" } }
}
```

Billing: a full `scanIdCard` consumes **three credits** — one `mrz`, one `nfc`,
one `liveness`.

---

## 4. Minimal integration example

```js
document.addEventListener('deviceready', async function () {
  const kyc = cordova.plugins.cortixiaKyc;
  try {
    await kyc.initialize({ apiToken: 'ck_live_...' });

    // Full guided flow: MRZ → NFC chip → liveness → one result.
    const r = await kyc.scanIdCard();          // or scanPassport()
    if (r.status === 'success' && r.liveness.decision === 'True') {
      // r.mrz, r.decoded.decoded (chip identity), r.liveness
    }
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

Each server call consumes **one credit** and appears on the portal dashboard
(`used` / `remaining`, broken down `by_pack`): `mrz`, `nfc` (the chip decode),
`liveness`. A full `scanIdCard` therefore costs **three** (one per pack).
Cancelled scans and `invalid_mrz` (bad check digits) are **free**; so is
`initialize`.

## 6. NFC notes

- The chip read is standard **ICAO 9303 eMRTD** (AID `A0000002471001`) via
  **jMRTD** (BAC + secure messaging). Passport and the Algerian ID card both use
  this path — device-verified on the ID card.
- The plugin uses NFC **ReaderMode** (no intent filters), so the app never
  auto-launches on a tag tap; the read only happens inside `readChip` /
  `scanIdCard`.
- It registers the full **BouncyCastle** provider at runtime (replacing
  Android's stripped `BC` stub) so 3DES for BAC is available. Safe on API 26+
  (TLS uses Conscrypt).
- **Release builds (R8/ProGuard): automatic since v0.1.2.** The plugin ships
  `cortixia-kyc-proguard.pro` and wires it into every build type itself — no
  manual keep rules. (It keeps `dz.cortixia.kyc.**` — the plugin class is
  loaded reflectively from config.xml, so without it R8 strips it and the
  build only fails at runtime — plus jMRTD/SCUBA/BouncyCastle.)
- **Licence:** jMRTD is **LGPL-3.0** (used as an unmodified AAR / dynamic link —
  compatible with a closed-source host app). SCUBA is LGPL likewise;
  BouncyCastle is MIT-style. No source-disclosure obligation for the host app.

---

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Could not find Gradle` at build | Cordova doesn't bundle Gradle — install it and add `bin/` to `PATH`. |
| Build fails on Java 11 | cordova-android 15 needs **JDK 17+**. Point `JAVA_HOME` at a 17/21 JDK. |
| `mergeDebugJavaResource` duplicate `META-INF/...` | BouncyCastle jars share metadata — the plugin's gradle already excludes it; don't strip that `packagingOptions` block. |
| `ClassNotFoundException …CortixiaKycPlugin` | Host build has no Kotlin — the plugin is Java for exactly this reason; don't re-add Kotlin sources. |
| `network_error` / everything fails on emulator | Emulator has no camera/NFC and often no DNS. Test on a real device. |
| MRZ "takes forever" | The scanner runs analysis at 1280×720 for legible glyphs; ensure good lighting and fill the frame. |
| NFC "En attente du document…" never advances | Find the phone's NFC antenna (usually upper-back); hold the document flat and still against it. |
| `bac_failed` on the chip read | The MRZ was misread — redo the MRZ scan so the BAC keys are correct. |

---

## 8. iOS setup

The JS API and result shape are identical to Android; iOS just needs a bit more
signing setup because NFC is a restricted capability.

- **Toolchain:** Xcode 15+, **cordova-ios 7+**, a real iPhone (the simulator has
  no camera/NFC). Deployment target **iOS 13+**.
- **Frameworks:** the plugin uses only system frameworks — **Vision** (MRZ OCR),
  **AVFoundation** (liveness), **CoreNFC** (chip). No CocoaPods, no third-party
  eMRTD library (the BAC + secure-messaging read is implemented in-plugin with
  CommonCrypto).
- **NFC requires a PAID Apple Developer account.** Free/Personal teams cannot use
  the NFC entitlement. Sign the app with a paid team.
- **Enable NFC on the App ID.** The plugin declares the
  `com.apple.developer.nfc.readersession.formats = [TAG]` entitlement and the
  eMRTD AID (`A0000002471001`) in `Info.plist`. With **automatic signing +
  `-allowProvisioningUpdates`**, Xcode enables "NFC Tag Reading" on the App ID and
  regenerates the profile for you. (If your account lacks permission to auto-enable
  it, add it once in Xcode: target → **Signing & Capabilities → + Capability →
  Near Field Communication Tag Reading**.)
- **Build:**
  ```bash
  cordova platform add ios
  cordova run ios --device --buildConfig=build.json   # build.json sets your paid developmentTeam
  ```
  Example `build.json`:
  ```json
  { "ios": { "debug":   { "codeSignIdentity": "Apple Development", "developmentTeam": "YOURTEAMID", "automaticProvisioning": true, "buildFlag": ["-allowProvisioningUpdates"] },
             "release": { "codeSignIdentity": "Apple Development", "developmentTeam": "YOURTEAMID", "automaticProvisioning": true, "buildFlag": ["-allowProvisioningUpdates"] } } }
  ```
- **OutSystems (MABS):** set the app's signing to a paid team with NFC enabled;
  MABS applies the plugin's entitlement + Info.plist keys automatically.
- **iOS NFC UX:** iOS shows its own system "Ready to Scan" sheet; the user holds
  the document to the **top** back edge of the iPhone (the NFC antenna sits at the
  top, unlike Android's mid-back).

**iOS troubleshooting**

| Symptom | Cause / fix |
|---|---|
| `nfc_unavailable` on a device with NFC | The NFC entitlement isn't in the *signed* build. Confirm `codesign -d --entitlements :- YourApp.app` shows `com.apple.developer.nfc.readersession.formats`, and that NFC is enabled on the App ID. |
| Signing fails: "No profiles / capability not available" | The team is free, or NFC isn't enabled on the App ID. Use a paid team and enable NFC Tag Reading. |
| `bac_failed` | The MRZ was misread — redo the MRZ scan so the BAC keys are correct. |
