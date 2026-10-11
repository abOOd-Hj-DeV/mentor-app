package dev.k230.mentor_app.protection.cloud

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import dev.k230.mentor_app.protection.security.SecurityFailure
import java.util.concurrent.TimeUnit

class RelayCredentials(val uid: String, val idToken: String, val appCheckToken: String? = null)
fun interface RelayIdentity { fun credentials(forceRefresh: Boolean): RelayCredentials }
fun interface RelayPushToken { fun token(): String }

class FirebaseRelayIdentity(context: Context, private val config: RelayConfiguration,
                            private val appCheckToken: (Boolean) -> String? = { null }) : RelayIdentity {
    private val auth = FirebaseAuth.getInstance(RelayConfigurationLoader.firebase(context, config))
    override fun credentials(forceRefresh: Boolean): RelayCredentials {
        var user = auth.currentUser
        if (user == null && config.allowAnonymousAuth) {
            Tasks.await(auth.signInAnonymously(), 15, TimeUnit.SECONDS)
            user = auth.currentUser
        }
        user ?: throw SecurityFailure("unauthenticated")
        val result = Tasks.await(user.getIdToken(forceRefresh), 15, TimeUnit.SECONDS)
        val token = result.token ?: throw SecurityFailure("unauthenticated")
        if (token.isEmpty() || auth.currentUser?.uid != user.uid) throw SecurityFailure("unauthenticated")
        return RelayCredentials(user.uid, token, appCheckToken(forceRefresh))
    }
}

class FirebaseRelayPushToken(context: Context, config: RelayConfiguration) : RelayPushToken {
    init { RelayConfigurationLoader.firebase(context, config) }
    override fun token(): String = Tasks.await(FirebaseMessaging.getInstance().token, 15, TimeUnit.SECONDS)
        ?: throw SecurityFailure("unavailable")
}
