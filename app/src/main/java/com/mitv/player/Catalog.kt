package com.mitv.player

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    val isLoaded get() = loadedKey != null

    private val mutex = Mutex()

    /** Carga el catálogo si falta o si cambió la cuenta. Seguro de llamar desde varias pantallas. */
    suspend fun ensureLoaded(prefs: Prefs, force: Boolean = false) {
        mutex.withLock {
            if (!force && loadedKey == prefs.accountKey) return
            load(prefs)
        }
    }

    private suspend fun load(prefs: Prefs) = coroutineScope {
        val client = prefs.client()
        val lc = async { client.categories(Kind.LIVE) }
        val vc = async { client.categories(Kind.MOVIE) }
        val sc = async { client.categories(Kind.SERIES) }
        val l = async { client.liveStreams() }
        val m = async { client.movies() }
        val s = async { client.series() }

        // Canales de México primero; el resto en el orden del proveedor.
        liveCats = lc.await().sortedBy { if (isMexico(it.name)) 0 else 1 }
        vodCats = vc.await()
        seriesCats = sc.await()
        live = l.await()
        movies = m.await()
        series = s.await()
        liveById = live.associateBy { it.id }
        movieById = movies.associateBy { it.id }
        seriesById = series.associateBy { it.id }
        seriesDetails.clear()
        loadedKey = prefs.accountKey

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
}

/** Lo que puede ver un perfil: los infantiles solo ven las categorías permitidas. */
class CatalogView(prefs: Prefs, val profile: Profile) {
    private val allowed: Set<String>? = if (profile.kids) prefs.kidsCategories else null

    private fun ok(c: Category) = allowed == null || c.key in allowed

    val liveCats = Catalog.liveCats.filter(::ok)
    val vodCats = Catalog.vodCats.filter(::ok)
    val seriesCats = Catalog.seriesCats.filter(::ok)

    private val liveIds = liveCats.map { it.id }.toSet()
    private val vodIds = vodCats.map { it.id }.toSet()
    private val seriesIds = seriesCats.map { it.id }.toSet()

    val live = Catalog.live.filter { it.categoryId in liveIds }
    val movies = Catalog.movies.filter { it.categoryId in vodIds }
    val series = Catalog.series.filter { it.categoryId in seriesIds }

    private val liveByCat = live.groupBy { it.categoryId }
    private val moviesByCat = movies.groupBy { it.categoryId }
    private val seriesByCat = series.groupBy { it.categoryId }

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

    val mexicoLive: List<LiveChannel> by lazy {
        liveCats.filter { Catalog.isMexico(it.name) }.flatMap { liveIn(it.id) }
    }
}
