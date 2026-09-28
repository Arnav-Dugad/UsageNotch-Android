# Verification

Release 1.1.0 was built and verified on September 29, 2026.

## The 1.0.x launch crash

- The published 1.0.1 APK was installed on an Android 16 (API 36) emulator and crashed on every launch. The crash happened in `androidx.startup.InitializationProvider` with `NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init>`: R8 removed a constructor that WorkManager's database loads by reflection. It happens before `Application.onCreate`, so it affected every device.
- The 1.0.1 asset's manifest reads `versionName 1.0.0`, `versionCode 1`. It was signed from a stale 1.0.0 build, so the attempted fix never shipped. That fix, a guard in `Application.onCreate`, also couldn't have caught a crash that happens earlier.
- 1.1.0 removes WorkManager, Room and androidx.startup. Background refresh uses the platform `JobScheduler`, and reset alerts use `AlarmManager`. Neither relies on reflection.

## Checks performed

- **Signed release APK** (`UsageNotch-1.1.0.apk`): manifest confirmed as 1.1.0 / versionCode 3. APK Signature Scheme v3 verified with certificate SHA-256 `ddafa1fb472f7ee28c91c7badab1930e5ba9b8a7e78b96ad33bec36798148091`, the same as 1.0.x. Launched on Android 16 and Android 9 (API 28, the minimum) emulators with empty crash logs. Installed over the published 1.0.1 APK as an in-place update, then launched.
- **Launch check**: `packaging/launch-check.sh` passes the new optimized build and fails the published 1.0.1 APK with its crash. CI now runs it on every push.
- **Android tests**: 22 unit and Robolectric tests pass. They cover pairing validation, pairing codes, relay address and key validation, decrypting an envelope produced by the Windows companion, wrong-key and wrong-label rejection, reset alert scheduling, renewed windows in the actual widget layout, the focus-widget fallback, version comparison, API-provider IDs, clocks, bounded reads and certificate pinning. Android lint: no errors.
- **Windows companion**: 31 self-test checks pass, both in the development build and in the published single-file `UsageNotch.Link.exe`. The new checks cover AES-GCM envelopes, fresh nonces, relay details in pairing files and codes, the GitHub token never leaving the PC, DPAPI persistence of sync settings, key rotation on revoke, point thinning, secret-gist creation containing only ciphertext, upload and skip of unchanged readings, a rejected token, gist deletion, and Wi-Fi being ranked before WSL/Hyper-V adapters.
- **End to end on an emulator with a real Link**, using synthetic history (no personal usage):
  - pasted a pairing code, then pinned HTTPS and Android Keystore storage returned live readings;
  - a window passing its reset time showed *Renewed* in the app and on a real home-screen widget;
  - Reset alerts posted "Codex limit renewed · Primary window reset at 12:01 AM";
  - with internet sync on, a secret gist was created in the maintainer's GitHub account. It held only ciphertext (`public: false`). With the LAN link stopped, the phone received the next reading from the gist, about four minutes after upload because of GitHub's raw-file cache. The test gist was then deleted and GitHub returned 404;
  - in airplane mode the app showed *PC offline* with the saved reading and no error, and the widget showed *PC offline · synced 3m ago*;
  - an invalid pairing code showed a clear message, and settings rows toggled by tapping the label on Android 9.
- **Link window**: rendered and inspected. On the development PC it first offered a WSL adapter (`172.25.240.1`) that phones can't reach. After the fix it offers `192.168.1.8 · Wi-Fi`.

## Not verified here

- A physical phone. Emulators run the real Android framework, but manufacturer battery optimizations and launchers vary, and can delay background refresh and reset alerts.
- Windows Firewall prompts and multi-network setups beyond this PC.
- Fine-grained GitHub tokens: the gist test used a token with the classic `gist` scope. Fine-grained tokens with *Gists: Read and write* use the same API.
