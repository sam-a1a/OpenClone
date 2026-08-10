# OpenClone

Install a second copy of an app you already have. Open OpenClone, find the app,
tap it. A few seconds later the clone shows up in your launcher with its own
icon, its own data, and its own notifications.

## How it works

Android identifies an app by its package name, so a clone is just the same APK
under a different one. OpenClone does that rewrite on the device:

1. **Read** the installed app's APK (and any config splits) straight from
   `/data/app`.
2. **Rewrite** the compiled `AndroidManifest.xml` — the package name and the
   handful of things that are implicitly relative to it.
3. **Copy** every other entry through byte-for-byte, still compressed.
4. **Sign** the result with APK Signature Scheme v2.
5. **Install** it through a `PackageInstaller` session.

Steps 3–5 are a single streaming pass. Entries are never decompressed, the
content digest is computed as the bytes go past, and the output is written
directly into the installer session — so nothing is ever staged to disk and a
clone costs about one sequential read plus one sequential write. On a 173 MB
APK that measures at roughly 1 GB/s.

### The manifest rewrite

Changing `package` is the easy half. On its own it produces an APK that either
refuses to install or dies on launch, because so much of a manifest is
implicitly relative to the package name:

| What | Why it has to change |
| --- | --- |
| Component `android:name` | Qualified against the **old** package — the classes still live in the untouched dex. `.App` becomes `com.example.app.App`; an already-absolute name is left alone. |
| Provider authorities | A device-wide unique key. Colliding with the still-installed original fails the install with `INSTALL_FAILED_CONFLICTING_PROVIDER`. |
| Declared permissions | Also device-wide unique; a collision is `INSTALL_FAILED_DUPLICATE_PERMISSION`. Every reference to a renamed permission is updated with it. |
| `sharedUserId` | Dropped. Sharing a uid requires the signing certificate a clone cannot have. |
| `taskAffinity` | Renamed, so the clone gets its own entry in Recents instead of merging into the original's task. |
| `testOnly` | Dropped — the installer UI has no way to pass `-t`. |
| `android:label` | Retargeted to "App (2)" so the two are distinguishable in the launcher. |

`resources.arsc` is deliberately **not** touched. The resource table's package
name is allowed to differ from the manifest's — that is exactly the mechanism
behind Gradle's `applicationId` and aapt's `--rename-manifest-package`.

Uncompressed entries keep their alignment across the rewrite: 4 bytes
generally, but a page boundary for uncompressed `.so` files (preserving 16 KiB
where the source had it). Losing that would leave the clone unable to map its
own native libraries.

### Signing

Clones are signed with a single RSA-2048 key generated once per device and kept
in app-private storage. It has to be stable: Android treats the signing
certificate as part of a package's identity, so re-cloning an app to pick up a
newer version counts as an *upgrade* only if it is signed with the same key. A
fresh key per clone would force an uninstall and take the clone's data with it.

The self-signed certificate is emitted as DER by hand. The JDK will generate a
key pair but not a certificate to go with it, and the usual answer to that —
BouncyCastle — would add several megabytes to an APK that is currently around
two.

## Limitations

These are inherent to re-signing an app, not bugs:

- **Apps that check their own signature** will notice. Anything using Play
  Integrity, SafetyNet, or a hand-rolled signature check may refuse to run,
  and Google account login inside a clone generally will not work.
- **`FileProvider` sharing may break** in apps that hardcode their authority
  string in code rather than deriving it from `getPackageName()`. The authority
  has to be renamed or the clone cannot install at all.
- **Data is not copied.** A clone starts empty, which is the point.
- **Play Protect** may block a sideloaded clone of an app targeting an old SDK.
  Preinstalled system apps are exempt from that gate; a clone of one is not.
- **Cloning a clone** works and numbers upward (`clone2` → `clone3`).

## Requirements

Android 13 (API 33) or newer. OpenClone needs "Install unknown apps" — it asks
before doing any work, not after.

## Building

```
./gradlew assembleRelease
```

Release is signed with the local debug key so the output installs directly. The
APK is about 2 MB.

## Layout

```
apk/      Pure-JVM engine, no Android imports: ZIP reader/writer, binary XML
          parser, manifest rewriter, certificate generator, v2 signer.
clone/    Android side: app discovery, icon loading, signing key storage,
          foreground service, install-result handling.
ui/       Compose screen.
```

The `apk/` package has no Android dependencies on purpose — it can be run and
verified on a desktop JVM against real APKs, which is how the rewrite and the
signature were validated against `aapt2`, `apksigner` and `zipalign`.
