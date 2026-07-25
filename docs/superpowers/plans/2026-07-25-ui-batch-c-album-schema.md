# Album write-schema contract — Batch C (Collections)

Read-only research output, 2026-07-25. Derived from the shipped READ path, the photo WRITE path
(PhotoFileBuilder), chat-kmp's file-mutation surface (pin `e67130cd`; files read at HEAD — the two
cited chat-kmp files show no relevant recent divergence from the copied API surface), design spec §4,
and HANDOFF.md. **This doc is the owner sign-off gate** (`discuss-schema-before-upload`): no upload
code until the Open Questions below are answered.

All paths repo-relative to the worktree root
(`/Users/biswa/Documents/GitHub/homebase-photos/.claude/worktrees/photos-ui-batch-a`) unless
prefixed `chat-kmp:` (= `/Users/biswa/Documents/GitHub/chat-kmp`).

---

## Evidence summary

### What an album IS (read path, shipped & device-QA'd)

- **Album file = `fileType 900`** on the Photos drive, read from the local DriveMainIndex:
  `shared/src/commonMain/kotlin/id/homebase/photos/data/AlbumsRepositoryImpl.kt:27-33`
  (`selectPhotosPage(fileType = PhotoConfig.ALBUM_FILE_TYPE …)`);
  `PhotoConfig.ALBUM_FILE_TYPE = 900` at
  `shared/src/commonMain/kotlin/id/homebase/photos/PhotoConfig.kt:15`.
- **The album's identity is its FIRST tag.** `AlbumMapper.fromHomebaseFile` returns null when the
  file has no tags, and takes `appData.tags.firstOrNull()` as `albumId`:
  `shared/src/commonMain/kotlin/id/homebase/photos/data/AlbumMapper.kt:18`;
  domain doc agrees: `shared/src/commonMain/kotlin/id/homebase/photos/domain/AlbumItem.kt:9-14`.
- **Album header content = JSON `{name, coverFileId}`** (decrypted string in `appData.content`),
  parsed leniently: `AlbumMapper.kt:10` (`AlbumContent(name, coverFileId)`), `:19-26`.
  `coverFileId` is a dashed-UUID string (`Uuid.parse`), `AlbumMapper.kt:25`; tests fix the exact
  shape `{"name":"Summer Trip","coverFileId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}`:
  `shared/src/commonTest/kotlin/id/homebase/photos/data/AlbumMapperTest.kt:22-23`.
- Spec §4 records the same: album = `fileType 900` "identified by a tag = the album id",
  content `{ name, coverFileId }`, membership = photo carries album-id in its `tags`:
  `docs/superpowers/specs/2026-06-21-homebase-photos-design.md:83-87`.
- **Caution — fileType 900 is overloaded:** the deferred library-metadata file is *also*
  `fileType 900` with content `{yearsWithMonths[], totalNumberOfPhotos, lastCursor}`
  (`…design.md:89-92`). Our read path only survives this because the metadata file is presumed
  untagged (AlbumMapper skips tagless rows). No album file carries payloads or a previewThumbnail
  in the read path — cover is resolved via `coverFileId` → a photo file.

### MEMBERSHIP is photo-side tags (not an album-side list)

- `loadAlbumPhotos` = **server** `queryBatch(fileType=[0], tagsMatchAtLeastOne=[albumId])`:
  `AlbumsRepositoryImpl.kt:39-43` →
  `shared/src/commonMain/kotlin/id/homebase/photos/PhotoQueries.kt:21-28`
  (`FileQueryParams.tagsMatchAtLeastOne`, defined at
  `shared/src/commonMain/kotlin/id/homebase/api/client/drives/query/FileQueryParams.kt:28`).
  Membership tags are not indexed locally (`AlbumsRepositoryImpl.kt:14-16` comment). This proves
  membership = the photo file's own `appData.tags` containing the albumId.
- Photos are currently uploaded with `tags = emptyList()`:
  `shared/src/commonMain/kotlin/id/homebase/photos/backup/PhotoFileBuilder.kt:171`.

### File-creation template (photo WRITE path — the only proven creator)

