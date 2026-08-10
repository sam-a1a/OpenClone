package com.sam.openclone.apk

import java.io.IOException

/** Elements whose named attributes are class references rather than free text. */
private val CLASS_NAME_ATTRIBUTES = mapOf(
    "application" to listOf(
        "name", "backupAgent", "appComponentFactory", "zygotePreloadName", "manageSpaceActivity",
    ),
    "activity" to listOf("name"),
    "activity-alias" to listOf("name", "targetActivity"),
    "service" to listOf("name"),
    "receiver" to listOf("name"),
    "provider" to listOf("name"),
    "instrumentation" to listOf("name"),
)

/** Elements that declare a permission name into the device-wide namespace. */
private val PERMISSION_DECLARATIONS = setOf("permission", "permission-group", "permission-tree")

/** Elements whose `android:name` refers to a permission. */
private val PERMISSION_NAME_ELEMENTS =
    PERMISSION_DECLARATIONS + setOf("uses-permission", "uses-permission-sdk-23")

/** Attributes that refer to a permission, on any element. */
private val PERMISSION_REF_ATTRIBUTES =
    setOf("permission", "readPermission", "writePermission", "targetPermission")

private const val LAUNCHER_CATEGORY = "android.intent.category.LAUNCHER"

internal class RewrittenManifest(
    val originalPackage: String,
    val bytes: ByteArray,
)

/**
 * Rewrites a compiled manifest so its APK installs alongside the original
 * instead of replacing it.
 *
 * The package name is the only identity the system keys on, but changing it in
 * isolation yields an APK that either refuses to install or dies on launch.
 * Everything else here repairs that fallout:
 *
 *  - component names resolve against the *old* package, because they still have
 *    to name classes that exist in the untouched dex
 *  - provider authorities and declared permissions are device-wide unique keys,
 *    and a collision with the original is a hard install failure
 *  - `sharedUserId` cannot survive, since sharing a uid requires the signing
 *    certificate that a clone by definition does not have
 */
internal object ManifestRewriter {

