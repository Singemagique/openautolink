# Linux build scripts

Linux equivalents of the PowerShell scripts in `scripts/`. The PowerShell
scripts remain the **authoritative Windows path**; the bash scripts here cover
the same workflows for Linux contributors.

> **Do not duplicate logic into both** when extending. If you change a
> workflow, update both the `.ps1` and `.sh` versions and document any
> intentional differences here.

## Quick start (Linux)

```bash
# 1. One-time native deps (run from repo root)
scripts/build-openssl-android.sh
scripts/setup-ndk-deps.sh
scripts/build-aasdk-android.sh

# 2. Build a debug APK
scripts/linux/build-android.sh                    # debug APK
scripts/linux/build-android.sh assembleRelease    # unsigned release APK

# 3. Build a signed release AAB
#    Pass passwords via env vars (no DPAPI equivalent on Linux):
export OAL_KEYSTORE_PASS='...'
export OAL_KEY_PASS='...'   # may equal OAL_KEYSTORE_PASS
scripts/linux/bundle-release.sh
```

## Environment

- `JAVA_HOME` — JDK 17 or 21 (NOT 25). Auto-detected from common locations
  (`/usr/lib/jvm/java-21-*`, etc.) if not set.
- `ANDROID_SDK_ROOT` — defaults to `$HOME/Android/Sdk`.
- `OAL_KEYSTORE_PASS` / `OAL_KEY_PASS` — required for `bundle-release.sh`.
  These replace the Windows DPAPI-encrypted `secrets/signing-credentials.xml`.

## Differences from the Windows scripts

| Concern | Windows (`scripts/*.ps1`) | Linux (`scripts/linux/*.sh`) |
|---|---|---|
| Signing credential storage | DPAPI-encrypted XML | env vars (or `secrets/signing.env` sourced manually) |
| Default JDK | Eclipse Adoptium under `Program Files` | distro `java-21-openjdk` etc. |
| Screenshot/log helpers | included | not ported (rarely useful from Linux dev box) |
