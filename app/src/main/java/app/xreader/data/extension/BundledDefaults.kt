package app.xreader.data.extension

/**
 * v1.3.0: the BUILT-IN default sources — the first layer of
 * the three-layer source model the app agreed with its user
 * (built-in verified defaults · provider packs through the
 * repo channel · the paste-a-link learner as safety net).
 *
 * A site earns a place here ONLY by passing the full bar at
 * bundle time — catalogue ≥10 real books, a coherent book,
 * ≥98% of its stated chapter total (or the gap explained by
 * the site's own numbering), two chapters of real prose, and
 * search returning a known title — driven live against the
 * real site (build-logs/V130-BUILD-REPORT.md carries the
 * per-site verdicts and the capture evidence under
 * fixtures-capture/). A site that fails any leg is NOT here;
 * it stays reachable through the learner and packs, and the
 * report says why.
 *
 * Every config is maintainer-verified ([DeclarativeConfig
 * .maintainerVerified]) and parameterised through the family
 * builders in [BundledFamilies] — the same recipe, written
 * once per site family, instantiated per site (the
 * family-template support of plan E2). Endpoints and shapes
 * are FACTS read off the live sites and off research into
 * other readers' provider tables (facts are not code; the
 * GPL sources consulted were never copied).
 *
 * This file is the Wave-1 assembly point: the verification
 * harness's PASS verdicts land here as one [Spec] per site.
 */
object BundledDefaults {

    /**
     * One bundled default before it is instantiated: a stable
     * config id (the row's identity — never rename one that
     * has shipped), the display name, and the builder that
     * produces the verified config for the row's id/name.
     */
    data class Spec(
        val configId: String,
        val name: String,
        val build: (id: String, name: String) -> DeclarativeConfig
    )

    /** Every bundled default, in Explore display order. */
    val SPECS: List<Spec> = BundledWave1.SPECS

    /**
     * v1.5.4 (finding #3 close-out, spec §0.3): the bundled
     * DEFINITIONS EPOCH — bumped by any release that must move
     * every bundled-provenance stored definition onto the
     * shipped one, whatever fingerprint that stored definition
     * happens to carry. [RepoManager.installBundledDefaults]
     * records the epoch it has applied beside each bundled
     * package (a sidecar file in the package dir) and replaces
     * the stored definition of any bundled row whose recorded
     * epoch is older than this value; a fresh install is
     * stamped at birth, and a row whose stored definition
     * already IS the shipped one is only stamped. Epoch 1 is
     * the v1.5.4 definition set.
     *
     * This is the ONLY decision input on the bundled refresh
     * path. It replaces the reconstructed-fingerprint gate
     * retired in v1.5.4 (see [SHIPPED_FINGERPRINTS]): a stored
     * hash that matched no reconstructed historical value
     * stranded a shipped definition forever — the owner's
     * BrightNovel kept its v1.5.2 DOM-anchor rules through
     * the whole v1.5.3 release and its catalogue shape-missed
     * on a signed-in page full of data. Nothing may
     * reintroduce a fingerprint-membership condition here.
     */
    /**
     * v1.5.7 (Scope E): epoch 2 — BotiTranslation's bundled
     * definition changed shape (the rendered /mobile/ recipe
     * retired for the site's direct JSON API recipe, see
     * KnownSites.botiConfig), so every bundled-provenance
     * BOTI row still carrying the v1.5.5/v1.5.6 definition
     * must move onto the shipped one on update. The epoch is
     * the mechanism's own decision input for exactly this
     * case; rows already at the shipped definition are only
     * stamped, as ever.
     */
    /**
     * v1.5.12 (Front 1, S3): epoch 3 — BrightNovel's bundled
     * definition gained its suspicious-empty floor
     * (ChapterListSpec.totalCount = 1 in
     * BundledSingles.brightNovel — the ONLY bundled-config
     * change in this release), so every bundled-provenance
     * BrightNovel row still carrying the hint-less definition
     * must move onto the shipped one on update.
     */
    /**
     * v1.5.36 (F4): epoch 4 — the mandatory companion to
     * the Repair-protection fix (F3). On v1.5.35 a Repair
     * could overwrite any single-config bundled row's
     * stored rules in place (the owner's Xnovel; BOTI and
     * every bundled single carried the same exposure), and
     * that overwrite persists across relaunches while the
     * recorded epoch stays current. The bump makes the
     * bundled pass displace any Repair-saved bundled
     * definition wholesale with the shipped one on first
     * launch — permissions re-derived, enabled state and
     * the owner's books untouched — healing installs this
     * build has never seen, sight unseen.
     */
    /**
     * v1.5.37 (combined build): epoch 5 — Fenrir's bundled
     * definition gained its decoy-strip remove list
     * (BundledSingles.fenrirRealm chapter spec), so installs
     * that already took epoch 4 must displace the stored
     * Fenrir definition with the shipped one on first
     * launch, same wholesale-heal terms as epoch 4.
     */
    /**
     * v1.5.39 (item 2): epoch 6 — Xnovel leaves the shipped
     * set (its Spec is gone from BundledWave1 and its name
     * from SeedCatalog), so every bundled row re-stamps
     * against a set that no longer contains it, and the
     * pass's retirement step below removes the stored
     * Xnovel row itself. No other bundled definition
     * changes in this release.
     */
    const val BUNDLED_DEFINITIONS_EPOCH = 6

