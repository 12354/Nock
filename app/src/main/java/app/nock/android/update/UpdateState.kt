package app.nock.android.update

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    data class Downloading(val progress: Float) : UpdateState()
    data object Installing : UpdateState()
    data object UpToDate : UpdateState()
    data class Error(val message: String) : UpdateState()
}
