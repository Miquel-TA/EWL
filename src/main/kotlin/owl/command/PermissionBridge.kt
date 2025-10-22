package owl.command

import net.minecraft.server.command.ServerCommandSource
import org.slf4j.LoggerFactory
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

object PermissionBridge {
    private val logger = LoggerFactory.getLogger("OwlPermissions")
    private val fallbackWarned = AtomicBoolean(false)
    private val checkMethod: Method? = run {
        try {
            val clazz = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions")
            clazz.getMethod(
                "check",
                ServerCommandSource::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType
            )
        } catch (ex: ClassNotFoundException) {
            null
        } catch (ex: NoSuchMethodException) {
            logger.warn("Fabric Permissions API found but expected check method is missing. Falling back to defaults.")
            null
        }
    }

    fun hasPermission(source: ServerCommandSource, node: String, defaultValue: Boolean): Boolean {
        val method = checkMethod
        if (method != null) {
            try {
                val result = method.invoke(null, source, node, defaultValue)
                if (result is Boolean) {
                    return result
                }
                logger.warn(
                    "Fabric Permissions API returned unexpected type {} for node {}. Using fallback handling.",
                    result?.javaClass,
                    node
                )
            } catch (ex: Throwable) {
                logger.warn("Fabric Permissions API invocation failed for node {}. Using fallback handling.", node, ex)
            }
        } else if (fallbackWarned.compareAndSet(false, true)) {
            logger.info(
                "Fabric Permissions API not detected. Falling back to vanilla operator level checks for permission nodes."
            )
        }
        return fallbackCheck(source, defaultValue)
    }

    private fun fallbackCheck(source: ServerCommandSource, defaultValue: Boolean): Boolean {
        if (source.entity == null) {
            return true
        }
        if (defaultValue) {
            return true
        }
        return source.hasPermissionLevel(3)
    }
}
