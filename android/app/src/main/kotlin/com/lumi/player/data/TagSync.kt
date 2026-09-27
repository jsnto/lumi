package com.lumi.player.data

/**
 * The in-sync state of the tag detail page's "Save to NFC tag" section:
 * the tag's CURRENT system payload (its name + its ordered song display
 * names - exactly what the card-write path composes) compared against the
 * tag's last known tag state (its per-uid saved memory: name + songs).
 *
 * The state is DERIVED, not event-driven: any tag edit (a rename, a song
 * added/removed/reordered) flips it to [Differs] immediately, and a
 * successful write (which records the memory) flips it to [InSync].
 */
enum class TagSyncState {
    /** The memory exists and equals the current system data: no change since the last save. */
    InSync,

    /** The memory exists but the name or the song list no longer matches: the system tag differs from the saved tag. */
    Differs,

    /** No saved memory for this tag's uid yet: nothing saved or compared yet. */
    Unknown,
}

/**
 * The comparison's verdict: [memory] is the tag's last known tag state
 * (null - no record for the uid - reads [TagSyncState.Unknown]), and
 * [name] + [songs] is the current system data in the same shape the
 * write path composes (the tag's name + its ordered song display names).
 */
fun tagSyncState(name: String, songs: List<String>, memory: TagMemory?): TagSyncState =
    when {
        memory == null -> TagSyncState.Unknown
        memory.name == name && memory.songs == songs -> TagSyncState.InSync
        else -> TagSyncState.Differs
    }
