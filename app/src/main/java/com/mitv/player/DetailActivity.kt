package com.mitv.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

/** Ficha de una película o serie: imagen grande, sinopsis, botones y episodios. */
class DetailActivity : AppCompatActivity() {

    private lateinit var profile: Profile
    private lateinit var prefs: Prefs
    private lateinit var store: ProfileStore
    private lateinit var kind: Kind
    private lateinit var id: String

    private lateinit var backdrop: ImageView
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var plot: TextView
    private lateinit var btnPlay: TextView
    private lateinit var btnRestart: TextView
    private lateinit var btnList: TextView
    private lateinit var seriesBox: View
    private lateinit var seasonsList: RecyclerView
    private lateinit var episodesList: RecyclerView
    private lateinit var loading: View

    private var seriesDetail: SeriesDetail? = null
    private var season = -1

    private val listKey get() = if (kind == Kind.MOVIE) "movie:$id" else "series:$id"
    private val resumeKey get() = if (kind == Kind.MOVIE) Resume.movieKey(id) else Resume.seriesKey(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Profiles.byId(this, intent.getStringExtra(Nav.EXTRA_PROFILE))
        val k = runCatching { Kind.valueOf(intent.getStringExtra(Nav.EXTRA_KIND) ?: "") }.getOrNull()
        val i = intent.getStringExtra(Nav.EXTRA_ID)
        if (p == null || k == null || i == null) {
            finish()
            return
        }
        profile = p
        kind = k
        id = i
        prefs = Prefs(this)
        store = ProfileStore(this, profile.id)
        setContentView(R.layout.activity_detail)

        backdrop = findViewById(R.id.backdrop)
        title = findViewById(R.id.title)
        meta = findViewById(R.id.meta)
        plot = findViewById(R.id.plot)
        btnPlay = findViewById(R.id.btnPlay)
        btnRestart = findViewById(R.id.btnRestart)
        btnList = findViewById(R.id.btnList)
        seriesBox = findViewById(R.id.seriesBox)
        seasonsList = findViewById(R.id.seasons)
        episodesList = findViewById(R.id.episodes)
        loading = findViewById(R.id.loading)

        seasonsList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        episodesList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        episodesList.itemAnimator = null

        btnList.setOnClickListener {
            val added = store.toggleList(listKey)
            updateListButton()
            Toast.makeText(this, if (added) "Agregado a Mi lista" else "Quitado de Mi lista", Toast.LENGTH_SHORT).show()
        }
        updateListButton()

        if (kind == Kind.MOVIE) setupMovie() else setupSeries()
        btnPlay.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (!::store.isInitialized || isFinishing) return
        updatePlayButtons()
        if (kind == Kind.SERIES && seriesDetail != null && season >= 0) showSeason(season, focus = false)
    }

    private fun updateListButton() {
        btnList.text = if (store.inList(listKey)) "✓  En mi lista" else "+  Mi lista"
    }

    // ---------- Películas ----------

