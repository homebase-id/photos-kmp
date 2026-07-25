package id.homebase.photos.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

/**
 * The album file's decrypted `appData.content` JSON.
 *
 * Official Odin Photos writes `{name, description?, tag}` (photo-app AlbumProvider.ts) —
 * `coverFileId` is our owner-approved extension, which the official app ignores and carries
 * through its own edits. Every field is optional so a half-written or future-shaped row still
 * parses.
 */
@Serializable
data class AlbumContent(
    val name: String? = null,
    val description: String? = null,
    val tag: String? = null,
    val coverFileId: String? = null,
)

/** Lenient reader — unknown official-app fields are ignored, garbage yields null. */
private val albumJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Writer for FRESH content only: absent fields drop out instead of serializing as null. */
private val albumContentWriter = Json { encodeDefaults = false }

internal const val FIELD_NAME = "name"
internal const val FIELD_TAG = "tag"
internal const val FIELD_COVER = "coverFileId"

internal fun parseAlbumContent(raw: String?): AlbumContent? =
    raw?.let { runCatching { albumJson.decodeFromString<AlbumContent>(it) }.getOrNull() }

/**
 * Album tags (and our coverFileId) are minted as bare hex — official `getNewId()` strips the
 * dashes — but a dashed value is just as valid on the wire, so accept either.
 */
internal fun parseLenientUuid(raw: String?): Uuid? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { Uuid.parse(trimmed) }.getOrNull()
        ?: runCatching { Uuid.parseHex(trimmed) }.getOrNull()
}

/** Content JSON for a brand-new album: `{"name":…,"description"?:…,"tag":"<bare hex>"}`. */
internal fun newAlbumContentJson(name: String, tag: Uuid, description: String? = null): String =
    albumContentWriter.encodeToString(
        AlbumContent.serializer(),
        AlbumContent(
            name = name,
            description = description?.takeIf { it.isNotBlank() },
            tag = tag.toHexString(),
        ),
    )

/**
 * Rewrite an EXISTING album's content, editing only [edits] and keeping every other key —
 * including fields the official app added that we don't model. A null edit value removes the key.
 * [tag] is only written when the existing content has no usable one (never overwrites theirs;
 * losing the tag would orphan every member photo).
 */
internal fun patchAlbumContent(
    existing: String?,
    tag: Uuid,
    edits: Map<String, String?>,
): String {
    val base = existing
        ?.let { runCatching { albumJson.parseToJsonElement(it).jsonObject }.getOrNull() }
        ?: JsonObject(emptyMap())
    val merged = LinkedHashMap<String, JsonElement>(base)
    for ((key, value) in edits) {
        if (value == null) merged.remove(key) else merged[key] = JsonPrimitive(value)
    }
    val existingTag = (merged[FIELD_TAG] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (parseLenientUuid(existingTag) == null) merged[FIELD_TAG] = JsonPrimitive(tag.toHexString())
    return JsonObject(merged).toString()
}
