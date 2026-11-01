package app.xreader.data.extension.player

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import java.io.File

/**
 * K2 (Keiyoushi player): the Application extensions receive via Injekt.
 *
 * K0's Asura disassembly shows what a source does with the injected
 * Application: `getSharedPreferences(name, mode)`, `getCacheDir()`,
 * `startActivity(intent)`. This wrapper answers those three the player's
 * way and delegates everything else to the real app:
 *
 * - SharedPreferences are namespaced per extension (see
 *   [PlayerContextPolicy.prefsFileName]) — one extension's stored tokens
 *   and settings can never collide with another's or with the app's own
 *   preference files. K5's preference editor reads/writes the same files.
 * - The cache dir (and files dir) are per extension, under
 *   `keiyoushi/<namespace>/` — the "one returns java.io.File (cache dir)"
 *   helper in Asura lands in its own sandbox.
 * - startActivity passes through [startActivityGate]: only bare
 *   ACTION_VIEW http(s) intents leave the app (opened by the system,
 *   with NEW_TASK added since an Application context requires it);
 *   everything else is refused and recorded, never thrown — a source
 *   that fire-and-forgets an intent must not crash the read path.
 *
 * Everything not overridden delegates through attachBaseContext to the
 * real Application, exactly like the donor's arrangement (Komikku
 * registers its real Application). One honest limit, named for K3/K4:
 * `applicationContext` on this wrapper resolves to the REAL app, so an
 * extension that deliberately routes its calls through
 * `applicationContext` escapes the namespace. The direct calls — the
 * ones K0 proved sources make — are namespaced.
 */
class PlayerApplicationContext(
    private val base: Application
) : Application() {

    init {
        attachBaseContext(base)
    }

    /** The funnel extension startActivity calls pass through. */
    val startActivityGate = StartActivityGate()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        base.getSharedPreferences(
            PlayerContextPolicy.prefsFileName(
                PlayerContextState.currentNamespace(), name
            ),
            mode
        )

    override fun getCacheDir(): File =
        File(
            base.cacheDir,
            "${PlayerContextPolicy.NAMESPACE_ROOT}/" +
                PlayerContextPolicy.cacheDirName(PlayerContextState.currentNamespace())
        ).apply { mkdirs() }

    override fun getFilesDir(): File =
        File(
            base.filesDir,
            "${PlayerContextPolicy.NAMESPACE_ROOT}/" +
                PlayerContextPolicy.cacheDirName(PlayerContextState.currentNamespace())
        ).apply { mkdirs() }

    override fun getDir(name: String, mode: Int): File =
        base.getDir(
            "${PlayerContextPolicy.NAMESPACE_ROOT}_" +
                "${PlayerContextPolicy.sanitize(PlayerContextState.currentNamespace())}_$name",
            mode
        )

    override fun startActivity(intent: Intent) {
        startActivityChecked(intent, null)
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        startActivityChecked(intent, options)
    }

    private fun startActivityChecked(intent: Intent, options: Bundle?) {
        val allowed = startActivityGate.allow(
            action = intent.action,
            scheme = intent.data?.scheme,
            hasComponent = intent.component != null,
            description = intent.toString()
        )
        if (!allowed) return
        // An Application-context start needs NEW_TASK; add it to a copy
        // so the caller's intent object is never mutated.
        val forwarded = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (options != null) {
            base.startActivity(forwarded, options)
        } else {
            base.startActivity(forwarded)
        }
    }
}