`PhotoFileBuilder.build` → `UploadFileRequest` (`PhotoFileBuilder.kt:108-188`):
- `UploadFileMetadata(allowDistribution=false, isEncrypted=true, accessControlList =
  AccessControlList(requiredSecurityGroup = SecurityGroupType.Owner.value))` (`:165-168`;
  ACL type at `shared/…/api/client/drives/ServerMetadata.kt:28`, `"owner"` at
  `shared/…/api/client/drives/files/SecurityGroupType.kt:13`).
- One random 16-byte file AES key; **file keyHeader IV encrypts metadata content only**; payloads/
  thumbs get a DISTINCT per-payload IV under the same aesKey (`:133-136`, per-payload-IV memory).
- `metadata.encryptContent(fileKeyHeader)` encrypts `appData.content`
  (`:179`; impl at `shared/…/api/client/drives/upload/UploadFileDescriptor.kt:60-76`).
- Upload API: `DriveUploadProvider.uploadFile(UploadFileRequest)`
  (`shared/…/api/client/drives/upload/DriveUploadProvider.kt:140-205`), or via outbox
  (`DriveOutboxUploader` type `UploadNewFile=1`, `shared/…/api/client/drives/files/DriveOutboxUploader.kt:434`).

### Header-mutation surface (chat-kmp / copied API)

- **Update existing file (full header replace):** `DriveUploadProvider.updateFileByFileId`
  (`DriveUploadProvider.kt:207-255`, PATCH `/drives/{driveId}/files/{fileId}`) and
  `updateFileByUniqueId` (`:257-299`, PATCH `/drives/{driveId}/files/by-uid/{uniqueId}`). Both take
  `keyHeader + FileUpdateInstructionSet + UploadFileMetadata` — the sent `appData` REPLACES the
  server header, so every field must be carried over.
- **Canonical update pattern** — chat-kmp `MomentsUserStateStore.updateFile`
  (`chat-kmp:homebase-core/src/commonMain/kotlin/id/homebase/core/moments/services/MomentsUserStateStore.kt:295-336`):
  * keyHeader: **preserve `existing.keyHeader.aesKey`, rotate IV** (`:297-300`).
  * `versionTag = existing.fileMetadata.versionTag` in the metadata (`:305`).
  * `FileUpdateInstructionSet(transferIv=rnd16, locale=UpdateLocale.Local, recipients=emptyList(),
    manifest=UpdateManifest.build(payloads=null,…))` (`:317-326`) — an **empty manifest ships the
    header only and leaves server payloads untouched** (explicit comment in
    `chat-kmp:…/MomentsPostSenderService.kt:607-609`: "an empty moment manifest would ship header only";
    payloads are only re-attached there because *peer transit* needs them).
  * `metadata.encryptContent(newKeyHeader)` before sending (`:334`).
  * Conflict handling: `OdinClientErrorCode.VersionTagMismatch` (and `ExistingFileWithUniqueId`) →
    re-fetch header, retry (`MomentsUserStateStore.kt:250-259`);
    `DriveUploadProvider.handleErrorResponse` also honors an `onVersionConflict` callback
    (`DriveUploadProvider.kt:535-560`).
- **Update of a file WITH payloads while preserving them** — chat-kmp
  `MomentsPostSenderService.addRecipientsToMoment`
  (`chat-kmp:…/MomentsPostSenderService.kt:562-634`): fetch fresh header
  (`getFileHeaderByUid`), assert versionTag, rotate IV / keep aesKey, and carry over
  `uniqueId, tags, fileType, userDate, previewThumbnail` in the new `UploadAppFileMetaData`
  (`:622-634`). This is the closest analog to "add/remove album tag on a photo".
- **Fetch fresh header before update:** `DriveFileProvider.getFileHeader(driveId, fileId)`
  (`shared/…/api/client/drives/files/DriveFileProvider.kt:83-111`) /
  `getFileHeaderByUid` (`:122`). Local-index `HomebaseFile`s are fully decrypted incl. `keyHeader`
  and `versionTag` (`shared/…/api/client/drives/HomebaseFile.kt:11-24`,
  `FileMetadata.versionTag` at `shared/…/api/client/drives/files/FileMetadata.kt:29`).
