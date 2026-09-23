package com.mtbanalyzer.clips

/**
 * Which clip, independent of how it is reached: one on this phone, or one on a paired
 * recorder. Two phones number their MediaStore rows independently, so a bare id cannot
 * say which of the two it means.
 */
sealed class ClipRef {

    /** The MediaStore id on the phone the clip lives on. */
    abstract val id: Long

    /**
     * A stable string for persisting state against this clip (compare sync, caches).
     * A local key is the bare id, which is what compare state was keyed on before remote
     * clips existed, so pairs already remembered on the phone survive. Remote keys carry a
     * prefix so they can never collide with a local one.
     */
    abstract val key: String

    data class Local(override val id: Long) : ClipRef() {
        override val key: String get() = id.toString()
    }

    data class Remote(val recorderId: String, override val id: Long) : ClipRef() {
        init {
            require(recorderId.isNotEmpty() && ':' !in recorderId) { "Bad recorder id: $recorderId" }
        }

        override val key: String get() = "$REMOTE_PREFIX$recorderId:$id"
    }

    companion object {
        private const val REMOTE_PREFIX = "r:"

        /** The inverse of [key], or null for anything [key] cannot have produced. */
        fun fromKey(key: String): ClipRef? {
            if (!key.startsWith(REMOTE_PREFIX)) {
                return key.toLongOrNull()?.takeIf { it >= 0 }?.let { Local(it) }
            }
            val rest = key.removePrefix(REMOTE_PREFIX)
            val split = rest.lastIndexOf(':')
            if (split <= 0) return null
            val recorderId = rest.substring(0, split)
            if (':' in recorderId) return null
            val id = rest.substring(split + 1).toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return Remote(recorderId, id)
        }
    }
}