    /**
     * v1.5.39 (item 2): bundled packages RETIRED from the
     * shipped set — package ids ("bundled.<configId>") whose
     * installed rows the bundled pass now removes on its
     * ordinary startup run (see RepoManager
     * .installBundledDefaults — the one deliberate exception
     * to that pass's never-delete rule, reaching only ids
     * named here). Xnovel is the first: added in v1.5.35,
     * its chapter text never once read on the owner's phone
     * across four builds of fixes, and the owner's standing
     * order (2026-11-07) was "if xnovel not working remove
     * it". Stored books stay in Library/History with their
     * downloaded chapters; opening one meets the app's
     * existing source-gone answer ("the source is no longer
     * installed" — ExtensionService.resolveBookSource).
     */
    val RETIRED_BUNDLED_PACKAGE_IDS: Set<String> =
        setOf("bundled.bundled-xnovel")

    /** The instantiated configs (maintainer verification is
     * stamped here so no builder can forget it). */
    fun configs(): List<DeclarativeConfig> = SPECS.map { spec ->
        val config = spec.build("bundled-${spec.configId}", spec.name)
        // v1.3.4 (T6): mirror identity registers whenever the
        // defaults are instantiated, so chapter identity is
        // host-canonical before any repair or merge can run.
        MirrorHosts.register(config.baseUrl, config.backupHosts)
        if (config.maintainerVerified) config
        else config.copy(maintainerVerified = true)
    }

