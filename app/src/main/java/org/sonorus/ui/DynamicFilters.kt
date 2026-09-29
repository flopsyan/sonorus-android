package org.sonorus.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.sonorus.data.model.DynamicOptions
import org.sonorus.data.model.DynamicRules
import org.sonorus.data.model.FilterOption

/** The five filters of a dynamic playlist, in the order the page lists them. */
enum class FilterKind(val key: String, val label: String, val search: String) {
    GENRES("genres", "Genres", "Genre suchen"),
    DECADES("decades", "Jahr", ""),
    ARTISTS("artists", "Interpreten", "Interpret suchen"),
    ALBUMS("albums", "Alben", "Album suchen"),
    STARS("stars", "Bewertung", ""),
}

fun DynamicOptions.of(kind: FilterKind): List<FilterOption> = when (kind) {
    FilterKind.GENRES -> genres
    FilterKind.DECADES -> decades
    FilterKind.ARTISTS -> artists
    FilterKind.ALBUMS -> albums
    FilterKind.STARS -> stars
}

/**
 * What is ticked, per filter. The same rules as the web page: a category with
 * everything ticked is "alle", ranges add to the ticked decades, and the name
 * the server writes follows from the same state.
 */
data class FilterState(val on: Map<FilterKind, Set<Int>>, val ranges: List<List<Int>>) {

    fun ticked(kind: FilterKind): Set<Int> = on[kind].orEmpty()

    fun isAll(kind: FilterKind, options: DynamicOptions): Boolean =
        options.of(kind).all { it.id in ticked(kind) }

    fun activeCount(options: DynamicOptions): Int = FilterKind.entries.count { !isAll(it, options) }

    fun with(kind: FilterKind, ids: Set<Int>): FilterState = copy(on = on + (kind to ids))

    fun toggle(kind: FilterKind, id: Int): FilterState {
        val now = ticked(kind)
        return with(kind, if (id in now) now - id else now + id)
    }

    /** A range on top of every decade would change nothing, so it replaces them. */
    fun addRange(from: Int, to: Int, options: DynamicOptions): FilterState {
        val base = if (isAll(FilterKind.DECADES, options)) with(FilterKind.DECADES, emptySet()) else this
        return base.copy(ranges = base.ranges + listOf(listOf(minOf(from, to), maxOf(from, to))))
    }

    fun payload(options: DynamicOptions): JsonObject = buildJsonObject {
        put("ranges", JsonArray(ranges.map { r -> JsonArray(r.map { JsonPrimitive(it) }) }))
        for (kind in FilterKind.entries) {
            put(
                kind.key,
                if (isAll(kind, options)) JsonPrimitive("all")
                else JsonArray(ticked(kind).sorted().map { JsonPrimitive(it) }),
            )
        }
    }

    /** The line next to a section title, and whether it filters at all. */
    fun summary(kind: FilterKind, options: DynamicOptions): Pair<String, Boolean> {
        if (isAll(kind, options)) return "alle" to false
        val all = options.of(kind)
        val onNames = all.filter { it.id in ticked(kind) }.map { it.name }
        return when (kind) {
            FilterKind.DECADES -> {
                val decades = if (onNames.size <= 3) onNames else listOf("${onNames.size} Jahrzehnte")
                (decades + ranges.map(::rangeText)).joinToString(", ").ifEmpty { "keine" } to true
            }
            FilterKind.STARS -> starsText(ticked(kind)) to true
            else -> {
                val off = all.filter { it.id !in ticked(kind) }.map { it.name }
                when {
                    onNames.isEmpty() -> "keine"
                    onNames.size <= 2 -> onNames.joinToString(", ")
                    off.size <= 2 -> "ohne ${off.joinToString(", ")}"
                    else -> "${onNames.size} von ${all.size}"
                } to true
            }
        }
    }

    companion object {
        fun from(rules: DynamicRules?, options: DynamicOptions): FilterState {
            fun read(kind: FilterKind, e: JsonElement?): Set<Int> = when (e) {
                is JsonArray -> e.mapNotNull { it.jsonPrimitive.intOrNull }.toSet()
                else -> options.of(kind).map { it.id }.toSet()
            }
            val on = mapOf(
                FilterKind.GENRES to read(FilterKind.GENRES, rules?.genres),
                FilterKind.DECADES to read(FilterKind.DECADES, rules?.decades),
                FilterKind.ARTISTS to read(FilterKind.ARTISTS, rules?.artists),
                FilterKind.ALBUMS to read(FilterKind.ALBUMS, rules?.albums),
                FilterKind.STARS to read(FilterKind.STARS, rules?.stars),
            )
            return FilterState(on, rules?.ranges.orEmpty())
        }

        fun rangeText(r: List<Int>): String = if (r.first() == r.last()) "${r.first()}" else "${r.first()}-${r.last()}"

        fun starsText(on: Set<Int>): String {
            val nums = on.filter { it > 0 }.sorted()
            val parts = mutableListOf<String>()
            if (nums.size == 1) parts += starLabel(nums[0])
            else if (nums.size > 1) {
                val run = nums.last() - nums.first() + 1 == nums.size
                parts += if (run) "${nums.first()}-${nums.last()} Sterne" else "${nums.joinToString(", ")} Sterne"
            }
            if (0 in on) parts += "unbewertet"
            return parts.joinToString(", ").ifEmpty { "keine" }
        }
    }
}
