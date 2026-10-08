# Pocket NES releases

## Automatic releases

The [releases page](https://github.com/culpen90/nes/releases) lists every beta and
stable update. Use this page during the beta period: GitHub's `/releases/latest`
link selects stable releases and does not identify the newest prerelease.

The release workflow runs after updates to `main`. Merged pull requests are
eligible regardless of author, including contributions from forks. Normal review
and merge permissions still apply. Every automatically published version includes
a **signed production APK**, built with release optimization and resource
shrinking, with the corresponding version embedded in the APK. Each release
also includes `SHA256SUMS` and `release.json` provenance; source archives are
available at its tag. A source tag or release note alone is not a completed
release. When several PRs reach `main` before a successful publication, one
release includes that unreleased range; after stable promotion the highest
requested bump in that range wins. A direct commit to `main` also causes an
update (a patch after stable promotion), but an unmerged PR cannot promote it.

### Beta period and stable promotion

Versions progress from `1.0.0-beta.1` to `1.0.0-beta.2`,
`1.0.0-beta.3`, and so on. These GitHub releases are prereleases. Ordinary
feature/breaking/bump markers continue this beta sequence until the first
explicit stable promotion.

The special code is the exact standalone line below in a merged PR's **body**,
targeting `main`:

```text
[release:stable]
```

The marker is case-sensitive, with no leading/trailing whitespace or other text
on its line. Keep it outside a code fence, HTML comment, blockquote, or example.
Merely mentioning it in a PR discussion comment, branch, title, issue, or
unmerged PR is not a promotion request. Any
contributor can propose it. Once merged and released, it promotes to **1.0.0**;
later releases stay stable permanently. The marker does not inspect first-stable
readiness. Maintainers MUST verify the [mandatory acceptance policy](../CONTRIBUTING.md#mandatory-first-stable-acceptance-policy)
and its evidence before merging this request. The bot does not verify the
criteria, evidence, or human signoff and does not reject a marker because its
author is a fork contributor.

### Stable semantic versions

After promotion, the PR title/body determine the next bump:

| PR metadata | Stable bump |
| --- | --- |
| Conventional Commit breaking title, such as `feat!: ...` or `fix(core)!: ...`, or a `BREAKING CHANGE:` body line | Major |
| `feat: ...` or `feat(scope): ...` title | Minor |
| Any other merged PR, including fixes, docs, tests, refactoring, or an ordinary non-Conventional title | Patch |

An exact standalone body line `[release:major]`, `[release:minor]`, or
`[release:patch]` explicitly overrides inference after stable promotion. Use
only one marker per PR. Conflicting markers are rejected; they do not silently
select a lower or higher bump. The initial stable request always produces
`1.0.0`. Maintainers should review version overrides as part of the PR; no
owner-only author exception is needed.

### Trusted signing configuration

Repository maintainers configure these GitHub Actions repository secrets using
the existing official release keystore, without committing its bytes or
passwords:

| Secret | Value |
| --- | --- |
| `POCKET_NES_KEYSTORE_BASE64` | Base64-encoded official release keystore |
| `POCKET_NES_STORE_PASSWORD` | Keystore password |
| `POCKET_NES_KEY_ALIAS` | Existing signing alias |
| `POCKET_NES_KEY_PASSWORD` | Signing-key password |

Set the repository Actions **variable** `POCKET_NES_SIGNING_CERT_SHA256` to the
SHA-256 fingerprint of the certificate signing the published official beta.
The public fingerprint may be read from `apksigner verify --print-certs` on that
APK; the keystore and passwords remain private. Do not generate a replacement
key for an established official release: Android would reject in-place updates.
Maintainers are responsible for keeping a secure backup of the original key.

The unprivileged PR workflow builds/tests contributions without official release
secrets. The release workflow checks out trusted `main`, materializes signing
configuration privately on the runner, verifies the expected certificate, and
cleans up signing files. Unmerged fork code MUST NOT run with official signing
credentials. Release workflow permissions MUST remain limited to their actual
build and GitHub release duties.

The release planner performs GitHub reads, but its trusted workflow token needs
`contents: write` because GitHub exposes unpublished drafts only to callers with
push access. This permission allows recovery to see existing drafts; it is not
granted to the contributor PR workflow. The publisher also needs release-write
access to upload and publish verified assets.

### Embedded versions and published assets

The planner supplies `POCKET_NES_VERSION_NAME` and `POCKET_NES_VERSION_CODE`
to Gradle and `tools/build-release.sh`. The APK's embedded `versionName` MUST
equal its release version without the tag's `v` prefix. Its `versionCode` MUST
increase beyond previous official releases, including the beta-to-stable
transition; stable `1.0.0` does not reset the Android version code. Do not hand
edit the source's bootstrap beta version for each automated release.

Published GitHub releases and their resolved immutable Git tags are the version
history; the bootstrap source version is not the release state. The history
starts at `v1.0.0-beta.1`. For each target, Android `versionCode` is **one plus
its first-parent commit distance from the original beta's tagged commit**.
Beta ordinals advance per published beta, independently of commit count, so
gaps in Android codes are expected. Preserve the bootstrap tag and the existing
release history; do not rewrite them or reset the code when switching channels.

The builder verifies the actual packaged version values and signature before
assembling the publishable files in `build/release/`:

| Asset | Meaning |
| --- | --- |
| `pocket-nes-VERSION.apk` | Officially signed, optimized APK with the matching embedded release version |
| `SHA256SUMS` | SHA-256 checksum naming that exact APK |
| `release.json` | Version name/code, merged source SHA, prerelease flag, APK filename/hash, and signing certificate fingerprint |

Publication verifies the target source SHA and expected asset set. The bot
uploads verified assets to an unpublished draft and publishes that release only
after the artifact checks pass. A failure remains a failed run or unpublished
draft; it MUST NOT be described as an installed update or completed release.
The bot does not overwrite an already public release's assets. Users can compare
their downloaded APK with both the checksum file and `release.json`.

### Recovery and first-stable evidence

Inspect `.github/workflows/release.yml` in GitHub Actions if an eligible update
has not been published. Restore missing/correct signing secrets and certificate
configuration before retrying. Re-run the failed job or manually dispatch the
release workflow on **`main`**. A pending draft pins its original source target
so recovery finishes that version rather than relabeling a different APK as the
same release; the workflow dispatches a follow-up for newer `main` changes after
recovering an older draft. Dispatch on `main` again if follow-up did not start.
Keep release publication serialized. Do not delete a published tag or reduce
its version code to force a retry, and do not upload a debug APK as a substitute.

Before the first stable marker is merged, maintainers test an official signed
`1.0.0` candidate from the prospective merge tree with the planned higher code.
Its app/native/assets/build payload MUST match the final merge; any intervening
payload change requires refreshed evidence. Prefer a final documentation-only
promotion PR after the candidate is validated. This is a human review policy;
the workflow does not add a readiness checklist gate.

After automatic publication, the designated release owner downloads the actual
APK, verifies its manifest/certificate/checksums/provenance against the merged
SHA, and repeats install/demo/save/audio smoke testing. Record that attestation
with the first-stable evidence. An unexpected artifact difference requires an
incident investigation and a corrected higher-version release; a public version
must not be silently replaced. Candidate evidence and a subsequent released
artifact smoke test are distinct records.

The release tooling uses **Python 3.11 or later**. A GitHub permission restriction
can prevent the contents-only workflow token from creating a tag at an old
recovery target whose workflow files differ from current `main`. If that exact
error occurs, a maintainer with appropriate administrative credentials MUST
verify the draft's pinned commit and intended tag, create that exact tag at that
exact commit, then dispatch the release workflow on `main`. Do not change the
draft target, replace an existing tag, or grant untrusted PRs broader permissions
to work around it. Verify the resulting tag/commit and final assets afterward.

## First beta

**1.0.0-beta.1** is the first beta, tagged [`v1.0.0-beta.1`](https://github.com/culpen90/nes/releases/tag/v1.0.0-beta.1). The production APK is signed with the project's release key, optimized with R8, and built with resource shrinking. It supports Android 8.0 / API 26 or later and includes `arm64-v8a` and `x86_64` native libraries. Its Android version code is **1**.

The release APK passes signature and 16 KiB ZIP-alignment verification, has no debugging enabled, and preserves the JNI entry points through R8 optimization. The exact signed artifact was exercised on an isolated Android API 36 arm64 emulator: the included demo, touch movement, quick save/load, pause, and rotation work. Android release lint and native regression checks pass.

| File | Purpose |
| --- | --- |
| [pocket-nes-1.0.0-beta.1.apk](https://github.com/culpen90/nes/releases/download/v1.0.0-beta.1/pocket-nes-1.0.0-beta.1.apk) | Installable production APK |
| [SHA256SUMS](https://github.com/culpen90/nes/releases/download/v1.0.0-beta.1/SHA256SUMS) | SHA-256 checksum for the APK |

Download both files into the same directory and verify the APK on macOS with:

```sh
shasum -a 256 -c SHA256SUMS
```

On Linux, use `sha256sum -c SHA256SUMS`. Android may ask you to allow installs from the application used to open the APK. Later official releases can update this installation while retaining its private library and saves.

The existing debug build uses a different signing certificate. Android cannot update it in place with the production APK. Preserve its private ROMs, saves, and preferences before uninstalling it; uninstalling removes that data. The app does not yet provide save export.

## Build a signed APK

Use the SDK and JDK requirements in the [README](../README.md#build). Independent builders should create their own release key. For example, this command prompts for the passwords and certificate details:

```sh
mkdir -p "$HOME/.config/pocket-nes"
keytool -genkeypair \
  -keystore "$HOME/.config/pocket-nes/my-pocket-nes-release.p12" \
  -storetype PKCS12 \
  -alias pocket-nes \
  -keyalg RSA -keysize 4096 -validity 10000
```

Create a private properties file at `$HOME/.config/pocket-nes/release-signing.properties` with these fields, using your keystore's absolute path and credentials:

```properties
storeFile=/absolute/path/to/my-pocket-nes-release.p12
storePassword=YOUR_KEYSTORE_PASSWORD
keyAlias=pocket-nes
keyPassword=YOUR_KEY_PASSWORD
```

For the PKCS12 example, use the keystore password as the key password. Keep both the keystore and properties file outside the repository, restrict their permissions, and retain a secure backup of the key. To use a different properties file, set `POCKET_NES_SIGNING_PROPERTIES` to its absolute path.

Build and verify the signed release:

```sh
./tools/build-release.sh
```

The Gradle release APK is written to `app/build/outputs/apk/release/app-release.apk`. The builder copies the versioned APK and `SHA256SUMS` into `build/release/` for upload. It uses the release build type with R8 and resource shrinking; the JNI bridge remains available to the native emulator. The APK is non-debuggable and uses the supplied private signing key. The first beta's APK is `build/release/pocket-nes-1.0.0-beta.1.apk`.

Each update must use the same signing key as the installation it updates. Increase `versionCode` for each published update and set `versionName` to the user-facing release version. APKs signed with independently generated keys cannot update official installations.

For a candidate or reproducible build of another release, set both version
inputs before running the builder. Replace the example placeholders with the
approved version name and numeric code from the plan or `release.json`:

```sh
export POCKET_NES_VERSION_NAME='RELEASE_VERSION'
export POCKET_NES_VERSION_CODE='RELEASE_VERSION_CODE'
./tools/build-release.sh
```

For official builds also set `POCKET_NES_SIGNING_CERT_SHA256` to the established
certificate fingerprint. An independent builder uses their own key and can
verify its fingerprint, but MUST NOT describe that APK as an official update.
The builder fails if packaged version metadata or the configured signing
certificate does not match its inputs. Preserve `release.json` and check the
APK itself, rather than inferring its embedded version from its filename.

## Source and licenses

The [tagged source](https://github.com/culpen90/nes/tree/v1.0.0-beta.1) includes the Android frontend, build files, complete vendored FCEUmm core, and the original demo's generator. GitHub's source archives for this tag contain the corresponding source used for the APK.

- [Source ZIP](https://github.com/culpen90/nes/archive/refs/tags/v1.0.0-beta.1.zip)
- [Source tar.gz](https://github.com/culpen90/nes/archive/refs/tags/v1.0.0-beta.1.tar.gz)

Pocket NES and the FCEUmm frontend/core are licensed under GPL version 2 or later; see [COPYING](../COPYING) and [third-party provenance](../third_party/README.md). The original **Star Garden** code and artwork are CC0; see [demo notes](demo.md). The release includes the original demo and no commercial game ROMs.
