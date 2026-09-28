# Releasing

1. Increment `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Run Android unit/UI checks, lint and optimized release build. Build the Windows companion and run `--self-test` under a Windows user with DPAPI support.
3. Sign the APK with `packaging/Sign-Android.ps1 -UnsignedApk app/build/outputs/apk/release/app-release-unsigned.apk -OutputApk artifacts/UsageNotch-VERSION.apk`. The local release key is persistent, outside the repository, under `%LOCALAPPDATA%\UsageNotch\AndroidSigning`. Its password is Windows-user-protected with DPAPI. Keep an encrypted private backup of that signing directory and its recovery credentials. Never regenerate the key for an existing package, print its password, or upload private material to GitHub.
4. Publish the companion: `dotnet publish companion/UsageNotch.Link.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:DebugType=None -p:DebugSymbols=false -o artifacts/link`.
5. Package the companion executable with setup instructions. Do not include identity files, pairing exports, test databases or user history.
6. Create a draft GitHub release with APK, companion ZIP and SHA-256 checksums. Verify the uploaded asset digests before publishing.

The public repository CI compiles and verifies code, but does not have the private Android signing key. Debug CI artifacts are for development; they cannot update a release-signed installation. Production updates are installed from Releases through Android's package installer, which checks that the signing certificate matches the existing app.

The Windows companion is not Authenticode signed. No silent Android package installation, root access or bypass of Android installation approval is used.
