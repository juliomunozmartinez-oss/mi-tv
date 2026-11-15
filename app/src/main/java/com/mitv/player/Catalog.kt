package com.mitv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Todo el contenido del servidor, cargado una vez y compartido entre pantallas. */
object Catalog {
    var liveCats: List<Category> = emptyList(); private set
    var vodCats: List<Category> = emptyList(); private set
    var seriesCats: List<Category> = emptyList(); private set
    var live: List<LiveChannel> = emptyList(); private set
    var movies: List<Movie> = emptyList(); private set
    var series: List<Series> = emptyList(); private set

    var liveById: Map<String, LiveChannel> = emptyMap(); private set
    var movieById: Map<String, Movie> = emptyMap(); private set
    var seriesById: Map<String, Series> = emptyMap(); private set

    var loadedKey: String? = null; private set

    /** Canales que se pueden cambiar con ▲▼ en el reproductor. */
    var liveQueue: List<LiveChannel> = emptyList()

    /** Detalles de series ya consultados (para pasar al siguiente episodio). */
    val seriesDetails = HashMap<String, SeriesDetail>()

    /** Detalles de películas ya consultados (para el avance automático). */
    val movieDetails = HashMap<String, MovieDetail>()

    /** Guía de programación por canal: (cuándo se pidió, programas). */
    val epg = HashMap<String, Pair<Long, List<EpgEntry>>>()

    suspend fun epgFor(client: XtreamClient, streamId: String): List<EpgEntry> {
        val now = System.currentTimeMillis()
        epg[streamId]?.let { (at, list) ->
            val stillValid = now - at < 5 * 60_000 && (list.isEmpty() || list.first().end * 1000 > now)
            if (stillValid) return list
        }
        val list = runCatching { client.shortEpg(streamId) }.getOrDefault(emptyList())
        epg[streamId] = now to list
        return list
    }

    /** Programación completa por canal: (cuándo se pidió, programas). Se renueva cada hora. */
    private val fullEpgCache = HashMap<String, Pair<Long, List<EpgEntry>>>()

    suspend fun fullEpgFor(client: XtreamClient, streamId: String): List<EpgEntry> {
        val now = System.currentTimeMillis()
        fullEpgCache[streamId]?.let { (at, list) -> if (now - at < 60 * 60_000) return list }
        val list = runCatching { client.fullEpg(streamId) }.getOrDefault(emptyList())
        fullEpgCache[streamId] = now to list
        return list
    }

    suspend fun movieDetail(client: XtreamClient, movie: Movie): MovieDetail =
        movieDetails[movie.id] ?: client.movieDetail(movie).also { movieDetails[movie.id] = it }

    suspend fun seriesDetail(client: XtreamClient, seriesId: String): SeriesDetail =
        seriesDetails[seriesId] ?: client.seriesDetail(seriesId, seriesById[seriesId]).also { seriesDetails[seriesId] = it }

    val isLoaded get() = loadedKey != null

    private val mutex = Mutex()

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshing = false

    /** Cada cuánto se vuelve a descargar el catálogo en segundo plano. */
    private const val REFRESH_MS = 6 * 60 * 60 * 1000L

    /**
     * Carga el catálogo si falta o si cambió la cuenta. Seguro de llamar desde varias pantallas.
     * Primero usa el que está guardado en la tele (abre en segundos) y, si ya es viejo,
     * lo actualiza en segundo plano. force = descargar ya, sin usar lo guardado.
     */
    suspend fun ensureLoaded(prefs: Prefs, force: Boolean = false) {
        mutex.withLock {
            if (!force && loadedKey == prefs.accountKey) return
            if (!force) {
                val cached = withContext(Dispatchers.IO) { CatalogCache.load(prefs.catalogFile(), prefs.accountKey) }
                if (cached != null) {
                    apply(cached, prefs, clearCaches = true)
                    if (System.currentTimeMillis() - cached.savedAt > REFRESH_MS) refreshInBackground(prefs)
                    return
                }
            }
            val data = download(prefs)
            apply(data, prefs, clearCaches = true)
            withContext(Dispatchers.IO) { runCatching { CatalogCache.save(prefs.catalogFile(), data) } }
        }
    }

    private fun refreshInBackground(prefs: Prefs) {
        if (refreshing) return
        refreshing = true
        background.launch {
            try {
                val data = download(prefs)
                runCatching { CatalogCache.save(prefs.catalogFile(), data) }
                withContext(Dispatchers.Main) {
                    if (loadedKey == data.key) apply(data, prefs, clearCaches = false)
                }
            } catch (_: Exception) {
                // Sin internet o servidor caído: seguimos con lo guardado.
            } finally {
                refreshing = false
            }
        }
    }

