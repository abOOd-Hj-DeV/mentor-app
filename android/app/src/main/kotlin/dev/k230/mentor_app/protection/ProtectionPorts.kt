package dev.k230.mentor_app.protection

internal data class ExecutionResult(
    val status: String, val stage: Int, val action: String,
    val executedUs: Long?, val rects: List<PixelRect>, val error: String? = null,
)

internal sealed interface JournalReservation {
    data object New : JournalReservation
    data class Cached(val result: ExecutionResult) : JournalReservation
    data object Conflict : JournalReservation
    data object Incomplete : JournalReservation
}

/** Implement with a strict Keystore-encrypted, atomic journal; no plaintext or volatile fallback. */
internal interface ExecutionJournal {
    fun reserve(decision: DecisionCommand, semanticSha256: String): JournalReservation
    fun finish(decision: DecisionCommand, result: ExecutionResult)
    /** Distinct successful cover/shield IDs in (afterUs,nowUs], current process and exact profile only.
     * Restored history and failed/pending/HOME records must never increase repetition authority. */
    fun executedEpisodes(packageName: String, policy: PolicyProfile, afterUs: Long, nowUs: Long): Set<String>
    fun release(eventId: String, revision: Long, releasedUs: Long)
}

internal interface ProtectionActions {
    /** Install the complete cover set atomically; on failure retain all previously installed covers. */
    fun cover(rects: List<PixelRect>, screen: ScreenSnapshot, done: (Result<List<PixelRect>>) -> Unit)
    fun shield(screen: ScreenSnapshot, done: (Result<List<PixelRect>>) -> Unit)
    fun home(onVerified: (Boolean) -> Unit): Boolean
    fun clear()
}

internal fun coverRectUnion(existing: List<PixelRect>, added: List<PixelRect>): List<PixelRect> {
    val complete = (existing + added).distinct()
    requireProtocol(complete.size in 1..8)
    return complete
}

internal enum class DeviceRole(val wire: String) { UNCONFIGURED("unconfigured"), GUARDIAN("guardian"), CHILD("child") }
internal data class SecurityState(
    val role: DeviceRole = DeviceRole.UNCONFIGURED, val guardianAuthenticated: Boolean = false,
    val pairing: String = "unpaired", val encryption: String = "failed", val cloud: String = "unconfigured",
    val outboxCount: Int = 0,
)

/** Owned by the security writer. Unconfigured bootstrap authenticates guardian credentials first,
 * or establishes child only from a validated physical offer. Never accept a Flutter role toggle. */
internal interface GuardianSecurityDelegate {
    fun state(): SecurityState
    fun profile(): PolicyProfile?
    fun journal(): ExecutionJournal?
    fun invoke(request: SecurityRequest, reply: (SecurityReply) -> Unit)
    fun onBackground()
    fun recordExecution(decision: DecisionCommand, result: ExecutionResult)
    fun recordRelease(eventId: String, revision: Long, reason: String)
}

internal sealed interface SecurityRequest {
    data object Authenticate : SecurityRequest
    data object CreatePairOffer : SecurityRequest
    data class ScanPairQr(val step: QrStep) : SecurityRequest
    data class GetPairQr(val step: QrStep) : SecurityRequest
    data class CreateChallenge(val operation: ControlOperation, val eventId: String?) : SecurityRequest
    data class SetChildAge(val age: Int) : SecurityRequest
    data class ListIncidents(val cursor: String?, val limit: Int) : SecurityRequest
    data class GetIncident(val eventId: String) : SecurityRequest
    data object SyncInbox : SecurityRequest
    data object RevokePair : SecurityRequest
}
internal enum class QrStep(val wire: String) {
    OFFER("offer"), RESPONSE("response"), CONFIRMATION("confirmation"), CHALLENGE("challenge"),
    CONTROL("control"), RECEIPT("receipt"),
}
internal enum class ControlOperation(val wire: String) {
    SET_PROFILE("set_profile"), UNLOCK("unlock"), REVOKE_PAIR("revoke_pair"),
}
internal sealed interface SecurityReply {
    data class Value(val data: Any?) : SecurityReply
    data class Error(val code: String) : SecurityReply
}

internal object UnconfiguredSecurity : GuardianSecurityDelegate {
    override fun state() = SecurityState()
    override fun profile(): PolicyProfile? = null
    override fun journal(): ExecutionJournal? = null
    override fun invoke(request: SecurityRequest, reply: (SecurityReply) -> Unit) {
        reply(SecurityReply.Error("security_unconfigured"))
    }
    override fun onBackground() = Unit
    override fun recordExecution(decision: DecisionCommand, result: ExecutionResult) = Unit
    override fun recordRelease(eventId: String, revision: Long, reason: String) = Unit
}

/** Native-only installation point. Clock approval requires supported-device PTS regression evidence,
 * not a string in bind, receipt-time rebasing, Flutter arguments, or a plausibility check alone. */
internal object ProtectionIntegration {
    var security: GuardianSecurityDelegate = UnconfiguredSecurity
    var guardianRelease: ((String, Long, (Boolean) -> Unit) -> Unit)? = null
    var activeEvent: () -> String? = { null }
    var receiveProtectedControl: ((ByteArray, (SecurityReply) -> Unit) -> Unit)? = null
    var onDisplayStateChanged: (() -> Unit)? = null
}
