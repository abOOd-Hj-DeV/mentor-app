package dev.k230.mentor_app.protection.cloud

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import dev.k230.mentor_app.R
import dev.k230.mentor_app.protection.security.SecurityFailure
import okhttp3.HttpUrl.Companion.toHttpUrl

class RelayConfiguration internal constructor(
    val relayUrl: String,
    val applicationId: String,
    val apiKey: String,
    val projectId: String,
    val senderId: String,
    val allowAnonymousAuth: Boolean,
)

object RelayConfigurationLoader {
    fun load(context: Context): RelayConfiguration? {
        val r = context.resources
        val values = listOf(R.string.mentor_relay_url, R.string.mentor_firebase_application_id,
            R.string.mentor_firebase_api_key, R.string.mentor_firebase_project_id, R.string.mentor_firebase_sender_id)
            .map { r.getString(it).trim() }
        return fromPublicValues(values, r.getBoolean(R.bool.mentor_firebase_anonymous_auth))
    }

    internal fun fromPublicValues(values: List<String>, allowAnonymousAuth: Boolean): RelayConfiguration? {
        if (values.size != 5) throw SecurityFailure("relay_unconfigured")
        if (values.all(String::isEmpty)) return null
        if (values.any(String::isEmpty)) throw SecurityFailure("relay_unconfigured")
        val url = try { values[0].toHttpUrl() } catch (_: Exception) { throw SecurityFailure("relay_unconfigured") }
        if (url.scheme != "https" || url.query != null || url.fragment != null || values[0].endsWith('/') ||
            url.username.isNotEmpty() || url.password.isNotEmpty())
            throw SecurityFailure("relay_unconfigured")
        return RelayConfiguration(values[0], values[1], values[2], values[3], values[4],
            allowAnonymousAuth)
    }

    @Synchronized fun firebase(context: Context, config: RelayConfiguration): FirebaseApp {
        FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }?.let { app ->
            val o = app.options
            if (o.applicationId != config.applicationId || o.projectId != config.projectId || o.apiKey != config.apiKey ||
                o.gcmSenderId != config.senderId)
                throw SecurityFailure("relay_unconfigured")
            return app
        }
        return FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setApplicationId(config.applicationId).setApiKey(config.apiKey).setProjectId(config.projectId)
            .setGcmSenderId(config.senderId).build())
    }
}
