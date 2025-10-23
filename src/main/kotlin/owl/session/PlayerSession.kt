package owl.session

import net.minecraft.util.math.Vec3d
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Tracks per-player authentication state while they are connected to the server.
 */
data class PlayerSession(
    val uuid: UUID,
    var username: String,
    val joinedAt: Instant,
    val anchorPos: Vec3d,
    val anchorYaw: Float,
    val anchorPitch: Float,
    var authenticated: Boolean = false,
    var failedAttempts: Int = 0,
    var lastReminder: Instant = Instant.EPOCH
) {
    fun hasTimedOut(now: Instant, timeoutSeconds: Long): Boolean {
        if (authenticated) {
            return false
        }
        return Duration.between(joinedAt, now).seconds >= timeoutSeconds
    }

    fun shouldSendReminder(now: Instant, intervalSeconds: Long): Boolean {
        return Duration.between(lastReminder, now).seconds >= intervalSeconds
    }
}
