package com.lumi.player.data

/**
 * One direct child of a folder inside a picked documents tree: a file or a
 * subfolder. [childUri] is the child's own uri as a string - the walker
 * recurses into subfolders through it and stores files through it.
 */
data class FolderChild(
    val displayName: String,
    val isDirectory: Boolean,
    val childUri: String,
)

/** A supported audio file found by [collectAudioFiles]. */
data class ScannedSong(
    val displayName: String,
    val uri: String,
)

/**
 * The audio file extensions the player can decode (ExoPlayer core: mp3,
 * aac/m4a, flac, wav). Scans of a picked folder skip every other file, so
 * a folder of documents does not turn into a pile of dead entries.
 */
val SUPPORTED_AUDIO_EXTENSIONS: Set<String> =
    setOf("mp3", "m4a", "aac", "flac", "wav")

/** Whether [displayName] ends in a supported audio extension (case-insensitive). */
fun isSupportedAudioFile(displayName: String): Boolean {
    val dot = displayName.lastIndexOf('.')
    if (dot < 0 || dot == displayName.length - 1) return false
    return displayName.substring(dot + 1).lowercase() in SUPPORTED_AUDIO_EXTENSIONS
}

/**
 * Walks a picked documents tree recursively (pre-order depth-first: a
 * subfolder is descended into right where it is listed, before the folder's
 * later children) and returns every supported audio file at any depth of
 * subfolder. [childrenOf] lists the direct children of the
 * folder identified by the (string) uri it is given - the tree uri itself
 * for the root - so the recursion is testable without a content resolver.
 *
 * Each folder uri is walked at most once: a listing that contains its own
 * folder again (a provider quirk, or a misbuilt child uri) must not loop
 * the walk forever - the repeat is skipped and the walk still terminates
 * with the file found once.
 */
fun collectAudioFiles(
    rootUri: String,
    childrenOf: (folderUri: String) -> List<FolderChild>,
): List<ScannedSong> {
    val songs = mutableListOf<ScannedSong>()
    val walkedFolders = mutableSetOf<String>()
    fun walk(folderUri: String) {
        if (!walkedFolders.add(folderUri)) return
        for (child in childrenOf(folderUri)) {
            if (child.isDirectory) {
                walk(child.childUri)
            } else if (isSupportedAudioFile(child.displayName)) {
                songs += ScannedSong(displayName = child.displayName, uri = child.childUri)
            }
        }
    }
    walk(rootUri)
    return songs
}

/**
 * The rescan decision for a folder that was added earlier: re-walks the tree
 * (every subfolder included) and returns only the supported audio files whose
 * uri is NOT already in [storedPayloads] (the content uris already in the
 * content list). Files that are already stored are skipped, and a folder with
 * no new songs returns an empty list - the rescan no-op. The walk shares
 * [collectAudioFiles], so the new songs come back in the same order the
 * initial add would have stored them.
 */
fun rescanNewSongs(
    rootUri: String,
    storedPayloads: Set<String>,
    childrenOf: (folderUri: String) -> List<FolderChild>,
): List<ScannedSong> =
    collectAudioFiles(rootUri, childrenOf).filter { it.uri !in storedPayloads }