- **Delete:** `DriveFileProvider.softDeleteFile(driveId, fileId)` → POST
  `/drives/{id}/files/{fileId}/delete` (`DriveFileProvider.kt:375-405`); batch
  `deleteFiles(driveId, fileIds)` (`:440`), already used by
  `PhotosRepositoryImpl.deletePhotos` (`shared/…/photos/data/PhotosRepositoryImpl.kt:84-86`).
  Soft-deleted files read back as `fileState == Deleted` (or legacy
  `archivalStatus == Removed`) — both filtered by `HomebaseFile.isSoftDeleted()`
  (`HomebaseFile.kt:39-41`); `ArchivalStatus = None(0)/Archived(1)/Removed(2)`
  (`shared/…/api/client/drives/files/ArchivalStatus.kt:13-17`).
- **NOT the tool for membership:** `uploadLocalMetadataTags` / `uploadLocalMetadataContent`
  (`DriveUploadProvider.kt:304-392`) write **localAppData** (device-local metadata,
  `FileMetadata.localAppData`, `FileMetadata.kt:53-60`) — NOT `appData.tags`, which is what
  `tagsMatchAtLeastOne` queries. Do not use for album membership.

### No official-app fixture in either repo

No dump of a real album row created by the official Odin Photos app exists in this repo or
chat-kmp (searched `coverFileId`, `AlbumDefinition`, odin-js/homebase-web references — only our own
code/docs). The `{name, coverFileId}` shape traces to spec §4, which was derived from the owner's
real drive, and the read path passed device QA (HANDOFF.md:98-100, 190) — but whether real
official-app albums were present on-screen during that QA is not recorded.

---

## Proposed write schema (exact shapes + API calls)

All operations target the Photos drive (`PhotoConfig.DRIVE_ALIAS`), same drive object used
everywhere. All content JSON uses dashed `Uuid.toString()` (chat-kmp-source-of-truth memory).

### CREATE album

New `albumId = Uuid.random()` (dashed). Call `DriveUploadProvider.uploadFile` (direct, foreground —
no outbox needed; albums are tiny header-only files) with:

```kotlin
val keyHeader = KeyHeader.newRandom16()               // KeyHeader.kt:120
UploadFileRequest(
  driveId = photosDriveId,
  keyHeader = keyHeader,
  metadata = UploadFileMetadata(
    allowDistribution = false,
    isEncrypted = true,                                // PROPOSAL — see OQ-4
    accessControlList = AccessControlList(requiredSecurityGroup = SecurityGroupType.Owner.value),
    appData = UploadAppFileMetaData(
      uniqueId = albumId,                              // PROPOSAL: uniqueId == albumId — see OQ-3
      tags = listOf(albumId),                          // REQUIRED: first tag IS the album id
      fileType = PhotoConfig.ALBUM_FILE_TYPE,          // 900
      dataType = 0,
      userDate = nowEpochMillis,                       // PROPOSAL — albums sort by creation; see OQ-7
      archivalStatus = ArchivalStatus.None,
      content = """{"name":"<name>","coverFileId":null}""",  // coverFileId omitted when null
      previewThumbnail = null,
    ),
  ).encryptContent(keyHeader),
  payloads = emptyList(),                              // no payloads, no thumbnails
  thumbnails = emptyList(),
)
```

Content JSON (kotlinx `encodeDefaults=false` so a null cover drops out, mirroring
`photoContentSerializer`, PhotoFileBuilder.kt:52):
`{"name":"Summer Trip"}` → later `{"name":"Summer Trip","coverFileId":"<dashed-uuid>"}`.

### ADD / REMOVE photo ↔ album (membership)

Per photo file (loop for batches — no batch header-update API exists):

1. Fresh header: `driveFileProvider.getFileHeader(driveId, photoFileId)` (never trust the possibly
   stale local index for `versionTag`).
2. Build `newTags = existing.fileMetadata.appData.tags.orEmpty() ± albumId` (dedup; preserve all
   other tags — a photo can be in several albums).
3. `updateFileByFileId(UpdateFileByFileIdRequest(...))` with:

