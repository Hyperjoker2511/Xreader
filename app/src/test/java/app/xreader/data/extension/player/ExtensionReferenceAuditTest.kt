package app.xreader.data.extension.player

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * v1.4.4 gate addition (b) — the extension-reference audit.
 *
 * The second v1.4.3 field disease (Asura's
 * NoClassDefFoundError): shared Keiyoushi extension code
 * references `kotlinx/serialization/json/okio/OkioStreamsKt`,
 * an artifact that was never on our classpath. No JVM test
 * could see it — the referencing code lives in the EXTENSION's
 * dex, not ours. This audit puts the extension's dex in the
 * room: for each pinned fixture it computes
 *
 *   referenced (every type its dex mentions)
 *   − resolvable (its own defined classes
 *                 ∪ the BUILT release APK's defined classes
 *                 ∪ the API-32 framework surface)
 *
 * and fails the build on anything left over. The fixtures are
 * real, published Keiyoushi packages — Asura Scans 1.6.71
 * (vc 106071) and Comix 1.6.60 (vc 106060), fetched from the
 * Keiyoushi index on 2026-11-02 and verified against the
 * index's published signing key
 * (9add655a…d4da2) with apksigner before pinning; their
 * SHA-256 hashes are pinned below so a swapped or stale
 * fixture fails loudly instead of auditing the wrong code.
 * Fixtures live in src/test/resources — test-only, never
 * shipped in the app.
 *
 * Gate wiring: the audit needs the BUILT release APK, so the
 * gate runs it in a second pass after assembleRelease (the
 * v1.4.4 build report documents the sequence). When the APK
 * or the fixtures are absent — a fresh clone, a tests-only
 * run — the audit SKIPS via Assume, unless XR_AUDIT_ENFORCE=1
 * is set in the environment, in which case absence is a hard
 * failure. A present-but-wrong fixture (hash mismatch) fails
 * in every mode.
 */
class ExtensionReferenceAuditTest {

    private data class Fixture(
        val fileName: String,
        val sha256: String
    )

    private val fixtures = listOf(
        Fixture(
            "tachiyomi-en.asurascans-v1.6.71.apk",
            "55fffd3ff0057fd45046e5e461abad188f55b435f8525f513a91d7564d8e01fa"
        ),
        Fixture(
            "tachiyomi-en.comix-v1.6.60.apk",
            "66d62134bebbd39f181076950682e6d3382204563726011c3907829fb2de4bd4"
        )
    )

    /**
     * The one known false positive, allowlisted BY NAME:
     * `dalvik.annotation.Signature` is a VM-internal annotation
     * type the dex format references for generic signatures.
     * The runtime synthesises it; neither the framework's API
     * surface nor any APK defines it, on any Android version.
     * (Named in V143-ASURA-CRASH-DIAGNOSIS as the audit's one
     * expected leftover.)
     */
    private val allowlist = setOf("Ldalvik/annotation/Signature;")

    private val enforce: Boolean
        get() = System.getenv("XR_AUDIT_ENFORCE") == "1"

    private fun absent(what: String) {
        if (enforce) {
            fail("extension audit enforced but $what is absent")
        } else {
            assumeTrue("extension audit skipped: $what absent", false)
        }
    }

    private fun fixtureFile(fixture: Fixture): File {
        val roots = listOf(
            File("src/test/resources/keiyoushi-fixtures"),
            File("app/src/test/resources/keiyoushi-fixtures")
        )
        return roots.map { it.resolve(fixture.fileName) }
            .firstOrNull { it.exists() }
            ?: File("src/test/resources/keiyoushi-fixtures/${fixture.fileName}")
    }

