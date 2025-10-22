package owl.command

import at.favre.lib.crypto.bcrypt.BCrypt
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.world.GameMode
import owl.config.OwlConfigManager
import owl.session.PlayerSessionManager
import org.slf4j.Logger

object CommandHandler {
    fun register(config: OwlConfigManager, sessions: PlayerSessionManager, logger: Logger) {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                CommandManager.literal("register")
                    .requires { it.entity is ServerPlayerEntity }
                    .executes { context ->
                        context.source.sendFeedback({ Text.literal("Usage: /register <password>") }, false)
                        0
                    }
                    .then(
                        CommandManager.argument("password", StringArgumentType.word())
                            .executes { context ->
                                val player = context.source.playerOrThrow
                                val username = player.gameProfile.name
                                if (!config.isWhitelisted(username)) {
                                    player.sendMessage(Text.literal("You are not authorized to register on this server."), false)
                                    return@executes 0
                                }
                                if (config.hasPassword(username)) {
                                    player.sendMessage(Text.literal("You are already registered. Use /login <password>."), false)
                                    return@executes 0
                                }

                                val password = StringArgumentType.getString(context, "password")
                                val minLength = config.minPasswordLength()
                                if (password.length < minLength) {
                                    player.sendMessage(
                                        Text.literal("Password must be at least $minLength characters long."),
                                        false
                                    )
                                    logger.warn(
                                        "Player {} attempted to register with a password shorter than the minimum length.",
                                        username
                                    )
                                    return@executes 0
                                }
                                val passwordChars = password.toCharArray()
                                val hash = try {
                                    BCrypt.withDefaults().hashToString(12, passwordChars)
                                } finally {
                                    passwordChars.fill('\u0000')
                                }

                                config.setPassword(username, hash)
                                config.save()
                                sessions.updateUsername(player)
                                sessions.markAuthenticated(player)
                                player.changeGameMode(GameMode.SURVIVAL)
                                player.sendMessage(Text.literal("Registration successful. You are now logged in."), false)
                                logger.info("Player {} registered successfully.", username)
                                1
                            }
                    )
            )

            dispatcher.register(
                CommandManager.literal("login")
                    .requires { it.entity is ServerPlayerEntity }
                    .executes { context ->
                        context.source.sendFeedback({ Text.literal("Usage: /login <password>") }, false)
                        0
                    }
                    .then(
                        CommandManager.argument("password", StringArgumentType.word())
                            .executes { context ->
                                val player = context.source.playerOrThrow
                                val username = player.gameProfile.name
                                val storedHash = config.getPasswordHash(username)
                                if (storedHash.isNullOrBlank()) {
                                    player.sendMessage(Text.literal("You must register first using /register <password>."), false)
                                    return@executes 0
                                }
                                val password = StringArgumentType.getString(context, "password")
                                val passwordChars = password.toCharArray()
                                val verified = try {
                                    BCrypt.verifyer().verify(passwordChars, storedHash).verified
                                } finally {
                                    passwordChars.fill('\u0000')
                                }
                                if (!verified) {
                                    val attempts = sessions.recordFailure(player)
                                    val maxAttempts = config.maxLoginAttempts()
                                    val remaining = maxAttempts - attempts
                                    logger.warn(
                                        "Player {} failed to login (attempt {}/{}).",
                                        username,
                                        attempts,
                                        maxAttempts
                                    )
                                    if (remaining <= 0) {
                                        player.networkHandler.disconnect(
                                            Text.literal("Too many failed login attempts. Try again later.")
                                        )
                                        sessions.endSession(player.uuid)
                                    } else {
                                        player.sendMessage(
                                            Text.literal("Incorrect password. Attempts remaining: $remaining"),
                                            false
                                        )
                                    }
                                    return@executes 0
                                }

                                sessions.updateUsername(player)
                                sessions.markAuthenticated(player)
                                player.changeGameMode(GameMode.SURVIVAL)
                                player.sendMessage(Text.literal("Login successful. Welcome!"), false)
                                logger.info("Player {} logged in successfully.", username)
                                1
                            }
                    )
            )

            dispatcher.register(
                CommandManager.literal("owl")
                    .then(
                        CommandManager.literal("add")
                            .requires { hasPermission(it, "owl.add", true) }
                            .then(
                                CommandManager.argument("username", StringArgumentType.word())
                                    .executes { context ->
                                        val username = StringArgumentType.getString(context, "username")
                                        val added = config.addUser(username)
                                        if (added) {
                                            config.save()
                                            logger.info("{} added {} to the whitelist.", describeSource(context.source), username)
                                            context.source.sendFeedback(
                                                { Text.literal("Added $username to the whitelist.") },
                                                false
                                            )
                                        } else {
                                            context.source.sendFeedback(
                                                { Text.literal("$username is already whitelisted.") },
                                                false
                                            )
                                        }
                                        1
                                    }
                            )
                    )
                    .then(
                        CommandManager.literal("remove")
                            .requires { hasPermission(it, "owl.remove", false) }
                            .then(
                                CommandManager.argument("username", StringArgumentType.word())
                                    .executes { context ->
                                        val username = StringArgumentType.getString(context, "username")
                                        val removed = config.removeUser(username)
                                        if (removed) {
                                            config.save()
                                            val player = findOnlinePlayer(context.source, username)
                                            if (player != null) {
                                                player.networkHandler.disconnect(
                                                    Text.literal("You have been removed from the whitelist.")
                                                )
                                                sessions.endSession(player.uuid)
                                            }
                                            logger.info(
                                                "{} removed {} from the whitelist.",
                                                describeSource(context.source),
                                                username
                                            )
                                            context.source.sendFeedback(
                                                { Text.literal("Removed $username from the whitelist.") },
                                                false
                                            )
                                        } else {
                                            context.source.sendFeedback(
                                                { Text.literal("$username is not currently whitelisted.") },
                                                false
                                            )
                                        }
                                        1
                                    }
                            )
                    )
            )
        }
    }

    private fun hasPermission(source: ServerCommandSource, node: String, defaultValue: Boolean): Boolean {
        return PermissionBridge.hasPermission(source, node, defaultValue)
    }

    private fun describeSource(source: ServerCommandSource): String {
        return source.entity?.name?.string ?: "Server"
    }

    private fun findOnlinePlayer(source: ServerCommandSource, username: String): ServerPlayerEntity? {
        val server = source.server
        return server.playerManager.playerList.firstOrNull {
            it.gameProfile.name.equals(username, ignoreCase = true)
        }
    }
}
