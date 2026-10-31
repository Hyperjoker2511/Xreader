package app.xreader

import android.app.Application
import app.xreader.core.error.CrashGuard
import app.xreader.di.AppContainer
import app.xreader.di.AppImageLoader
import coil.ImageLoader
import coil.ImageLoaderFactory

/**
 * Application entry point (PLAN §3): installs the crash guard first
 * (§1.5), then builds the single [AppContainer] (manual DI, §1.2).
 *
 * v1.2.0 (audit finding 3): also Coil's [ImageLoaderFactory] —
 * Coil invokes [newImageLoader] the first time any image loads,
 * which is necessarily after [onCreate] has built the container,
 * so the tuned loader (see [AppImageLoader]) is installed
 * app-wide with no race against the first image request.
 */
class XreaderApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader =
        AppImageLoader.build(this, container.totalRamMb())
}
