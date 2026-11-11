package app.xreader.data.extension

import android.webkit.JavascriptInterface
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.5.5 (§2/§3): the Kotlin half of the in-WebView fetch and
 * mint machinery — the @JavascriptInterface bridge and the
 * injected scripts that report through it.
 *
 * Amendments 2 law, kept in one place: NO fetch result is
 * ever read from evaluateJavascript's return callback (a
 * Promise serialises to "{}" there). Every in-context answer
 * — endpoint fetch, in-context mint, token harvest — travels
 * through [Bridge.onResult] / [Bridge.onHarvest] into a
 * CompletableDeferred on the Kotlin side. All injected JS is
 * strict ES6 (the Android 9 WebView has no `?.` or `??`), and
 * every script is wrapped so it can never throw into the
 * page's own JavaScript.
 *
 * The payload parsing below (`org.json`) is deliberately
 * separated from the WebView glue so the JVM harness can
 * drive this seam with scripted payloads — the real WebView
 * round-trip itself is device-bound and reported as such.
 */
object InContextBridge {

    /** The @JavascriptInterface object name inside the page. */
    const val INTERFACE_NAME = "XreaderBridge"

    /** The read-token path the harvest hook watches for
     * (FenrirRealm's mint endpoint; the hook matches by path
     * so a host change cannot silently disarm it). */
    const val READ_TOKEN_PATH = "/api/read-token"

    /** Parse one bridge fetch answer: a JSON object
     * `{status: Int, body: String}` produced by [fetchScript].
     * Null when the payload is not that shape. */
    fun parseFetchResult(payload: String): InContextFetchResult? =
        runCatching {
            val json = JSONObject(payload)
            InContextFetchResult(
                status = json.optInt("status", -1),
                body = json.optString("body", "")
            )
        }.getOrNull()

    /** Parse one harvest report: `{status, body, token}` —
     * the token already extracted page-side from the cloned
     * mint answer, or from the storage scan. Returns the
     * harvest when a non-blank token rode in; null otherwise
     * (a mint answer without a token is still classified by
     * the coordinator from the raw wire answer, via
     * [parseFetchResult]). */
    fun parseHarvest(payload: String): TokenHarvestResult? =
        runCatching {
            val json = JSONObject(payload)
            val token = json.optString("token", "").trim()
            if (token.isEmpty()) return null
            TokenHarvestResult(
                token = token,
                status = json.optInt("status", -1),
                body = json.optString("body", "")
            )
        }.getOrNull()

    /** Fish a bearer token out of a mint answer body — the
     * same shapes the OkHttp mint accepts (a token/tokenJwt/
     * jwt/access_token string field, or a bare token body).
     * Shared by the harvest parser and the coordinator's
     * in-context mint so both roads read one definition. */
    fun extractTokenFromMintBody(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        runCatching {
            val json = JSONObject(trimmed)
            for (field in TOKEN_FIELDS) {
                val value = json.optString(field, "").trim()
                if (value.isNotEmpty()) return value
            }
        }
        // A bare-token answer: short, single-line, no markup.
        if (trimmed.length in 8..4096 && '\n' !in trimmed &&
            '<' !in trimmed && ' ' !in trimmed
        ) {
            return trimmed
        }
        return null
    }

    private val TOKEN_FIELDS =
        listOf("token", "tokenJwt", "jwt", "access_token", "accessToken")

    /**
     * The in-context fetch script (strict ES6): runs fetch()
     * for [url] from the parked page's origin and reports the
     * answer through the bridge under [callId]. The declared
     * [headers] ride verbatim (the §3 transport switch copies
     * the source's custom headers into the call); the XHR
     * markers the endpoints demand are set by the caller.
     */
    fun fetchScript(
        callId: String,
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>
    ): String {
        val headerPairs = JSONArray()
        for ((name, value) in headers) {
            val pair = JSONArray()
            pair.put(name)
            pair.put(value)
            headerPairs.put(pair)
        }
        val bodyLiteral =
            if (body == null) "null" else JSONObject.quote(body)
        return """
            (function () {
              try {
                var headers = {};
                var pairs = $headerPairs;
                for (var i = 0; i < pairs.length; i++) {
                  headers[pairs[i][0]] = pairs[i][1];
                }
                var opts = {
                  method: ${JSONObject.quote(method)},
                  headers: headers,
                  credentials: 'include'
                };
                var body = $bodyLiteral;
                if (body !== null) { opts.body = body; }
                fetch(${JSONObject.quote(url)}, opts).then(function (r) {
                  return r.text().then(function (t) {
                    return { status: r.status, body: t };
                  });
                }).then(function (res) {
                  try {
                    window.$INTERFACE_NAME.onResult(
                      ${JSONObject.quote(callId)},
                      JSON.stringify(res)
                    );
                  } catch (e) {}
                }).catch(function (err) {
                  try {
                    window.$INTERFACE_NAME.onResult(
                      ${JSONObject.quote(callId)},
                      JSON.stringify({
                        status: 0,
                        body: String(err)
                      })
                    );
                  } catch (e) {}
                });
              } catch (e) {
                try {
                  window.$INTERFACE_NAME.onResult(
                    ${JSONObject.quote(callId)},
                    JSON.stringify({ status: 0, body: String(e) })
                  );
                } catch (e2) {}
              }
            })();
        """.trimIndent()
    }

