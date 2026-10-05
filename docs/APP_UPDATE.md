# In-app updates

Indigo Observatory checks for new builds over HTTPS, downloads the APK, verifies
it, and hands it to the Android package installer. The user confirms the install
in the system dialog; Android does not permit silent in-place updates for
sideloaded applications.

## Update feed

The application reads a version manifest from an ordered list of locations
configured at build time:

1. `https://github.com/Indigo2233/MobileObservatory/releases/latest/download/update.json`
2. `https://indigo2233.github.io/MobileObservatory/update.json`

The GitHub release asset is authoritative. The GitHub Pages copy is a mirror for
the case where the release asset is unavailable. Both must use the same format:

```json
{
  "schema": 1,
  "versionCode": 43,
  "versionName": "1.0.7",
  "apkUrl": "https://github.com/Indigo2233/MobileObservatory/releases/download/v1.0.7/IndigoObservatory_android_v1.0.7-build43.apk",
  "sha256": "…64 hex characters…",
  "notes": "Release notes shown in the update prompt.",
  "mandatory": false,
  "minSupportedVersionCode": 0
}
```

`versionCode` must be the Android build number. A manifest only triggers an
update when its `versionCode` is greater than the installed one. When
`minSupportedVersionCode` is greater than the installed build, or `mandatory` is
true, the prompt cannot be dismissed.

A manifest is rejected when `apkUrl` is not HTTPS or the `sha256` field is
missing or malformed.

## Publishing a release

Run the `Android Release Publish` workflow, either by pushing a `v*` tag or by
dispatching it manually with a tag such as `v1.0.7`. The workflow:

1. builds and signs the release APK through `Build.ps1 -Release -NonCommercial`;
2. keeps the unfinished sequence feature hidden (`-ShowSequence` opts in for
   development);
3. generates `update.json` from the build information written by `Build.ps1`;
4. attaches the APK, checksum, build information, and `update.json` to the
   GitHub Release.

`scripts/Write-UpdateManifest.ps1` can be run locally against an existing
`bin/Installer` directory:

```powershell
.\Build.ps1 -Release -NonCommercial
.\scripts\Write-UpdateManifest.ps1 -Tag v1.0.7 -NotesFile release-notes.md
```

The tag must contain the version name recorded in the APK build information.

## Verification

Before the installer launches, the application:

1. streams the APK to `cacheDir/updates/` and computes its SHA-256 while
   downloading, rejecting a mismatch;
2. reads the archive package name and version code through `PackageManager` and
   requires them to match the running application and the manifest;
3. compares the archive signer with the installed application signer.

The Android installer still performs its own signature enforcement. The
application checks are an early, explicit failure path.

## Install permission

Android 8.0 and later require `REQUEST_INSTALL_PACKAGES` and the user's
"install unknown apps" approval for the application. When approval is missing,
the update prompt offers a shortcut to the system settings page and lets the
user install again afterwards.

## Enabling the GitHub Pages mirror

The fallback URL is only usable after GitHub Pages is enabled for the repository
and a manifest is published at the matching path. Either:

- enable Pages with the `/docs` folder and place `update.json` there, or
- let the publish workflow write an extra Pages deployment.

Until a mirror exists the fallback fetch fails and the release asset remains the
only feed.

## Preferences

- `update_auto_check` (boolean, default true) controls the automatic startup
  check. The check runs at most once every 24 hours.
- `update_last_check_ms` records the last attempt.

Both live in the `mobile_observatory` shared preferences file and are visible in
Settings → General.