```kotlin
val kh = KeyHeader(iv = ByteArrayUtil.getRndByteArray(16), aesKey = existing.keyHeader.aesKey) // rotate IV, keep key
UpdateFileByFileIdRequest(
  driveId = driveId, fileId = existing.fileId,
  keyHeader = kh,
  instructions = FileUpdateInstructionSet(
    transferIv = ByteArrayUtil.getRndByteArray(16),
    locale = UpdateLocale.Local, recipients = emptyList(),
    manifest = UpdateManifest.build(payloads = null, toDeletePayloads = null,
                                    thumbnails = null, generatePayloadIv = false), // header-only: payloads untouched
  ),
  metadata = UploadFileMetadata(
    allowDistribution = false,
    isEncrypted = existing.serverFileIsEncrypted,
    versionTag = existing.fileMetadata.versionTag,       // MUST carry over
    accessControlList = null,                            // omit — server keeps existing ACL (chat-kmp precedent)
    appData = UploadAppFileMetaData(
      uniqueId  = existing.fileMetadata.appData.uniqueId,          // carry over EVERY field —
      tags      = newTags,                                          // header replace is wholesale
      fileType  = existing.fileMetadata.appData.fileType,           // 0
      dataType  = existing.fileMetadata.appData.dataType,           // 0
      userDate  = existing.fileMetadata.appData.userDate,
      groupId   = existing.fileMetadata.appData.groupId,
      archivalStatus = existing.fileMetadata.appData.archivalStatus,
      content   = existing.fileMetadata.appData.content,            // decrypted string from header
      previewThumbnail = existing.fileMetadata.appData.previewThumbnail,
    ),
  ).encryptContent(kh),                                  // re-encrypt content under rotated IV
  payloads = null, thumbnails = null,
)
```

On `VersionTagMismatch` → re-fetch header, rebuild, retry (bounded, per
MomentsUserStateStore.kt:250-262 pattern).

### RENAME album / SET COVER

Same update mechanism, applied to the **album file** (`updateFileByFileId` with the album's
`fileId`): carry over everything, replace only `appData.content`:
- RENAME → `{"name":"<newName>","coverFileId":<existing-or-absent>}`
- SET COVER → `{"name":"<existing>","coverFileId":"<photoFileId dashed>"}`
  (cover = a photo **fileId**, per `AlbumItem.coverFileId` / AlbumsViewModel cover resolution).
Keep `tags = listOf(albumId)` unchanged — changing the tag orphans all membership.

### DELETE album

`driveFileProvider.softDeleteFile(driveId, album.fileId)` (or `deleteFiles` for symmetry with
`deletePhotos`). Photo files are untouched — deletion of the fileType-900 file cannot touch
fileType-0 files; member photos simply keep a dangling tag (harmless: albums are enumerated from
album FILES, not from tags — `AlbumsRepositoryImpl.loadAlbums`). PROPOSAL: leave dangling tags
(no cleanup fan-out); see OQ-5.

### Post-write consistency

Album files live in the local DriveMainIndex via sync — after create/rename/delete either trigger a
drive sync or optimistically patch the repository result before `refresh()`; membership reads are
already server-side (`queryBatch`) so add/remove is visible on next `loadAlbumPhotos`.

---

## Open questions for owner

1. **OQ-1 — Verify against one real official-app album row.** Create (or find) an album with the
   official Odin Photos app on the owner's drive and dump its header (fileType, tags, uniqueId,
   isEncrypted, content JSON). Every "PROPOSAL" above should be checked against it. This is the
   single highest-value action; everything else is derived from our own spec/read path.
2. **OQ-2 — `content` field names.** `{name, coverFileId}` comes from spec §4 + our mapper; no
   official-app fixture exists in-repo. If the official app uses e.g. `{name, description}` with
   cover stored elsewhere, our rename/set-cover writes would be read back by the official app
   without the cover (lenient parsers both sides make this non-fatal, but confirm).
3. **OQ-3 — Album `uniqueId`.** NO EVIDENCE of what the official app sets. Proposal
   `uniqueId = albumId` (enables `updateFileByUniqueId` + dedups double-taps). Harmless to readers
   (nothing queries album uniqueId today) but should match the official app if it sets one.
4. **OQ-4 — Album encryption.** NO direct EVIDENCE whether official-app album files are
   `isEncrypted = true`. Proposal: encrypted (consistent with every write path in chat-kmp and our
   photo files). Read path handles either.