    /**
     * The read-token harvest hook (strict ES6), injected at
     * DOCUMENT START (WebViewCompat.addDocumentStartJavaScript
     * where available, onPageStarted as the old-WebView
     * fallback). It clones window.fetch and XMLHttpRequest so
     * any /api/read-token response is reported to Kotlin with
     * its body — and it scans web storage for the minted
     * token, because the sanctioned reader page keeps it
     * there. Hard rules: responses are CLONED before reading
     * (the page's own reader is never disturbed), fetch inputs
     * that are Request objects are read via input.url, and
     * every step is try/catch-wrapped so the hook can never
     * throw into the page's own JS.
     */
    fun tokenHarvestHookScript(): String = """
        (function () {
          try {
            if (window.__xrTokenHook) { return; }
            window.__xrTokenHook = true;
            var PATH = '$READ_TOKEN_PATH';
            function isMintUrl(u) {
              try {
                return typeof u === 'string' &&
                  u.indexOf(PATH) !== -1;
              } catch (e) { return false; }
            }
            function report(status, body) {
              try {
                var token = '';
                try {
                  var parsed = JSON.parse(body);
                  var fields = ['token', 'tokenJwt', 'jwt',
                    'access_token', 'accessToken'];
                  for (var i = 0; i < fields.length; i++) {
                    var v = parsed[fields[i]];
                    if (typeof v === 'string' && v.length > 0) {
                      token = v;
                      break;
                    }
                  }
                } catch (e) {}
                window.$INTERFACE_NAME.onHarvest(JSON.stringify({
                  status: status, body: body, token: token
                }));
              } catch (e) {}
            }
            function scanStorage() {
              try {
                var stores = [window.localStorage,
                  window.sessionStorage];
                for (var s = 0; s < stores.length; s++) {
                  var store = stores[s];
                  if (!store) { continue; }
                  for (var i = 0; i < store.length; i++) {
                    var key = store.key(i);
                    if (!key) { continue; }
                    var lk = key.toLowerCase();
                    if (lk.indexOf('readtoken') === -1 &&
                        lk.indexOf('read_token') === -1 &&
                        lk.indexOf('read-token') === -1) {
                      continue;
                    }
                    var value = store.getItem(key);
                    if (value && value.length > 0) {
                      report(200, JSON.stringify({ token: value }));
                      return;
                    }
                  }
                }
              } catch (e) {}
            }
            var origFetch = window.fetch;
            if (typeof origFetch === 'function') {
              window.fetch = function (input, init) {
                var url = '';
                try {
                  url = (typeof input === 'string')
                    ? input
                    : (input && input.url) || '';
                } catch (e) {}
                var promise = origFetch.apply(this, arguments);
                if (isMintUrl(url)) {
                  promise.then(function (response) {
                    try {
                      var clone = response.clone();
                      clone.text().then(function (text) {
                        report(response.status, text);
                      }).catch(function () {});
                    } catch (e) {}
                    return response;
                  }).catch(function () {});
                }
                return promise;
              };
            }
            var origOpen = window.XMLHttpRequest &&
              window.XMLHttpRequest.prototype.open;
            var origSend = window.XMLHttpRequest &&
              window.XMLHttpRequest.prototype.send;
            if (origOpen && origSend) {
              window.XMLHttpRequest.prototype.open = function (
                method, url
              ) {
                try { this.__xrUrl = url; } catch (e) {}
                return origOpen.apply(this, arguments);
              };
              window.XMLHttpRequest.prototype.send = function () {
                var xhr = this;
                try {
                  if (isMintUrl(xhr.__xrUrl || '')) {
                    xhr.addEventListener('load', function () {
                      try {
                        report(
                          xhr.status,
                          xhr.responseText || ''
                        );
                      } catch (e) {}
                    });
                  }
                } catch (e) {}
                return origSend.apply(this, arguments);
              };
            }
            scanStorage();
            setTimeout(scanStorage, 1500);
            setTimeout(scanStorage, 4000);
          } catch (e) {}
        })();
    """.trimIndent()

    /**
     * The @JavascriptInterface object pages report through.
     * One instance per WebView; calls are keyed so concurrent
     * legs never cross. All methods are non-blocking: they
     * complete the waiting deferred and return — the WebView's
     * JS thread is never held.
     */
    class Bridge {

