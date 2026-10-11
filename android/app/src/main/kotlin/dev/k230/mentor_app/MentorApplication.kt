package dev.k230.mentor_app

import android.app.Application
import dev.k230.mentor_app.protection.NativeSecurityProvider

class MentorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NativeSecurityProvider.install(this)
    }
}
