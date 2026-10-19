package ai.storyteller.lightcraft

import ai.storyteller.lightcraft.engine.Engine
import android.app.Application
import android.content.ComponentCallbacks2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class LightcraftApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The engine, opened once per process on a background thread: the library stays locked while
     * it is open, so activities come and go but share this one.
     */
    val engine: Deferred<Result<Engine>> by lazy { scope.async { runCatching { Engine.open(this@LightcraftApplication) } } }

    // (the RUNNING_ levels: phones before Android 14 also say when memory runs low in the foreground)
    @Suppress("DEPRECATION")
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val low = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (low && engine.isCompleted) {
            engine.getCompleted().getOrNull()?.trimMemory()
        }
    }
}