5. **OQ-5 — Delete semantics.** Leave member photos' dangling album tags after album delete
   (proposal), or best-effort untag every member (N header updates)? Official-app behavior unknown.
6. **OQ-6 — fileType 900 collision.** Spec assigns 900 to BOTH albums and the deferred
   library-metadata file, distinguished only by tag presence. Confirm the official app really uses
   900 for albums (vs. a distinct fileType) before we mint files the official web app might
   misparse as library metadata (and vice versa).
7. **OQ-7 — Album `userDate`.** Proposal: creation epoch millis (gives stable ordering in
   `selectPhotosPage`, which pages on userDate; null falls back to `created` server-time —
   `HomebaseFile.sqlUserDateMs`, HomebaseFile.kt:56-57). Official-app value unknown.

## Risks

- **Header replace is wholesale.** Any `appData` field dropped in an update **nulls it on the
  server** (e.g. forgetting `previewThumbnail` on a photo tag-write blanks the blur placeholder;
  forgetting `userDate` destroys timeline position). Mitigate: a single tested
  `carryOverAppData(existing, tags=…)` helper + commonTest asserting field-for-field parity.
- **Wrong keyHeader bricks payloads.** On photo updates the aesKey MUST be
  `existing.keyHeader.aesKey` (payloads/thumbnails stay encrypted under it, header-only update
  doesn't re-ship them). A fresh key would make every thumbnail/payload undecryptable. IV must
  rotate per revision (MomentsUserStateStore.kt:296-300).
- **Version conflicts under concurrency** (two devices, or backup racing an album write on the same
  photo): must implement the re-fetch-and-retry loop; a silent drop loses the membership write.
- **N-photo add/remove is N PATCH round-trips** (no batch header update in the API). Foreground
  add-to-album of a large selection needs progress + partial-failure reporting; consider capping or
  going through the outbox (`DriveOutboxUploader` type `UpdateFile=2`) for resilience.
- **Membership reads are server-only** (`queryBatch`) — offline album views won't reflect local
  writes; UI should optimistically update.
- **Interop risk with official Photos web app** until OQ-1/OQ-2/OQ-6 are answered: albums we create
  might not render (or render as "Untitled") in the official app, and theirs might not carry a
  parsable cover for us. The read path's lenient JSON + tag-presence guard bounds the blast radius.

---

# REVISED SCHEMA — SUPERSEDES "Proposed write schema" ABOVE

Owner sign-off 2026-07-25: "read the odin-photo app in the github directory. that will give u the
exact schema we had." Source of truth = `/Users/biswa/Documents/GitHub/photo-app` (official Odin
Photos app, packages/common) + `/Users/biswa/Documents/GitHub/dotyoucore-js` (js-lib). Evidence
below is exact, with file:line. Every OQ above is resolved; where the official app differs from the
earlier proposal, the official app WINS.

## Official facts (evidence)

- **Album file = fileType 400, NOT 900.** `PhotoConfig.AlbumDefinitionFileType = 400`;
  `PhotoLibraryMetadataFileType = 900` (photo-app `packages/common/src/provider/photos/PhotoTypes.ts:14-15`).
  → OUR SHIPPED READ PATH IS WRONG (`PhotoConfig.ALBUM_FILE_TYPE = 900` queries library-metadata
  files). Batch C must fix it.
- **Album appData:** `uniqueId = def.tag`, `tags = []` (EMPTY — identity is NOT in appData.tags),
  `fileType = 400`, `isEncrypted = true` (`encryptAlbums = true`), ACL Owner,
  `allowDistribution = false`, content = JSON of `{name, description?, tag}` — NO coverFileId
  (AlbumProvider.ts:56-85, PhotoTypes.ts:31-37). No payloads/thumbnails; `userDate` not set.
- **Album tag format: bare hex, no dashes.** `getNewId()` = `Guid.create().toString().replace(/-/g,'')`
  (dotyoucore-js `packages/libs/js-lib/src/helpers/DataUtil.ts:89-92`); minted per album in
  NewAlbumDialog (`saveAlbum({ tag: getNewId(), name, description })`). The bare-hex string lives in
  content.tag; server normalizes GUID params, so our Kotlin `Uuid` (dashed) API args still match.
