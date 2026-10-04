package dev.k230.mentor_app.protection

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dev.k230.mentor_app.LayoutService
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

internal class ProtectionChannel(private val activity: Activity, messenger: BinaryMessenger) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var sink: EventChannel.EventSink? = null
    private val methods = MethodChannel(messenger, "dev.k230.mentor/protection")
    private val events = EventChannel(messenger, "dev.k230.mentor/protection/events")
    private val emit: () -> Unit = {
        handler.post { sink?.success(mapOf("v" to 2, "type" to "state_changed", "state" to state())) }
        Unit
    }
    init {
        ProtectionIntegration.onDisplayStateChanged = emit
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) { sink = events; emit() }
            override fun onCancel(arguments: Any?) { sink = null }
        })
        methods.setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "getRemoteStatus" -> result.success(RemoteCaptureService.status())
                    "stopRemote" -> {
                        requireProtocol(call.arguments == null)
                        activity.stopService(Intent(activity, RemoteCaptureService::class.java))
                        result.success(null)
                    }
                    "startRemote" -> {
                        val args = call.arguments as? Map<*, *> ?: throw ProtocolFailure("bounds")
                        requireProtocol(args.keys == setOf("endpoint", "token", "pin"))
                        val endpoint = args["endpoint"] as? String ?: throw ProtocolFailure("bounds")
                        val token = args["token"] as? String ?: throw ProtocolFailure("bounds")
                        val pin = args["pin"] as? String ?: throw ProtocolFailure("bounds")
                        RemoteConnectionConfig.parse(endpoint, token, pin)
                        val security = ProtectionIntegration.security.state()
                        requireProtocol(security.role == DeviceRole.CHILD && security.pairing == "paired", "unpaired")
                        requireProtocol(LayoutService.instance?.protection != null, "permission_missing")
                        activity.startActivity(Intent(activity, RemoteCaptureActivity::class.java)
                            .putExtra("endpoint", endpoint).putExtra("token", token).putExtra("pin", pin))
                        result.success(null)
                    }
                    "getState" -> { requireProtocol(call.arguments == null); result.success(state()) }
                    "openAccessibilitySettings" -> {
                        requireProtocol(call.arguments == null)
                        activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); result.success(null)
                    }
                    "navigateHome" -> {
                        requireProtocol(call.arguments == null)
                        val runtime = LayoutService.instance?.protection
                        if (runtime == null) result.error("permission_missing", null, null)
                        else runtime.navigateHome { result.success(it) }
                    }
                    else -> {
                        val delegate = ProtectionIntegration.security
                        val request = ProtectionMethodRouter.request(call.method, call.arguments, delegate.state())
                        delegate.invoke(request) { reply ->
                            handler.post {
                                when (reply) {
                                    is SecurityReply.Value -> result.success(reply.data)
                                    is SecurityReply.Error -> result.error(sanitizedError(reply.code), null, null)
                                }
                                emit()
                            }
                        }
                    }
                }
            } catch (e: ProtocolFailure) {
                if (e.code == "unsupported_method") result.notImplemented()
                else result.error(e.code, null, null)
            } catch (_: Exception) { result.error("action_failed", null, null) }
        }
    }

    private fun state(): Map<String, Any?> {
        LayoutService.instance?.protection?.let { return it.displayState() }
        val delegate = ProtectionIntegration.security
        val s = delegate.state()
        val p = delegate.profile()
        return mapOf("role" to s.role.wire, "guardianAuthenticated" to s.guardianAuthenticated,
            "profile" to p?.let { mapOf("age" to it.age, "profile" to it.profile,
                "policyVersion" to PolicyProfile.POLICY_VERSION, "policyRevision" to it.revision.toString()) },
            "pairing" to s.pairing, "health" to mapOf("accessibility" to false, "companion" to "disconnected",
                "analysis" to if (p == null) "unconfigured" else "unknown", "execution" to "degraded",
                "encryption" to s.encryption, "cloud" to s.cloud, "outboxCount" to s.outboxCount),
            "activeProtection" to null, "error" to "permission_missing")
    }
    fun backgrounded() {
        ProtectionIntegration.security.onBackground()
        emit()
    }
    override fun close() {
        if (ProtectionIntegration.onDisplayStateChanged === emit) ProtectionIntegration.onDisplayStateChanged = null
        methods.setMethodCallHandler(null); events.setStreamHandler(null); sink = null
        handler.removeCallbacksAndMessages(null)
    }
    private fun sanitizedError(code: String): String = if (code in setOf("security_unconfigured",
        "guardian_auth_required", "permission_missing", "invalid_age", "bounds", "locked", "storage_failed",
        "action_failed", "policy_mismatch", "stale", "event_conflict", "key_lost", "unpaired", "busy",
        "invalid_signature", "invalid_pairing", "cloud_unconfigured", "clock_unverified", "challenge_required",
        "camera_unavailable", "scan_cancelled", "secure_lock_required", "authentication_unavailable", "unavailable")) code else "action_failed"
}
