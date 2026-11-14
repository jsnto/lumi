# Lumi v0.3.1 — release notes

**One-time cutover — please read first.** This release is signed with Lumi's
permanent release key. Earlier builds were signed with a throwaway debug key
that changed with every build, so Android treats this one as coming from a
different developer and **cannot update an old install in place**. If you have
an earlier lumi version installed: uninstall it once (this wipes the app's
data on the device — registered tags, song lists and settings — your music
files are not touched), then install this build fresh. From now on, updates
install normally through the app's Update area.

**What changed**

- Releases are now signed with one permanent signing key instead of a
  throwaway debug key that changed on every build. Each release is recognized
  as the same app, the in-app update works quietly between releases, and the
  Play Protect "harmful app" false alarms caused by the changing developer
  identity are gone after the first clean install.
- Version 0.3.1 (version code 4).

**Download:** the `lumi-app-release.apk` asset on this release. Install it
with the device's built-in installer (allow "Install unknown apps" for the
app or file manager you open it from, if prompted).
