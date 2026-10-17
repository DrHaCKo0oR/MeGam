package app.shaqati

import android.app.Application
import android.content.Context
import app.shaqati.core.util.OkHttpHttp
import app.shaqati.data.KeyVault
import app.shaqati.data.Repository
import app.shaqati.data.SettingsStore
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** App-wide singletons. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val okHttp: OkHttpClient = OkHttpHttp.defaultClient()
    val settings = SettingsStore(app)
    val vault = KeyVault(app)
    val repository = Repository(app, scope, okHttp, settings, vault)
}

class ShaqatiApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.repository.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).crossfade(true).build()
}

val Context.graph: AppGraph get() = (applicationContext as ShaqatiApp).graph
