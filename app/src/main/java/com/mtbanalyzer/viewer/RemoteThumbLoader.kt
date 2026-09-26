package com.mtbanalyzer.viewer

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import java.io.IOException
import java.io.InputStream

/** A recorder clip's tile image, as a Glide model. */
data class RemoteThumb(val address: RecorderAddress, val recorderId: String, val clipId: Long) {
    /** Keyed on the clip, not the URL: the token in the URL changes every Viewer Link start. */
    val cacheKey: String get() = "remote-thumb:$recorderId:$clipId"
}

/**
 * Glide's stock network path is HttpURLConnection, which the cleartext policy refuses for the
 * recorder's address exactly as it refuses media3's (Phase 2 spec §4), so thumbnails come
 * through [ViewerHttpClient] too.
 */
class RemoteThumbLoader(private val http: ViewerHttpClient) : ModelLoader<RemoteThumb, InputStream> {

    companion object {
        @Volatile private var registered = false

        /** Idempotent; called before the first remote tile binds. */
        fun register(context: Context) {
            if (registered) return
            synchronized(this) {
                if (registered) return
                Glide.get(context).registry.prepend(RemoteThumb::class.java, InputStream::class.java, Factory())
                registered = true
            }
        }
    }

    override fun buildLoadData(model: RemoteThumb, width: Int, height: Int, options: Options) =
        ModelLoader.LoadData(ObjectKey(model.cacheKey), Fetcher(http, model))

    override fun handles(model: RemoteThumb) = true

    private class Fetcher(private val http: ViewerHttpClient, private val model: RemoteThumb) : DataFetcher<InputStream> {
        @Volatile private var response: RemoteResponse? = null

        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                val remote = http.get(model.address.thumbUrl(model.clipId))
                response = remote
                if (remote.status != 200) {
                    remote.close()
                    callback.onLoadFailed(IOException("Thumbnail HTTP ${remote.status}"))
                    return
                }
                callback.onDataReady(remote.body)
            } catch (e: IOException) {
                callback.onLoadFailed(e)
            }
        }

        override fun cleanup() {
            response?.close()
        }

        override fun cancel() {
            response?.close()
        }

        override fun getDataClass() = InputStream::class.java

        override fun getDataSource() = DataSource.REMOTE
    }

    private class Factory : ModelLoaderFactory<RemoteThumb, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<RemoteThumb, InputStream> =
            RemoteThumbLoader(ViewerHttpClient())

        override fun teardown() = Unit
    }
}
