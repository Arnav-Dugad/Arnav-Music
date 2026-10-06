package com.arnav.music

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.arnav.music.core.firebase.Analytics
import com.arnav.music.core.firebase.CloudSync
import com.arnav.music.core.firebase.FirebaseGate
import com.arnav.music.core.firebase.RemoteConfigRepository
import com.arnav.music.core.settings.SettingsRepository
import com.arnav.music.di.appModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class ArnavApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@ArnavApp)
            modules(appModule)
        }
        // Cloud work is deferred off the launch path; the UI never waits on Firebase.
        val scope: CoroutineScope = get()
        scope.launch(Dispatchers.Default) {
            val gate: FirebaseGate = get()
            gate.ensure()
            val settings: SettingsRepository = get()
            val analytics: Analytics = get()
            launch { settings.settings.map { it.analytics }.distinctUntilChanged().collect { analytics.setEnabled(it) } }
            launch { settings.settings.map { it.weeklyRecapNotification }.distinctUntilChanged().collect { com.arnav.music.core.notify.RecapWorker.schedule(this@ArnavApp, it) } }
            get<RemoteConfigRepository>().refresh()
            get<CloudSync>().schedulePeriodic()
        }
    }

    /** Artwork pipeline: shared OkHttp, 25% memory cache, 256 MB disk cache, gentle crossfade. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { get<OkHttpClient>() })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork").toOkioPath()).maxSizeBytes(256L * 1024 * 1024).build() }
        .crossfade(220)
        .build()
}
