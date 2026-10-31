UsageNotch for Android 1.7.1 makes self-updates dependable.

- **Updates work on busy networks.** GitHub limits how often a network can ask its release service (a campus or office can use that up). UsageNotch now falls back to the ordinary release page, then downloads the same files from GitHub with the same checksum and signature checks.
- **No false "up to date".** If GitHub can't be reached, Settings says so and suggests trying again later, instead of claiming you have the latest version.

If you're on 1.6.0 or 1.7.0 and your network has hit GitHub's limit, the update will arrive at the next check, or download UsageNotch-1.7.1.apk from the release page. The APK is signed with the same key as before (certificate SHA-256 `ddafa1fb472f7ee28c91c7badab1930e5ba9b8a7e78b96ad33bec36798148091`).
