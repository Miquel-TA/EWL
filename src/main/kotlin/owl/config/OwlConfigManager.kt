package owl.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import org.slf4j.Logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.LinkedHashMap
import java.util.Locale

class OwlConfigManager(
    private val configDir: Path,
    private val logger: Logger
) {
    data class StoredUser(
        var username: String,
        var passwordHash: String? = null
    )

    data class ConfigOptions(
        var minPasswordLength: Int = DEFAULT_MIN_PASSWORD_LENGTH,
        var maxLoginAttempts: Int = DEFAULT_MAX_LOGIN_ATTEMPTS,
        var loginTimeoutSeconds: Int = DEFAULT_LOGIN_TIMEOUT_SECONDS
    )

    private data class PersistedData(
        var config: ConfigOptions = ConfigOptions(),
        var users: MutableList<String> = mutableListOf()
    )

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val lock = Any()
    private val configPath: Path = configDir.resolve(CONFIG_FILE)
    private val backupPath: Path = configDir.resolve(BACKUP_FILE)

    private val users: MutableMap<String, StoredUser> = mutableMapOf()
    private var options: ConfigOptions = ConfigOptions()

    fun load() {
        Files.createDirectories(configDir)
        synchronized(lock) {
            val primary = read(configPath)
            if (primary != null) {
                applyState(primary)
                ensurePersisted()
                return
            }

            val backup = read(backupPath)
            if (backup != null) {
                applyState(backup)
                logger.warn("Primary whitelist data was unavailable. Restored from backup.")
                ensurePersisted()
                return
            }

            logger.warn("No whitelist data found. Starting with an empty configuration.")
            users.clear()
            options = ConfigOptions()
            ensurePersisted()
        }
    }

    fun save() {
        synchronized(lock) {
            ensurePersisted()
        }
    }

    fun isWhitelisted(username: String): Boolean = synchronized(lock) {
        val trimmed = username.trim()
        if (trimmed.isEmpty()) {
            return@synchronized false
        }
        users.containsKey(normalize(trimmed))
    }

    fun addUser(username: String): Boolean = synchronized(lock) {
        val trimmed = username.trim()
        if (trimmed.isEmpty()) {
            return@synchronized false
        }
        val key = normalize(trimmed)
        val stored = users[key]
        if (stored != null) {
            stored.username = trimmed
            return@synchronized false
        }
        users[key] = StoredUser(trimmed)
        true
    }

    fun removeUser(username: String): Boolean = synchronized(lock) {
        val trimmed = username.trim()
        if (trimmed.isEmpty()) {
            return@synchronized false
        }
        val key = normalize(trimmed)
        users.remove(key) != null
    }

    fun setPassword(username: String, passwordHash: String) {
        synchronized(lock) {
            val trimmed = username.trim()
            if (trimmed.isEmpty()) {
                return
            }
            val key = normalize(trimmed)
            val stored = users.computeIfAbsent(key) { StoredUser(trimmed) }
            stored.username = trimmed
            stored.passwordHash = passwordHash
        }
    }

    fun clearPassword(username: String) {
        synchronized(lock) {
            val trimmed = username.trim()
            if (trimmed.isEmpty()) {
                return
            }
            users[normalize(trimmed)]?.passwordHash = null
        }
    }

    fun hasPassword(username: String): Boolean = synchronized(lock) {
        val trimmed = username.trim()
        if (trimmed.isEmpty()) {
            return@synchronized false
        }
        users[normalize(trimmed)]?.passwordHash?.isNotBlank() == true
    }

    fun getPasswordHash(username: String): String? = synchronized(lock) {
        val trimmed = username.trim()
        if (trimmed.isEmpty()) {
            return@synchronized null
        }
        users[normalize(trimmed)]?.passwordHash
    }

    fun updateStoredUsername(username: String) {
        synchronized(lock) {
            val trimmed = username.trim()
            if (trimmed.isEmpty()) {
                return
            }
            users[normalize(trimmed)]?.username = trimmed
        }
    }

    fun allUsers(): Map<String, StoredUser> = synchronized(lock) {
        users.toMap()
    }

    fun minPasswordLength(): Int = synchronized(lock) { options.minPasswordLength }

    fun maxLoginAttempts(): Int = synchronized(lock) { options.maxLoginAttempts }

    fun loginTimeoutSeconds(): Long = synchronized(lock) { options.loginTimeoutSeconds.toLong() }

    private fun ensurePersisted() {
        try {
            Files.createDirectories(configDir)
            val tempPath = configPath.resolveSibling("${configPath.fileName}.tmp")
            Files.newBufferedWriter(
                tempPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ).use { writer ->
                gson.toJson(PersistedData(options.copy(), serializeUsers()), writer)
            }
            try {
                Files.move(
                    tempPath,
                    configPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (ex: IOException) {
                Files.move(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.copy(
                configPath,
                backupPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
            )
        } catch (ex: IOException) {
            logger.error("Failed to persist whitelist data", ex)
        }
    }

    private fun serializeUsers(): MutableList<String> {
        return users.values
            .filter { !it.username.isBlank() }
            .sortedBy { it.username.lowercase(Locale.ROOT) }
            .mapTo(mutableListOf()) { user ->
                val hash = user.passwordHash?.takeIf { it.isNotBlank() }
                if (hash != null) {
                    "${user.username}$USER_DELIMITER$hash"
                } else {
                    "${user.username}$USER_DELIMITER"
                }
            }
    }

    private fun read(path: Path): PersistedData? {
        if (!Files.exists(path)) {
            return null
        }
        return try {
            Files.newBufferedReader(path).use { reader ->
                gson.fromJson(reader, PersistedData::class.java)
            }
        } catch (ex: JsonParseException) {
            logger.error("Failed to parse {}", path, ex)
            null
        } catch (ex: IOException) {
            logger.error("Failed to read {}", path, ex)
            null
        }
    }

    private fun applyState(input: PersistedData) {
        val sanitizedOptions = sanitizeOptions(input.config)
        val sanitizedUsers = sanitizeUsers(input.users)
        options = sanitizedOptions
        users.clear()
        users.putAll(sanitizedUsers)
    }

    private fun sanitizeOptions(raw: ConfigOptions?): ConfigOptions {
        val defaults = ConfigOptions()
        if (raw == null) {
            return defaults
        }
        val minLength = raw.minPasswordLength.coerceIn(MIN_PASSWORD_LENGTH_MIN, BCRYPT_MAX_INPUT_LENGTH)
        val maxAttempts = raw.maxLoginAttempts.coerceAtLeast(MIN_LOGIN_ATTEMPTS)
        val timeout = raw.loginTimeoutSeconds.coerceAtLeast(MIN_LOGIN_TIMEOUT_SECONDS)
        return ConfigOptions(minLength, maxAttempts, timeout)
    }

    private fun sanitizeUsers(raw: MutableList<String>?): Map<String, StoredUser> {
        if (raw == null) {
            return emptyMap()
        }
        val sanitized = LinkedHashMap<String, StoredUser>()
        raw.forEach { entry ->
            if (entry.isNullOrBlank()) {
                return@forEach
            }
            val parts = entry.split(USER_DELIMITER, limit = 2)
            val username = parts.getOrNull(0)?.trim()?.takeUnless { it.isEmpty() } ?: return@forEach
            val normalized = normalize(username)
            val hash = parts.getOrNull(1)?.trim()?.takeUnless { it.isEmpty() }
            sanitized[normalized] = StoredUser(username, hash)
        }
        return sanitized
    }

    private fun normalize(username: String): String = username.lowercase(Locale.ROOT)

    companion object {
        private const val CONFIG_FILE = "owl.json"
        private const val BACKUP_FILE = "owl_backup.json"
        private const val USER_DELIMITER = ":"
        private const val DEFAULT_MIN_PASSWORD_LENGTH = 5
        private const val DEFAULT_MAX_LOGIN_ATTEMPTS = 5
        private const val DEFAULT_LOGIN_TIMEOUT_SECONDS = 300
        private const val MIN_PASSWORD_LENGTH_MIN = 1
        private const val BCRYPT_MAX_INPUT_LENGTH = 72
        private const val MIN_LOGIN_ATTEMPTS = 1
        private const val MIN_LOGIN_TIMEOUT_SECONDS = 10
    }
}