- **Membership = album tag in the PHOTO file's appData.tags**, written via header-only patch:
  `updatePhoto` fetches fresh header, keeps versionTag, replaces `tags` with the new full array,
  `patchFile` locale 'local' (PhotoProvider.ts:74-123). Confirms the chat-kmp
  `updateFileByFileId` pattern above (keep aesKey, rotate IV, carry over ALL appData fields).
- **Album photo query:** `tagsMatchAll=[albumTag]`, `fileType=[101, 0]` (PostFileType=101,
  MediaFileType=0 — js-lib PostTypes.ts:39, MediaTypes.ts:91), `archivalStatus=[0,1,3]` for albums
  (PhotoProvider.ts:27-36,48-56). ArchivalStatus: 0 none, 1 archived, 2 bin, 3 apps.
- **Cover: NOT STORED.** `getAlbumThumbnail` = first result of the album query, maxRecords 1,
  newest-first (PhotoProvider.ts:218-254). No set-cover concept in the official schema.
- **Rename/update album:** full `uploadFile` with `overwriteFileId = def.fileId` +
  `versionTag = def.versionTag`, content re-serialized (AlbumProvider.ts:56-85). Our
  `updateFileByFileId` (PATCH + versionTag) produces the identical end state — use it.
- **Delete album:** `deleteFile(album.fileId)` ONLY — member photos keep dangling tags, no
  fan-out untag (AlbumProvider.ts:87-94). Matches "leave dangling tags". Owner asked about
  delete-by-groupId: `DriveFileProvider.deleteFilesByGroupId` EXISTS in our copied API
  (shared/…/files/DriveFileProvider.kt:475) — available, not needed for albums (photos don't carry
  albumId as groupId). No TODO required.
- **Favorites (Batch D preview):** favorite = tag `toGuidId('favorite')` on the photo
  (PhotoTypes.ts:11, PhotoProvider.ts:54).

## Binding write schema (final)

- CREATE: mint `albumTag` = bare-hex UUID (32 lowercase hex chars, no dashes). Upload via
  `DriveUploadProvider.uploadFile`: fileType 400, dataType 0, `uniqueId = albumTag`,
  `tags = emptyList()`, `userDate = null`, encrypted content
  `{"name":"<name>","description":"<desc-if-any>","tag":"<albumTag>"}` (kotlinx encodeDefaults=false
  drops absent description), no payloads/thumbnails, ACL Owner, allowDistribution=false.
- ADD/REMOVE photo↔album: per photo, fresh `getFileHeader` → newTags = existing.tags ± albumTag →
  `updateFileByFileId`, header-only manifest, keep aesKey / rotate IV, `versionTag` carried,
  ALL appData fields carried over, `encryptContent` under rotated IV. VersionTagMismatch →
  re-fetch + bounded retry.
- RENAME: `updateFileByFileId` on the album file, replace content JSON only (keep tag field +
  uniqueId + empty tags).
- DELETE: `softDeleteFile(driveId, album.fileId)`. Nothing else.
- SET COVER: OWNER-APPROVED EXTENSION (2026-07-25). `coverFileId` (dashed photo fileId string) as an
  extra field in the album content JSON: `{"name":…,"description":…,"tag":…,"coverFileId":…}`.
  The official app ignores it and preserves it through its own edits (payload/def spreads).
  Set via the same RENAME-style header update. Cover resolution: coverFileId if set, else newest
  member photo (official behavior).

## Read-path fixes (now in Batch C scope)

- `PhotoConfig.ALBUM_FILE_TYPE` 900 → 400; add `LIBRARY_METADATA_FILE_TYPE = 900` (Batch E uses it).
- `AlbumMapper`: albumId = content JSON `tag` (fallback `uniqueId`), NOT `appData.tags.firstOrNull()`;
  parse `{name, description?, tag, coverFileId?}` (all lenient); cover = coverFileId if present,
  else newest member photo.
- `loadAlbumPhotos`: archivalStatus filter should match official `[0,1,3]` if our query layer
  exposes it; fileType stays `[0]` (we don't render feed posts; divergence noted, harmless —
  official app also shows fileType-101 posts in albums).
- Verify the local DriveMainIndex sync actually indexes fileType-400 rows (it should — it indexes
  all fileTypes; the 900 constant was only a query filter).
