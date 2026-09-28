UsageNotch Android 1.0.1 fixes a startup crash seen on some Android devices.

The dashboard no longer depends on WorkManager background scheduling during launch. Widget refresh remains best effort and still runs through Android's normal scheduling rules. A real activity startup test now exercises the application class and the no-pairing path.

Download the Android APK and Windows Link companion from the latest GitHub release. Existing 1.0.0 installations update in place with the same signing identity.

The Android project and the existing UsageNotch Windows app remain separate. AI credentials remain on Windows.
