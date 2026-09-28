# Verification

Release 1.0.1 contains the startup crash fix and was built and verified on September 28, 2026.

- Optimized APK: `:app:assembleRelease` passed with R8 shrinking and compiled startup/baseline profile assets.
- Android checks: all 9 tests passed. These cover private HTTPS pairing validation, bounded imports/network responses, noon/midnight and 12/24-hour clocks, distinct usage windows, passed-reset handling, exact-certificate acceptance and wrong/empty certificate rejection, onboarding/navigation/settings, 320dp and 840dp layouts, and actual widget layout/reading states. The widget check asserts both provider rows fit without clipping.
- Android lint and release vital lint passed with no errors. Remaining warnings include dependency upgrade suggestions, hardcoded English copy, older-platform attributes and the intentionally custom certificate trust manager. The latter validates the exact paired certificate and validity period; it is covered by positive and negative tests and does not trust arbitrary certificates.
- Actual Android views were rendered and visually inspected for onboarding, overview, widget previews, settings, a narrow phone, a tablet and the actual RemoteViews widget. These are synthetic-data previews, not images of a connected user's accounts.
- Windows companion: build succeeded with zero warnings/errors. Eleven security/data checks passed: current-account isolation, database byte preservation, private-address selection, DPAPI restart, unauthenticated rejection, authorized pinned HTTPS, response privacy, query rejection, read-only routing, immediate revocation and replacement-key acceptance.
- Release APK signature verified with APK Signature Scheme v3 and a 4096-bit RSA key. Signing certificate SHA-256: `ddafa1fb472f7ee28c91c7badab1930e5ba9b8a7e78b96ad33bec36798148091`.
- The existing UsageNotch Windows repository and installed app were left unchanged. No real pairing exports, credentials, private signing material or personal history were committed.
- The startup path now treats WorkManager scheduling as optional and catches vendor-specific background-service initialization failures, so the dashboard can still open on devices that restrict background work. An actual `MainActivity` launch with `NotchApplication` is covered by the startup test.

The Android app is implemented in native Jetpack Compose. The Windows companion accesses the existing usage database read-only. Tests use synthetic accounts and readings; no personal usage, authentication tokens or account names are included in the repository.

An Android phone was not connected to the development computer and no emulator image was preconfigured. Robolectric executes Android UI behavior and renders views for inspection; those checks do not measure physical-device animation frame times or manufacturer-specific widget scheduling. Actual 60/90/120 Hz performance and a physical phone's Wi-Fi/firewall path require testing on that device.
