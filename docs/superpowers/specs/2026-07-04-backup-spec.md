# Homebase Photos — Backup spec (owner approval draft)

**Status: APPROVED by owner 2026-07-04 — D1–D5 all as recommended.** The
schema-before-upload gate is cleared; backup code may be written against this
spec. Scope decided by owner: **full auto-backup service, Android first**; UI
stays beautiful-minimalistic; everything modular + strict TDD.

Protocol source of truth: **chat-kmp** (pin `e67130cd`), NOT the official Odin
Photos app. Where this doc says "match the library", it means: match the real
rows already synced from baggins.demo.rocks into our local index.

---

## 1. What one backed-up photo writes to the drive

Target: Photos drive `alias 6483b7b1-f71b-d43e-b689-6c86148668cc`,
`type 2af68fe7-2fb8-4896-f39f-97c59d60813a`, appId `32f0bdbf-017f-4fc0-8004-2d4631182d1e`
(all dashed-canonical, chat-kmp `Uuid.toString()` format).

| Field | Value |
|---|---|
| `fileType` / `dataType` | `0` / `0` |
| `uniqueId` | **[D1]** deterministic UUID from SHA-256 of original bytes (first 16 bytes → UUID). Cross-device dedup key. |
| `userDate` | **[D2/D3]** EXIF `DateTimeOriginal` millis. TZ: use EXIF `OffsetTimeOriginal` when present; else interpret wall-clock in the device's timezone. No EXIF at all → MediaStore `DATE_TAKEN`, else `DATE_ADDED`. |
| `content` | **[D4]** `{ camera: {make, model}, captureDetails: {geolocation: {latitude, longitude, altitude}}, originalFileName }` — exposureTime/fNumber/iso/focalLength **deferred** (our copied EXIF reader doesn't extract them; nullable display-only fields, nothing sorts on them). |
| `previewThumbnail` | inline base64 webp, maxDim 20 / ≤768 B (copied `tinyThumbSize` generator), tagged with the **original** pixel dims. |
| payload `dflt_key` | the original file **byte-for-byte** ("Original quality"), encrypted. ContentType mirrored from whatever the real library rows carry (verified by the §2 gate, not assumed). |
| payload thumbnails | webp tiers **maxDim 300** and **maxDim 1200** (the 225×300 / 900×1200 classes the existing library carries and the grid/viewer already request). Generated via the copied `createThumbnails()` with custom tier instructions — no fork. |
| ACL | `requiredSecurityGroup: owner` |
| `tags` | `[]` (no album; albums are a later batch) |
| encryption | per-file KeyHeader, the exact chat-kmp attachment-upload encryption path |

Videos: **out of scope** for this wave (chat-kmp's video path transcodes to HLS —
wrong for original-quality backup; needs its own path later).

## 2. Format-verification gate (before the first real upload)

A jvmTest builds the full upload descriptor for a fixture image, then diffs it
field-by-field against a **real photo row** pulled from the synced library
(header JSON shape, thumbnail classes, payload descriptor, ACL). Any unexplained
divergence fails the test. This is how "match the existing library" is enforced
mechanically, not by eyeball. First on-device upload goes to the real drive only
after this gate is green, and is then re-synced and rendered back in our own
timeline as the end-to-end proof.

## 3. Pipeline (all logic in `shared`, strict TDD, test-first)

```
MediaStore crawler ──► dedup ledger check ──► PhotoFileBuilder ──► Outbox ──► DriveOutboxUploader ──► ledger record
 (Android, new)      (KeyValue: assetId→     (new: EXIF + thumbs   (copied,     (copied: retries,      (assetId→fileId)
                      fileId + content hash)   + descriptor)        durable)      failure classes)
```

- **Copied, already in-tree with tests**: Outbox + OutboxSync + failure
  classifier + DriveOutboxUploader, the whole upload envelope
  (UploadInstructionSet/DescriptorBuilder/Manifest), KeyHeader encryption,
  EXIF reader (`readImageMetadata`), `createThumbnails()`.
- **New (this wave)**: MediaStore crawler, dedup ledger, PhotoFileBuilder,
  BackupManager state machine, WorkManager scheduling, BackupViewModel.
- **BackupManager** (shared): drives backfill newest-first + incremental
  catch-up; exposes `StateFlow<BackupUiState>` — `enabled`, `done/total`,
  `currentItem`, `lastError`. One-shot events on a SharedFlow. TDD:
  state-machine tests with fake crawler/uploader before any real wiring.
- **Scheduling (Android)**: WorkManager — expedited one-shot for "back up now"
  + periodic incremental worker. **[D5]** MVP constraint default: any network,
  no charging requirement (demo-friendly); Wi-Fi-only toggle is a fast follow.
- **[D6 — owner amendment, 2026-07-04 late]** Backup is **folder-selective**
  (Google-Photos "device folders" model): the crawler enumerates device folders
  (MediaStore buckets) with photo counts, the user selects which folders back
  up, the selection persists, and **the default is NO folders selected** — so
  enabling backup uploads nothing until folders are deliberately chosen. This
  supersedes the whole-camera-roll backfill and is also the safety mechanism
  (no racy mid-backfill stops).
- **iOS**: pipeline is shared-ready by construction; PHAsset crawler + BGTask
  land in the next wave.

## 4. UI (minimalistic, modular, TDD)

One backup status surface on the timeline (replaces today's dead FAB): a compact
card/row — toggle, progress `done/total`, last-backed-up time. Stateless
component + stateful wrapper over `BackupViewModel`, testTags on every
interactive element, one Compose UI flow test. Nothing else — no settings
screen this wave.

## 5. Test plan

1. Unit (jvm, TDD-first): PhotoFileBuilder (EXIF fixtures incl. no-EXIF,
   no-GPS, HEIC), dedup ledger, BackupManager state machine, D2/D3 date rules.
2. §2 format-diff gate against a real library row.
3. Integration: upload envelope against Ktor MockEngine (copied tests already
   cover the transport; we add the photos-descriptor case).
4. Compose UI flow test for the backup card.
5. On-device (Redmi): enable backup → newest photos upload → force refresh →
   the uploaded photo renders in the timeline from the drive (the loop-closer).

## 6. Explicitly out of scope this wave

Video backup, iOS crawler/BGTask, Wi-Fi/charging constraint UI, albums,
multi-account, quota handling, re-upload healing beyond outbox retries.

---

## Decisions for owner approval

| # | Decision | Recommendation |
|---|---|---|
| D1 | `uniqueId` derivation | content hash (SHA-256→UUID); survives cross-device dedup |
| D2 | EXIF wall-clock TZ | `OffsetTimeOriginal` if present, else device timezone |
| D3 | No-EXIF fallback | MediaStore `DATE_TAKEN` → `DATE_ADDED` |
| D4 | `content.captureDetails` | ship geolocation only; exposure fields deferred |
| D5 | MVP backup constraints | any network, no charging gate; Wi-Fi toggle later |

Approve as-is, or mark changes inline — code starts on approval.
