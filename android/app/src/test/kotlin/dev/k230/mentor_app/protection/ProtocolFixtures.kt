package dev.k230.mentor_app.protection

internal object ProtocolFixtures {
    const val SESSION = "10000000-0000-4000-8000-000000000001"
    const val SCREEN = "10000000-0000-4000-8000-000000000002"
    const val STREAM = "10000000-0000-4000-8000-000000000004"
    const val EVENT = "10000000-0000-4000-8000-000000000005"
    const val CONTINUITY = "10000000-0000-4000-8000-000000000006"
    val profile = PolicyProfile(12, 1)
    val screen = ScreenSnapshot(SCREEN, 1, 1080, 2400, 0, 7, "com.example.viewer", 10_450_000, 9_000_000)
    fun bind() = mapOf("v" to 2, "type" to "bind", "session_id" to SESSION, "seq" to "1",
        "stream_id" to STREAM, "pts_clock" to "android_system_nano_time_us",
        "capture" to mapOf("source" to "scrcpy-4.0-display", "display_id" to 0, "mirror" to false,
            "custom_crop" to false, "custom_rotation" to false))
    fun region(p: Double, h: Double, s: Double, pts: List<Long>, track: Long = 1,
        crop: PixelRect = PixelRect(20, 100, 160, 300), route: String? = null): Map<String, Any?> =
        mapOf("track_id" to track.toString(), "kind" to "Image", "crop_frame_px" to crop.wire(),
            "scores" to mapOf("porn" to p, "hentai" to h, "sexy" to s,
                "explicit_score" to (p + h).coerceIn(0.0, 1.0)),
            "evidence" to mapOf("route" to (route ?: if (h > p && p < .6) "hentai_dominant" else "explicit"),
                "observations" to pts.map { mapOf("pts_us" to it.toString(), "porn" to p, "hentai" to h, "sexy" to s) },
                "analysis_continuity_id" to CONTINUITY, "analysis_complete" to true))
    fun decision(
        stage: Int = 1, p: Double = .65, h: Double = .02, s: Double = .10,
        pts: List<Long> = listOf(10_000_000, 10_200_000, 10_400_000), age: Int = 12,
        revision: Long = 1, reason: String = "threshold", event: String = EVENT,
        regions: List<Map<String, Any?>> = listOf(region(p, h, s, pts)),
    ): Map<String, Any?> = mapOf("v" to 2, "type" to "decision", "session_id" to SESSION, "seq" to "3",
        "stream_id" to STREAM, "event_id" to event, "action_revision" to revision.toString(),
        "pts_us" to pts.last().toString(), "expires_at_us" to (pts.last() + 750_000).toString(),
        "screen_token" to SCREEN, "content_epoch" to "1", "package" to screen.packageName, "window_id" to 7,
        "policy" to PolicyProfile(age, 1).wire(),
        "frame" to mapOf("width" to 360, "height" to 800, "display_rotation_deg" to 0),
        "transform" to mapOf("rotation_cw_deg" to 0, "viewport_display_px" to PixelRect(0, 0, 1080, 2400).wire()),
        "requested_stage" to stage, "requested_action" to CompanionProtocol.actionFor(stage),
        "reason" to reason, "regions" to regions)
    fun parse(map: Map<String, Any?>) = CompanionProtocol.parse(CompanionProtocol.encode(map)) as DecisionCommand
    fun valid(d: DecisionCommand, now: Long = d.ptsUs + 50_000, masks: List<AppliedMask> = emptyList(),
        repetition: Boolean = false) = DecisionValidator.validate(d, screen.copy(sampledUs = now), d.policy,
            now, true, false, masks, repetition)
}
