package de.rki.coronawarnapp.appconfig

import de.rki.coronawarnapp.appconfig.download.AppConfigServer
import de.rki.coronawarnapp.appconfig.download.AppConfigStorage
import de.rki.coronawarnapp.appconfig.download.ApplicationConfigurationInvalidException
import de.rki.coronawarnapp.appconfig.mapping.ConfigParser
import de.rki.coronawarnapp.util.TimeStamper
import de.rki.coronawarnapp.util.coroutine.AppScope
import de.rki.coronawarnapp.util.coroutine.DispatcherProvider
import de.rki.coronawarnapp.util.flow.HotDataFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import org.joda.time.Duration
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppConfigProvider @Inject constructor(
    private val server: AppConfigServer,
    private val storage: AppConfigStorage,
    private val parser: ConfigParser,
    private val dispatcherProvider: DispatcherProvider,
    @AppScope private val scope: CoroutineScope,
    private val timeStamper: TimeStamper
) {

    private val configHolder = HotDataFlow(
        loggingTag = "AppConfigProvider",
        scope = scope,
        coroutineContext = dispatcherProvider.IO,
        sharingBehavior = SharingStarted.Lazily
    ) {
        retrieveConfig()
    }

    private suspend fun retrieveConfig(): ConfigData = withContext(dispatcherProvider.IO) {
        Timber.v("retrieveConfig()")
        val (serverBytes, serverError) = try {
            server.downloadAppConfig() to null
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to download AppConfig from server .")
            null to e
        }

        var parsedConfig: ConfigData? = serverBytes?.let { configDownload ->
            try {
                parser.parse(configDownload.rawData).let {
                    Timber.tag(TAG).d("Got a valid AppConfig from server, saving.")
                    storage.setStoredConfig(configDownload)
                    DefaultConfigData(
                        mappedConfig = it,
                        serverTime = configDownload.serverTime,
                        localOffset = configDownload.localOffset,
                        isFallback = false
                    )
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to parse AppConfig from server, trying fallback.")
                null
            }
        }

        if (parsedConfig == null) {
            parsedConfig = storage.getStoredConfig()?.let { storedDownloadConfig ->
                try {
                    storedDownloadConfig.let {
                        DefaultConfigData(
                            mappedConfig = parser.parse(it.rawData),
                            serverTime = it.serverTime,
                            localOffset = it.localOffset,
                            isFallback = true
                        )
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Fallback config exists but could not be parsed!")
                    throw e
                }
            }
        }

        if (parsedConfig == null) {
            throw ApplicationConfigurationInvalidException(serverError)
        }

        return@withContext parsedConfig
    }

    fun getConfig(tryUpdate: Boolean = false): Flow<ConfigData> = configHolder.data.onStart {
        val now = timeStamper.nowUTC
        configHolder.updateBlocking {
            if (tryUpdate || now.isAfter(updatedAt.plus(CACHE_TIMEOUT))) {
                retrieveConfig()
            } else {
                this
            }
        }.also { emit(it) }
    }.distinctUntilChanged()

    fun forceUpdate() {
        Timber.tag(TAG).v("forceUpdate()")
        configHolder.updateSafely {
            storage.setStoredConfig(null)

            // We are using Dispatchers IO to make it appropriate
            @Suppress("BlockingMethodInNonBlockingContext")
            server.clearCache()

            retrieveConfig()
        }
    }

    suspend fun getAppConfig(): ConfigData = getConfig().first()

    companion object {
        private const val TAG = "AppConfigProvider"
        private val CACHE_TIMEOUT = Duration.standardMinutes(3)
    }
}
