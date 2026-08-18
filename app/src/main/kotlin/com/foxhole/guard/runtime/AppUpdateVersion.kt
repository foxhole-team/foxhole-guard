package com.foxhole.guard.runtime

enum class AppUpdateSeverity {
    NONE,

    BEHIND_ONE,

    BEHIND_TWO,

    BEHIND_MANY,
    ;

    val notifies: Boolean get() = this == BEHIND_MANY

    companion object {
        const val MANY_THRESHOLD = 3

        fun forVersionsBehind(versionsBehind: Int): AppUpdateSeverity =
            when {
                versionsBehind <= 0 -> NONE
                versionsBehind == 1 -> BEHIND_ONE
                versionsBehind == 2 -> BEHIND_TWO
                else -> BEHIND_MANY
            }
    }
}

data class AppUpdateVersion(
    val numbers: List<Long>,
    val preRelease: String,
) : Comparable<AppUpdateVersion> {
    override fun compareTo(other: AppUpdateVersion): Int {
        val width = maxOf(numbers.size, other.numbers.size)
        for (index in 0 until width) {
            val mine = numbers.getOrElse(index) { 0L }
            val theirs = other.numbers.getOrElse(index) { 0L }
            if (mine != theirs) {
                return mine.compareTo(theirs)
            }
        }
        return comparePreRelease(preRelease, other.preRelease)
    }

    companion object {
        fun parseOrNull(raw: String?): AppUpdateVersion? {
            val ordered = appUpdateDisplayVersionName(raw.orEmpty()).substringBefore('+')
            if (ordered.isEmpty() || !ordered[0].isDigit()) {
                return null
            }
            val suffixStart = ordered.indexOfFirst { character -> !character.isDigit() && character != '.' }
            val numericPart = if (suffixStart < 0) ordered else ordered.take(suffixStart)
            val suffix = if (suffixStart < 0) "" else ordered.substring(suffixStart).trimStart('-', '_', '.')
            val numbers =
                numericPart
                    .split('.')
                    .filter(String::isNotEmpty)
                    .map { part -> part.toLongOrNull() ?: return null }
            if (numbers.isEmpty()) {
                return null
            }
            return AppUpdateVersion(numbers = numbers, preRelease = suffix.lowercase())
        }
    }
}

fun appUpdateDisplayVersionName(raw: String): String {
    val trimmed = raw.trim().substringAfterLast('/')
    val carriesVersionPrefix =
        trimmed.length > 1 && trimmed[0].lowercaseChar() == 'v' && trimmed[1].isDigit()
    return if (carriesVersionPrefix) trimmed.substring(1) else trimmed
}

fun appUpdateVersionsBehind(
    installed: AppUpdateVersion,
    latest: AppUpdateVersion,
): Int {
    if (latest <= installed) {
        return 0
    }
    val width = maxOf(installed.numbers.size, latest.numbers.size)
    for (index in 0 until width) {
        val mine = installed.numbers.getOrElse(index) { 0L }
        val theirs = latest.numbers.getOrElse(index) { 0L }
        if (mine == theirs) {
            continue
        }
        val delta = (theirs - mine).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        return if (index == width - 1) delta else maxOf(AppUpdateSeverity.MANY_THRESHOLD, delta)
    }
    return 1
}

private fun comparePreRelease(
    left: String,
    right: String,
): Int {
    if (left == right) {
        return 0
    }
    if (left.isEmpty()) {
        return 1
    }
    if (right.isEmpty()) {
        return -1
    }
    val leftParts = left.split('.', '-')
    val rightParts = right.split('.', '-')
    for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
        val mine = leftParts.getOrNull(index) ?: return -1
        val theirs = rightParts.getOrNull(index) ?: return 1
        val step = compareIdentifier(mine, theirs)
        if (step != 0) {
            return step
        }
    }
    return 0
}

private fun compareIdentifier(
    left: String,
    right: String,
): Int {
    val leftMatch = PRE_RELEASE_IDENTIFIER.matchEntire(left)
    val rightMatch = PRE_RELEASE_IDENTIFIER.matchEntire(right)
    if (leftMatch == null || rightMatch == null) {
        return left.compareTo(right)
    }
    val alphabetic = leftMatch.groupValues[1].compareTo(rightMatch.groupValues[1])
    if (alphabetic != 0) {
        return alphabetic
    }
    return (leftMatch.groupValues[2].toLongOrNull() ?: 0L)
        .compareTo(rightMatch.groupValues[2].toLongOrNull() ?: 0L)
}

private val PRE_RELEASE_IDENTIFIER = Regex("""^([A-Za-z]*)(\d*)$""")
