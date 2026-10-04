package com.shouzhe.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shouzhe.app.data.repository.ItemRepository
import com.shouzhe.app.domain.model.Item
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<Item> = emptyList(),
    val searching: Boolean = false,
    val searched: Boolean = false,
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: ItemRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val results = MutableStateFlow<List<Item>>(emptyList())
    private val searching = MutableStateFlow(false)
    private val searched = MutableStateFlow(false)

    init {
        // 输入防抖 250ms，避免每敲一个字就查一次
        viewModelScope.launch {
            query
                .debounce(250)
                .distinctUntilChanged()
                .collectLatest { q ->
                    if (q.isBlank()) {
                        results.value = emptyList()
                        searched.value = false
                        return@collectLatest
                    }
                    searching.value = true
                    results.value = runCatching { repo.search(q) }.getOrDefault(emptyList())
                    searching.value = false
                    searched.value = true
                }
        }
    }

    val state: StateFlow<SearchUiState> = combine(
        query, results, searching, searched,
    ) { q, r, s, done ->
        SearchUiState(query = q, results = r, searching = s, searched = done)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchUiState())

    fun onQueryChange(v: String) { query.value = v }
    fun clear() { query.value = "" }
}