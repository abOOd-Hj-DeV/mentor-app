package dev.k230.local_inspector

import android.app.UiAutomation
import android.content.ComponentName
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionActionTest {
    @Test
    fun confirmedObservationsDisplayCoversAndShieldsAndExecuteHome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val component = "dev.k230.local_inspector/dev.k230.local_inspector.MonitorService"
        val enabled = shell(automation, "settings --user current get secure enabled_accessibility_services")
            .takeUnless { it == "null" }.orEmpty()
        val others = enabled.split(':').filter {
            it.isNotEmpty() && ComponentName.unflattenFromString(it) != ComponentName.unflattenFromString(component)
        }
        for (command in listOf(
            if (others.isEmpty()) "settings --user current delete secure enabled_accessibility_services"
                else "settings --user current put secure enabled_accessibility_services ${others.joinToString(":")}",
            "settings --user current put secure enabled_accessibility_services ${(others + component).joinToString(":")}",
            "settings --user current put secure accessibility_enabled 1"
        )) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (MonitorService.instance == null && SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(100)
        instrumentation.runOnMainSync {
            val service = requireNotNull(MonitorService.instance) { "Enable local protection accessibility service first" }
            try {
                for ((porn, expectedStage) in listOf(.65f to 1, .85f to 2, .99f to 3)) {
                    service.stop()
                    val policy = LocalPolicy(10)
                    policy.captureIntervalMs = 1000
                    val observations = listOf(Observation(Region(0, 0, 100, 100),
                        Scores(0f, 0f, 1f - porn, porn, 0f)))
                    val samples = if (expectedStage == 3) 5 else 3
                    var decision = Decision()
                    for (i in 0 until samples)
                        decision = policy.evaluate(observations, i * 1000L, "test.fixture", 1080, 2400)
                    assertEquals(expectedStage, decision.stage)
                    service.apply(decision)
                    assertEquals(MonitorState.error, expectedStage, MonitorState.stage)
                }
                service.start(10)
                service.onInterrupt()
                assertTrue(MonitorState.active)
            } finally {
                service.stop()
            }
        }
        shell(automation, if (enabled.isEmpty()) "settings --user current delete secure enabled_accessibility_services"
            else "settings --user current put secure enabled_accessibility_services $enabled")
    }
    private fun shell(automation: UiAutomation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }
}
