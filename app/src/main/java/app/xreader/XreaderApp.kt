package app.xreader

import android.app.Application
import app.xreader.core.error.CrashGuard
import app.xreader.di.AppContainer

/**
 * Application entry point (PLAN §3): installs the crash guard first
 * (§1.5), then builds the single [AppContainer] (manual DI, §1.2).
 */
class XreaderApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        container = AppContainer(this)
    }
}
