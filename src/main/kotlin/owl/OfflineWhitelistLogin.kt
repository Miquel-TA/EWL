package owl

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.world.GameMode
import org.slf4j.LoggerFactory
import owl.command.CommandHandler
import owl.config.OwlConfigManager
import owl.session.PlayerSessionManager
import java.time.Instant

object OfflineWhitelistLogin : ModInitializer {
    const val UNAUTHORIZED_JOIN_MESSAGE = "You are not authorized to join this server."
    private val logger = LoggerFactory.getLogger("offline-whitelist-login")
    private const val WHITELIST_AUDIT_INTERVAL_TICKS = 20 * 60

    lateinit var configManager: OwlConfigManager
        private set

    val sessionManager = PlayerSessionManager(logger)

    private var tickCounter = 0

    override fun onInitialize() {
        logger.info("Initializing Offline Whitelist Login")
        val configDir = FabricLoader.getInstance().configDir
        configManager = OwlConfigManager(configDir, logger)
        configManager.load()

        CommandHandler.register(configManager, sessionManager, logger)
        registerConnectionEvents()
        registerTickEvents()

        logger.info("Offline Whitelist Login ready")
    }

    private fun registerConnectionEvents() {
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            val username = player.gameProfile.name

            if (!configManager.isWhitelisted(username)) {
                logger.warn("Rejected connection from {}: not present in whitelist", username)
                handler.disconnect(Text.literal(UNAUTHORIZED_JOIN_MESSAGE))
                return@register
            }

            sessionManager.startSession(player)
            configManager.updateStoredUsername(username)
            player.changeGameMode(GameMode.SPECTATOR)
            player.sendMessage(
                if (configManager.hasPassword(username)) {
                    Text.literal("Please login with /login <password> to start playing.")
                } else {
                    Text.literal("Please register with /register <password> to start playing.")
                },
                false
            )
            logger.info("Player {} joined pending authentication.", username)
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            sessionManager.endSession(handler.player.uuid)
        }
    }

    fun isConfigLoaded(): Boolean = this::configManager.isInitialized

    fun isUserAuthorized(username: String): Boolean =
        isConfigLoaded() && configManager.isWhitelisted(username)

    private fun registerTickEvents() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            tickCounter++
            checkLoginTimeouts(server)
            enforceMovementRestrictions(server)
            if (tickCounter >= WHITELIST_AUDIT_INTERVAL_TICKS) {
                tickCounter = 0
                auditWhitelist(server)
            }
        }
    }

    private fun checkLoginTimeouts(server: MinecraftServer) {
        val now = Instant.now()
        val timeoutSeconds = configManager.loginTimeoutSeconds()
        val toKick = mutableListOf<ServerPlayerEntity>()
        server.playerManager.playerList.forEach { player ->
            val session = sessionManager.getSession(player) ?: return@forEach
            if (session.hasTimedOut(now, timeoutSeconds)) {
                toKick += player
            }
        }

        toKick.forEach { player ->
            logger.warn(
                "Kicking {} for failing to authenticate within {} seconds.",
                player.gameProfile.name,
                timeoutSeconds
            )
            player.networkHandler.disconnect(Text.literal("Login timed out. Please reconnect and try again."))
            sessionManager.endSession(player.uuid)
        }
    }

    private fun enforceMovementRestrictions(server: MinecraftServer) {
        server.playerManager.playerList.forEach { player ->
            val session = sessionManager.getSession(player) ?: return@forEach
            if (!session.authenticated) {
                sessionManager.enforceRestrictions(player)
            }
        }
    }

    private fun auditWhitelist(server: MinecraftServer) {
        val toKick = mutableListOf<ServerPlayerEntity>()
        server.playerManager.playerList.forEach { player ->
            if (!configManager.isWhitelisted(player.gameProfile.name)) {
                toKick += player
            }
        }

        toKick.forEach { player ->
            logger.warn("Kicking {} because they are no longer whitelisted.", player.gameProfile.name)
            player.networkHandler.disconnect(Text.literal("You are no longer authorized to play on this server."))
            sessionManager.endSession(player.uuid)
        }
    }
}
