package com.mitv.player

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : AppCompatActivity() {

    private enum class Tab(val label: String) {
        HOME("Inicio"), LIVE("TV en vivo"), MOVIES("Películas"), SERIES("Series"), SEARCH("Buscar")
    }

    private lateinit var profile: Profile
    private lateinit var prefs: Prefs
    private lateinit var store: ProfileStore
    private var view: CatalogView? = null

    private lateinit var rows: RecyclerView
    private lateinit var adapter: RowsAdapter
    private lateinit var tabsBox: LinearLayout
    private lateinit var searchBox: View
    private lateinit var searchInput: EditText
    private lateinit var message: TextView
    private lateinit var loading: View

    private val tabViews = mutableListOf<TextView>()
    private var tab = Tab.HOME
    private var renderedSignature: String? = null
    private var searchJob: Job? = null
    private var heroJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Profiles.byId(intent.getStringExtra(Nav.EXTRA_PROFILE))
        if (p == null) {
            finish()
            return
        }
        profile = p
        prefs = Prefs(this)
        store = ProfileStore(this, profile.id)
        setContentView(R.layout.activity_home)

        rows = findViewById(R.id.rows)
        tabsBox = findViewById(R.id.tabs)
        searchBox = findViewById(R.id.searchBox)
        searchInput = findViewById(R.id.searchInput)
        message = findViewById(R.id.message)
        loading = findViewById(R.id.loading)

        adapter = RowsAdapter(
            onCard = { row, i -> Nav.open(this, profile, row, i) },
            onHeroPlay = ::heroPlay,
            onHeroInfo = { h -> Nav.detail(this, profile, h.kind, h.id) },
        )
        rows.layoutManager = LinearLayoutManager(this)
        rows.adapter = adapter
        rows.itemAnimator = null
        rows.setItemViewCacheSize(6)

        val inf = LayoutInflater.from(this)
        Tab.values().forEach { t ->
            val tv = inf.inflate(R.layout.item_season, tabsBox, false) as TextView
            tv.text = t.label
            tv.setOnClickListener { selectTab(t) }
            tabsBox.addView(tv)
            tabViews += tv
        }
        val avatar = findViewById<View>(R.id.avatar)
        avatar.background = Ui.circle(profile.color)
        findViewById<TextView>(R.id.avatarInitial).text = profile.initial
        avatar.setOnClickListener { finish() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = scheduleSearch(s?.toString().orEmpty())
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val focused = currentFocus
                if (focused != null && !isInTopBar(focused)) {
                    rows.scrollToPosition(0)
                    tabViews[tab.ordinal].requestFocus()
                } else {
                    finish()
                }
            }
        })
        markTabs()
    }

    override fun onResume() {
        super.onResume()
        if (!::profile.isInitialized) return
        if (!prefs.isConfigured) {
            showMessage("Falta configurar el servidor.\nRegresa a perfiles y entra a «Ajustes».")
            return
        }
        if (Catalog.loadedKey == prefs.accountKey && view != null) {
            // Volvimos de ver algo: refrescar «Seguir viendo» si cambió.
            if (tab == Tab.HOME && homeSignature() != renderedSignature) render()
            return
        }
        loading.visibility = View.VISIBLE
        message.visibility = View.GONE
        lifecycleScope.launch {
            try {
                Catalog.ensureLoaded(prefs)
                view = withContext(Dispatchers.Default) { CatalogView(prefs, profile) }
                render()
                tabViews[tab.ordinal].post { focusFirstContent() }
            } catch (e: Exception) {
                showMessage("No se pudo cargar el catálogo.\n\n${XtreamClient.friendlyError(e)}")
                tabViews[tab.ordinal].requestFocus()
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    private fun isInTopBar(v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p.id == R.id.topBar) return true
            p = p.parent as? View
        }
        return false
    }

    private fun markTabs() {
        tabViews.forEachIndexed { i, tv -> tv.isSelected = i == tab.ordinal }
    }

    private fun selectTab(t: Tab) {
        if (t == tab && t != Tab.SEARCH) {
            focusFirstContent()
            return
        }
        tab = t
        markTabs()
        render()
        if (t == Tab.SEARCH) searchInput.requestFocus()
    }

    private fun focusFirstContent() {
        rows.post {
            val first = rows.layoutManager?.findViewByPosition(0) ?: return@post
            first.requestFocus()
        }
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    // ---------- Construcción de filas ----------

    private fun movieCard(m: Movie) = Card(Kind.MOVIE, m.id, m.name, image = m.poster)
    private fun seriesCard(s: Series) = Card(Kind.SERIES, s.id, s.name, image = s.poster)
    private fun liveCard(c: LiveChannel) = Card(Kind.LIVE, c.id, c.name, image = c.icon, logo = true)
    private fun resumeCard(r: Resume) =
        Card(r.kind, r.id, r.title, r.subtitle, r.image, progress = r.progress, resume = r)

    private fun homeSignature(): String =
        store.continueWatching().joinToString { it.key + it.updatedAt } + "|" + store.myList.joinToString()

    private fun render() {
        val v = view ?: return
        searchBox.visibility = if (tab == Tab.SEARCH) View.VISIBLE else View.GONE
        rows.setPadding(0, if (tab == Tab.SEARCH) 170.dp else 72.dp, 0, 80.dp)
        message.visibility = View.GONE
        heroJob?.cancel()

        var hero: Hero? = null
        val list = mutableListOf<Row>()
        when (tab) {
            Tab.HOME -> {
                renderedSignature = homeSignature()
                val continueRow = store.continueWatching().filter { v.canSee(it.key) }.map(::resumeCard)
                if (continueRow.isNotEmpty()) list += Row("Seguir viendo", continueRow, wide = true)

                val myList = store.myList.filter(v::canSee).mapNotNull { key ->
                    val (type, id) = key.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    when (type) {
                        "movie" -> Catalog.movieById[id]?.let(::movieCard)
                        "series" -> Catalog.seriesById[id]?.let(::seriesCard)
                        else -> null
                    }
                }
                if (myList.isNotEmpty()) list += Row("Mi lista", myList, wide = false)

                val liveTitle = if (profile.kids) "Canales infantiles" else "Canales de México"
                val liveSource = if (profile.kids) v.live else v.mexicoLive.ifEmpty { v.live }
                if (liveSource.isNotEmpty()) list += Row(liveTitle, liveSource.take(40).map(::liveCard), wide = true)

                val recentMovies = v.movies.sortedByDescending { it.added }.take(30)
                if (recentMovies.isNotEmpty()) list += Row("Películas recién agregadas", recentMovies.map(::movieCard), false)
                val recentSeries = v.series.sortedByDescending { it.updated }.take(30)
                if (recentSeries.isNotEmpty()) list += Row("Series con episodios nuevos", recentSeries.map(::seriesCard), false)

                v.vodCats.take(6).forEach { c ->
                    val items = v.moviesIn(c.id)
                    if (items.isNotEmpty()) list += Row(c.name, items.take(30).map(::movieCard), false)
                }
                v.seriesCats.take(4).forEach { c ->
                    val items = v.seriesIn(c.id)
                    if (items.isNotEmpty()) list += Row(c.name, items.take(30).map(::seriesCard), false)
                }
                hero = pickMovieHero(recentMovies) ?: pickSeriesHero(recentSeries)
            }
            Tab.LIVE -> v.liveCats.forEach { c ->
                val items = v.liveIn(c.id)
                if (items.isNotEmpty()) list += Row(c.name, items.map(::liveCard), wide = true)
            }
            Tab.MOVIES -> {
                v.vodCats.forEach { c ->
                    val items = v.moviesIn(c.id)
                    if (items.isNotEmpty()) list += Row(c.name, items.map(::movieCard), false)
                }
                hero = pickMovieHero(v.movies.sortedByDescending { it.added }.take(20))
            }
            Tab.SERIES -> {
                v.seriesCats.forEach { c ->
                    val items = v.seriesIn(c.id)
                    if (items.isNotEmpty()) list += Row(c.name, items.map(::seriesCard), false)
                }
                hero = pickSeriesHero(v.series.sortedByDescending { it.updated }.take(20))
            }
            Tab.SEARCH -> {
                adapter.submit(null, emptyList())
                runSearch(searchInput.text.toString())
                return
            }
        }
        if (list.isEmpty() && hero == null) {
            showMessage(
                if (profile.kids) "No hay contenido infantil todavía.\nUn adulto puede elegir categorías en Ajustes."
                else "No hay contenido en esta sección."
            )
        }
        adapter.submit(hero, list)
        rows.scrollToPosition(0)
        hero?.let(::loadHeroDetails)
    }

    private fun pickMovieHero(candidates: List<Movie>): Hero? {
        val m = candidates.filter { it.poster != null }.take(10).randomOrNull() ?: return null
        return Hero("PELÍCULA", Kind.MOVIE, m.id, m.name, Ui.ratingText(m.rating.toString()), null, m.poster)
    }

    private fun pickSeriesHero(candidates: List<Series>): Hero? {
        val s = candidates.filter { it.backdrop != null || it.poster != null }.take(10).randomOrNull() ?: return null
        return Hero("SERIE", Kind.SERIES, s.id, s.name, Ui.ratingText(s.rating.toString()), s.plot, s.backdrop ?: s.poster)
    }

    /** Trae la imagen horizontal y la sinopsis del destacado. */
    private fun loadHeroDetails(h: Hero) {
        heroJob = lifecycleScope.launch {
            runCatching {
                val client = prefs.client()
                if (h.kind == Kind.MOVIE) {
                    val movie = Catalog.movieById[h.id] ?: return@runCatching
                    val d = client.movieDetail(movie)
                    h.plot = d.plot
                    h.image = d.backdrop ?: h.image
                    h.meta = listOfNotNull(d.year, Ui.ratingText(d.rating), Ui.duration(d.durationSecs), d.genre)
                        .joinToString("  ·  ").ifBlank { null }
                } else {
                    val d = Catalog.seriesDetails[h.id]
                        ?: client.seriesDetail(h.id, Catalog.seriesById[h.id]).also { Catalog.seriesDetails[h.id] = it }
                    h.plot = d.plot ?: h.plot
                    h.image = d.backdrop ?: h.image
                    val seasons = d.seasons.size
                    h.meta = listOfNotNull(
                        d.year, Ui.ratingText(d.rating),
                        if (seasons > 0) "$seasons temporada${if (seasons > 1) "s" else ""}" else null, d.genre,
                    ).joinToString("  ·  ").ifBlank { null }
                }
                adapter.heroChanged()
            }
        }
    }

    private fun heroPlay(h: Hero) {
        if (h.kind == Kind.MOVIE) {
            val start = store.get(Resume.movieKey(h.id))?.positionMs ?: 0L
            Nav.playMovie(this, profile, h.id, start)
        } else {
            Nav.detail(this, profile, h.kind, h.id)
        }
    }

    // ---------- Búsqueda ----------

    private fun scheduleSearch(q: String) {
        if (tab != Tab.SEARCH) return
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(350)
            runSearch(q)
        }
    }

    private fun runSearch(q: String) {
        val v = view ?: return
        val query = Ui.normalize(q.trim())
        if (query.length < 2) {
            adapter.submit(null, emptyList())
            showMessage("Escribe al menos 2 letras.\nTip: usa el micrófono del control o el teclado de tu celular.")
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                val out = mutableListOf<Row>()
                val movies = v.movies.filter { Ui.normalize(it.name).contains(query) }.take(60)
                val series = v.series.filter { Ui.normalize(it.name).contains(query) }.take(60)
                val live = v.live.filter { Ui.normalize(it.name).contains(query) }.take(60)
                if (movies.isNotEmpty()) out += Row("Películas", movies.map(::movieCard), false)
                if (series.isNotEmpty()) out += Row("Series", series.map(::seriesCard), false)
                if (live.isNotEmpty()) out += Row("Canales", live.map(::liveCard), true)
                out
            }
            adapter.submit(null, result)
            if (result.isEmpty()) showMessage("Sin resultados para «$q».") else message.visibility = View.GONE
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
