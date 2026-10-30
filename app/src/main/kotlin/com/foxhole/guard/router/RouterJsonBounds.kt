package com.foxhole.guard.router

internal fun requireBoundedRouterJson(bytes: ByteArray, maxDepth: Int = 32, maxNodes: Int = 16384) {
    require(maxDepth in 1..32 && maxNodes > 0)
    val nesting = ByteArray(maxDepth)
    var depth = 0
    var nodes = 0
    var quoted = false
    var escaped = false
    for (value in bytes) {
        val char = value.toInt().toChar()
        if (quoted) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') quoted = false
            continue
        }
        when (char) {
            '"' -> { quoted = true; nodes++ }
            '{', '[' -> {
                require(depth < maxDepth)
                nesting[depth++] = value
                nodes++
            }
            '}', ']' -> {
                require(depth > 0 && nesting[depth - 1].toInt().toChar() == if (char == '}') '{' else '[')
                depth--
            }
            ',', ':' -> nodes++
        }
        require(nodes <= maxNodes)
    }
    require(depth == 0 && !quoted && !escaped)
}