    private fun releaseApk(): File {
        val roots = listOf(
            File("build/outputs/apk/release"),
            File("app/build/outputs/apk/release")
        )
        for (root in roots) {
            val apk = root.listFiles()
                ?.filter { it.extension == "apk" }
                ?.maxByOrNull { it.lastModified() }
            if (apk != null) return apk
        }
        return File("build/outputs/apk/release/app-release.apk")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ── the minimal dex reader ──────────────────────────────
    // Just enough of the format to answer two questions per
    // dex: which types does it DEFINE (class_defs), and which
    // types does it REFERENCE (the whole type_ids table —
    // every type a dex mentions, in any signature, field,
    // method or instruction, owns a type_id).

    private class DexSets(
        val defined: Set<String>,
        val referenced: Set<String>
    )

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readUleb128(bytes: ByteArray, start: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var offset = start
        while (true) {
            val byte = bytes[offset].toInt() and 0xFF
            offset++
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return result to offset
    }

    private fun dexSets(dex: ByteArray): DexSets {
        require(
            dex.size > 0x70 &&
                dex[0] == 'd'.code.toByte() &&
                dex[1] == 'e'.code.toByte() &&
                dex[2] == 'x'.code.toByte()
        ) { "not a dex file" }
        val stringIdsSize = u32(dex, 0x38)
        val stringIdsOff = u32(dex, 0x3C)
        val typeIdsSize = u32(dex, 0x40)
        val typeIdsOff = u32(dex, 0x44)
        val classDefsSize = u32(dex, 0x60)
        val classDefsOff = u32(dex, 0x64)

        val strings = ArrayList<String>(stringIdsSize)
        for (i in 0 until stringIdsSize) {
            val dataOff = u32(dex, stringIdsOff + i * 4)
            val (_, bodyStart) = readUleb128(dex, dataOff)
            var end = bodyStart
            while (dex[end] != 0.toByte()) end++
            // MUTF-8: close enough to UTF-8 for class
            // descriptors, which are ASCII by construction.
            strings += String(dex, bodyStart, end - bodyStart, Charsets.UTF_8)
        }
        val types = ArrayList<String>(typeIdsSize)
        for (i in 0 until typeIdsSize) {
            val descriptorIdx = u32(dex, typeIdsOff + i * 4)
            types += strings[descriptorIdx]
        }
        val defined = HashSet<String>()
        for (i in 0 until classDefsSize) {
            val classIdx = u32(dex, classDefsOff + i * 32)
            defined += types[classIdx]
        }
        return DexSets(defined, types.toSet())
    }

    /**
     * Reduce a type_ids descriptor to the CLASS it needs the
     * platform to provide, or null when it needs none: array
     * descriptors resolve through their component type, and
     * the one-letter primitives are built into the VM. (The
     * diagnosis's referenced-minus-resolvable was computed
     * over class descriptors exactly this way.)
     */
    private fun classDescriptorOf(type: String): String? {
        var t = type
        while (t.startsWith("[")) t = t.substring(1)
        return if (t.length == 1) null else t
    }

    private fun apkDexSets(apk: File): DexSets {
        val defined = HashSet<String>()
        val referenced = HashSet<String>()
        ZipFile(apk).use { zip ->
            val dexEntries = zip.entries().toList()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
            require(dexEntries.isNotEmpty()) { "no dex in ${apk.name}" }
            for (entry in dexEntries) {
                val sets = dexSets(zip.getInputStream(entry).readBytes())
                defined += sets.defined
                referenced += sets.referenced
            }
        }
        return DexSets(defined, referenced)
    }

    /** The API-32 framework surface: every class the SDK's
     * api-versions.xml (android-36 platform data) lists with
     * since ≤ 32 — the user's phone runs API 32. */
    private fun frameworkSurface(): Set<String> {
        val sdk = listOf(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            "${System.getProperty("user.home")}/workspace/tools/android-sdk"
        ).firstOrNull { !it.isNullOrBlank() }
            ?: run { absent("Android SDK location"); return emptySet() }
        val xml = File(sdk, "platforms/android-36/data/api-versions.xml")
        if (!xml.exists()) {
            absent("api-versions.xml")
            return emptySet()
        }
        val surface = HashSet<String>()
        val classTag = Regex("""<class name="([^"]+)" since="(\d+)"""")
        xml.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                for (match in classTag.findAll(line)) {
                    if (match.groupValues[2].toInt() <= 32) {
                        surface += "L${match.groupValues[1]};"
                    }
                }
            }
        }
        return surface
    }

    @Test
    fun `pinned extensions reference nothing the platform cannot resolve`() {
        // Fixtures first: present-but-wrong fails in EVERY mode.
        val present = fixtures.map { it to fixtureFile(it) }
        for ((fixture, file) in present) {
            if (!file.exists()) {
                absent("fixture ${fixture.fileName}")
                return
            }
            assertTrue(
                "fixture ${fixture.fileName} hash drift — re-fetch " +
                    "from the Keiyoushi index, re-verify its cert " +
                    "against the index key, and re-pin deliberately",
                sha256(file).equals(fixture.sha256, ignoreCase = true)
            )
        }

        val hostApk = releaseApk()
        if (!hostApk.exists()) {
            absent("built release APK")
            return
        }
        // A stale APK (older than the catalog that defines the
        // platform) proves nothing about THIS platform.
        val catalog = listOf(
            File("../gradle/libs.versions.toml"),
            File("gradle/libs.versions.toml")
        ).firstOrNull { it.exists() }
        if (catalog != null && hostApk.lastModified() < catalog.lastModified()) {
            absent("fresh release APK (the one present predates the catalog)")
            return
        }

        val host = apkDexSets(hostApk)
        val framework = frameworkSurface()
        val resolvableBase = host.defined + framework

        for ((fixture, file) in present) {
            val extension = apkDexSets(file)
            // Sanity: the audit is looking at the right code —
            // both pinned extensions are KNOWN to reference the
            // artifact whose absence crashed Asura in the field.
            assertTrue(
                "${fixture.fileName} no longer references " +
                    "OkioStreamsKt — if the fixture was re-pinned, " +
                    "update this expectation deliberately",
                "Lkotlinx/serialization/json/okio/OkioStreamsKt;" in
                    extension.referenced
            )
            val unresolved = extension.referenced
                .mapNotNull { classDescriptorOf(it) }.toSet() -
                extension.defined - resolvableBase - allowlist
            assertTrue(
                "${fixture.fileName} references types the built app " +
                    "+ API-32 framework cannot resolve: " +
                    unresolved.sorted().take(25).joinToString(", ") +
                    if (unresolved.size > 25) {
                        " … and ${unresolved.size - 25} more"
                    } else {
                        ""
                    },
                unresolved.isEmpty()
            )
        }
    }
}
