package com.mitv.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.load
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_MS = 4000L
        private const val MAX_RETRIES = 3
        private const val SAVE_EVERY_MS = 15_000L
        private const val NEXT_COUNTDOWN = 10
    }

    private lateinit var profile: Profile
    private lateinit var prefs: Prefs
    private lateinit var store: ProfileStore
    private lateinit var client: XtreamClient
    private lateinit var kind: Kind

    private lateinit var playerView: PlayerView
    private lateinit var loadingView: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var overlay: View
    private lateinit var overlayLogo: ImageView
    private lateinit var overlayName: TextView
    private lateinit var overlayHint: TextView
    private lateinit var overlayEpg: TextView
    private lateinit var nextPanel: View
    private lateinit var nextTitle: TextView
    private lateinit var nextCountdown: TextView

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    // TV en vivo
    private var channels: List<LiveChannel> = emptyList()
    private var index = 0
    private var useHls = false
    private var triedHls = false
    private var wasPlaying = false
    private var retries = 0

    // Películas / series
    private var movie: Movie? = null
    private var movieImage: String? = null
    private var seriesId: String? = null
    private var series: SeriesDetail? = null
    private var episode: Episode? = null
    private var startMs = 0L
    private var nextEpisode: Episode? = null
    private var countdown = 0
    private var centerLongPressed = false
    private var recorded = false
    private lateinit var channelPanel: View
    private lateinit var panelChannels: androidx.recyclerview.widget.RecyclerView
    /** Canal que se estaba viendo al abrir la lista (para regresar con Atrás). */
    private var panelOrigin = -1
    private val panelTune = Runnable { panelPreview() }
    private var panelFocus = -1
    private lateinit var episodePanel: View
    private lateinit var panelSeasons: androidx.recyclerview.widget.RecyclerView
    private lateinit var panelEpisodes: androidx.recyclerview.widget.RecyclerView
    private lateinit var btnSkipIntro: View
    private var panelSeason = -1
    private var introSkipped = false
    private val introCheck = object : Runnable {
        override fun run() {
            updateSkipIntro()
            handler.postDelayed(this, 1000)
        }
    }
    /** Programa grabado de la guía (catch-up): se reproduce como película. */
    private var catchupUrl: String? = null
    private var sleepWarned = false
    private val sleepCheck = object : Runnable {
        override fun run() {
            if (SleepTimer.deadline > 0) {
                val left = SleepTimer.remainingMs()
                if (left <= 0) {
                    sleepNow()
                    return
                }
                if (left <= 60_000 && !sleepWarned) {
                    sleepWarned = true
                    showSleepWarning()
                }
            }
            handler.postDelayed(this, 10_000)
        }
    }
    private var epgJob: kotlinx.coroutines.Job? = null

    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private val retry = Runnable { startLive() }
    private val saver = object : Runnable {
        override fun run() {
            saveProgress(ended = false)
            handler.postDelayed(this, SAVE_EVERY_MS)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            countdown--
            if (countdown <= 0) {
                playNextNow()
            } else {
                nextCountdown.text = "Empieza en $countdown s"
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Profiles.byId(this, intent.getStringExtra(Nav.EXTRA_PROFILE))
        val k = runCatching { Kind.valueOf(intent.getStringExtra(Nav.EXTRA_KIND) ?: "") }.getOrNull()
        if (p == null || k == null) {
            finish()
            return
        }
        profile = p
        kind = k
        prefs = Prefs(this)
        store = ProfileStore(this, profile.id)
        client = prefs.client()
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.playerView)
        loadingView = findViewById(R.id.loading)
        errorView = findViewById(R.id.error)
        overlay = findViewById(R.id.overlay)
        overlayLogo = findViewById(R.id.overlayLogo)
        overlayName = findViewById(R.id.overlayName)
        overlayHint = findViewById(R.id.overlayHint)
        overlayEpg = findViewById(R.id.overlayEpg)
        nextPanel = findViewById(R.id.nextPanel)
        nextTitle = findViewById(R.id.nextTitle)
        nextCountdown = findViewById(R.id.nextCountdown)
        findViewById<View>(R.id.btnNextNow).setOnClickListener { playNextNow() }
        channelPanel = findViewById(R.id.channelPanel)
        panelChannels = findViewById(R.id.panelChannels)
        panelChannels.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        panelChannels.itemAnimator = null
        episodePanel = findViewById(R.id.episodePanel)
        panelSeasons = findViewById(R.id.panelSeasons)
        panelEpisodes = findViewById(R.id.panelEpisodes)
        btnSkipIntro = findViewById(R.id.btnSkipIntro)
        btnSkipIntro.setOnClickListener { skipIntro() }
        panelSeasons.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        panelEpisodes.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        panelEpisodes.itemAnimator = null
        findViewById<View>(R.id.btnNextCancel).setOnClickListener { finish() }

        startMs = savedInstanceState?.getLong(Nav.EXTRA_START) ?: intent.getLongExtra(Nav.EXTRA_START, 0L)
        when (kind) {
            Kind.LIVE -> {
                catchupUrl = intent.getStringExtra(Nav.EXTRA_URL)
                if (catchupUrl != null) {
                    setupVodControls()
                    return
                }
                channels = Catalog.liveQueue
                if (channels.isEmpty()) {
                    finish()
                    return
                }
                index = (savedInstanceState?.getInt(Nav.EXTRA_INDEX) ?: intent.getIntExtra(Nav.EXTRA_INDEX, 0))
                    .coerceIn(0, channels.size - 1)
                playerView.useController = false
            }
            Kind.MOVIE -> {
                movie = Catalog.movieById[intent.getStringExtra(Nav.EXTRA_ID)]
                if (movie == null) {
                    finish()
                    return
                }
                movieImage = movie?.poster
                setupVodControls()
            }
            Kind.SERIES -> {
                seriesId = intent.getStringExtra(Nav.EXTRA_ID)
                setupVodControls()
            }
        }
    }

    private fun setupVodControls() {
        playerView.useController = true
        playerView.controllerShowTimeoutMs = 4000
        playerView.controllerAutoShow = false
        overlayHint.text = "OK: pausa y controles  ·  ◀ ▶ adelantar o regresar  ·  ⚙ subtítulos y audio  ·  Atrás: salir"
        applySubtitleStyle()
    }

    /** Letra blanca con fondo oscuro, del tamaño elegido en Ajustes. */
    private fun applySubtitleStyle() {
        val factor = when (prefs.subtitleSize) {
            0 -> 1.0f
            2 -> 1.6f
            else -> 1.3f
        }
        playerView.subtitleView?.apply {
            setApplyEmbeddedStyles(false)
            setStyle(
                androidx.media3.ui.CaptionStyleCompat(
                    android.graphics.Color.WHITE, 0xB3000000.toInt(), android.graphics.Color.TRANSPARENT,
                    androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null,
                )
            )
            setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * factor)
        }
    }

    private fun sleepNow() {
        SleepTimer.cancel()
        saveProgress(ended = false)
        player?.pause()
        android.widget.Toast.makeText(this, "🌙 Temporizador: se detuvo la reproducción", android.widget.Toast.LENGTH_LONG).show()
        finishAffinity()
    }

    private fun showSleepWarning() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🌙 Se detendrá en 1 minuto")
            .setMessage("¿Siguen despiertos?")
            .setPositiveButton("Seguir viendo") { _, _ -> SleepTimer.cancel() }
            .setNegativeButton("Detener ahora") { _, _ -> sleepNow() }
            .show()
    }

    override fun onStart() {
        super.onStart()
        if (!::kind.isInitialized) return
        initPlayer()
        sleepWarned = false
        handler.postDelayed(sleepCheck, 10_000)
        when (kind) {
            Kind.LIVE -> catchupUrl?.let { url ->
                showInfo(intent.getStringExtra(Nav.EXTRA_TITLE) ?: "Programa grabado", null)
                play(url, hls = false, positionMs = startMs)
            } ?: tune(index)
            Kind.MOVIE -> startMovie()
            Kind.SERIES -> startSeries()
        }
    }

    override fun onStop() {
        super.onStop()
        if (player != null && (kind != Kind.LIVE || catchupUrl != null)) {
            saveProgress(ended = false)
            startMs = player?.currentPosition ?: startMs
        }
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(Nav.EXTRA_INDEX, index)
        outState.putLong(Nav.EXTRA_START, player?.currentPosition ?: startMs)
    }

    private fun initPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(XtreamClient.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        // En películas y series se prefiere FFmpeg para el audio: muchos archivos traen AC3/DTS,
        // que el Chromecast no siempre reproduce por sí solo (se veía el video pero sin sonido).
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(
                if (kind == Kind.LIVE) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            )
            .setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        // Preferir audio en español (latino primero) cuando el archivo trae varios idiomas.
        val subLang = store.subtitleLanguage
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages("es-419", "es-MX", "es", "spa")
            .apply {
                when {
                    subLang == null -> Unit // nunca eligió: lo que traiga el archivo por defecto
                    subLang.isEmpty() -> setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                    else -> setPreferredTextLanguage(subLang)
                }
            }
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> loadingView.visibility = View.VISIBLE
                    Player.STATE_READY -> {
                        loadingView.visibility = View.GONE
                        errorView.visibility = View.GONE
                        // Al empezar a ver algo, que aparezca de inmediato en «Seguir viendo».
                        if (!wasPlaying && kind != Kind.LIVE) saveProgress(ended = false, force = true)
                        if (kind == Kind.LIVE && catchupUrl == null) {
                            channels.getOrNull(index)?.let { DeadChannels.recordSuccess(this@PlayerActivity, it.id) }
                        }
                        wasPlaying = true
                        retries = 0
                    }
                    Player.STATE_ENDED -> {
                        loadingView.visibility = View.GONE
                        if (kind != Kind.LIVE || catchupUrl != null) onVodEnded()
                    }
                    else -> loadingView.visibility = View.GONE
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (kind == Kind.LIVE && catchupUrl == null) handleLiveError(error) else showError(error)
            }

            /** Recordar los subtítulos que eligió este perfil (o que los quitó). */
            override fun onTrackSelectionParametersChanged(params: androidx.media3.common.TrackSelectionParameters) {
                if (kind == Kind.LIVE && catchupUrl == null) return
                if (params.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT)) {
                    store.subtitleLanguage = ""
                    return
                }
                val override = params.overrides.values.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
                val lang = override?.mediaTrackGroup?.getFormat(0)?.language
                if (!lang.isNullOrBlank()) store.subtitleLanguage = lang
            }
        })
        playerView.player = p
        playerView.requestFocus()
        player = p
    }

    private fun play(url: String, hls: Boolean, positionMs: Long) {
        val p = player ?: return
        val item = MediaItem.Builder()
            .setUri(url)
            .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        loadingView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        p.setMediaItem(item, positionMs.coerceAtLeast(0L))
        p.prepare()
        p.playWhenReady = true
    }

    private fun showInfo(name: String, image: String?) {
        overlayName.text = name
        overlayLogo.load(image) {
            error(R.drawable.ic_launcher)
            fallback(R.drawable.ic_launcher)
        }
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, OVERLAY_MS)
    }

    private fun showError(error: PlaybackException) {
        loadingView.visibility = View.GONE
        errorView.text = "No se pudo reproducir.\n(${error.errorCodeName})\n\nAtrás para salir"
        errorView.visibility = View.VISIBLE
    }

    // ---------- TV en vivo ----------

    /** Cambia al canal siguiente/anterior, saltándose los que están «sin señal». */
    private fun zap(step: Int) {
        val n = channels.size
        val dead = DeadChannels.deadIds(this)
        var i = index
        for (k in 0 until n) {
            i = ((i + step) % n + n) % n
            if (channels[i].id !in dead) break
        }
        tune(i)
    }

    private fun tune(i: Int) {
        val n = channels.size
        index = ((i % n) + n) % n
        useHls = false
        triedHls = false
        wasPlaying = false
        retries = 0
        handler.removeCallbacks(retry)
        val ch = channels[index]
        store.addRecentChannel(ch.id)
        val star = if (store.inList("live:${ch.id}")) "★ " else ""
        showInfo("${index + 1}  $star${ch.name}", ch.icon)
        loadEpg(ch)
        startLive()
    }

    private fun loadEpg(ch: LiveChannel) {
        overlayEpg.visibility = View.GONE
        epgJob?.cancel()
        epgJob = lifecycleScope.launch {
            val epg = Catalog.epgFor(client, ch.id)
            if (channels.getOrNull(index)?.id != ch.id) return@launch
            val now = epg.firstOrNull() ?: return@launch
            if (SleepTimer.afterCurrent) {
                // En vivo, «al terminar» = cuando acabe el programa actual.
                SleepTimer.setMinutes((((now.end * 1000 - System.currentTimeMillis()) / 60_000) + 1).toInt())
            }
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale("es", "MX"))
            fun t(s: Long) = fmt.format(java.util.Date(s * 1000))
            val next = epg.getOrNull(1)
            overlayEpg.text = "Ahora: ${now.title}  (${t(now.start)}–${t(now.end)})" +
                (next?.let { "\nDespués: ${it.title}  (${t(it.start)})" } ?: "")
            overlayEpg.visibility = View.VISIBLE
        }
    }

    private fun startLive() {
        val ch = channels[index]
        play(client.liveUrl(ch.id, if (useHls) "m3u8" else "ts"), useHls, 0L)
    }

    private fun handleLiveError(error: PlaybackException) {
        when {
            wasPlaying && retries < MAX_RETRIES -> {
                retries++
                handler.postDelayed(retry, 2000)
            }
            !triedHls -> {
                triedHls = true
                useHls = true
                startLive()
            }
            else -> {
                loadingView.visibility = View.GONE
                DeadChannels.recordFailure(this, channels[index].id)
                errorView.text = "No se pudo abrir «${channels[index].name}».\n(${error.errorCodeName})\n\n" +
                    "▲ ▼ para cambiar de canal · OK para reintentar"
                errorView.visibility = View.VISIBLE
            }
        }
    }

    // ---------- Lista de canales encima del video ----------

    private fun openChannelPanel() {
        if (channels.isEmpty()) return
        panelOrigin = index
        panelFocus = index
        overlay.visibility = View.GONE
        channelPanel.visibility = View.VISIBLE
        val dead = DeadChannels.deadIds(this)
        panelChannels.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(
                parent: android.view.ViewGroup, viewType: Int,
            ): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val v = layoutInflater.inflate(R.layout.item_channel_row, parent, false)
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val ch = channels[position]
                val v = holder.itemView
                v.findViewById<TextView>(R.id.name).text = "${position + 1}  ${ch.name}"
                v.findViewById<ImageView>(R.id.logo).load(ch.icon) { error(R.drawable.ic_launcher); fallback(R.drawable.ic_launcher) }
                val now = v.findViewById<TextView>(R.id.now)
                now.text = if (ch.id in dead) "Sin señal" else ""
                v.alpha = if (ch.id in dead) 0.5f else 1f
                v.setOnFocusChangeListener { _, has ->
                    if (has) {
                        panelFocus = holder.bindingAdapterPosition
                        // Al detenerse en un canal, se ve detrás de la lista.
                        handler.removeCallbacks(panelTune)
                        handler.postDelayed(panelTune, 700)
                    }
                }
                v.setOnClickListener {
                    panelFocus = holder.bindingAdapterPosition
                    closeChannelPanel(keep = true)
                }
                if (ch.id !in dead) lifecycleScope.launch {
                    val epg = Catalog.epgFor(client, ch.id)
                    val cur = epg.firstOrNull()
                    if (holder.bindingAdapterPosition == position && cur != null) now.text = "Ahora: ${cur.title}"
                }
            }

            override fun getItemCount() = channels.size
        }
        panelChannels.scrollToPosition(index)
        panelChannels.post {
            panelChannels.layoutManager?.findViewByPosition(index)?.requestFocus()
        }
    }

    private fun panelPreview() {
        val i = panelFocus
        if (channelPanel.visibility != View.VISIBLE || i < 0 || i == index) return
        tune(i)
        overlay.visibility = View.GONE
    }

    /** keep = quedarse en el canal enfocado; si no, regresar al que se estaba viendo. */
    private fun closeChannelPanel(keep: Boolean) {
        handler.removeCallbacks(panelTune)
        channelPanel.visibility = View.GONE
        val target = if (keep) panelFocus else panelOrigin
        if (target >= 0 && target != index) tune(target)
        else channels.getOrNull(index)?.let { showInfo("${index + 1}  ${it.name}", it.icon) }
        playerView.requestFocus()
    }

    // ---------- Películas ----------

    private fun startMovie() {
        val m = movie ?: return
        showInfo(m.name, m.poster)
        if (!recorded) {
            recorded = true
            store.recordView(Resume.movieKey(m.id), Category.keyOf(Kind.MOVIE, m.categoryId), m.name)
        }
        play(client.movieUrl(m.id, m.ext), hls = false, positionMs = startMs)
        handler.postDelayed(saver, SAVE_EVERY_MS)
        if (movieImage == m.poster) {
            lifecycleScope.launch {
                runCatching { Catalog.movieDetail(client, m) }.onSuccess { d -> d.backdrop?.let { movieImage = it } }
            }
        }
    }

    // ---------- Series ----------

    private fun startSeries() {
        val sid = seriesId ?: return finish()
        val cached = Catalog.seriesDetails[sid]
        if (cached != null) {
            series = cached
            startEpisodeFromIntent()
            return
        }
        loadingView.visibility = View.VISIBLE
        lifecycleScope.launch {
            runCatching { Catalog.seriesDetail(client, sid) }
                .onSuccess {
                    series = it
                    startEpisodeFromIntent()
                }
                .onFailure {
                    Toast.makeText(this@PlayerActivity, XtreamClient.friendlyError(it), Toast.LENGTH_LONG).show()
                    finish()
                }
        }
    }

    private fun startEpisodeFromIntent() {
        val d = series ?: return
        val wanted = episode?.id ?: intent.getStringExtra(Nav.EXTRA_EPISODE)
        val ep = d.allEpisodes.firstOrNull { it.id == wanted } ?: d.allEpisodes.firstOrNull()
        if (ep == null) {
            Toast.makeText(this, "Esta serie no tiene episodios disponibles", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        startEpisode(ep, startMs)
    }

    private fun startEpisode(ep: Episode, positionMs: Long) {
        episode = ep
        nextPanel.visibility = View.GONE
        playerView.useController = true
        showInfo("${series?.name ?: ""}  ·  T${ep.season}:E${ep.num}  ${ep.title}", ep.image ?: series?.poster)
        val sid = seriesId
        if (!recorded && sid != null) {
            recorded = true
            val cat = Catalog.seriesById[sid]?.categoryId ?: ""
            store.recordView(Resume.seriesKey(sid), Category.keyOf(Kind.SERIES, cat), series?.name ?: "")
        }
        play(client.episodeUrl(ep.id, ep.ext), hls = false, positionMs = positionMs)
        introSkipped = positionMs > 180_000
        handler.removeCallbacks(introCheck)
        handler.postDelayed(introCheck, 1000)
        handler.removeCallbacks(saver)
        handler.postDelayed(saver, SAVE_EVERY_MS)
    }

    private fun onVodEnded() {
        saveProgress(ended = true)
        handler.removeCallbacks(saver)
        if (SleepTimer.afterCurrent) {
            sleepNow()
            return
        }
        val d = series
        val ep = episode
        val next = if (d != null && ep != null) d.next(ep) else null
        if (next == null) {
            finish()
            return
        }
        nextEpisode = next
        playerView.hideController()
        playerView.useController = false
        nextTitle.text = "Siguiente: T${next.season}:E${next.num}  ${next.title}"
        countdown = NEXT_COUNTDOWN
        nextCountdown.text = "Empieza en $countdown s"
        nextPanel.visibility = View.VISIBLE
        findViewById<View>(R.id.btnNextNow).requestFocus()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000)
    }

    private fun playNextNow() {
        handler.removeCallbacks(tick)
        val next = nextEpisode ?: return finish()
        nextEpisode = null
        startMs = 0L
        startEpisode(next, 0L)
        playerView.requestFocus()
    }

    // ---------- Episodios mientras se ve y «Saltar intro» ----------

    private fun showEpisodes(): Boolean {
        val d = series ?: return false
        val ep = episode ?: return false
        if (d.seasons.isEmpty()) return false
        playerView.hideController()
        playerView.useController = false
        btnSkipIntro.visibility = View.GONE
        episodePanel.visibility = View.VISIBLE
        val seasons = d.seasons.keys.sorted()
        panelSeasons.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(
                parent: android.view.ViewGroup, viewType: Int,
            ): androidx.recyclerview.widget.RecyclerView.ViewHolder =
                object : androidx.recyclerview.widget.RecyclerView.ViewHolder(
                    layoutInflater.inflate(R.layout.item_season, parent, false)
                ) {}

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val sNum = seasons[position]
                (holder.itemView as TextView).apply {
                    text = "Temporada $sNum"
                    isSelected = sNum == panelSeason
                    setOnClickListener { fillPanel(sNum, focus = true) }
                }
            }

            override fun getItemCount() = seasons.size
        }
        fillPanel(ep.season, focus = true)
        return true
    }

    private fun fillPanel(seasonNum: Int, focus: Boolean) {
        val d = series ?: return
        val current = episode
        panelSeason = seasonNum
        panelSeasons.adapter?.notifyDataSetChanged()
        val watched = store.watched().toHashSet()
        val eps = d.seasons[seasonNum].orEmpty()
        val cards = eps.map { e ->
            Card(
                kind = Kind.SERIES, id = e.id, title = "${e.num}. ${e.title}",
                subtitle = listOfNotNull(
                    when {
                        e.id == current?.id -> "▶ Viendo"
                        e.id in watched -> "✓ Visto"
                        else -> null
                    },
                    Ui.duration(e.durationSecs),
                ).joinToString("  ·  ").ifBlank { null },
                image = e.image ?: d.backdrop ?: d.poster,
            )
        }
        panelEpisodes.adapter = CardAdapter(Row("Temporada $seasonNum", cards, wide = true), onClick = { _, i ->
            val chosen = eps[i]
            hideEpisodes()
            if (chosen.id != current?.id) {
                saveProgress(ended = false)
                startMs = 0L
                startEpisode(chosen, 0L)
            }
        })
        val idx = eps.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        panelEpisodes.scrollToPosition(idx)
        if (focus) panelEpisodes.post {
            panelEpisodes.layoutManager?.findViewByPosition(idx)?.let { (it.findViewById<View>(R.id.frame) ?: it).requestFocus() }
        }
    }

    private fun hideEpisodes() {
        episodePanel.visibility = View.GONE
        playerView.useController = true
        playerView.requestFocus()
    }

    /** Muestra «Saltar intro» en los primeros 3 minutos de cada episodio. */
    private fun updateSkipIntro() {
        val p = player ?: return
        val show = kind == Kind.SERIES && !introSkipped && episodePanel.visibility != View.VISIBLE &&
            p.currentPosition in 5_000L..180_000L && p.isPlaying
        btnSkipIntro.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun skipIntro() {
        val p = player ?: return
        introSkipped = true
        btnSkipIntro.visibility = View.GONE
        p.seekTo(p.currentPosition + 85_000L)
    }

    // ---------- Seguir viendo ----------

    /**
     * Guarda el avance para «Seguir viendo». Si el archivo no trae su duración
     * (pasa con muchos .ts/.mkv del proveedor), se usa la del catálogo o se guarda sin barra.
     * force = guardar aunque lleve pocos segundos (al empezar a reproducir).
     */
    private fun saveProgress(ended: Boolean, force: Boolean = false) {
        val p = player ?: return
        if (kind == Kind.LIVE) return
        val pos = p.currentPosition.coerceAtLeast(0L)
        val known = p.duration.takeIf { it > 0 }
            ?: episode?.durationSecs?.takeIf { it > 0 }?.let { it * 1000L }
            ?: movie?.let { Catalog.movieDetails[it.id]?.durationSecs }?.takeIf { it > 0 }?.let { it * 1000L }
        val dur = known ?: 0L
        val finished = ended || (dur > 0 && pos > dur * 0.95)
        val minPos = if (force) 0L else 10_000L
        val now = System.currentTimeMillis()

        if (kind == Kind.MOVIE) {
            val m = movie ?: return
            val key = Resume.movieKey(m.id)
            when {
                finished -> store.remove(key)
                pos >= minPos -> store.save(
                    Resume(key, Kind.MOVIE, m.id, m.name, null, movieImage ?: m.poster, m.ext, null, pos, dur, now)
                )
            }
            return
        }

        val d = series ?: return
        val ep = episode ?: return
        val sid = seriesId ?: return
        val key = Resume.seriesKey(sid)
        if (finished) store.markWatched(ep.id)
        if (finished) {
            val next = d.next(ep)
            if (next == null) {
                store.remove(key)
            } else {
                store.save(
                    Resume(
                        key, Kind.SERIES, sid, d.name, "T${next.season}:E${next.num} · ${next.title}",
                        next.image ?: d.backdrop ?: d.poster, next.ext, next.id,
                        0L, next.durationSecs * 1000L, now,
                    )
                )
            }
        } else if (pos >= minPos) {
            store.save(
                Resume(
                    key, Kind.SERIES, sid, d.name, "T${ep.season}:E${ep.num} · ${ep.title}",
                    ep.image ?: d.backdrop ?: d.poster, ep.ext, ep.id, pos, dur, now,
                )
            )
        }
    }

    // ---------- Control remoto ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (kind == Kind.SERIES && catchupUrl == null && nextPanel.visibility != View.VISIBLE) {
            val down = event.action == KeyEvent.ACTION_DOWN
            if (episodePanel.visibility == View.VISIBLE) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (down) hideEpisodes()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
            val controllerShown = playerView.isControllerFullyVisible
            // ▼ con los controles ocultos: abrir los episodios sin salir.
            if (down && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !controllerShown) {
                if (showEpisodes()) return true
            }
            // OK mientras se ve «Saltar intro» (y los controles están ocultos): saltar.
            val center = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
            if (center && btnSkipIntro.visibility == View.VISIBLE && !controllerShown) {
                if (down) skipIntro()
                return true
            }
        }
        if (kind != Kind.LIVE || catchupUrl != null || nextPanel.visibility == View.VISIBLE) {
            return super.dispatchKeyEvent(event)
        }

        if (channelPanel.visibility == View.VISIBLE) {
            val down = event.action == KeyEvent.ACTION_DOWN
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (down) closeChannelPanel(keep = false)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) closeChannelPanel(keep = true)
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        val key = event.keyCode
        val isCenter = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER

        // Mantener OK = agregar o quitar de Mi lista
        if (isCenter && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 1) {
            centerLongPressed = true
            val ch = channels[index]
            val added = store.toggleList("live:${ch.id}")
            Toast.makeText(this, if (added) "★ Agregado a Mi lista" else "Quitado de Mi lista", Toast.LENGTH_SHORT).show()
            return true
        }
        if (isCenter) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (centerLongPressed) {
                    centerLongPressed = false
                } else if (errorView.visibility == View.VISIBLE) tune(index)
                else showInfo("${index + 1}  ${channels[index].name}", channels[index].icon)
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        return when (key) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { openChannelPanel(); true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                showInfo("${index + 1}  ${channels[index].name}", channels[index].icon); true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }
}
