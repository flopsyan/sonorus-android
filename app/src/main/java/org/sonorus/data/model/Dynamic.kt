package org.sonorus.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The filters of a dynamic playlist as the server hands them out: per category
 * the string "all" or the ids that are ticked, plus the custom year ranges.
 * JsonElement, because one field carries either shape.
 */
@Serializable
data class DynamicRules(
    val ranges: List<List<Int>> = emptyList(),
    val genres: JsonElement? = null,
    val artists: JsonElement? = null,
    val albums: JsonElement? = null,
    val decades: JsonElement? = null,
    val stars: JsonElement? = null,
)

/** One thing a filter can tick. `artist` and `year` only come with an album. */
@Serializable
data class FilterOption(
    val id: Int,
    val name: String = "",
    val artist: String = "",
    val year: Int? = null,
)

/** GET /api/dynamic-options: what each filter offers, in the order to show it. */
@Serializable
data class DynamicOptions(
    val genres: List<FilterOption> = emptyList(),
    val artists: List<FilterOption> = emptyList(),
    val albums: List<FilterOption> = emptyList(),
    val decades: List<FilterOption> = emptyList(),
    val stars: List<FilterOption> = emptyList(),
)

@Serializable
data class PlaylistTreeResponse(
    val playlist: Playlist,
    val tree: PlaylistTree = PlaylistTree(),
)

@Serializable
data class RulesResponse(
    val playlist: Playlist,
    val tracks: List<Track> = emptyList(),
    val tree: PlaylistTree = PlaylistTree(),
)
