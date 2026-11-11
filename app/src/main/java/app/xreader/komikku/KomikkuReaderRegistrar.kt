/*
 * Copyright 2024 The Komikku Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * FIX-18 (v1.5.2): the transplanted reader resolves its
 * dependencies through Injekt, as the donor does (ReaderActivity's
 * `private val readerPreferences = Injekt.get<ReaderPreferences>()`
 * is the standing example) and as Xreader's Keiyoushi player
 * already does. Registration follows the PlayerRegistrar pattern
 * exactly: an InjektModule + a process-wide, re-entrant registrar
 * called from AppContainer's construction. The donor's
 * AppModule registers ReaderPreferences as a singleton over its
 * PreferenceStore; here the singleton is the GLOBAL-defaults
 * ReaderPreferences over Xreader's KomikkuPreferenceStore.
 * Per-book overlays are our addition on top (option-scope rule)
 * and are built by the reader's ViewModel from the same stores —
 * they are never registered globally.
 */
package app.xreader.komikku

import app.xreader.komikku.setting.KomikkuPreferenceStore
import app.xreader.komikku.setting.ReaderPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory

class KomikkuReaderModule(
    private val globalStore: KomikkuPreferenceStore
) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory<ReaderPreferences> { ReaderPreferences(globalStore) }
        addSingletonFactory<KomikkuPreferenceStore> { globalStore }
    }
}

/** Process-wide, re-entrant registration (PlayerRegistrar pattern):
 * every call imports into the CURRENT Injekt scope; the
 * `registered` flag is a diagnostic, never a gate (see
 * PlayerRegistrar for the gate-repair rationale). */
object KomikkuReaderRegistrar {

    @Volatile
    private var registered = false

    fun isRegistered(): Boolean = registered

    fun register(globalStore: KomikkuPreferenceStore) {
        synchronized(this) {
            // v1.5.3 (finding #1): no patchInjekt() here — the
            // patch replaces the global scope, so AppContainer
            // performs it exactly once before both registrars
            // run; this registrar's patch was the one that wiped
            // the Keiyoushi player's registrations at startup.
            // v1.5.3 (gate-repair, round-17 sift claim 8): no
            // `if (registered) return` early-return either — after
            // a patch the flag would lie over an empty scope;
            // register() now always imports (importModule
            // overwrites factories in the active scope).
            Injekt.importModule(KomikkuReaderModule(globalStore))
            registered = true
        }
    }
}
