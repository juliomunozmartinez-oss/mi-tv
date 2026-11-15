package com.mitv.player

enum class Kind { LIVE, MOVIE, SERIES }

data class Category(val id: String, val name: String, val kind: Kind) {
    /** Llave única entre los tres tipos, p. ej. "vod:12". */
    val key: String get() = keyOf(kind, id)

    companion object {
        fun keyOf(kind: Kind, id: String) = when (kind) {
            Kind.LIVE -> "live:$id"
            Kind.MOVIE -> "vod:$id"
            Kind.SERIES -> "series:$id"
        }
    }
}

data class LiveChannel(
    val id: String,
    val name: String,
    val icon: String?,
    val categoryId: String,
    val num: Int,
)

data class Movie(
    val id: String,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val added: Long,
    val rating: Double,
    val ext: String,
    val genre: String? = null,
)

data class Series(
    val id: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val categoryId: String,
    val updated: Long,
    val rating: Double,
    val plot: String?,
    val genre: String? = null,
)

data class Episode(
    val id: String,
    val season: Int,
    val num: Int,
    val title: String,
    val ext: String,
    val image: String?,
    val plot: String?,
    val durationSecs: Int,
)

data class SeriesDetail(
    val series: Series?,
    val name: String,
    val plot: String?,
    val backdrop: String?,
    val poster: String?,
    val genre: String?,
    val year: String?,
    val rating: String?,
    val seasons: Map<Int, List<Episode>>,
) {
    val allEpisodes: List<Episode> by lazy { seasons.keys.sorted().flatMap { seasons[it].orEmpty() } }

    fun next(after: Episode): Episode? {
        val list = allEpisodes
        val i = list.indexOfFirst { it.id == after.id }
        return if (i >= 0 && i + 1 < list.size) list[i + 1] else null
    }
}

data class MovieDetail(
    val name: String,
    val plot: String?,
    val backdrop: String?,
    val poster: String?,
    val genre: String?,
    val year: String?,
    val rating: String?,
    val durationSecs: Int,
    val ext: String,
)

data class AccountInfo(val status: String, val expDate: Long?)

data class Profile(val id: String, val name: String, val kids: Boolean, val color: Int) {
    val initial: String get() = name.trim().take(1).uppercase().ifBlank { "?" }
}

/** Un programa de la guía de TV en vivo. Horas en segundos (epoch). */
data class EpgEntry(val title: String, val description: String?, val start: Long, val end: Long) {
    fun progress(nowSec: Long): Float =
        if (end > start) ((nowSec - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f
}
