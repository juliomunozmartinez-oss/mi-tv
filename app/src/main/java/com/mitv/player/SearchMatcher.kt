package com.mitv.player

/**
 * Búsqueda tolerante: sin acentos, sin etiquetas como «4K», «LAT» o el año,
 * y acepta pequeños errores de escritura («andy mac» encuentra «Andi Mack»).
 */
object SearchMatcher {

    private val noise = setOf(
        "4k", "uhd", "fhd", "hd", "sd", "hevc", "x265", "lat", "latino", "cast", "castellano",
        "esp", "es", "sub", "subt", "subtitulado", "dual", "multi", "en", "eng", "mx", "vip", "the",
        "el", "la", "los", "las", "de", "del", "un", "una",
    )

    /** Palabras limpias de un título o de lo que se escribió. */
    fun tokens(text: String): List<String> =
        Ui.normalize(text)
            .replace(Regex("\\(\\d{4}\\)|\\[[^\\]]*\\]"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .split(' ')
            .filter { it.isNotBlank() && it !in noise && !(it.length == 4 && it.toIntOrNull() in 1900..2099) }

    class Prepared(val tokens: List<String>) {
        val joined = tokens.joinToString(" ")
        val compact = tokens.joinToString("")
    }

    fun prepare(title: String) = Prepared(tokens(title))

    /** Puntuación de qué tan bien coincide; 0 = no coincide. */
    fun score(query: List<String>, item: Prepared): Int {
        if (query.isEmpty() || item.tokens.isEmpty()) return 0
        val q = query.joinToString(" ")
        val qCompact = query.joinToString("")
        if (item.joined == q) return 1000
        if (item.joined.startsWith(q)) return 900
        if (query.all { qt -> item.tokens.any { it.startsWith(qt) } }) return 800
        if (item.joined.contains(q) || item.compact.contains(qCompact)) return 700
        // Con errores de escritura: cada palabra buscada se parece a alguna palabra del título.
        var total = 0
        for (qt in query) {
            val best = item.tokens.maxOf { tokenSimilarity(qt, it) }
            if (best == 0) return 0
            total += best
        }
        return 300 + total / query.size
    }

    private fun tokenSimilarity(q: String, t: String): Int {
        if (t.startsWith(q)) return 100
        val allowed = when {
            q.length <= 3 -> 0
            q.length <= 6 -> 1
            else -> 2
        }
        if (allowed == 0) return 0
        // Compara con la palabra completa y con su inicio del mismo largo (para palabras a medio escribir).
        val d = minOf(distance(q, t), distance(q, t.take(q.length)))
        return if (d <= allowed) 90 - d * 20 else 0
    }

    /** Distancia de edición (Levenshtein). */
    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }
}
