package com.mitv.player

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.File

/** Todo el catálogo descargado, para guardarlo en la tele y abrir rápido. */
class CatalogData(
    val key: String,
    val savedAt: Long,
    val liveCats: List<Category>,
    val vodCats: List<Category>,
    val seriesCats: List<Category>,
    val live: List<LiveChannel>,
    val movies: List<Movie>,
    val series: List<Series>,
)

/** Guarda y lee el catálogo en un archivo de la app (sin pasar todo a texto en memoria). */
object CatalogCache {

    fun save(file: File, d: CatalogData) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        JsonWriter(tmp.bufferedWriter(Charsets.UTF_8)).use { w ->
            w.beginObject()
            w.name("v").value(1)
            w.name("key").value(d.key)
            w.name("savedAt").value(d.savedAt)
            fun cats(name: String, list: List<Category>) {
                w.name(name).beginArray()
                list.forEach { w.beginArray().value(it.id).value(it.name).endArray() }
                w.endArray()
            }
            cats("liveCats", d.liveCats)
            cats("vodCats", d.vodCats)
            cats("seriesCats", d.seriesCats)
            w.name("live").beginArray()
            d.live.forEach { w.beginArray().value(it.id).value(it.name).value(it.icon).value(it.categoryId).value(it.num.toLong()).endArray() }
            w.endArray()
            w.name("movies").beginArray()
            d.movies.forEach {
                w.beginArray().value(it.id).value(it.name).value(it.poster).value(it.categoryId)
                    .value(it.added).value(it.rating).value(it.ext).value(it.genre).endArray()
            }
            w.endArray()
            w.name("series").beginArray()
            d.series.forEach {
                w.beginArray().value(it.id).value(it.name).value(it.poster).value(it.backdrop).value(it.categoryId)
                    .value(it.updated).value(it.rating).value(it.plot).value(it.genre).endArray()
            }
            w.endArray()
            w.endObject()
        }
        tmp.renameTo(file)
    }

    /** Regresa el catálogo guardado si es de esta misma cuenta; si no, null. */
    fun load(file: File, key: String): CatalogData? {
        if (!file.exists()) return null
        return runCatching {
            JsonReader(file.bufferedReader(Charsets.UTF_8)).use { r ->
                var k = ""
                var savedAt = 0L
                var liveCats = emptyList<Category>()
                var vodCats = emptyList<Category>()
                var seriesCats = emptyList<Category>()
                var live = emptyList<LiveChannel>()
                var movies = emptyList<Movie>()
                var series = emptyList<Series>()
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "key" -> {
                            k = r.nextString()
                            if (k != key) return null
                        }
                        "savedAt" -> savedAt = r.nextLong()
                        "liveCats" -> liveCats = rows(r) { Category(it.s(), it.s(), Kind.LIVE) }
                        "vodCats" -> vodCats = rows(r) { Category(it.s(), it.s(), Kind.MOVIE) }
                        "seriesCats" -> seriesCats = rows(r) { Category(it.s(), it.s(), Kind.SERIES) }
                        "live" -> live = rows(r) { LiveChannel(it.s(), it.s(), it.sn(), it.s(), it.l().toInt()) }
                        "movies" -> movies = rows(r) {
                            Movie(it.s(), it.s(), it.sn(), it.s(), it.l(), it.d(), it.s(), it.sn())
                        }
                        "series" -> series = rows(r) {
                            Series(it.s(), it.s(), it.sn(), it.sn(), it.s(), it.l(), it.d(), it.sn(), it.sn())
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (k != key) null
                else CatalogData(k, savedAt, liveCats, vodCats, seriesCats, live, movies, series)
            }
        }.getOrNull()
    }

    /** Lector de una fila guardada como arreglo [a, b, c…]. */
    private class Cells(private val r: JsonReader) {
        fun s(): String = sn() ?: ""
        fun sn(): String? = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else r.nextString()
        fun l(): Long = if (r.peek() == JsonToken.NULL) { r.nextNull(); 0L } else r.nextLong()
        fun d(): Double = if (r.peek() == JsonToken.NULL) { r.nextNull(); 0.0 } else r.nextDouble()
    }

    private fun <T> rows(r: JsonReader, read: (Cells) -> T): List<T> {
        val out = ArrayList<T>()
        val cells = Cells(r)
        r.beginArray()
        while (r.hasNext()) {
            r.beginArray()
            out += read(cells)
            while (r.hasNext()) r.skipValue()
            r.endArray()
        }
        r.endArray()
        return out
    }
}