    private fun setupMovie() {
        val movie = Catalog.movieById[id]
        if (movie == null) {
            finish()
            return
        }
        title.text = movie.name
        meta.text = Ui.ratingText(movie.rating.toString()) ?: ""
        backdrop.load(movie.poster)
        btnPlay.setOnClickListener {
            Nav.playMovie(this, profile, id, store.get(resumeKey)?.positionMs ?: 0L)
        }
        btnRestart.setOnClickListener { Nav.playMovie(this, profile, id, 0L) }

        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            runCatching { Catalog.movieDetail(prefs.client(), movie) }
                .onSuccess { d ->
                    title.text = d.name
                    plot.text = d.plot ?: ""
                    meta.text = listOfNotNull(d.year, Ui.ratingText(d.rating), Ui.duration(d.durationSecs), d.genre)
                        .joinToString("  ·  ")
                    d.backdrop?.let { backdrop.load(it) { crossfade(true) } }
                }
            loading.visibility = View.GONE
        }
    }

    // ---------- Series ----------

    private fun setupSeries() {
        val series = Catalog.seriesById[id]
        title.text = series?.name ?: ""
        plot.text = series?.plot ?: ""
        meta.text = Ui.ratingText(series?.rating?.toString()) ?: ""
        backdrop.load(series?.backdrop ?: series?.poster)
        btnPlay.isEnabled = false

        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            runCatching { Catalog.seriesDetail(prefs.client(), id) }.onSuccess { d ->
                seriesDetail = d
                title.text = d.name
                plot.text = d.plot ?: ""
                val n = d.seasons.size
                meta.text = listOfNotNull(
                    d.year, Ui.ratingText(d.rating),
                    if (n > 0) "$n temporada${if (n > 1) "s" else ""}" else null, d.genre,
                ).joinToString("  ·  ")
                d.backdrop?.let { backdrop.load(it) { crossfade(true) } }
                btnPlay.isEnabled = true
                setupSeasons(d)
                updatePlayButtons()
            }.onFailure { e ->
                Toast.makeText(this@DetailActivity, XtreamClient.friendlyError(e), Toast.LENGTH_LONG).show()
            }
            loading.visibility = View.GONE
        }

        btnPlay.setOnClickListener {
            val d = seriesDetail ?: return@setOnClickListener
            val r = store.get(resumeKey)
            val ep = r?.episodeId?.let { eid -> d.allEpisodes.firstOrNull { it.id == eid } } ?: d.allEpisodes.firstOrNull()
            if (ep != null) {
                val start = r?.takeIf { it.episodeId == ep.id }?.positionMs ?: 0L
                Nav.playEpisode(this, profile, id, ep.id, start)
            }
        }
        btnRestart.setOnClickListener {
            val first = seriesDetail?.allEpisodes?.firstOrNull() ?: return@setOnClickListener
            Nav.playEpisode(this, profile, id, first.id, 0L)
        }
    }

    private fun setupSeasons(d: SeriesDetail) {
        val seasons = d.seasons.keys.sorted()
        if (seasons.isEmpty()) return
        seriesBox.visibility = View.VISIBLE
        val r = store.get(resumeKey)
        val current = r?.episodeId?.let { eid -> d.allEpisodes.firstOrNull { it.id == eid }?.season }
        season = current ?: seasons.first()
        seasonsList.adapter = SeasonAdapter(seasons)
        showSeason(season, focus = false)
    }

    private fun showSeason(s: Int, focus: Boolean) {
        val d = seriesDetail ?: return
        season = s
        (seasonsList.adapter as? SeasonAdapter)?.notifyDataSetChanged()
        val r = store.get(resumeKey)
        val eps = d.seasons[s].orEmpty()
        val cards = eps.map { ep ->
            val resumeHere = r?.episodeId == ep.id
            Card(
                kind = Kind.SERIES,
                id = ep.id,
                title = "${ep.num}. ${ep.title}",
                subtitle = Ui.duration(ep.durationSecs),
                image = ep.image ?: d.backdrop ?: d.poster,
                progress = if (resumeHere) r?.progress ?: -1f else -1f,
            )
        }
        val row = Row("Temporada $s", cards, wide = true)
        episodesList.adapter = CardAdapter(row, onClick = { _, i ->
            val ep = eps[i]
            val start = r?.takeIf { it.episodeId == ep.id }?.positionMs ?: 0L
            Nav.playEpisode(this, profile, id, ep.id, start)
        })
        val idx = eps.indexOfFirst { it.id == r?.episodeId }
        if (idx > 0) episodesList.scrollToPosition(idx)
        if (focus) episodesList.post { episodesList.layoutManager?.findViewByPosition(maxOf(idx, 0))?.requestFocus() }
    }

    private fun updatePlayButtons() {
        val r = store.get(resumeKey)
        if (kind == Kind.MOVIE) {
            if (r != null && r.positionMs > 0) {
                val left = ((r.durationMs - r.positionMs) / 60000).coerceAtLeast(1)
                btnPlay.text = "▶  Continuar  ·  quedan $left min"
                btnRestart.visibility = View.VISIBLE
            } else {
                btnPlay.text = "▶  Reproducir"
                btnRestart.visibility = View.GONE
            }
        } else {
            val d = seriesDetail
            val ep = r?.episodeId?.let { eid -> d?.allEpisodes?.firstOrNull { it.id == eid } }
            if (ep != null) {
                btnPlay.text = "▶  Continuar  T${ep.season}:E${ep.num}"
                btnRestart.visibility = View.VISIBLE
                btnRestart.text = "↺  Desde el primer episodio"
            } else {
                btnPlay.text = "▶  Ver T1:E1"
                d?.allEpisodes?.firstOrNull()?.let { btnPlay.text = "▶  Ver  T${it.season}:E${it.num}" }
                btnRestart.visibility = View.GONE
            }
        }
    }

    private inner class SeasonAdapter(private val seasons: List<Int>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_season, parent, false)
            return object : RecyclerView.ViewHolder(tv) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val s = seasons[position]
            val tv = holder.itemView as TextView
            tv.text = "Temporada $s"
            tv.isSelected = s == season
            tv.setOnClickListener { showSeason(s, focus = true) }
        }

        override fun getItemCount() = seasons.size
    }
}
