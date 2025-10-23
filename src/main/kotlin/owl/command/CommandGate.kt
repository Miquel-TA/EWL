package owl.command

import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import owl.OfflineWhitelistLogin
import java.time.Instant
import java.util.Locale

object CommandGate {
    private val allowed = setOf("login", "register")
    private const val REMINDER_INTERVAL_SECONDS = 5L

    @JvmStatic
    fun shouldBlock(source: ServerCommandSource, rawCommand: String): Boolean {
        val player = source.entity as? ServerPlayerEntity ?: return false
        val session = OfflineWhitelistLogin.sessionManager.getSession(player) ?: return false
        if (session.authenticated) {
            return false
        }
        val command = rawCommand.trim().substringBefore(' ').lowercase(Locale.ROOT)
        if (command.isEmpty() || allowed.contains(command)) {
            return false
        }
        val now = Instant.now()
        if (session.shouldSendReminder(now, REMINDER_INTERVAL_SECONDS)) {
            session.lastReminder = now
            player.sendMessage(
                Text.literal("Please authenticate with /login <password> or /register <password>."),
                false
            )
        }
        return true
    }
}
