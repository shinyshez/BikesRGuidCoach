package com.mtbanalyzer.viewer

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import java.io.Closeable

/**
 * Bumps [signal] whenever the video collection changes. That covers a recording finishing
 * (CameraX clears IS_PENDING, an update MediaStore notifies), an import, and a delete, with
 * no hook in the recording code. The streams re-query and diff, so the observer's noise
 * while a recording is being written is harmless.
 */
class MediaStoreWatcher(context: Context, private val signal: ClipChangeSignal) : Closeable {

    private val resolver = context.applicationContext.contentResolver
    private val thread = HandlerThread("viewer-link-mediastore").apply { start() }
    private val observer = object : ContentObserver(Handler(thread.looper)) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = signal.bump()
    }

    init {
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    override fun close() {
        resolver.unregisterContentObserver(observer)
        thread.quitSafely()
    }
}
