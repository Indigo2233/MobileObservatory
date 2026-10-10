# Release process

Indigo Observatory release artifacts use a stable maintainer-controlled signing key.
The public repository and CI contain no production key material.

## One-time key setup

Create the key on an encrypted maintainer machine and keep at least two encrypted
backups in separate locations:

```powershell
keytool -genkeypair -v `
  -keystore indigo-observatory-release.jks `
  -alias indigo-observatory `
  -keyalg RSA -keysize 4096 -validity 10000
```

Configure the following secrets in the protected GitHub `release` environment:

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`
- `ANDROID_RELEASE_OLD_KEYSTORE_BASE64`
- `ANDROID_RELEASE_OLD_STORE_PASSWORD`
- `ANDROID_RELEASE_OLD_KEY_ALIAS`
- `ANDROID_RELEASE_OLD_KEY_PASSWORD`

The four `ANDROID_RELEASE_OLD_*` values hold the certificate used by `v1.0.6`.
`Build.ps1` creates an APK Signature Scheme v3 lineage at build time. Android 8
receives an APK Signature Scheme v2 signature from the legacy certificate, and
Android 9 or newer receives the maintainer certificate through the rotation
lineage. The build verifies the legacy SHA-256 fingerprint before producing the
installer. The maintainer certificate SHA-256 fingerprint is
`46a82d0f1ba2989f42448b7ef9845a8283a9a2cb8e8938bb49a7bae3a27aaa3a`.
The legacy `v1.0.6` certificate fingerprint is
`bc4b926780ae1826eb2b66c3c2bea99a546bac91f5bc9f6274547680cc86c374`.

The keystore Base64 value can be generated locally with:

```powershell
[Convert]::ToBase64String(
  [IO.File]::ReadAllBytes("indigo-observatory-release.jks")
) | Set-Clipboard
```

For local release builds, set `ANDROID_RELEASE_KEYSTORE` and
`ANDROID_RELEASE_OLD_KEYSTORE` to their keystore paths and set the other six
variables directly. Gradle validates the maintainer key before `Build.ps1`
applies and verifies the rotation lineage.

## Build a release candidate

Run the `Android Release Candidate` workflow manually, or build locally:

```powershell
.\Build.ps1 -Clean -Release -NonCommercial
```

The advanced sequencer is hidden by default because it is still incomplete, so
release APKs carry no sequence entry points:

```powershell
.\Build.ps1 -Release -NonCommercial
```

Development builds opt in with `-ShowSequence`, which passes
`-PsequenceEnabled=true` and restores the sequence tab, the sequence settings
page, and the "add to sequence" actions:

```powershell
.\Build.ps1 -NonCommercial -ShowSequence
```

The build produces:

- signed, versioned APK and latest-name APK;
- SHA-256 checksum;
- build information containing version, commit, variant, timestamp, and hash.

GitHub Releases attach only those installer artifacts. Application and Stellarium
source stay in this repository at the release tag; do not upload
`MobileObservatory_Source_*.zip` or `StellariumWebEngine_*.zip`.

The workflow uploads a 14-day release-candidate artifact. It does not publish a
GitHub Release. Publication remains a separate go/no-go decision after validation.

## Publish a release

After the go/no-go gates pass, run the `Android Release Publish` workflow by
pushing the release tag (`v1.0.7`) or dispatching it manually. It builds the
signed APK, generates `update.json`, and creates the GitHub Release with the APK,
checksum, build information, and manifest attached.

`Write-UpdateManifest.ps1` rejects debug variants, missing Stellarium assets,
enabled sequence entry points, and unexpected current or legacy certificate
fingerprints before publication.

The in-app updater reads `update.json` from the newest published release, so a
release without the manifest is not distributed to existing installations.
See `docs/APP_UPDATE.md` for the manifest format and verification steps.

## Go/no-go gates

- Android CI passes for the exact release commit.
- The APK certificate matches the stored production certificate fingerprint.
- Android 8 verification reports the `v1.0.6` certificate and Android 9+
  verification reports the maintainer certificate.
- Version code is greater than every published build.
- Required rows in `docs/testing/HARDWARE_SMOKE_TESTS.md` have dated evidence.
- Camera, mount, guide, accessory, STOP, reconnect, and permission paths pass on
  the affected hardware families.
- APK, checksum, and build information are uploaded together.
- Release notes state unresolved hardware coverage and phone-solver validation.

## R8 status

R8 remains disabled for release builds until ZWO, QHY, ToupTek, Player One, JNI,
and accessory paths pass the hardware matrix with shrinking enabled. Native entry
points and vendor SDK reflection make build-only validation insufficient. Enabling
R8 requires a dedicated release candidate and recorded hardware regression results.
