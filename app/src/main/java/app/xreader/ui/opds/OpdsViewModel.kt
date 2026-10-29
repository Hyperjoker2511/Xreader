package app.xreader.ui.opds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.core.security.KeyVault
import app.xreader.data.db.Book
import app.xreader.data.db.OpdsServer
import app.xreader.data.db.SourceType
import app.xreader.data.opds.OpdsEntry
import app.xreader.data.opds.OpdsFeed
import app.xreader.di.AppContainer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OpdsBrowseState(
    val feed: OpdsFeed? = null,
    val loading: Boolean = false,
    val errorMessage: String? = null,
    /** Navigation stack of feed URLs (root first). */
    val stack: List<String> = emptyList()
) {
    val canGoBack: Boolean get() = stack.size > 1
}

/**
 * OPDS browsing (PLAN §4): servers are added with name + address +
 * optional sign-in (password goes to the KeyVault, never Room).
 * Feeds are browsed as a stack; a book's best acquisition downloads
 * through the queue and imports on completion (the engine's hook).
 */
class OpdsViewModel(private val container: AppContainer) : ViewModel() {

    val servers: StateFlow<List<OpdsServer>> = container.database.opdsServerDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _browse = MutableStateFlow(OpdsBrowseState())
    val browse: StateFlow<OpdsBrowseState> = _browse

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var activeServer: OpdsServer? = null

    fun addServer(name: String, baseUrl: String, username: String, password: String) {
        viewModelScope.launch {
            val url = baseUrl.trim().let {
                if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
            }
            val id = container.database.opdsServerDao().insert(
                OpdsServer(
                    name = name.trim().ifBlank { "My server" },
                    baseUrl = url,
                    username = username.trim().takeIf { it.isNotEmpty() },
                    passwordRef = null
                )
            )
            if (password.isNotEmpty()) {
                container.keyVault.put(KeyVault.opdsPassword(id), password)
                val server = container.database.opdsServerDao().getById(id)
                if (server != null) {
                    container.database.opdsServerDao().update(
                        server.copy(passwordRef = KeyVault.opdsPassword(id))
                    )
                }
            }
            _messages.emit("Server added")
        }
    }

    fun removeServer(server: OpdsServer) {
        viewModelScope.launch {
            container.keyVault.remove(KeyVault.opdsPassword(server.id))
            container.database.opdsServerDao().delete(server)
            if (activeServer?.id == server.id) {
                activeServer = null
                _browse.value = OpdsBrowseState()
            }
        }
    }

    fun openServer(server: OpdsServer) {
        activeServer = server
        _browse.value = OpdsBrowseState(loading = true, stack = listOf(server.baseUrl))
        loadFeed(server.baseUrl)
    }

    fun openEntry(entry: OpdsEntry) {
        val url = entry.navigationUrl ?: return
        _browse.value = _browse.value.copy(
            loading = true,
            stack = _browse.value.stack + url,
            errorMessage = null
        )
        loadFeed(url)
    }

    fun openNextPage() {
        val next = _browse.value.feed?.nextPageUrl ?: return
        _browse.value = _browse.value.copy(
            loading = true,
            stack = _browse.value.stack + next,
            errorMessage = null
        )
        loadFeed(next)
    }

    fun goBack(): Boolean {
        val stack = _browse.value.stack
        if (stack.size <= 1) return false
        val newStack = stack.dropLast(1)
        _browse.value = _browse.value.copy(loading = true, stack = newStack, errorMessage = null)
        loadFeed(newStack.last())
        return true
    }

    /** Queues the book's best acquisition: placeholder row + FILE job;
     * the downloaded file imports on completion (AppContainer hook). */
    fun downloadBook(entry: OpdsEntry) {
        val server = activeServer ?: return
        val acquisition = entry.acquisitions.firstOrNull() ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val bookId = container.database.bookDao().insert(
                Book(
                    title = entry.title,
                    author = entry.author ?: "",
                    description = entry.summary ?: "",
                    sourceType = SourceType.OPDS,
                    remoteId = acquisition.url,
                    opdsServerId = server.id,
                    addedAt = now,
                    updatedAt = now
                )
            )
            container.downloadController.enqueueAcquisition(bookId)
            _messages.emit("Downloading “${entry.title}” — watch the Downloads tab")
        }
    }

    private fun loadFeed(url: String) {
        val server = activeServer ?: return
        viewModelScope.launch {
            when (val result = container.opdsClient.fetchFeed(server.id, url, container.opdsCredentials)) {
                is AppResult.Ok -> _browse.value = _browse.value.copy(
                    feed = result.value, loading = false, errorMessage = null
                )
                is AppResult.Err -> _browse.value = _browse.value.copy(
                    loading = false,
                    errorMessage = ErrorText.displayText(result.error)
                )
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    OpdsViewModel(container) as T
            }
    }
}
