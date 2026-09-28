UsageNotch is now on Android, with a native glass-inspired interface and home-screen widgets.

Download **UsageNotch-1.0.0.apk** on your Android phone (Android 9+). Download **UsageNotch-Link-1.0.0-win-x64.zip** on your Windows PC.

1. Keep UsageNotch Windows 2.2.0 or newer running with your providers connected.
2. Extract the Link ZIP, run UsageNotch.Link.exe and click Start secure link. Allow your trusted Private network if Windows Firewall asks.
3. Export a pairing file, transfer it privately to your phone, and choose Import PC pairing in Android. Both devices must be reachable over Wi-Fi or a private VPN.
4. Open Widgets to add an overview or single-provider widget. Keep both Windows apps running for fresh readings.

Includes separate provider windows, observed-history charts, exact local reset clocks, in-app countdowns, clear saved-reading labels, animated rings, adaptive phone/tablet layouts, AM/PM or 24-hour time and reduced motion. Pairing keeps AI credentials on Windows and uses HTTPS with an exact certificate pin and an encrypted pairing key.

The APK is release-signed; future updates use the same Android identity. SHA256SUMS.txt lists download checksums. Full setup, source and verification notes are in the repository README.

Validation: nine Android tests, Android lint, optimized release build and eleven Windows companion security/data checks passed locally. Actual app views and widget layouts were rendered and reviewed. Physical-phone animation frame times, launcher behavior and Wi-Fi pairing still require device validation; no Android device was connected during development. Background refresh is scheduled by Android and can be delayed by battery saving.