        private val pending =
            ConcurrentHashMap<String, CompletableDeferred<String>>()

        private val harvests =
            ConcurrentHashMap<String, CompletableDeferred<String>>()

        /** Register a fetch call and return its deferred; the
         * page's answer completes it via [onResult]. */
        fun register(callId: String): CompletableDeferred<String> {
            val deferred = CompletableDeferred<String>()
            pending[callId] = deferred
            return deferred
        }

        /** Register the harvest wait for [key] (a host) and
         * return its deferred; [onHarvest] completes the FIRST
         * matching waiter. */
        fun registerHarvest(
            key: String
        ): CompletableDeferred<String> {
            val deferred = CompletableDeferred<String>()
            harvests[key] = deferred
            return deferred
        }

        fun cancel(callId: String) {
            pending.remove(callId)
        }

        fun cancelHarvest(key: String) {
            harvests.remove(key)
        }

        @JavascriptInterface
        fun onResult(callId: String, payload: String) {
            pending.remove(callId)?.complete(payload)
        }

        @JavascriptInterface
        fun onHarvest(payload: String) {
            // The harvest report carries no key: complete every
            // waiter (there is at most one parked harvest at a
            // time — the fetcher serialises) plus the process
            // sink the sanctioned screen reports into.
            val waiters = harvests.values.toList()
            harvests.clear()
            for (waiter in waiters) waiter.complete(payload)
            FenrirHarvestSink.record(payload)
        }
    }
}

/**
 * v1.5.5 (§3): the process-wide sink for tokens the SANCTIONED
 * SCREEN's own page harvested while the user was on it — the
 * reader page fires its mint during the solve itself, before
 * any app fetch runs. The coordinator checks here first on
 * the post-solve road: a token the page already minted is
 * adopted without a second mint. Keyed by the mint host,
 * timestamped, in memory only (tokens are session material;
 * the durable record stays the persisted ReadTokenStore one
 * the coordinator writes on adoption).
 */
object FenrirHarvestSink {

    private data class Entry(val result: TokenHarvestResult, val atMs: Long)

    /** The hook's report carries no URL, so the sink keeps the
     * single freshest harvest (read-token mints are rare and
     * per-install effectively single-site) plus a per-host
     * index the bridge fills when the coordinator parks a
     * harvest on a known page. */
    @Volatile
    private var latest: Entry? = null

    private val byHost = ConcurrentHashMap<String, Entry>()

    /** Record one bridge harvest report (any /api/read-token
     * answer the hook saw). Reports without a token are
     * ignored — the coordinator classifies raw answers from
     * its own roads. */
    fun record(payload: String) {
        val harvest = InContextBridge.parseHarvest(payload) ?: return
        latest = Entry(harvest, System.currentTimeMillis())
    }

    /** Record a harvest the coordinator parked on [parkUrl]'s
     * page — host-keyed, so a later mint for the same host
     * adopts exactly its own page's token. */
    fun recordForHost(host: String, harvest: TokenHarvestResult) {
        val entry = Entry(harvest, System.currentTimeMillis())
        byHost[host.lowercase()] = entry
        latest = entry
    }

    /** A harvest younger than the solve window for a mint on
     * [mintHost]: the host-keyed entry when one exists, else
     * the freshest harvest overall (the sanctioned screen's
     * page IS the mint's origin in the flows that report
     * here). Null when nothing fresh was harvested. */
    fun recentFor(
        mintHost: String,
        nowMs: Long = System.currentTimeMillis()
    ): TokenHarvestResult? {
        fun fresh(entry: Entry?): TokenHarvestResult? {
            if (entry == null) return null
            if (nowMs - entry.atMs !in
                0..SessionSolvedRegistry.SOLVE_WINDOW_MS
            ) {
                return null
            }
            return entry.result
        }
        return fresh(byHost[mintHost.lowercase()])
            ?: fresh(latest)
    }

    /** v1.5.8 (C3 / F7 F3): a fresh harvest WITH the instant
     * it was OBSERVED (the entry's own timestamp) — an
     * adopted token's expiry anchors at that instant, never
     * at adoption time, so a token the page minted minutes
     * before adoption is not re-anchored as freshly issued.
     * Same freshness law as [recentFor]. */
    fun recentObservedFor(
        mintHost: String,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<TokenHarvestResult, Long>? {
        fun fresh(entry: Entry?): Pair<TokenHarvestResult, Long>? {
            if (entry == null) return null
            if (nowMs - entry.atMs !in
                0..SessionSolvedRegistry.SOLVE_WINDOW_MS
            ) {
                return null
            }
            return entry.result to entry.atMs
        }
        return fresh(byHost[mintHost.lowercase()])
            ?: fresh(latest)
    }

    fun clearForTests() {
        latest = null
        byHost.clear()
    }
}
