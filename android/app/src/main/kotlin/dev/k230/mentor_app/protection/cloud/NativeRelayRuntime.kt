package dev.k230.mentor_app.protection.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.k230.mentor_app.R
import dev.k230.mentor_app.protection.security.SecurityFailure
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

object NativeRelayRuntime {
    private const val UNIQUE_NOW = "mentor-relay-now-v2"
    private const val UNIQUE_PERIODIC = "mentor-relay-periodic-v2"
    @Volatile private var installed: NativeRelayAdapter? = null
    internal val workerLock = ReentrantLock()

    fun install(context: Context, adapter: NativeRelayAdapter) {
        installed = adapter
        start(context.applicationContext)
    }

    fun start(context: Context) {
        if (RelayConfigurationLoader.load(context) == null) { installed?.relayState(RelayState.UNCONFIGURED); return }
        schedulePeriodic(context.applicationContext)
        requestSync(context.applicationContext)
    }

    fun requestSync(context: Context) = request(context, "sync")
    fun foreground(context: Context) = requestSync(context)
    fun requestRevoke(context: Context) = request(context, "revoke")

    private fun request(context: Context, action: String) {
        if (try { RelayConfigurationLoader.load(context.applicationContext) } catch (_: Exception) { null } == null) return
        val request = OneTimeWorkRequestBuilder<RelayWorker>()
            .setInputData(workDataOf("action" to action))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(if (action == "revoke") "$UNIQUE_NOW-revoke" else UNIQUE_NOW,
            ExistingWorkPolicy.KEEP, request)
    }

    private fun schedulePeriodic(context: Context) {
        if (RelayConfigurationLoader.load(context) == null) return
        val request = PeriodicWorkRequestBuilder<RelayWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    internal fun adapter(context: Context): NativeRelayAdapter? {
        installed?.let { return it }
        val name = context.getString(R.string.mentor_relay_adapter_factory).trim()
        if (name.isEmpty()) return null
        val factory = try { Class.forName(name).getDeclaredConstructor().newInstance() as NativeRelayAdapterFactory }
        catch (_: Exception) { throw SecurityFailure("relay_unconfigured") }
        return factory.create(context.applicationContext).also { installed = it }
    }
}

class RelayWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!NativeRelayRuntime.workerLock.tryLock()) return Result.retry()
        return try { runWork() } finally { NativeRelayRuntime.workerLock.unlock() }
    }

    private fun runWork(): Result {
        val config = try { RelayConfigurationLoader.load(applicationContext) } catch (_: Exception) { return Result.failure() }
            ?: return Result.success()
        val adapter = try { NativeRelayRuntime.adapter(applicationContext) } catch (_: Exception) { return Result.failure() }
            ?: return Result.failure()
        return try {
            val sync = NativeRelaySync(FirebaseRelayIdentity(applicationContext, config, adapter::appCheckToken),
                FirebaseRelayPushToken(applicationContext, config), RelayHttpClient(config.relayUrl), adapter)
            if (inputData.getString("action") == "revoke") { sync.revoke(); Result.success() }
            else if (sync.synchronize()) Result.success() else Result.retry()
        } catch (failure: RelayFailure) {
            adapter.relayState(if (failure.retryable) RelayState.BACKOFF else RelayState.ERROR, failure.fixedCode)
            if (failure.retryable) Result.retry() else Result.failure()
        } catch (failure: SecurityFailure) {
            adapter.relayState(RelayState.ERROR, failure.code); Result.failure()
        } catch (_: Exception) {
            adapter.relayState(RelayState.BACKOFF, "unavailable"); Result.retry()
        }
    }
}

class RelayMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (isGenericRelayWakeUp(message.data, message.notification != null))
            NativeRelayRuntime.requestSync(applicationContext)
    }
    override fun onNewToken(token: String) { NativeRelayRuntime.requestSync(applicationContext) }
}

internal fun isGenericRelayWakeUp(data: Map<String, String>, hasNotification: Boolean): Boolean =
    !hasNotification && data == mapOf("v" to "2", "type" to "inbox_changed")
