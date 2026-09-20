package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.music.netease.NeteasePlaylist
import com.metrolist.music.netease.NeteaseRepository
import com.metrolist.music.netease.NeteaseSyncMapping
import com.metrolist.music.netease.NeteaseUser
import com.metrolist.music.netease.safeSyncError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NeteaseViewModel @Inject constructor(private val repository: NeteaseRepository) : ViewModel() {
    val binding = repository.dao.observeBinding().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val mappings = binding.flatMapLatest { current ->
        if (current == null) flowOf(emptyList<NeteaseSyncMapping>())
        else repository.dao.observeMappings(current.neteasePlaylistId, current.youtubePlaylistId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val logs = repository.dao.observeLogs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val user = MutableStateFlow<NeteaseUser?>(null)
    val sources = MutableStateFlow<List<NeteasePlaylist>>(emptyList())
    val targets = MutableStateFlow<List<PlaylistItem>>(emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val progress = MutableStateFlow<Pair<Int, Int>?>(null)

    init { refreshUser() }

    private fun run(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        error.value = null
        progress.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error.value = failure.safeSyncError()
            } finally {
                busy.value = false
                progress.value = null
            }
        }
    }

    fun refreshUser() = run { user.value = repository.auth.currentUser() }
    fun login(cookie: String) = run { user.value = repository.login(cookie) }
    fun logout() = run {
        repository.logout()
        user.value = null
        sources.value = emptyList()
        targets.value = emptyList()
    }
    fun loadPlaylists() = run {
        sources.value = repository.getUserPlaylists()
        targets.value = repository.getTargetPlaylists()
    }
    fun bind(source: NeteasePlaylist, target: PlaylistItem) = run { repository.bind(source, target) }
    fun preview() = run { repository.preview { done, total -> progress.value = done to total } }
    fun sync() = run { repository.sync { done, total -> progress.value = done to total } }
    fun allowRetry(songId: String) = run { repository.allowRetry(songId) }
}
