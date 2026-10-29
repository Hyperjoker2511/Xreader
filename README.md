# Xreader

Android novel and e-book reader. Private repository.

Cloud builds run in GitHub Actions: the full unit-test gate first, then a
signed release APK as a downloadable artifact.

Binary assets (fonts, launcher icons, sounds, the Gradle wrapper) are stored
as `.b64` text files for transport; the workflow restores them before
building. The signing key is never in this repository — it is supplied to
the build from GitHub Secrets.

## Cloud build setup

The workflow needs four repository secrets (Settings -> Secrets and
variables -> Actions): KEYSTORE_B64, XR_STORE_PASSWORD, XR_KEY_ALIAS,
XR_KEY_PASSWORD. Values live only in the owner's private keystore note,
never in this repository.
