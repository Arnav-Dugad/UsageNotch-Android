UsageNotch for Android 1.1.0 fixes the crash on launch and keeps working when your PC is off.

**If the app closed as soon as you opened it, this release fixes that.** Every phone was affected. The optimized build removed a piece of Android's background-job library (WorkManager) that it loads by name, so the app crashed before its first screen. The 1.0.1 download was also accidentally built from the 1.0.0 code. 1.1.0 uses Android's built-in job scheduler, and this exact APK was launch-tested on Android 16 and Android 9 before upload. It installs over 1.0.0 and 1.0.1.

**Works when your PC is off**
- Saved readings, countdowns and widgets keep working. The status says *PC offline* instead of showing an error.
- When a limit's reset time passes, the app and widgets show **Renewed** instead of an old percentage.
- New **Reset alerts** notify you when a limit resets. They work from saved readings, so the laptop can be shut down.
- Optional **internet sync**: UsageNotch Link can upload each reading, end-to-end encrypted, to a secret gist in your own GitHub account. The phone then gets readings on mobile data or any Wi-Fi, and keeps the last one after the PC shuts down. GitHub only ever stores ciphertext.

**Easier pairing and setup**
- **Paste pairing code**: copy a one-line code from Link instead of transferring a file. Pairing files also open straight from your file manager.
- Link now suggests your real Wi-Fi address first. It used to offer WSL/Hyper-V adapters that phones can't reach.
- Link can start with Windows, remembers whether sharing was on, and keeps running in the notification area when you close its window.

**Also**
- Update notices when a newer APK is published (can be turned off).
- Tap anywhere on a settings row to toggle it.
- If the app ever fails to start twice in a row, it opens a safe mode where you can share a crash report or reset its data. Reports stay on the phone unless you share them.
- API spend providers from Windows now show their names and logos, and Codex windows read "Primary window" and "Secondary window".

Download **UsageNotch-1.1.0.apk** on your phone and **UsageNotch-Link-1.1.0-win-x64.zip** on your PC. SHA256SUMS.txt lists checksums. The APK is signed with the same key as 1.0.x (certificate SHA-256 `ddafa1fb472f7ee28c91c7badab1930e5ba9b8a7e78b96ad33bec36798148091`).
