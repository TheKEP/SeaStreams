package com.seastreams.tv.usenet.session

import com.seastreams.tv.usenet.archive.JoinedData

/**
 * Keeps one full read-ahead window per release (ARCHITECTURE §10 Read-ahead): every volume starts with read-ahead
 * suspended, and only the volume being played runs its window. When playback crosses into another volume (or seeks
 * there) the previous one's queued downloads are cancelled, so a multi-volume RAR or `.001` split never multiplies
 * its in-flight articles by its volume count.
 */
class ReadAheadFocus(volumes: List<PostedVolume>) {
    private var active: PostedVolume? = null

    init {
        volumes.forEach { it.file.suspendReadAhead() }
    }

    fun focus(volume: PostedVolume) {
        val previous = synchronized(this) {
            if (active === volume) return
            active.also { active = volume }
        }
        previous?.file?.suspendReadAhead()
        volume.file.resumeReadAhead()
    }

    /** Follows [data]'s reads from volume to volume. */
    fun follow(data: JoinedData) {
        data.onVolumeChange = { volume -> (volume as? PostedVolume)?.let(::focus) }
    }

    fun activeVolume(): PostedVolume? = synchronized(this) { active }
}