    fun rewrite(manifest: ByteArray, clonePackage: String, cloneLabel: String?): RewrittenManifest {
        val doc = AxmlDocument.parse(manifest)
        val pool = doc.pool

        /** Finds an attribute by name, in the android namespace or in none. */
        fun AxmlChunk.StartElement.attr(name: String, android: Boolean = true): AxmlAttribute? {
            for (a in attributes) {
                if (pool[a.name] != name) continue
                if (android == (a.ns != NO_STRING && pool[a.ns] == ANDROID_NS)) return a
            }
            return null
        }

        /**
         * A compiled attribute only holds a string when it is typed as one; a
         * resource reference stores a resource id in the same field, and reading
         * that as a pool index would silently produce an unrelated string.
         */
        fun AxmlAttribute.stringValue(): String? =
            if (valueType == TYPE_STRING) pool[data] else null

        fun AxmlAttribute.set(value: String) = setString(pool.add(value))

        // One walk, keeping the ancestor chain so a LAUNCHER category can be
        // attributed back to the activity that declares it.
        val stack = ArrayList<AxmlChunk.StartElement>(8)
        val elements = ArrayList<Pair<String, AxmlChunk.StartElement>>()
        val launcherComponents = HashSet<AxmlChunk.StartElement>()
        var root: AxmlChunk.StartElement? = null

        for (chunk in doc.chunks) {
            when (chunk) {
                is AxmlChunk.StartElement -> {
                    val name = pool[chunk.name].orEmpty()
                    stack.add(chunk)
                    elements.add(name to chunk)
                    if (root == null && name == "manifest") root = chunk
                    if (name == "category" && chunk.attr("name")?.stringValue() == LAUNCHER_CATEGORY) {
                        // …/activity/intent-filter/category — the component is two up.
                        val owner = stack.getOrNull(stack.size - 3)
                        if (owner != null && pool[owner.name] in CLASS_NAME_ATTRIBUTES) {
                            launcherComponents.add(owner)
                        }
                    }
                }
                is AxmlChunk.EndElement -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> Unit
            }
        }

        val manifestEl = root ?: throw IOException("Manifest has no <manifest> element")
        val packageAttr = manifestEl.attr("package", android = false)
            ?: throw IOException("Manifest declares no package")
        val originalPackage = packageAttr.stringValue()
            ?: throw IOException("Manifest package is not a literal string")

        // Declared permissions are collected up front so that references
        // elsewhere in the manifest resolve to the same renamed value.
        val permissionRenames = HashMap<String, String>()
        for ((name, el) in elements) {
            if (name !in PERMISSION_DECLARATIONS) continue
            val declared = el.attr("name")?.stringValue() ?: continue
            permissionRenames[declared] =
                if (declared.contains(originalPackage)) {
                    declared.replace(originalPackage, clonePackage)
                } else {
                    // Still has to be unique, or the install trips
                    // INSTALL_FAILED_DUPLICATE_PERMISSION against the original.
                    "$clonePackage.$declared"
                }
        }

        for ((elementName, el) in elements) {
            // Component classes live in the untouched dex under the original
            // package, so relative names expand against it and absolute names
            // are left alone.
            CLASS_NAME_ATTRIBUTES[elementName]?.forEach { attrName ->
                val attr = el.attr(attrName) ?: return@forEach
                val value = attr.stringValue() ?: return@forEach
                val qualified = qualifyClass(value, originalPackage)
                if (qualified != value) attr.set(qualified)
            }

            if (elementName in PERMISSION_NAME_ELEMENTS) {
                el.attr("name")?.let { attr ->
                    permissionRenames[attr.stringValue()]?.let(attr::set)
                }
            }
            for (refAttr in PERMISSION_REF_ATTRIBUTES) {
                el.attr(refAttr)?.let { attr ->
                    permissionRenames[attr.stringValue()]?.let(attr::set)
                }
            }

            // Authorities are a device-wide namespace; a clash blocks the
            // install outright with INSTALL_FAILED_CONFLICTING_PROVIDER.
            if (elementName == "provider") {
                el.attr("authorities")?.let { attr ->
                    attr.stringValue()?.let { authorities ->
                        attr.set(
                            authorities.split(';').joinToString(";") { authority ->
                                if (authority.contains(originalPackage)) {
                                    authority.replace(originalPackage, clonePackage)
                                } else {
                                    "$clonePackage.$authority"
                                }
                            }
                        )
                    }
                }
            }

            // Left alone, the clone's activities would join the original app's
            // task and share its entry in Recents.
            el.attr("taskAffinity")?.let { attr ->
                val value = attr.stringValue()
                if (value != null && value.contains(originalPackage)) {
                    attr.set(value.replace(originalPackage, clonePackage))
                }
            }

            // Only honoured on system images, but if it survived it could make
            // the system treat the clone as an upgrade of the original.
            if (elementName == "original-package") {
                el.attr("name")?.set(clonePackage)
            }

            if (cloneLabel != null && (elementName == "application" || el in launcherComponents)) {
                // Rewritten only where already present. An activity with no
                // label inherits the application's, which is covered here too,
                // and inserting an attribute would disturb resource-id order.
                el.attr("label")?.set(cloneLabel)
            }

            if (elementName == "application") {
                // A debug build's testOnly flag makes the install demand an
                // explicit -t that the installer UI has no way to pass.
                el.attr("testOnly")?.let { el.attributes.remove(it) }
            }
        }

        for (attrName in listOf("sharedUserId", "sharedUserLabel")) {
            manifestEl.attr(attrName)?.let { removeAttribute(manifestEl, it) }
        }

        packageAttr.set(clonePackage)

        return RewrittenManifest(originalPackage, doc.toByteArray())
    }

    /** Mirrors the framework's own relative-to-absolute component name rule. */
    private fun qualifyClass(name: String, pkg: String): String = when {
        name.isEmpty() -> name
        name[0] == '.' -> pkg + name
        !name.contains('.') -> "$pkg.$name"
        else -> name
    }

    /**
     * Drops an attribute, keeping the element's id/class/style back-references
     * aimed at the same attributes they were before.
     */
    private fun removeAttribute(el: AxmlChunk.StartElement, attr: AxmlAttribute) {
        val at = el.attributes.indexOf(attr)
        if (at < 0) return
        el.attributes.removeAt(at)
        el.idIndex = shiftIndex(el.idIndex, at)
        el.classIndex = shiftIndex(el.classIndex, at)
        el.styleIndex = shiftIndex(el.styleIndex, at)
    }

    private fun shiftIndex(index: Int, removedAt: Int): Int = when {
        index == 0 -> 0                       // absent
        index - 1 == removedAt -> 0           // the attribute just removed
        index - 1 > removedAt -> index - 1
        else -> index
    }
}
