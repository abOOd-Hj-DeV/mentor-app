package dev.k230.mentor_app.protection

internal object ProtectionMethodRouter {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    fun request(method: String, raw: Any?, state: SecurityState): SecurityRequest {
        @Suppress("UNCHECKED_CAST")
        val args = if (raw == null) emptyMap() else raw as? Map<String, Any?>
            ?: throw ProtocolFailure("bounds")
        fun keys(vararg expected: String) = requireProtocol(args.keys == expected.toSet())
        fun authenticatedGuardian() = requireProtocol(state.role == DeviceRole.GUARDIAN &&
            state.guardianAuthenticated, "guardian_auth_required")
        fun child() = requireProtocol(state.role == DeviceRole.CHILD, "permission_missing")
        fun id(key: String): String = (args[key] as? String)?.also { requireProtocol(uuid.matches(it)) }
            ?: throw ProtocolFailure("bounds")
        fun qr(): QrStep = QrStep.entries.firstOrNull { it.wire == args["step"] }
            ?: throw ProtocolFailure("bounds")
        return when (method) {
            "guardianAuthenticate" -> {
                keys()
                requireProtocol((state.role == DeviceRole.GUARDIAN ||
                    state.role == DeviceRole.UNCONFIGURED && state.pairing == "unpaired") &&
                    state.pairing !in setOf("revoked", "key_lost"),
                    "permission_missing")
                SecurityRequest.Authenticate
            }
            "createPairOffer" -> { keys(); authenticatedGuardian(); SecurityRequest.CreatePairOffer }
            "scanPairQr", "getPairQr" -> {
                keys("step")
                val step = qr()
                when (step) {
                    QrStep.OFFER -> requireProtocol(method == "scanPairQr" &&
                        state.role != DeviceRole.GUARDIAN && state.pairing in setOf("unpaired", "pending"),
                        "permission_missing")
                    QrStep.RESPONSE -> if (method == "scanPairQr") authenticatedGuardian() else child()
                    QrStep.CONFIRMATION -> if (method == "scanPairQr") child() else authenticatedGuardian()
                    QrStep.CHALLENGE -> if (method == "scanPairQr") authenticatedGuardian() else child()
                    QrStep.CONTROL -> if (method == "scanPairQr") child() else authenticatedGuardian()
                    QrStep.RECEIPT -> if (method == "scanPairQr") authenticatedGuardian() else child()
                }
                if (method == "scanPairQr") SecurityRequest.ScanPairQr(step) else SecurityRequest.GetPairQr(step)
            }
            "createControlChallenge", "requestGuardianUnlock" -> {
                child()
                val op = if (method == "requestGuardianUnlock") {
                    keys("eventId"); ControlOperation.UNLOCK
                } else {
                    keys("operation", "eventId")
                    ControlOperation.entries.firstOrNull { it.wire == args["operation"] }
                        ?: throw ProtocolFailure("bounds")
                }
                val event = if (op == ControlOperation.UNLOCK) id("eventId")
                    else { requireProtocol(args["eventId"] == null); null }
                SecurityRequest.CreateChallenge(op, event)
            }
            "setChildAge" -> {
                keys("age"); authenticatedGuardian()
                val age = args["age"] as? Int ?: throw ProtocolFailure("invalid_age")
                requireProtocol(age in 10..15, "invalid_age")
                SecurityRequest.SetChildAge(age)
            }
            "listIncidents" -> {
                keys("cursor", "limit"); authenticatedGuardian()
                val cursor = args["cursor"]
                requireProtocol(cursor == null || cursor is String && cursor.length <= 512)
                val limit = args["limit"] as? Int ?: throw ProtocolFailure("bounds")
                requireProtocol(limit in 1..50)
                SecurityRequest.ListIncidents(cursor as? String, limit)
            }
            "getIncident" -> { keys("eventId"); authenticatedGuardian(); SecurityRequest.GetIncident(id("eventId")) }
            "syncInbox" -> { keys(); SecurityRequest.SyncInbox }
            "revokePair" -> { keys(); authenticatedGuardian(); SecurityRequest.RevokePair }
            else -> throw ProtocolFailure("unsupported_method")
        }
    }
}