    /**
     * RETIRED as a decision input (v1.5.4, spec §0.3): the
     * fingerprints of bundled definition lists shipped in
     * earlier app versions, per config id. Through v1.5.3
     * this map was the refresh allow-list — a stored bundled
     * row refreshed ONLY while its stored definition hashed
     * to one of these values. That gate is gone: the refresh
     * decision is the definitions epoch now
     * ([BUNDLED_DEFINITIONS_EPOCH]), because a stored hash
     * that matched no reconstructed value stranded a shipped
     * definition permanently (finding #3). The values stay
     * in the tree as diagnostics evidence — a stored row's
     * hash can still be compared against them when a report
     * asks which vintage an install came from — but no code
     * path may consult them to decide a refresh.
     */
    /** v1.5.2 (§8.1 fingerprint append): the fingerprints shipped
     * in v1.5.1 — computed from the v1.5.1 tree's bundled
     * definitions (the tree this build started from; FenrirRealm's
     * entry is its pre-kindField shape, i.e. its true v1.5.1
     * fingerprint). An untouched row still carrying one of these
     * hashes refreshes to the current shipped definition; a
     * user-Repaired row's hash is absent by construction and the
     * row is never touched. (v1.3.0–v1.5.0 definition sets are not
     * recoverable from this tree — no git history ships with it —
     * so the append covers the v1.5.1 population exactly; see the
     * build report's named limitation.) */
    /**
     * v1.5.2 (FIX-11): the fingerprint every shipped
     * release actually wrote for each bundled source,
     * recomputed by replaying each release APK's own
     * builders through its own serialiser schema
     * (v1.4.1, v1.4.2, v1.4.3, v1.4.4, v1.5.0, v1.5.1 —
     * the six vintages whose artefacts execute
     * off-device; v1.4.0/v1.3.4 are R8 builds whose
     * sets could not be replayed — see the v1.5.2
     * build report). A stored row whose sha256 is in
     * its source's set is an untouched shipped row
     * and refreshes; anything else is the user's own.
     */
    /** v1.5.3 append: the fingerprints v1.5.2 itself wrote,
     * for every config this build changes (items 4, 9, 11,
     * 12, 13, 14, 17) — plus the two sources v1.5.2 shipped
     * with no earlier vintage to append (bundled-lnt,
     * bundled-scrollerspub, new keys below). Derived by
     * running the install path's own code
     * (BundledDefaults.configs() →
     * DeclarativeFingerprints.encode) over the pre-edit
     * bundled definitions and hashing with Hashes.sha256Hex;
     * because WS-2's §0 schema additions landed in the tree
     * first (encodeDefaults = true serialises them), the
     * v1.5.2 canonical form was recovered by removing exactly
     * those four added keys (DeclarativeConfig
     * .coverDenyPatterns/.illustrations, DetailsSpec
     * .jsonCover/.jsonCoverTemplate — the complete v1.5.3
     * schema delta) from the parsed canonical tree before
     * hashing. Full derivation table:
     * build-logs/V153-WS3-FINGERPRINT-DERIVATION.txt. */
    val SHIPPED_FINGERPRINTS: Map<String, Set<String>> = mapOf(
        "bundled-asianovel" to setOf("78d41c903975ea285fa39dd5db849e1b6178a85efeaa60b5b0a9108cd43ce9d8"),
        "bundled-brightnovel" to setOf("f34b8af22729199bfe5182b712e4a1e46b79f26d73f81a71766329c0681b54b7", "ea67e4eb62d721926523314812b0ac2b34abbbe14fc651246b3a69943ef5979d"),
        "bundled-chikari" to setOf("032f3b59499377a6dc4a5c795fec4e58d13a70d06e0a8f14750669021d2ce967", "d8b42db2b4dde71e9a5c3a31888ee624e4044f7c79935207695df32ad67eaf12", "0d521f50d6b3f023851f53b089fed015f09be737104bb95056de2ff695062423"),
        "bundled-fanmtl" to setOf("d66f4766f0876f5b329a73423c80f72c06178afd2cee9b5d0fab82a7b6a72491"),
        "bundled-faqwiki" to setOf("7b872b27071769ee68e9373e532bfcdcf839c1cbef6ad2efcd0c73061b97577e"),
        "bundled-fenrirealm" to setOf("70a84e57f9d448186c296507d864f4cc5914650d62057a73201aa755e40b08de", "87f540da2bb8f398c85f7f80e34dc7f51a3cfaa4d902fc9d6032c5d6b4a19aeb", "9ffe6985ecaeb9f1c18d99ed7b73f8b5c089318e9e64d624456e6b091dbb5f15"),
        "bundled-freewebnovel" to setOf("8fbb6ceb8d82bbca5f8732f1fa35e5b8fe18fda39dda568f3f906f5389d965fa"),
        "bundled-hiraethtranslation" to setOf("a3b605e94db7b0162df4c359daca8b2fbed4322c310d039ad31e2ff191f29f9c", "d69d7b632db73e76a3a3dd4b1654aa1d093dfcdc3c87ec16ddda04efa0348f45"),
        "bundled-libread" to setOf("ffb5e726faf18306e3a14ca4b40f5206312535785695df5816fe57017bd58d66"),
        "bundled-mtlnovel" to setOf("5813b0ec7a358e67e2b5de7198b761c82e8e27c9e58019984792e15898eb1903"),
        "bundled-nobadnovel" to setOf("39c986a94752ef3bf547c42a0d96d0a6063127c3d0452c73a1439354e0f6fba2", "f066314feac366d8e09b75a035a6980d5a75f6eafabfb1061379ad8240589edb"),
        "bundled-novelarchive" to setOf("24c1a36f3b953712f852b76f708784f12d38d4a1ca2843533ed5d58e56bc89b1", "87e808452e5fa54c538a78945cab422e21d95f2e0d66794608abdd4bc9d3a54c"),
        "bundled-novelbin" to setOf("bfc8f9284e580da1759580551405a634eea2d250dbdea9b5dded1b2f57996c7d"),
        "bundled-novelbuddy" to setOf("8061d7e756f7bcb0e216d501cff2df3a1bd37443db12dd1e36690b2497573d06"),
        "bundled-novelfire" to setOf("c8aeae5931bf50c7e9942354862ed9250716a35c95ab60db36c27857e78b4554", "9826c647c2043c6a5608886a56be6ee3a5f16b7584df9cf309cb3c8f97832b7d"),
        "bundled-novelfull" to setOf("931bd356a706398950faf1b8a866eb9b0f2da53cbdfa09b1c2b4b3c054a3a9a8"),
        "bundled-novelfull-net" to setOf("ad9bf38d1b7451176a8c4fdfe0c23c0d7156529437280ed74ce432dc97a18edf"),
        "bundled-noveli" to setOf("67de52405a1d07c465577db6a3a96ae3775d22f75b15fd92367a25a2451bc86e", "2c2444695d3bda6c0de7e92db9f479547d43a4ca6bf1960b0bb108fd50fd9a44"),
        "bundled-novelight" to setOf("b1575cf7725e2f86a542f4a64c542be1d4d6144cfdc9354c98a6850fdbdcd81b"),
        "bundled-novelphoenix" to setOf("b743d57e7563cd8eaef7794d6fb8c660fe6c2b1e7c9e16b33e45c4c70e0b74bd", "194cb08f16c39ca61742d1e90fc1d5f1038e13a8d1cba5c9f07d49d5ee6da9ec"),
        "bundled-ranovel" to setOf("ede691c6b817c868445d9a0ec5f8bf652134dc3af62e380491e59c8999f9b49a", "90fe8288be0ad16e86a3149f274fcb5033af0ad0e6eb4fcda96cd0886856abfa"),
        "bundled-royalroad" to setOf("01f907bacb9f7bb2a7f8486b7a68a959f7c860d78d0a027a5a9e76d6850069d3", "5dfec978d2e758222fdc770b9977ed7aa91c720d4210db9ce7a95d38377f6225"),
        "bundled-scribblehub" to setOf("42b6948927c59c51d6dce21460f4d42c8d52ef91870fba823dd89cb7e3a2bc8e"),
        "bundled-sonicmtl" to setOf("0e0792ef5e21c9cb74ed28bb6c034b86f61ae61a7846c3b1471f52b815c3b82d", "dd241d00901c46f9fabc9bd8fba8c83ed31d9c41ebf73f2426809c5f4d9d1bb9"),
        "bundled-syosetu" to setOf("9741da3bb7cf9509ff0c2486350515d0d3b462199802f5b10e4d04a0199d6401"),
        "bundled-wetriedtls" to setOf("43f51d5307ce2ac161eb4c70f17ccfe3aefe9acc275c5c2ddc46410298267aa2", "8936a2dfe52edd7f00c7c3f9a5254e34d02d5f866146fa4e60ccfe1fafee0946"),
        "bundled-wntl" to setOf("5078eebbe4cca653babfb212ce9d88cc71c414d79855d3b54344e6dfd6ad050e", "52cf65ee25704ddcfe27ebe399b452649d661e18f08b7fb0b7f241b5c403e530"),
        "bundled-woopread" to setOf("afbe6deeabdf52ddacab0fcacf880ec2d058689ef5db2172b29026d9b343d39d"),
        "bundled-wordrain69" to setOf("fa39314c8811b5aef039b1cd40afbdc5706c0e9d49e7319f46a58f6e3fa74801", "34c35e698333a4f623caf0e49fe204783b7b4bcbac742da938e259e12bf5b5da"),
        "bundled-wuxiabox" to setOf("40181fd9882d6ad93e9b01680825bc81a505b23e1ef4a6e1552e5b7cdca9f6b4"),
        // New keys in v1.5.3: sources first shipped by
        // v1.5.2 itself, so v1.5.2's fingerprint is their
        // first superseded vintage.
        "bundled-lnt" to setOf("dbb3374fb3dcdbec56cee6e94f0580de90e95c42410ebb27a12f24a4051f9ee7"),
        "bundled-scrollerspub" to setOf("69e97fc059b51d4650e14844b14104bc75f386a692a633f3c5bedb43928c482f")
    )
}
