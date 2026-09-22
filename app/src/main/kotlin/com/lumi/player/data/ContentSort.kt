package com.lumi.player.data

/**
 * The direction the content list is sorted by title. [DESCENDING] (Z->A) is
 * the list's default order; the content pane's sort control offers both
 * directions and persists the pick.
 */
enum class ContentSortDirection {
    ASCENDING,
    DESCENDING,
}

/**
 * Reads a stored sort direction. Unset or invalid values fall back to
 * [ContentSortDirection.DESCENDING] - the content list's default.
 */
fun contentSortDirectionFromStored(value: String?): ContentSortDirection =
    when (value?.trim()?.lowercase()) {
        "asc", "ascending" -> ContentSortDirection.ASCENDING
        else -> ContentSortDirection.DESCENDING
    }

/** The settings-table value [this] direction stores under. */
fun ContentSortDirection.storedValue(): String =
    when (this) {
        ContentSortDirection.ASCENDING -> "asc"
        ContentSortDirection.DESCENDING -> "desc"
    }

/**
 * Orders [items] by their title (case-insensitive), with [id] breaking ties
 * so the order is deterministic. [ContentSortDirection.DESCENDING] is the
 * exact reverse of the ascending order. The stored order is untouched - a
 * new list is returned.
 */
fun <T> List<T>.sortedByTitle(
    title: (T) -> String,
    id: (T) -> Long,
    direction: ContentSortDirection,
): List<T> {
    val ascending = sortedWith(compareBy({ title(it).lowercase() }, { id(it) }))
    return if (direction == ContentSortDirection.ASCENDING) ascending else ascending.asReversed()
}
