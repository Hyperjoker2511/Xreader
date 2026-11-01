package app.xreader.data.extension.player

import android.app.Application
import dev.mihon.injekt.patchInjekt
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory

/**
 * K2 (Keiyoushi player): the Injekt module for the player — the donor's
 * AppModule shape reduced to what real extensions ask Injekt for (K0
 * evidence, all three sampled APKs): the Application and the
 * NetworkHelper. K6 adds the third registration the donor carries,
 * JavaScriptEngine (Komikku's AppModule registers it as a singleton
 * factory over the app context): the K6 survey found no current index
 * extension referencing it, but a JS-using source arriving in a later
 * repo update resolves it from here — or constructs it directly with
 * the registered Application, which also works.
 *
 * The Application is registered typed as [Application] (the lookup
 * extensions perform) and IS the per-extension wrapper
 * ([PlayerApplicationContext]). The NetworkHelper is registered typed
 * as the base [NetworkHelper] — HttpSource resolves exactly that type —
 * via a singleton factory returning the player's prebuilt instance,
 * the same registration shape Komikku uses.
 *
 * Anything else an extension requests fails its lookup with the missing
 * type named (Injekt's own exception), which K3's loader surfaces as the
 * source's FAILED state — honest and diagnosable, per the plan.
 */
class PlayerModule(
    private val app: Application,
    private val networkHelper: NetworkHelper
) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingleton(app)
        addSingletonFactory<NetworkHelper> { networkHelper }
        addSingletonFactory<JavaScriptEngine> { JavaScriptEngine(app) }
    }
}

/**
 * One-time, process-wide registration. Called from AppContainer's
 * construction — which XreaderApp.onCreate performs before anything
 * else can run — so registration always precedes any extension class
 * load (K3's loader only ever runs after the container exists).
 * Idempotent: later calls are no-ops.
 */
object PlayerRegistrar {

    @Volatile
    private var registered = false

    fun isRegistered(): Boolean = registered

    fun register(application: Application, networkHelper: NetworkHelper) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            // The mihonapp injekt fork's patch: swaps the default
            // registrar for its thread-safe one. Komikku calls this
            // first in App.onCreate, before any importModule.
            patchInjekt()
            Injekt.importModule(PlayerModule(application, networkHelper))
            registered = true
        }
    }
}
