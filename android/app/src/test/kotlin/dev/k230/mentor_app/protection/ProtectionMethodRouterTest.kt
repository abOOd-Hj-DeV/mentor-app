package dev.k230.mentor_app.protection

import org.junit.Assert.*
import org.junit.Test

class ProtectionMethodRouterTest {
    private fun fails(code: String, block: () -> Unit) {
        try { block(); fail("must reject") } catch (e: ProtocolFailure) { assertEquals(code,e.code) }
    }
    @Test fun unconfiguredBootstrapDelegatesActualAuthenticationWithoutFakingSuccess() {
        assertEquals(SecurityRequest.Authenticate,
            ProtectionMethodRouter.request("guardianAuthenticate",null,SecurityState()))
        var reply: SecurityReply? = null
        UnconfiguredSecurity.invoke(SecurityRequest.Authenticate) { reply=it }
        assertEquals(SecurityReply.Error("security_unconfigured"),reply)
        assertEquals(DeviceRole.UNCONFIGURED,UnconfiguredSecurity.state().role)
        assertFalse(UnconfiguredSecurity.state().guardianAuthenticated)
        assertNull(UnconfiguredSecurity.profile())
        assertNull(UnconfiguredSecurity.journal())
        assertEquals(SecurityRequest.ScanPairQr(QrStep.OFFER),
            ProtectionMethodRouter.request("scanPairQr",mapOf("step" to "offer"),SecurityState()))
        for (pairing in listOf("pending", "paired", "revoked", "key_lost")) {
            fails("permission_missing") {
                ProtectionMethodRouter.request("guardianAuthenticate",null,SecurityState(pairing=pairing))
            }
        }
    }
    @Test fun pairedChildCannotBecomeGuardianOrAccessIncidentsOrInstallPolicy() {
        val child=SecurityState(DeviceRole.CHILD,pairing="paired")
        fails("permission_missing") { ProtectionMethodRouter.request("guardianAuthenticate",null,child) }
        fails("guardian_auth_required") { ProtectionMethodRouter.request("setChildAge",mapOf("age" to 13),child) }
        fails("guardian_auth_required") { ProtectionMethodRouter.request("getIncident",
            mapOf("eventId" to ProtocolFixtures.EVENT),child) }
        fails("unsupported_method") { ProtectionMethodRouter.request("setRole",mapOf("role" to "guardian"),child) }
        fails("permission_missing") { ProtectionMethodRouter.request("scanPairQr",mapOf("step" to "offer"),child) }
    }
    @Test fun guardianAgeIsTypedValidatedAndThirteenUsesOlderProfile() {
        val guardian=SecurityState(DeviceRole.GUARDIAN,true,"paired")
        assertEquals(SecurityRequest.SetChildAge(13),
            ProtectionMethodRouter.request("setChildAge",mapOf("age" to 13),guardian))
        assertEquals("13-15",PolicyProfile(13,1).profile)
        for (age in listOf(9,16,13.5,"13",true,null)) fails("invalid_age") {
            ProtectionMethodRouter.request("setChildAge",mapOf("age" to age),guardian)
        }
        fails("guardian_auth_required") {
            ProtectionMethodRouter.request("setChildAge",mapOf("age" to 13),guardian.copy(guardianAuthenticated=false))
        }
    }
    @Test fun childRequestCreatesChallengeOnlyAndRejectsSpoofedFields() {
        val child=SecurityState(DeviceRole.CHILD,pairing="paired")
        assertEquals(SecurityRequest.CreateChallenge(ControlOperation.UNLOCK,ProtocolFixtures.EVENT),
            ProtectionMethodRouter.request("requestGuardianUnlock",mapOf("eventId" to ProtocolFixtures.EVENT),child))
        fails("bounds") { ProtectionMethodRouter.request("requestGuardianUnlock",
            mapOf("eventId" to ProtocolFixtures.EVENT,"authorized" to true),child) }
        fails("unsupported_method") { ProtectionMethodRouter.request("unlock",mapOf("eventId" to ProtocolFixtures.EVENT),child) }
        fails("unsupported_method") { ProtectionMethodRouter.request("decision",mapOf("porn" to 1.0),child) }
    }
    @Test fun companionPeerAllowlistRemainsExactlyShellOrRoot() {
        assertTrue(CompanionSocket.allowedUid(0))
        assertTrue(CompanionSocket.allowedUid(2000))
        for(uid in listOf(-1,1,1000,10000,2001)) assertFalse(CompanionSocket.allowedUid(uid))
    }
}
