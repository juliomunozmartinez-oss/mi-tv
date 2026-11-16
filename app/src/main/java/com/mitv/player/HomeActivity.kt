package com.mitv.player

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private enum class Tab(val label: String) {
        HOME("Inicio"), LIVE("TV en vivo"), MOVIES("Películas"), SERIES("Series"), MYLIST("Mi lista"), SEARCH("Buscar")
    }

    /** El título destacado de cada pestaña (el que tiene botones «Ver» y «Más información»). */
    private data class Featured(
        val label: String,
        val kind: Kind,
        val id: String,
        val title: String,
        var meta: String?,
        var plot: String?,
        var image: String?,
    )

    private lateinit var profile: Profile
    private lateinit var prefs: Prefs
    private lateinit var store: ProfileStore
    private var view: CatalogView? = null
    private var trending: Trending.Result? = null

    private lateinit var rows: RecyclerView
    private lateinit var rowsLayout: LinearLayoutManager
    private lateinit var adapter: RowsAdapter
    private lateinit var tabsBox: LinearLayout
    private lateinit var searchBox: View
    private lateinit var searchInput: EditText
    private lateinit var message: TextView
    private lateinit var loading: View

    private lateinit var previewArea: View
    private lateinit var backdrop: ImageView
    private lateinit var pLabel: TextView
    private lateinit var pTitle: TextView
    private lateinit var pMeta: TextView
    private lateinit var pPlot: TextView
    private lateinit var pEpg: ProgressBar
    private lateinit var heroButtons: View
    private lateinit var btnPlay: TextView
    private lateinit var btnSleep: TextView
    private lateinit var ambient: View
    private lateinit var topBar: View
    private var ambientColor = 0xFF0B1220.toInt()
    private var navHidden = false
    private var ambientJob: Job? = null

    private val tabViews = mutableListOf<TextView>()
    private var tab = Tab.HOME
    private var featured: Featured? = null
    private var renderedSignature: String? = null
    private var searchJob: Job? = null
    private var previewJob: Job? = null
    private var previewKey: String? = null
    private val timeFmt = SimpleDateFormat("HH:mm", Locale("es", "MX"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Profiles.byId(this, intent.getStringExtra(Nav.EXTRA_PROFILE))
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
        previewArea = findViewById(R.id.previewArea)
        backdrop = findViewById(R.id.backdrop)
        pLabel = findViewById(R.id.label)
        pTitle = findViewById(R.id.title)
        pMeta = findViewById(R.id.meta)
        pPlot = findViewById(R.id.plot)
        pEpg = findViewById(R.id.epgProgress)
        heroButtons = findViewById(R.id.heroButtons)
        btnPlay = findViewById(R.id.btnPlay)
        ambient = findViewById(R.id.ambient)
        topBar = findViewById(R.id.topBar)
        paintAmbient(ambientColor)

        adapter = RowsAdapter(
            onCard = { row, i -> Nav.open(this, profile, row, i) },
            onFocus = ::onCardFocused,
            store = store,
        )
        // Las filas no se desplazan solas: al enfocar una fila la acomodamos arriba.
        rowsLayout = object : LinearLayoutManager(this) {
            override fun requestChildRectangleOnScreen(
                parent: RecyclerView, child: View, rect: android.graphics.Rect,
                immediate: Boolean, focusedChildVisible: Boolean,
            ) = false
        }
        rows.layoutManager = rowsLayout
        rows.adapter = adapter
        rows.itemAnimator = null
        rows.setItemViewCacheSize(4)

        val inf = LayoutInflater.from(this)
        Tab.values().forEach { t ->
            val tv = inf.inflate(R.layout.item_season, tabsBox, false) as TextView
            tv.text = t.label
            tv.setOnClickListener { selectTab(t) }
            tabsBox.addView(tv)
            tabViews += tv
        }
        val avatar = findViewById<View>(R.id.avatar)
        Avatars.bind(avatar, findViewById(R.id.avatarInitial), findViewById(R.id.avatarImage), profile)
        avatar.setOnClickListener { finish() }
        btnSleep = findViewById(R.id.btnSleep)
        btnSleep.setOnClickListener { SleepTimer.showDialog(this) { btnSleep.text = SleepTimer.label() } }

        btnPlay.setOnClickListener { featured?.let(::featuredPlay) }
        findViewById<View>(R.id.btnInfo).setOnClickListener {
            featured?.let { Nav.detail(this, profile, it.kind, it.id) }
        }
        val backToFeatured = View.OnFocusChangeListener { _, has -> if (has) showFeatured() }
        btnPlay.onFocusChangeListener = backToFeatured
        findViewById<View>(R.id.btnInfo).onFocusChangeListener = backToFeatured

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
                    showFeatured()
                    setNavHidden(false)
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
        btnSleep.text = SleepTimer.label()
        if (!prefs.isConfigured) {
            showMessage("Falta configurar el servidor.\nRegresa a perfiles y entra a «Ajustes».")
            return
        }
        if (Catalog.loadedKey == prefs.accountKey && view != null) {
            // Volvimos de ver algo: refrescar «Seguir viendo» y recomendaciones si cambió.
            if ((tab == Tab.HOME || tab == Tab.MYLIST) && homeSignature() != renderedSignature) refreshKeepingPlace()
            return
        }
        loading.visibility = View.VISIBLE
        message.visibility = View.GONE
        lifecycleScope.launch {
            try {
                Catalog.ensureLoaded(prefs)
                val v = withContext(Dispatchers.Default) { CatalogView(prefs, profile) }
                view = v
                trending = Trending.cached(profile.id)
                render()
                // Preparar la búsqueda en segundo plano para que «Buscar» abra al instante.
                launch(Dispatchers.Default) { runCatching { SearchIndex.get(prefs, profile, v) } }
                // «Lo más visto» llega después, sin hacer esperar la pantalla.
                launch {
                    val before = trending
                    val fresh = runCatching { Trending.forView(this@HomeActivity, prefs, profile, v) }.getOrNull()
                    trending = fresh
                    val changed = fresh != null && !fresh.isEmpty &&
                        (before == null || before.movies != fresh.movies || before.series != fresh.series)
                    // Si llegó después, se agrega la fila sin mover el foco.
                    if (changed && tab in listOf(Tab.HOME, Tab.MOVIES, Tab.SERIES)) refreshKeepingPlace()
                }
                focusStart()
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
        if (t == Tab.SEARCH) {
            startActivity(
                android.content.Intent(this, SearchActivity::class.java).putExtra(Nav.EXTRA_PROFILE, profile.id)
            )
            return
        }
        if (t == tab) {
            focusStart()
            return
        }
        tab = t
        markTabs()
        render()
        if (t == Tab.SEARCH) searchInput.requestFocus()
    }

    /** Enfoca el botón «Ver» del destacado, o la primera tarjeta si no hay destacado. */
    private fun focusStart() {
        rows.post {
            if (featured != null && heroButtons.visibility == View.VISIBLE) {
                btnPlay.requestFocus()
            } else {
                rowsLayout.findViewByPosition(0)?.requestFocus()
            }
        }
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    // ---------- Zona de avance ----------

    private fun setPreview(label: String, title: String, meta: String?, plot: String?, image: String?) {
        pLabel.text = label
        pTitle.text = title
        pMeta.text = meta ?: ""
        pMeta.visibility = if (meta.isNullOrBlank()) View.GONE else View.VISIBLE
        pPlot.text = plot ?: ""
        pPlot.visibility = if (plot.isNullOrBlank()) View.GONE else View.VISIBLE
        pEpg.visibility = View.GONE
        if (image == null) backdrop.setImageDrawable(null) else backdrop.load(image) { crossfade(true) }
        updateAmbient(image)
    }

    private fun showFeatured() {
        previewJob?.cancel()
        previewKey = null
        val f = featured
        if (f == null) {
            heroButtons.visibility = View.GONE
            setPreview(tab.label.uppercase(), "", null, null, null)
            return
        }
        heroButtons.visibility = View.VISIBLE
        val guide = f.id == GUIDE_ID
        btnPlay.text = if (guide) "📺  Abrir guía" else "▶  Ver"
        findViewById<View>(R.id.btnInfo).visibility = if (guide) View.GONE else View.VISIBLE
        setPreview(f.label, f.title, f.meta, f.plot, f.image)
    }

    private fun onCardFocused(row: Row, index: Int) {
        val rowPos = adapter.indexOf(row)
        // La barra de arriba se esconde al bajar de la primera fila.
        setNavHidden(rowPos >= 1)
        if (!prefs.autoPreview || tab == Tab.SEARCH) return
        val card = row.cards.getOrNull(index) ?: return
        val key = "${card.kind}:${card.id}"
        if (key == previewKey) return
        previewKey = key
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(450)
            heroButtons.visibility = View.GONE
            showCardPreview(card)
        }
    }

    /**
     * ▲ ▼ entre filas los maneja la app: acomoda la fila destino arriba al instante y enfoca
     * el póster donde se quedó esa fila. Así no se «recorre» ni se traba al subir rápido.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = event.keyCode == KeyEvent.KEYCODE_DPAD_UP
        val down = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        if (event.action == KeyEvent.ACTION_DOWN && (up || down) && ::rows.isInitialized) {
            val focused = currentFocus
            val rowPos = focused?.let(::rowIndexOf) ?: -1
            if (rowPos >= 0) {
                val target = rowPos + if (down) 1 else -1
                when {
                    target < 0 -> {
                        setNavHidden(false)
                        if (heroButtons.visibility == View.VISIBLE && previewArea.visibility == View.VISIBLE) {
                            showFeatured()
                            btnPlay.requestFocus()
                        } else {
                            tabViews[tab.ordinal].requestFocus()
                        }
                    }
                    target < adapter.itemCount -> focusRow(target)
                }
                return true
            }
            // Desde los botones del destacado, ▼ va a la primera fila (sin adivinar).
            if (down && focused != null && (focused.id == R.id.btnPlay || focused.id == R.id.btnInfo) && adapter.itemCount > 0) {
                focusRow(0)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Número de fila que contiene a esta vista, o -1 si no está en las filas. */
    private fun rowIndexOf(v: View): Int {
        var child: View = v
        var parent = v.parent
        while (parent is View) {
            if (parent === rows) return rows.getChildAdapterPosition(child)
            child = parent
            parent = (parent as View).parent
        }
        return -1
    }

    private fun focusRow(target: Int) {
        rowsLayout.scrollToPositionWithOffset(target, 0)
        rows.post {
            val rowView = rowsLayout.findViewByPosition(target) ?: return@post
            val rv = rowView.findViewById<RowRecyclerView>(R.id.rowList) ?: return@post
            val lm = rv.layoutManager as? LinearLayoutManager ?: return@post
            val pos = (rv.row?.focusPos ?: 0).coerceIn(0, maxOf(0, (rv.adapter?.itemCount ?: 1) - 1))
            val child = lm.findViewByPosition(pos)
            if (child != null) {
                focusCard(child)
            } else {
                lm.scrollToPositionWithOffset(pos, 0)
                rv.post { lm.findViewByPosition(pos)?.let(::focusCard) }
            }
        }
    }

    private fun focusCard(item: View) {
        (item.findViewById<View>(R.id.frame) ?: item).requestFocus()
    }

    private fun snapRow(position: Int) {
        val scroller = object : LinearSmoothScroller(this) {
            override fun getVerticalSnapPreference() = SNAP_TO_START
            override fun calculateSpeedPerPixel(dm: DisplayMetrics) = 40f / dm.densityDpi
        }
        scroller.targetPosition = position
        rowsLayout.startSmoothScroll(scroller)
    }

    private suspend fun showCardPreview(card: Card) {
        val client = prefs.client()
        when (card.kind) {
            Kind.MOVIE -> {
                val m = Catalog.movieById[card.id] ?: return
                val cached = Catalog.movieDetails[m.id]
                setPreview(
                    "PELÍCULA", m.name, Ui.ratingText(m.rating.toString()), cached?.plot,
                    cached?.backdrop ?: m.poster,
                )
                val d = runCatching { Catalog.movieDetail(client, m) }.getOrNull() ?: return
                if (previewKey != "${card.kind}:${card.id}") return
                setPreview(
                    "PELÍCULA", d.name,
                    listOfNotNull(d.year, Ui.ratingText(d.rating), Ui.duration(d.durationSecs), d.genre)
                        .joinToString("  ·  "),
                    d.plot, d.backdrop ?: d.poster ?: m.poster,
                )
            }
            Kind.SERIES -> {
                val s = Catalog.seriesById[card.id] ?: return
                setPreview("SERIE", s.name, Ui.ratingText(s.rating.toString()), s.plot, s.backdrop ?: s.poster)
                val d = runCatching { Catalog.seriesDetail(client, s.id) }.getOrNull() ?: return
                if (previewKey != "${card.kind}:${card.id}") return
                val n = d.seasons.size
                setPreview(
                    "SERIE", d.name,
                    listOfNotNull(
                        d.year, Ui.ratingText(d.rating),
                        if (n > 0) "$n temporada${if (n > 1) "s" else ""}" else null, d.genre,
                    ).joinToString("  ·  "),
                    d.plot ?: s.plot, d.backdrop ?: s.backdrop ?: s.poster,
                )
            }
            Kind.LIVE -> {
                val ch = Catalog.liveById[card.id] ?: return
                setPreview("EN VIVO", ch.name, "Buscando programación…", null, null)
                val epg = Catalog.epgFor(client, ch.id)
                if (previewKey != "${card.kind}:${card.id}") return
                showEpg(ch.name, epg)
            }
        }
    }

    private fun showEpg(name: String, epg: List<EpgEntry>) {
        val now = epg.firstOrNull()
        if (now == null) {
            setPreview("EN VIVO", name, "Sin guía de programación para este canal", null, null)
            return
        }
        val next = epg.getOrNull(1)
        val nowSec = System.currentTimeMillis() / 1000
        val meta = "Ahora: ${now.title}  ·  ${hhmm(now.start)} – ${hhmm(now.end)}"
        val plot = listOfNotNull(
            now.description?.takeIf { it.isNotBlank() },
            next?.let { "Después (${hhmm(it.start)}): ${it.title}" },
        ).joinToString("\n")
        setPreview("EN VIVO", name, meta, plot, null)
        pEpg.progress = (now.progress(nowSec) * 1000).toInt()
        pEpg.visibility = View.VISIBLE
    }

    private fun hhmm(sec: Long) = timeFmt.format(Date(sec * 1000))

    // ---------- Construcción de filas ----------

    private fun movieCard(m: Movie) = Card(Kind.MOVIE, m.id, m.name, image = m.poster)
    private fun seriesCard(s: Series) = Card(Kind.SERIES, s.id, s.name, image = s.poster)
    private fun liveCard(c: LiveChannel) = Card(Kind.LIVE, c.id, c.name, image = c.icon, logo = true)
    private fun resumeCard(r: Resume) =
        Card(r.kind, r.id, r.title, r.subtitle, r.image, progress = r.progress, resume = r)

    private fun homeSignature(): String =
        store.continueWatching().joinToString { it.key + it.updatedAt } + "|" + store.myList.joinToString() +
            "|" + store.seen().take(5).joinToString()

    /**
     * Al volver de ver algo: actualiza «Seguir viendo», favoritos y recomendaciones
     * pero deja la pantalla en la misma fila y el mismo póster (y el mismo destacado).
     */
    private fun refreshKeepingPlace() {
        val focused = currentFocus
        val rowPos = focused?.let(::rowIndexOf) ?: -1
        val oldRow = adapter.rows.getOrNull(rowPos)
        val title = oldRow?.title
        val cardPos = oldRow?.focusPos ?: 0
        val oldTitles = adapter.rows.associate { it.title to it.focusPos }
        render(keepHero = true)
        // Cada fila conserva el póster donde se había quedado.
        adapter.rows.forEach { r -> oldTitles[r.title]?.let { r.focusPos = it } }
        if (title == null) {
            focusStart()
            return
        }
        val target = adapter.rows.indexOfFirst { it.title == title }.takeIf { it >= 0 } ?: rowPos.coerceAtMost(adapter.itemCount - 1)
        if (target >= 0) {
            adapter.rows[target].focusPos = cardPos.coerceIn(0, maxOf(0, adapter.rows[target].cards.size - 1))
            heroButtons.visibility = View.GONE
            focusRow(target)
        }
    }

    private fun render(keepHero: Boolean = false) {
        val v = view ?: return
        val searching = tab == Tab.SEARCH
        searchBox.visibility = if (searching) View.VISIBLE else View.GONE
        previewArea.visibility = if (searching) View.GONE else View.VISIBLE
        (rows.layoutParams as ViewGroup.MarginLayoutParams).topMargin = if (searching) 150.dp else 300.dp
        rows.requestLayout()
        message.visibility = View.GONE

        var hero: Featured? = null
        val list = mutableListOf<Row>()
        when (tab) {
            Tab.HOME -> {
                renderedSignature = homeSignature()
                val continueRow = store.continueWatching().filter { v.canSee(it.key) }.map(::resumeCard)
                if (continueRow.isNotEmpty()) list += Row("Seguir viendo", continueRow, wide = true)

                trending?.let { t ->
                    val mixed = ArrayList<Card>()
                    val n = maxOf(t.movies.size, t.series.size)
                    for (i in 0 until n) {
                        t.series.getOrNull(i)?.let { mixed += seriesCard(it) }
                        t.movies.getOrNull(i)?.let { mixed += movieCard(it) }
                    }
                    if (mixed.isNotEmpty()) list += top10("🔥 Top 10 en México hoy", mixed)
                }

                val favs = store.favorites().filter(v::canSee).mapNotNull(::cardForKey)
                if (favs.isNotEmpty()) list += Row("Favoritos de ${profile.name}", favs, wide = false)

                list += recommendedRows(v)

                val mine = myListCards(v)
                if (mine.isNotEmpty()) list += row("Mi lista", mine, false)


                if (v.recentMovies.isNotEmpty()) list += row("Películas recién agregadas", v.recentMovies, false, ::movieCard)
                if (v.recentSeries.isNotEmpty()) list += row("Series con episodios nuevos", v.recentSeries, false, ::seriesCard)

                v.movieGenres.take(3).forEach { (name, items) ->
                    list += genreRow("Películas: $name", items.map(::movieCard)) { v.moviesOfGenre(name).map(::movieCard) }
                }
                v.seriesGenres.take(2).forEach { (name, items) ->
                    list += genreRow("Series: $name", items.map(::seriesCard)) { v.seriesOfGenre(name).map(::seriesCard) }
                }
                hero = pickMovieHero(v.recentMovies.take(30)) ?: pickSeriesHero(v.recentSeries.take(30))
            }
            Tab.LIVE -> {
                v.liveCats.forEach { c ->
                    val items = v.liveIn(c.id)
                    if (items.isNotEmpty()) list += row(c.name, items, true, ::liveCard)
                }
                if (list.isNotEmpty()) {
                    hero = Featured(
                        "TV EN VIVO", Kind.LIVE, GUIDE_ID, "Guía de programación",
                        "Qué pasa ahora, qué sigue y lo que ya pasó en cada canal", null, null,
                    )
                }
            }
            Tab.MOVIES -> {
                trending?.movies?.takeIf { it.isNotEmpty() }?.let {
                    list += top10("🔥 Top 10 películas en México", it.map(::movieCard))
                }
                v.movieGenres.forEach { (name, items) ->
                    list += genreRow(name, items.map(::movieCard)) { v.moviesOfGenre(name).map(::movieCard) }
                }
                v.vodCats.forEach { c ->
                    val items = v.moviesIn(c.id)
                    if (items.isNotEmpty()) list += row(c.name, items, false, ::movieCard)
                }
                hero = pickMovieHero(v.recentMovies.take(20))
            }
            Tab.SERIES -> {
                trending?.series?.takeIf { it.isNotEmpty() }?.let {
                    list += top10("🔥 Top 10 series en México", it.map(::seriesCard))
                }
                v.seriesGenres.forEach { (name, items) ->
                    list += genreRow(name, items.map(::seriesCard)) { v.seriesOfGenre(name).map(::seriesCard) }
                }
                v.seriesCats.forEach { c ->
                    val items = v.seriesIn(c.id)
                    if (items.isNotEmpty()) list += row(c.name, items, false, ::seriesCard)
                }
                hero = pickSeriesHero(v.recentSeries.take(20))
            }
            Tab.MYLIST -> {
                renderedSignature = homeSignature()
                val mine = myListCards(v)
                val series = mine.filter { it.kind == Kind.SERIES }
                val movies = mine.filter { it.kind == Kind.MOVIE }
                val live = mine.filter { it.kind == Kind.LIVE }
                if (series.isNotEmpty()) list += row("Series", series, false)
                if (movies.isNotEmpty()) list += row("Películas", movies, false)
                if (live.isNotEmpty()) list += row("TV en vivo", live, true)
                if (list.isEmpty()) {
                    featured = null
                    adapter.submit(emptyList())
                    showFeatured()
                    showMessage(
                        "Aún no tienes nada en Mi lista.\n\n" +
                            "En la ficha de una película o serie toca «+ Mi lista».\n" +
                            "En TV en vivo, mantén presionado OK sobre el canal."
                    )
                    return
                }
            }
            Tab.SEARCH -> {
                featured = null
                adapter.submit(emptyList())
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
        val keep = keepHero && featured != null && hero != null && featured?.kind == hero?.kind
        if (!keep) featured = hero
        adapter.submit(list)
        rows.scrollToPosition(0)
        showFeatured()
        if (!keep) hero?.let(::loadFeaturedDetails)
    }

    /** Fila con las primeras tarjetas y, si hay más, una tarjeta «Ver todo» al final. */
    private fun row(title: String, cards: List<Card>, wide: Boolean): Row {
        if (cards.size <= ROW_LIMIT) return Row(title, cards, wide)
        return Row(title, cards.take(ROW_LIMIT) + moreCard(cards.first().kind), wide) { cards }
    }

    /** Igual, pero sin convertir en tarjetas todo el contenido hasta que se pida «Ver todo». */
    private fun <T> row(title: String, items: List<T>, wide: Boolean, toCard: (T) -> Card): Row {
        if (items.size <= ROW_LIMIT) return Row(title, items.map(toCard), wide)
        val shown = items.take(ROW_LIMIT).map(toCard)
        return Row(title, shown + moreCard(shown.first().kind), wide) { items.map(toCard) }
    }

    /** Fila «Top 10» con números grandes (y «Ver todo» con el resto del top). */
    private fun top10(title: String, cards: List<Card>): Row {
        val ranked = cards.take(10).mapIndexed { i, c -> c.copy(rank = i + 1) }
        return if (cards.size > 10) Row(title, ranked + moreCard(cards.first().kind), false) { cards }
        else Row(title, ranked, false)
    }

    /** Fila por tipo: siempre ofrece «Ver todo», que busca en todo el catálogo. */
    private fun genreRow(title: String, cards: List<Card>, all: () -> List<Card>): Row =
        Row(title, cards.take(ROW_LIMIT) + moreCard(cards.first().kind), false, all)

    private fun moreCard(kind: Kind) = Card(kind, "__more__", "Ver todo", image = null, more = true)

    private fun myListCards(v: CatalogView): List<Card> = store.myList.filter(v::canSee).mapNotNull(::cardForKey)

    /** Tarjeta para una llave "movie:ID", "series:ID" o "live:ID". */
    private fun cardForKey(key: String): Card? {
        val (type, id) = key.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when (type) {
            "movie" -> Catalog.movieById[id]?.let(::movieCard)
            "series" -> Catalog.seriesById[id]?.let(::seriesCard)
            "live" -> Catalog.liveById[id]?.let(::liveCard)
            else -> null
        }
    }

    /** «Porque viste…» y «Recomendado para ti», según lo que ha visto este perfil. */
    private fun recommendedRows(v: CatalogView): List<Row> {
        val seen = store.seen().toSet()
        val out = mutableListOf<Row>()

        fun cardsIn(catKey: String): List<Card> {
            val (type, id) = catKey.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            return when (type) {
                "vod" -> v.moviesIn(id).filter { "movie:${it.id}" !in seen }
                    .sortedWith(compareByDescending<Movie> { it.rating }.thenByDescending { it.added })
                    .map(::movieCard)
                "series" -> v.seriesIn(id).filter { "series:${it.id}" !in seen }
                    .sortedWith(compareByDescending<Series> { it.rating }.thenByDescending { it.updated })
                    .map(::seriesCard)
                else -> emptyList()
            }
        }

        store.lastSeen()?.let { (title, catKey) ->
            val items = cardsIn(catKey).take(20)
            if (items.size >= 4 && title.isNotBlank()) out += Row("Porque viste $title", items, false)
        }

        val top = store.taste().entries.sortedByDescending { it.value }.take(3).map { it.key }
        if (top.isNotEmpty()) {
            val lists = top.map { cardsIn(it) }
            val mixed = LinkedHashMap<String, Card>()
            var i = 0
            while (mixed.size < 30 && lists.any { i < it.size }) {
                lists.forEach { l ->
                    l.getOrNull(i)?.let { c ->
                        val k = "${c.kind}:${c.id}"
                        if (k !in mixed) mixed[k] = c
                    }
                }
                i++
            }
            // Evitar repetir exactamente la fila «Porque viste…»
            val firstRow = out.firstOrNull()?.cards?.map { it.id }?.toSet().orEmpty()
            val cards = mixed.values.sortedBy { if (it.id in firstRow) 1 else 0 }
            if (cards.size >= 4) out += Row("Recomendado para ti", cards, false)
        }
        return out
    }

    private fun pickMovieHero(candidates: List<Movie>): Featured? {
        val m = candidates.filter { it.poster != null }.take(10).randomOrNull() ?: return null
        return Featured("PELÍCULA", Kind.MOVIE, m.id, m.name, Ui.ratingText(m.rating.toString()), null, m.poster)
    }

    private fun pickSeriesHero(candidates: List<Series>): Featured? {
        val s = candidates.filter { it.backdrop != null || it.poster != null }.take(10).randomOrNull() ?: return null
        return Featured("SERIE", Kind.SERIES, s.id, s.name, Ui.ratingText(s.rating.toString()), s.plot, s.backdrop ?: s.poster)
    }

    /** Trae la imagen horizontal y la sinopsis del destacado. */
    private fun loadFeaturedDetails(f: Featured) {
        if (f.id == GUIDE_ID) return
        lifecycleScope.launch {
            runCatching {
                val client = prefs.client()
                if (f.kind == Kind.MOVIE) {
                    val movie = Catalog.movieById[f.id] ?: return@runCatching
                    val d = Catalog.movieDetail(client, movie)
                    f.plot = d.plot
                    f.image = d.backdrop ?: f.image
                    f.meta = listOfNotNull(d.year, Ui.ratingText(d.rating), Ui.duration(d.durationSecs), d.genre)
                        .joinToString("  ·  ").ifBlank { null }
                } else {
                    val d = Catalog.seriesDetail(client, f.id)
                    f.plot = d.plot ?: f.plot
                    f.image = d.backdrop ?: f.image
                    val n = d.seasons.size
                    f.meta = listOfNotNull(
                        d.year, Ui.ratingText(d.rating),
                        if (n > 0) "$n temporada${if (n > 1) "s" else ""}" else null, d.genre,
                    ).joinToString("  ·  ").ifBlank { null }
                }
                if (featured === f && previewKey == null) showFeatured()
            }
        }
    }

    private fun featuredPlay(f: Featured) {
        if (f.id == GUIDE_ID) {
            startActivity(android.content.Intent(this, GuideActivity::class.java).putExtra(Nav.EXTRA_PROFILE, profile.id))
            return
        }
        if (f.kind == Kind.MOVIE) {
            val start = store.get(Resume.movieKey(f.id))?.positionMs ?: 0L
            Nav.playMovie(this, profile, f.id, start)
        } else {
            Nav.detail(this, profile, f.kind, f.id)
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
            adapter.submit(emptyList())
            showMessage("Escribe al menos 2 letras.\nTip: usa el micrófono del control o el teclado de tu celular.")
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                val out = mutableListOf<Row>()
                val movies = v.movies.filter { Ui.normalize(it.name).contains(query) }.take(60)
                val series = v.series.filter { Ui.normalize(it.name).contains(query) }.take(60)
                val live = v.live.filter { Ui.normalize(it.name).contains(query) }.take(60)
                if (series.isNotEmpty()) out += Row("Series", series.map(::seriesCard), false)
                if (movies.isNotEmpty()) out += Row("Películas", movies.map(::movieCard), false)
                if (live.isNotEmpty()) out += Row("Canales", live.map(::liveCard), true)
                out
            }
            adapter.submit(result)
            if (result.isEmpty()) showMessage("Sin resultados para «$q».") else message.visibility = View.GONE
        }
    }

    // ---------- Diseño: fondo ambiental y barra que se esconde ----------

    private fun setNavHidden(hide: Boolean) {
        if (hide == navHidden) return
        navHidden = hide
        topBar.animate().translationY(if (hide) -topBar.height.toFloat() else 0f)
            .alpha(if (hide) 0f else 1f).setDuration(220).start()
    }

    /** Toma el color dominante de la imagen y lo pasa suavemente al fondo. */
    private fun updateAmbient(image: String?) {
        ambientJob?.cancel()
        if (image == null) {
            animateAmbient(0xFF0B1220.toInt())
            return
        }
        ambientJob = lifecycleScope.launch {
            val req = coil.request.ImageRequest.Builder(this@HomeActivity)
                .data(image).size(64).allowHardware(false).build()
            val drawable = runCatching { coil.Coil.imageLoader(this@HomeActivity).execute(req).drawable }.getOrNull()
            val bmp = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@launch
            val color = withContext(Dispatchers.Default) {
                val p = androidx.palette.graphics.Palette.from(bmp).generate()
                p.getDarkVibrantColor(p.getVibrantColor(p.getDarkMutedColor(0xFF1E3A8A.toInt())))
            }
            animateAmbient(color)
        }
    }

    private fun animateAmbient(to: Int) {
        val from = ambientColor
        ambientColor = to
        android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(), from, to).apply {
            duration = 500
            addUpdateListener { paintAmbient(it.animatedValue as Int) }
            start()
        }
    }

    private fun paintAmbient(color: Int) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        ambient.background = android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            gradientRadius = w * 0.75f
            setGradientCenter(0.75f, 0.15f)
            colors = intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(color, 0xCC), 0xFF0B1220.toInt())
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val ROW_LIMIT = 20
        private const val GUIDE_ID = "__guide__"
    }
}
