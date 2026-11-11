package app.xreader.data.extension.player

import android.app.Application
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
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
 * v1.4.3 (F1, the field diagnosis's trunk): the current Keiyoushi core
 * also resolves its SERIALISERS from Injekt — `keiyoushi.utils`
 * holds `val jsonInstance: Json = Injekt.get()`, a `protoInstance`
 * and an XML instance the same way, and every `parseAs` in every
 * new-generation extension defaults to them. Komikku's AppModule
 * registers all three; this module originally didn't, so a source
 * could install, open and show its settings, then die on its FIRST
 * data call with Injekt's unregistered-type error (rendered to the
 * user as "it asked the player for something the player doesn't
 * provide" — the Comix card). The three registrations below mirror
 * the donor's configuration exactly (Json's flags matter: extension
 * DTOs rely on unknown-key tolerance and explicit-nulls-off).
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
        // v1.4.3 (F1): the serialisers the Keiyoushi core resolves
        // from Injekt — Komikku's AppModule registrations, mirrored.
        addSingletonFactory<Json> {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
        addSingletonFactory<ProtoBuf> { ProtoBuf }
        addSingletonFactory<XML> {
            XML {
                defaultPolicy {
                    ignoreUnknownChildren()
                }
                autoPolymorphic = true
                xmlDeclMode = XmlDeclMode.Charset
                indent = 2
                xmlVersion = XmlVersion.XML10
            }
        }
    }
}

/**
 * Process-wide registration. Called from AppContainer's
 * construction — which XreaderApp.onCreate performs before anything
 * else can run — so registration always precedes any extension class
 * load (K3's loader only ever runs after the container exists).
 * RE-ENTRANT by design: every call imports the module into the
 * CURRENT Injekt scope (importModule overwrites factories in the
 * active scope — donor semantics), so a call that follows a
 * patchInjekt() reset genuinely re-registers instead of trusting a
 * stale flag. [isRegistered] reports whether an import has
 * happened; it is a diagnostic, never a gate on registering.
 */
object PlayerRegistrar {

    @Volatile
    private var registered = false

    fun isRegistered(): Boolean = registered

    fun register(application: Application, networkHelper: NetworkHelper) {
        synchronized(this) {
            // v1.5.3 (finding #1): NO patchInjekt() here. The
            // patch REPLACES the global Injekt scope with a fresh
            // empty one, so it must happen exactly once per
            // process: AppContainer performs it before BOTH
            // registrars run (spec §5.8 — the donor's App.onCreate
            // order). When each registrar patched for itself, the
            // second patch wiped the first module's registrations
            // wholesale (the "0 ready" Keiyoushi shelf).
            //
            // v1.5.3 (gate-repair, round-17 sift claim 8): NO
            // `if (registered) return` early-return either. The
            // flag was a one-way latch: after any later
            // patchInjekt() replaced the scope, register()
            // no-oped on the stale flag and left the fresh scope
            // EMPTY while isRegistered() read true — the exact
            // split the v1.5.3 gate's bootstrap test caught when
            // an earlier AppContainer test in its Robolectric
            // sandbox had set the flag. Re-import is harmless
            // within a scope (factories are overwritten with the
            // latest construction's instances) and REQUIRED after
            // a patch, so register() now always imports.
            Injekt.importModule(PlayerModule(application, networkHelper))
            registered = true
        }
    }
}
