# UsageNotch for Android

A calm, glass-inspired companion for your AI usage. Native Kotlin and Jetpack Compose, with soft light, animated rings, spring motion and home-screen widgets.

**[Download the Android APK and Windows Link companion](https://github.com/Arnav-Dugad/UsageNotch-Android/releases/latest)**

Android 9 or newer. This is a sideloaded APK, not a Play Store listing. No subscription or cloud account is required by UsageNotch.

## Get connected

1. On Android, download `UsageNotch-1.0.0.apk` from Releases. Open it and permit installation from your browser/file manager when Android asks.
2. On Windows, keep [UsageNotch Windows 2.2.0 or newer](https://github.com/Arnav-Dugad/UsageNotch-Windows/releases/latest) running with your providers connected.
3. Download and extract `UsageNotch-Link-1.0.0-win-x64.zip`. Run `UsageNotch.Link.exe` and click **Start secure link**. If Windows Firewall asks, allow it on your trusted **Private** network.
4. Choose the PC's Wi-Fi/private VPN address and click **Export pairing file**. Transfer that `.usagenotch` file privately to your phone, for example by USB. It is an access key; do not post it in an issue or public repository.
5. In Android, tap **Import PC pairing** and select the file. Both devices must be reachable on the same Wi-Fi or private VPN. Import verifies the PC before replacing an existing pairing. Delete the transferred pairing file after import.
6. Open **Widgets** and tap **Add to home screen**, or long-press your launcher → Widgets → UsageNotch. The focus widget lets you select a provider.

Keep UsageNotch and UsageNotch Link running on Windows. Minimizing Link keeps sharing active; closing it stops sharing. There is no cloud relay, automatic router configuration or public port forwarding. A reserved local IP helps keep pairing stable; export another file if the PC's address changes.

## What you get

- All providers recorded by the Windows app, including Claude, Codex, Gemini and Cursor, with independent quota windows.
- Percentage remaining or used, exact local reset timestamps, and second-by-second countdowns in the app.
- Recent observed history; missing intervals and reset boundaries are never joined into a misleading continuous line.
- Overview and single-provider widgets, refresh actions and clearly dated saved readings.
- Adaptive phone/tablet cards, animated ring changes, soft page transitions, reduced motion, AM/PM or 24-hour time.
- Explicit sample preview, separate from real readings and never used by widgets.

The companion reads Windows history in read-only mode. It only returns the current account's latest observation batch and up to 512 recent points per window. Windows retains the full history. Window IDs whose duration is not recorded in that database are labelled **primary** or **secondary**, rather than assuming five-hour or weekly durations.

Android controls periodic background work. A 15-minute refresh is requested, but battery saving, Doze and manufacturers can delay it. Widgets show observation age; they do not pretend to be live. Tap refresh to request a sync. Refreshing the phone reads the PC cache; it does not force provider polling. A passed reset time stays labelled as awaiting a new reading until Windows observes the reset.

## Privacy and security

AI tokens, account names and account identifiers are not sent to Android. Pairing uses a random 256-bit bearer key over HTTPS and the exact PC certificate fingerprint transferred out of band. The app rejects HTTP, redirects, public addresses and mismatched certificates. Android Keystore encrypts pairing data; app data is excluded from backup and device transfer. The PC identity is protected with Windows DPAPI.

**Revoke all paired phones** on Windows invalidates every previously exported file immediately. **Disconnect this phone** clears its local pairing and cache. There are no advertisements, analytics SDKs, tracking or third-party relay servers. Widgets may be visible to anyone looking at your home screen.

## Build

Use Android Studio or JDK 17–25, Android SDK 36 and the checked-in Gradle wrapper:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The optimized release is built with `:app:assembleRelease`. Release APKs use a persistent private signing key. See [release instructions](docs/RELEASING.md). The APK package is `io.github.arnavdugad.usagenotch`; compatible updates must use the same signing certificate.

Windows companion:

```powershell
dotnet build companion/UsageNotch.Link.csproj -c Release
dotnet companion/bin/Release/net8.0-windows/UsageNotch.Link.dll --self-test
```

See [verification](docs/VERIFICATION.md) for actual checks and limitations. Glass here means layered translucent surfaces and restrained light, not a claim of Apple's proprietary rendering or launcher-wide real-time blur. Widget rendering is controlled by Android launchers.

Independent community project. Not affiliated with Anthropic, OpenAI, Google, Cursor or Apple.
