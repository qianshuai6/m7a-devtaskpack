package com.march7th.hkrpg.manager

import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.models.RemoteBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object RemoteAccessCoordinator {

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listener = RemoteAccessStateListener { refresh() }
    private val _state = MutableStateFlow(snapshot())

    @Volatile
    private var appSettings: AppSettingsManager? = null

    val state: StateFlow<RemoteAccessState> = _state.asStateFlow()

    private val backends = mapOf(
        RemoteBackend.ROOT to RootManager,
        RemoteBackend.SHIZUKU to ShizukuManager
    )

    fun initialize(appSettings: AppSettingsManager) {
        this.appSettings = appSettings
        if (initialized.compareAndSet(false, true)) {
            backends.values.forEach { it.addStateListener(listener) }

            scope.launch {
                val backend = configuredBackend()
                if (backend == RemoteBackend.ROOT) {
                    backends.getValue(RemoteBackend.ROOT).requestPermission()
                }
            }
        }
        refresh()
    }

    fun refresh(): RemoteAccessState {
        val current = snapshot()
        _state.value = current
        return current
    }

    fun isAvailable(backend: RemoteBackend): Boolean {
        return state.value.isAvailable(backend)
    }

    fun isGranted(backend: RemoteBackend): Boolean {
        return state.value.isGranted(backend)
    }

    suspend fun request(backend: RemoteBackend = configuredBackend()): Boolean {
        val current = refresh()
        if (current.isGranted(backend)) {
            return true
        }
        // Shizuku 的 binder 可能延迟送达；交给 ShizukuManager 内部等待并再次判定，
        // 避免这里提前返回 false 导致「授权」按钮点了没反应。
        if (!current.isAvailable(backend) && backend != RemoteBackend.SHIZUKU) {
            return false
        }
        // Shizuku 的授权回调是权威结果；服务端权限状态刷新可能滞后，
        // 旧实现在这里再查一次 isGranted，会把刚成功的授权误判为失败。
        val granted = backends.getValue(backend).requestPermission()
        refresh()
        return granted
    }

    fun configuredBackend(): RemoteBackend {
        return appSettings?.startupBackend?.value ?: RemoteBackend.SHIZUKU
    }

    private fun snapshot(): RemoteAccessState {
        val shizukuAvailable = ShizukuManager.isAvailable()
        val shizukuGranted = ShizukuManager.isGranted()
        val rootAvailable = RootManager.isAvailable()
        val rootGranted = RootManager.isGranted()
        return RemoteAccessState(
            shizukuAvailable = shizukuAvailable,
            shizukuGranted = shizukuGranted,
            rootAvailable = rootAvailable,
            rootGranted = rootGranted,
            configuredBackend = configuredBackend()
        )
    }
}
