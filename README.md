# cordova-plugin-cortixia-kyc

Algerian eKYC for Cordova apps (incl. **OutSystems O11 Reactive**): guided
**MRZ scan → NFC chip read → liveness**, with the same UX as the Cortixia
Flutter SDK, returning one result object.

The chip cannot be read from JavaScript — the native layer performs the eMRTD
BAC/secure-messaging read (jMRTD on Android, NFCPassportReader on iOS) and posts
the raw datagroups to the Cortixia server for decoding. All other calls
(`init`, `mrz`, `liveness`, `events`) are the stable `/api/sdk/v1/*` REST API.

## Status

- **Phase 0 (done):** plugin packaging + JS↔native bridge proven on Android
  (`ping`). Java (not Kotlin — see below).
- **Phase 1 (done):** REST client + guided **MRZ** (`scanMrz`) + **liveness**
  (`checkLiveness`) on Android.
- **Phase 2 (done):** Android **NFC chip read** (jMRTD BAC/SM, `readChip`) and
  the composed **`scanIdCard`** flow — MRZ → chip → liveness → one `KycResult`,
  with liveness matching the live face against the chip portrait. Device-verified
  on a Xiaomi with a real Algerian ID card; all three packs (`mrz`/`nfc`/
  `liveness`) bill on the portal. See [`docs/INTEGRATION.md`](docs/INTEGRATION.md).
- Phase 3: iOS (Vision + NFCPassportReader + AVFoundation).

> Passport uses the same eMRTD path (`documentType: 'passport'`); untested
> end-to-end only for lack of a passport on hand.

**Integration guide:** [`docs/INTEGRATION.md`](docs/INTEGRATION.md) (install,
OutSystems import, JS API, billing, troubleshooting).

## Build notes (learned the hard way)

- **Java 17+ required** (Cordova-android 15). Point `JAVA_HOME` at Android
  Studio's JBR; a system Java 11 fails at the Gradle step.
- **Gradle must be on `PATH`** — Cordova does not bundle one.
- **The Android plugin is Java, not Kotlin.** A stock Cordova/OutSystems host
  build has no Kotlin support, so Kotlin sources silently fail to compile
  (`ClassNotFoundException` at runtime). Java needs no host config.

## API

```js
await cordova.plugins.cortixiaKyc.initialize({ apiToken: 'ck_live_...' });
const r = await cordova.plugins.cortixiaKyc.scanIdCard();   // guided flow
// r.status, r.personal, r.document, r.biometric, r.liveness
```
