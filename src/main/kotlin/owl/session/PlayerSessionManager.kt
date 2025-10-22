package owl.session

import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.math.Vec3d
import org.slf4j.Logger
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerSessionManager(private val logger: Logger) {
    private val sessions = ConcurrentHashMap<UUID, PlayerSession>()

    fun startSession(player: ServerPlayerEntity) {
        val session = PlayerSession(
            uuid = player.uuid,
            username = player.gameProfile.name,
            joinedAt = Instant.now(),
            anchorPos = player.pos,
            anchorYaw = player.yaw,
            anchorPitch = player.pitch
        )
        sessions[player.uuid] = session
    }

    fun endSession(uuid: UUID) {
        sessions.remove(uuid)
    }

    fun getSession(uuid: UUID): PlayerSession? = sessions[uuid]

    fun getSession(player: ServerPlayerEntity): PlayerSession? = sessions[player.uuid]

    fun markAuthenticated(player: ServerPlayerEntity) {
        sessions[player.uuid]?.let {
            it.authenticated = true
            it.failedAttempts = 0
            it.lastReminder = Instant.EPOCH
        }
    }

    fun recordFailure(player: ServerPlayerEntity): Int {
        val session = sessions[player.uuid]
        if (session == null) {
            logger.warn("Login failure recorded for {} without an active session", player.gameProfile.name)
            return 0
        }
        session.failedAttempts += 1
        return session.failedAttempts
    }

    fun updateUsername(player: ServerPlayerEntity) {
        sessions[player.uuid]?.username = player.gameProfile.name
    }

    fun enforceRestrictions(player: ServerPlayerEntity) {
        val session = sessions[player.uuid] ?: return
        if (session.authenticated) {
            return
        }

        player.setVelocity(Vec3d.ZERO)
        val pos = player.pos
        val dx = pos.x - session.anchorPos.x
        val dy = pos.y - session.anchorPos.y
        val dz = pos.z - session.anchorPos.z
        if (dx * dx + dy * dy + dz * dz > POSITION_EPSILON) {
            player.networkHandler.requestTeleport(
                session.anchorPos.x,
                session.anchorPos.y,
                session.anchorPos.z,
                session.anchorYaw,
                session.anchorPitch
            )
        }
    }

    companion object {
        private const val POSITION_EPSILON = 0.0001
    }
}
