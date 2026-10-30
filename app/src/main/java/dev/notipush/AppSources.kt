package dev.notipush

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

/** Package names alone don't identify OxygenOS clones: each clone has its own user ID. */
class AppSources(private val context: Context) {
    private val pm = context.packageManager
    private val owner = Process.myUid() / 100_000
    private val oplus: Pair<Class<*>, Any>? by lazy {
        runCatching {
            val type = Class.forName("com.oplus.multiapp.OplusMultiAppManager")
            type to type.getMethod("getInstance").invoke(null)!!
        }.getOrNull()
    }
    private fun clonePackages(user: Int): List<String> = runCatching {
        val (type, manager) = oplus ?: return emptyList()
        // LIST_TYPE_CREATED is 0 in the device's public vendor extension.
        (type.getMethod("getMultiAppList", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(manager, 0, user) as? List<*>)?.filterIsInstance<String>().orEmpty()
    }.getOrDefault(emptyList())
    private fun cloneAlias(pkg: String, user: Int): String? = runCatching {
        val (type, manager) = oplus ?: return null
        (type.getMethod("getMultiAppAlias", String::class.java, Int::class.javaPrimitiveType)
            .invoke(manager, pkg, user) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
    fun label(pkg: String, user: Int): String {
        if (user != owner) cloneAlias(pkg, user)?.let { return it }
        val launcherLabel = runCatching {
            context.getSystemService(LauncherApps::class.java)
                .getActivityList(pkg, UserHandle.getUserHandleForUid(user * 100_000))
                .firstOrNull()?.label?.toString()
        }.getOrNull()
        val base = launcherLabel ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        return if (user == owner) base else "$base (profile $user)"
    }
    data class Catalog(val sources: List<SourceApp>, val installed: Set<String>)
    fun discover(observed: List<SourceApp> = emptyList()): Catalog {
        val excluded = setOf(context.packageName, "net.superblock.pushover")
        val main = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { SourceApp(it.activityInfo.packageName, it.loadLabel(pm).toString(), owner) }
            .filter { it.packageName !in excluded }
        val installed = main.map { it.key }.toMutableSet()
        val starters = starterApps.map { it.copy(userId = owner, label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it.packageName, 0)).toString() }.getOrDefault(it.label)) }
        starters.forEach { if (runCatching { pm.getApplicationInfo(it.packageName, 0) }.isSuccess) installed.add(it.key) }
        val standardClones = context.getSystemService(UserManager::class.java).userProfiles.flatMap { handle ->
            runCatching { context.getSystemService(LauncherApps::class.java).getActivityList(null, handle) }
                .getOrDefault(emptyList()).filter { it.applicationInfo.uid / 100_000 != owner }
                .filter { it.componentName.packageName !in excluded }.map { activity ->
                    val user = activity.applicationInfo.uid / 100_000
                    val pkg = activity.componentName.packageName
                    SourceApp(pkg, label(pkg, user), user).also { installed.add(it.key) }
                }
        }
        // OxygenOS reserves 990–999 for clone users; its vendor API can expose clones
        // that aren't included in the standard launcher profile list.
        val vendorClones = if (oplus != null) (990..999).flatMap { user ->
            clonePackages(user).filter { it !in excluded }.map { pkg ->
                SourceApp(pkg, label(pkg, user), user).also { installed.add(it.key) }
            }
        } else emptyList()
        val seen = observed.map { it.copy(label = label(it.packageName, it.userId)) }.onEach { installed.add(it.key) }
        val clones = standardClones + vendorClones + seen
        return Catalog((starters + main.sortedBy { it.label.lowercase() } + clones.sortedBy { it.label.lowercase() })
            .distinctBy { it.key }, installed)
    }
}