    private suspend fun download(prefs: Prefs): CatalogData = coroutineScope {
        val client = prefs.client()
        val lc = async { client.categories(Kind.LIVE) }
        val vc = async { client.categories(Kind.MOVIE) }
        val sc = async { client.categories(Kind.SERIES) }
        val l = async { client.liveStreams() }
        val m = async { client.movies() }
        val s = async { client.series() }
        CatalogData(
            key = prefs.accountKey,
            savedAt = System.currentTimeMillis(),
            liveCats = lc.await(),
            vodCats = vc.await(),
            seriesCats = sc.await(),
            live = l.await(),
            movies = m.await(),
            series = s.await(),
        )
    }

    private fun apply(d: CatalogData, prefs: Prefs, clearCaches: Boolean) {
        // Canales de México primero; el resto en el orden del proveedor.
        liveCats = d.liveCats.sortedBy { if (isMexico(it.name)) 0 else 1 }
        vodCats = d.vodCats
        seriesCats = d.seriesCats
        live = d.live
        movies = d.movies
        series = d.series
        liveById = live.associateBy { it.id }
        movieById = movies.associateBy { it.id }
        seriesById = series.associateBy { it.id }
        if (clearCaches) {
            seriesDetails.clear()
            movieDetails.clear()
            epg.clear()
        }
        loadedKey = d.key

        if (!prefs.kidsInitialized) {
            prefs.kidsCategories = autoKidsCategories()
            prefs.kidsInitialized = true
        }
    }

    fun autoKidsCategories(): Set<String> =
        (liveCats + vodCats + seriesCats).filter { isKids(it.name) }.map { it.key }.toSet()

    private val mexicoRegex = Regex("""(^|[^a-z])(mx|mex|m[eé]xico|mexicanos?|azteca|televisa)([^a-z]|$)""", RegexOption.IGNORE_CASE)
    private val kidsRegex = Regex(
        """infantil|kids|ni[ñn][oa]s|animad|animaci[oó]n|caricatura|cartoon|junior|nick|baby|beb[eé]s|peques|discovery\s*k|familiar""",
        RegexOption.IGNORE_CASE,
    )

    fun isMexico(name: String) = mexicoRegex.containsMatchIn(name)
    fun isKids(name: String) = kidsRegex.containsMatchIn(name)

    /** Ordena según lo que eligió el usuario; lo que no eligió conserva el orden del proveedor al final. */
    fun applyOrder(base: List<Category>, order: List<String>): List<Category> {
        if (order.isEmpty()) return base
        val idx = order.withIndex().associate { it.value to it.index }
        return base.sortedBy { idx[it.key] ?: Int.MAX_VALUE }
    }

    fun ordered(kind: Kind, prefs: Prefs): List<Category> = applyOrder(
        when (kind) {
            Kind.LIVE -> liveCats
            Kind.MOVIE -> vodCats
            Kind.SERIES -> seriesCats
        },
        prefs.categoryOrder(kind),
    )
}

/** Secciones por tipo. Se buscan en el género del título y en el nombre de su categoría. */
object Genres {
    val all: List<Pair<String, Regex>> = listOf(
        "Animación" to "animaci|animad|animation|anime|cartoon|caricatura|dibujos",
        "Acción y aventura" to "acci[oó]n|action|aventura|adventure|artes marciales|b[eé]lic|guerra|war",
        "Comedia" to "comedia|comedy|humor|stand.?up",
        "Terror y suspenso" to "terror|horror|miedo|suspenso|suspense|thriller",
        "Ciencia ficción y fantasía" to "ciencia ficci|sci.?fi|science fiction|fantas",
        "Romance" to "romance|rom[aá]ntic|amor",
        "Documentales" to "document|docu",
        "Para toda la familia" to "familia|family|infantil|kids|ni[ñn]os|animaci|animad",
    ).map { (name, re) -> name to Regex(re, RegexOption.IGNORE_CASE) }

    fun matches(regex: Regex, genre: String?, categoryName: String?): Boolean =
        (genre != null && regex.containsMatchIn(genre)) || (categoryName != null && regex.containsMatchIn(categoryName))
}

/** Lo que puede ver un perfil: los infantiles solo ven las categorías permitidas. */
class CatalogView(prefs: Prefs, val profile: Profile) {
    private val allowed: Set<String>? = if (profile.kids) prefs.kidsCategories else null
    private val hidden: Set<String> = if (profile.kids) emptySet() else prefs.hiddenCategories

    private fun ok(c: Category) = (allowed == null || c.key in allowed) && c.key !in hidden

