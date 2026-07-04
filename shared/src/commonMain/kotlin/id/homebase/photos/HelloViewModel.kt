package id.homebase.photos

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Batch-0 throwaway: proves StateFlow + suspend cross the native boundary on both platforms. */
class HelloViewModel : ViewModel() {
    private val _state = MutableStateFlow("Homebase Photos — shared layer is live")
    val state: StateFlow<String> = _state.asStateFlow()

    suspend fun ping(): String = "pong from shared"
}
