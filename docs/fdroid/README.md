# F-Droid

LightCraft for Android is built for F-Droid as a **reproducible build**: F-Droid's build server
builds each tagged release from source and checks it is byte-for-byte the APK on GitHub Releases,
then ships ours (signed with our key), so people can move between F-Droid and GitHub builds without
reinstalling.

`io.github.artcraftandroid.lightcraft.yml` is the recipe for
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) (`metadata/io.github.artcraftandroid.lightcraft.yml`).
The store listing (title, descriptions, changelogs, icon, screenshots) is in
`fastlane/metadata/android/en-US/` at the repository root; F-Droid reads it from there.

## What makes the build reproducible

- **One Rust release** (`rust-toolchain.toml`), **one cargo-ndk** (4.1.2) and **one NDK**
  (29.0.14206865) everywhere: CI, F-Droid and your machine.
- **No build paths in the Rust library**: the Gradle task that runs cargo-ndk remaps the checkout
  and the Cargo home (`--remap-path-prefix`), so where it was built leaves no trace.
- **No AGP extras that differ**: no VCS info and no dependency metadata block in the APK
  (F-Droid's checker rejects the latter), and no baseline profile.
- **One APK per ABI**, each with its own version code: version code × 10 + 1 (armeabi-v7a) or
  + 2 (arm64-v8a). F-Droid builds each with `lightcraft.unsigned=true` and compares it with ours.

Releases are built by the release workflow on Linux, as F-Droid's are, so Windows line endings in
a local checkout don't matter.

## A release

1. Set `lightcraft.version` and `lightcraft.versionCode` in
   `apps/lightcraft-android/android/gradle.properties` (the build checks they agree: the code is
   `((major·100 + minor)·100 + patch)·100 + (pre-release number, or 99)`, e.g. 0.0.3-beta → 301).
2. Add `fastlane/metadata/android/en-US/changelogs/<code>1.txt` and `<code>2.txt` (one per APK,
   at most 500 characters).
3. Commit, tag `v<version>` and push the tag; the workflow refuses a tag that isn't the version in
   gradle.properties. Publish the draft release it makes.
4. F-Droid's update check finds the tag and adds the builds itself (`AutoUpdateMode: Version`).

## Submitting (once)

1. Fork fdroiddata on GitLab, branch `io.github.artcraftandroid.lightcraft`.
2. Copy the recipe to `metadata/io.github.artcraftandroid.lightcraft.yml` and replace
   `TAG_COMMIT_SHA` with the full 40-character commit of the release tag.
3. Run `fdroid lint io.github.artcraftandroid.lightcraft` and `fdroid rewritemeta
   io.github.artcraftandroid.lightcraft` (both must change nothing), commit as
   `New App: io.github.artcraftandroid.lightcraft`, push and open the merge request with the
   "App inclusion" template.

The release key's certificate SHA-256 (`AllowedAPKSigningKeys`):
`7356d58ae3446b3e6cc457d0b9901700376c787310c9dba14e9c11866588eeb5`.
