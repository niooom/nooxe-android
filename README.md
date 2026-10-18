# Nooxe Android

This repository holds only the small Android wrapper app that opens the published Nooxe web app in a secure in-app browser, plus one GitHub Actions workflow that builds it.

- App URL: https://nooxe.base44.app
- Build: on every push (or manually from the Actions tab) the single workflow builds an APK.
- Download: the APK is always published on one single release, tag `latest`, file `Nooxe.apk`.
- Link: https://github.com/OWNER/REPO/releases/latest/download/Nooxe.apk

Only one workflow and only one release exist on purpose: every new build replaces the APK inside the same release.
