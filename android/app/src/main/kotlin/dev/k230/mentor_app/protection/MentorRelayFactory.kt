package dev.k230.mentor_app.protection

import android.content.Context
import androidx.annotation.Keep
import dev.k230.mentor_app.protection.cloud.NativeRelayAdapter
import dev.k230.mentor_app.protection.cloud.NativeRelayAdapterFactory

@Keep
class MentorRelayFactory : NativeRelayAdapterFactory {
    override fun create(context: Context): NativeRelayAdapter = NativeSecurityProvider.install(context).relayAdapter
}
