package org.wearabs.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.wearabs.WearAbsApp
import org.wearabs.net.LoginFailedException

data class LoginUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null
) {
    val canSubmit: Boolean
        get() = !busy && serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

class LoginViewModel : ViewModel() {

    private val container = WearAbsApp.container()
    private val api = container.api

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun setServerUrl(value: String) {
        _state.value = _state.value.copy(serverUrl = value.trim(), error = null)
    }

    fun setUsername(value: String) {
        _state.value = _state.value.copy(username = value.trim(), error = null)
    }

    fun setPassword(value: String) {
        _state.value = _state.value.copy(password = value, error = null)
    }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.value = current.copy(busy = true, error = null)

        viewModelScope.launch {
            try {
                val previous = container.authStore.current
                api.login(current.serverUrl, current.username, current.password)

                // Item ids are per-server, so anything downloaded for another
                // account would be meaningless — and confusing — here.
                val now = container.authStore.current
                val switchedAccount = previous != null && now != null &&
                    (previous.serverUrl != now.serverUrl || previous.username != now.username)
                if (switchedAccount) container.repository.clearLocalLibrary()
                // The session is now stored; drop the password from memory and let
                // the navigation react to AuthStore.session.
                _state.value = LoginUiState(
                    serverUrl = current.serverUrl,
                    username = current.username
                )
            } catch (e: LoginFailedException) {
                _state.value = current.copy(busy = false, password = "", error = e.message)
            } catch (e: IOException) {
                _state.value = current.copy(
                    busy = false,
                    error = "Cannot reach server. Check the address and Wi-Fi."
                )
            }
        }
    }
}
