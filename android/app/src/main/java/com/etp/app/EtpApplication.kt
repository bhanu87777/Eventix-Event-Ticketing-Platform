package com.etp.app

import android.app.Application
import com.etp.app.data.Repository
import com.etp.app.data.SessionManager
import com.etp.app.data.SettingsManager
import com.etp.app.data.TicketCache
import com.etp.app.data.buildApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(app: Application, appScope: CoroutineScope) {
    val session = SessionManager(app)
    val settings = SettingsManager(app, appScope)
    val ticketCache = TicketCache(app)
    val repository = Repository(buildApi(session), session, ticketCache)
}

class EtpApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, appScope)
        appScope.launch { container.session.load() }
    }
}