    val liveCats = Catalog.ordered(Kind.LIVE, prefs).filter(::ok)
    val vodCats = Catalog.ordered(Kind.MOVIE, prefs).filter(::ok)
    val seriesCats = Catalog.ordered(Kind.SERIES, prefs).filter(::ok)

    private val liveIds = liveCats.map { it.id }.toSet()
    private val vodIds = vodCats.map { it.id }.toSet()
    private val seriesIds = seriesCats.map { it.id }.toSet()

    val live = Catalog.live.filter { it.categoryId in liveIds }
    val movies = Catalog.movies.filter { it.categoryId in vodIds }
    val series = Catalog.series.filter { it.categoryId in seriesIds }

    private val liveByCat = live.groupBy { it.categoryId }

    /** Películas: lo más nuevo primero (como en las apps de streaming). */
    private val moviesByCat = movies.sortedByDescending { it.added }.groupBy { it.categoryId }

    /** Series: las mejor calificadas y con episodios recientes primero. */
    private val seriesByCat = series.sortedByDescending { seriesScore(it) }.groupBy { it.categoryId }

    fun liveIn(catId: String) = liveByCat[catId].orEmpty()
    fun moviesIn(catId: String) = moviesByCat[catId].orEmpty()
    fun seriesIn(catId: String) = seriesByCat[catId].orEmpty()

    fun canSee(key: String): Boolean {
        if (allowed == null) return true
        val (type, id) = key.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when (type) {
            "movie" -> Catalog.movieById[id]?.categoryId in vodIds
            "series" -> Catalog.seriesById[id]?.categoryId in seriesIds
            "live" -> Catalog.liveById[id]?.categoryId in liveIds
            else -> false
        }
    }

    /** Lo más nuevo primero (se calcula una sola vez, fuera de la pantalla). */
    val recentMovies: List<Movie> = movies.sortedByDescending { it.added }
    val recentSeries: List<Series> = series.sortedByDescending { it.updated }

    /** Series ordenadas por calificación y novedad (para las secciones por tipo). */
    private val rankedSeries: List<Series> = series.sortedByDescending { seriesScore(it) }

    /** Filas por tipo (Animación, Comedia…), con lo más reciente primero. */
    val movieGenres: List<Pair<String, List<Movie>>> = Genres.all.map { (name, re) ->
        // El nombre de cada categoría se revisa una sola vez; el género de cada título solo si existe.
        val catMatch = vodCats.filter { re.containsMatchIn(it.name) }.map { it.id }.toSet()
        name to recentMovies.asSequence()
            .filter { it.categoryId in catMatch || (it.genre != null && re.containsMatchIn(it.genre)) }
            .take(40).toList()
    }.filter { it.second.size >= 4 }

    val seriesGenres: List<Pair<String, List<Series>>> = Genres.all.map { (name, re) ->
        val catMatch = seriesCats.filter { re.containsMatchIn(it.name) }.map { it.id }.toSet()
        name to rankedSeries.asSequence()
            .filter { it.categoryId in catMatch || (it.genre != null && re.containsMatchIn(it.genre)) }
            .take(40).toList()
    }.filter { it.second.size >= 4 }

    /** Todas las películas/series de un tipo (para «Ver todo»). */
    fun moviesOfGenre(name: String): List<Movie> {
        val re = Genres.all.firstOrNull { it.first == name }?.second ?: return emptyList()
        val catMatch = vodCats.filter { re.containsMatchIn(it.name) }.map { it.id }.toSet()
        return recentMovies.filter { it.categoryId in catMatch || (it.genre != null && re.containsMatchIn(it.genre)) }
    }

    fun seriesOfGenre(name: String): List<Series> {
        val re = Genres.all.firstOrNull { it.first == name }?.second ?: return emptyList()
        val catMatch = seriesCats.filter { re.containsMatchIn(it.name) }.map { it.id }.toSet()
        return rankedSeries.filter { it.categoryId in catMatch || (it.genre != null && re.containsMatchIn(it.genre)) }
    }

    val mexicoLive: List<LiveChannel> =
        liveCats.filter { Catalog.isMexico(it.name) }.flatMap { liveIn(it.id) }

    /**
     * Puntuación para ordenar series: calificación (0–10) + hasta 10 puntos por novedad,
     * que bajan poco a poco durante unos 10 meses desde el último episodio.
     */
    private fun seriesScore(s: Series): Double {
        val ageDays = if (s.updated > 0) (nowSec - s.updated) / 86_400.0 else 3650.0
        val freshness = (10.0 - ageDays / 30.0).coerceIn(0.0, 10.0)
        return s.rating.coerceIn(0.0, 10.0) + freshness
    }

    private val nowSec: Long get() = System.currentTimeMillis() / 1000
}
