# Building and publishing this fork

## Test APKs

After setting up the Android build environment (see [INSTALL.md](INSTALL.md)):

```sh
cd android
./gradlew assembleGoogleExperimental -Parm64
```

The APK is under `android/app/build/outputs/apk/google/experimental/`.
This variant is named **Experimental Organic Maps** and uses a separate
application ID, so it can coexist with the official app. It is release-like,
not a debuggable build, but uses the repository's **public debug signing key**.
It is suitable for testing, not a secure public release channel.

Rebuild after changing or merging code; an existing APK does not update itself.
Updating an installed APK requires the same application ID, a compatible version
code, and the same signing certificate. Back up bookmarks and tracks before
uninstalling an app or changing its application ID or signing key.

## GitHub Actions

Forks inherit workflow files, but Actions may need enabling in the fork settings.
The existing **Android Check** workflow supports manual runs, pushes to `master`,
and eligible pull requests. It builds debug APKs and uploads them as temporary
workflow artifacts. A push to another branch alone does not trigger it.

The upstream **Android Beta** and **Android Release** workflows depend on
upstream-specific secrets and distribution services. They are not a ready-made
release pipeline for this fork. Do not configure them with upstream credentials.

For public distribution, configure a dedicated fork workflow that:

- Builds the intended fork variant and application ID.
- Uses a stable private signing key, not the public debug key.
- Reads signing material from GitHub Actions secrets, never source files.
- Runs checks and publishes an APK and checksum to a GitHub Release.
- Requires explicit approval or a release tag before publishing.

An uploaded artifact or GitHub Release does not automatically install updates on
users' phones. Users must download and install the new APK unless a separate
update mechanism is configured.

## Publication hygiene

Publish only the reviewed branch, not every local branch or a mirror of the
working repository. Old backup branches may contain work that was not reviewed.

Never commit private keystores, signing passwords, access tokens, local build
configuration, personal GPX/KML files, device logs, or measurement results.
The upstream `android/app/debug.keystore` and placeholder `private.h` are public
build inputs; they are not personal release credentials. Existing ignore rules
protect the standard local credential paths, but do not protect arbitrary names.

Before pushing, inspect both the staged changes and all fork-only commits. Run
a secret scanner over that history as well as pending files. A clean scan lowers
the risk but is not a guarantee that every possible secret has been detected.
