package com.etp.app

import android.app.Application
import com.etp.app.data.Repository
import com.etp.app.data.SessionManager
import com.etp.app.data.buildApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(app: Application) {
    val session = SessionManager(app)
    val repository = Repository(buildApi(session), session)
}

class EtpApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch { container.session.load() }
    }
}
