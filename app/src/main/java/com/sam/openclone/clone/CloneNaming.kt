package com.sam.openclone.clone

/** A clone's package name plus the ordinal shown to the user. */
internal class CloneName(val packageName: String, val ordinal: Int) {
    /** Distinguishes the clone in the launcher, e.g. "Signal (2)". */
    fun label(originalLabel: String): String = "$originalLabel ($ordinal)"
}

/**
 * Picks the identity a new clone will install under.
 *
 * Clones are numbered from 2, counting the original as the first copy, so the
 * first clone of `com.example.app` is `com.example.app.clone2` labelled
 * "Example (2)". Cloning a clone strips the existing suffix first, so the
 * numbering stays flat instead of producing `…clone2.clone2`.
 */
internal object CloneNaming {

    private val CLONE_SUFFIX = Regex("""\.clone\d+$""")

    fun originalPackageOf(packageName: String): String =
        CLONE_SUFFIX.replace(packageName, "")

    fun isClone(packageName: String): Boolean = CLONE_SUFFIX.containsMatchIn(packageName)

    /** Returns the lowest unused clone identity for [packageName]. */
    fun allocate(packageName: String, installed: Set<String>): CloneName {
        val base = originalPackageOf(packageName)
        var ordinal = 2
        while ("$base.clone$ordinal" in installed) ordinal++
        return CloneName("$base.clone$ordinal", ordinal)
    }
}
