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

The keystore Base64 value can be generated locally with:

```powershell
[Convert]::ToBase64String(
  [IO.File]::ReadAllBytes("indigo-observatory-release.jks")
) | Set-Clipboard
```

For local release builds, set `ANDROID_RELEASE_KEYSTORE` to the keystore path and
set the other three variables directly. Gradle rejects missing credentials,
`debug.keystore`, and the standard `androiddebugkey` alias.

## Build a release candidate

Run the `Android Release Candidate` workflow manually, or build locally:

```powershell
.\Build.ps1 -Clean -Release -NonCommercial
```

The build produces:

- signed, versioned APK and latest-name APK;
- SHA-256 checksum;
- build information containing version, commit, variant, timestamp, and hash;
- application Corresponding Source, including the pinned libusb source;
- Stellarium Web Engine source fixed to the integrated upstream commit.

The workflow uploads a 14-day release-candidate artifact. It does not publish a
GitHub Release. Publication remains a separate go/no-go decision after validation.

## Go/no-go gates

- Android CI passes for the exact release commit.
- The APK certificate matches the stored production certificate fingerprint.
- Version code is greater than every published build.
- Required rows in `docs/testing/HARDWARE_SMOKE_TESTS.md` have dated evidence.
- Camera, mount, guide, accessory, STOP, reconnect, and permission paths pass on
  the affected hardware families.
- APK, checksum, build information, application source, and Stellarium source are
  uploaded together.
- Release notes state unresolved hardware coverage and phone-solver validation.

## R8 status

R8 remains disabled for release builds until ZWO, QHY, ToupTek, Player One, JNI,
and accessory paths pass the hardware matrix with shrinking enabled. Native entry
points and vendor SDK reflection make build-only validation insufficient. Enabling
R8 requires a dedicated release candidate and recorded hardware regression results.
