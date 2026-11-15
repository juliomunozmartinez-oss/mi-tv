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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.load

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INDEX = "index"
        private const val OVERLAY_MS = 4000L
        private const val MAX_RETRIES = 3
    }

    private lateinit var prefs: Prefs
    private lateinit var client: XtreamClient
    private lateinit var playerView: PlayerView
    private lateinit var loadingView: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var overlay: View
    private lateinit var overlayLogo: ImageView
    private lateinit var overlayName: TextView

    private var player: ExoPlayer? = null
    private var channels: List<Channel> = emptyList()
    private var index = 0

    private var useHls = false
    private var triedHls = false
    private var wasPlaying = false
    private var retries = 0

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private val retry = Runnable { startStream() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        channels = ChannelRepo.current
        if (channels.isEmpty()) {
            finish()
            return
        }
        prefs = Prefs(this)
        client = XtreamClient(prefs.server, prefs.username, prefs.password)
        index = (savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0))
            .coerceIn(0, channels.size - 1)

        playerView = findViewById(R.id.playerView)
        loadingView = findViewById(R.id.loading)
        errorView = findViewById(R.id.error)
        overlay = findViewById(R.id.overlay)
        overlayLogo = findViewById(R.id.overlayLogo)
        overlayName = findViewById(R.id.overlayName)
    }

    override fun onStart() {
        super.onStart()
        if (channels.isEmpty()) return
        initPlayer()
        tune(index)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
    }

    private fun initPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(XtreamClient.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> loadingView.visibility = View.VISIBLE
                    Player.STATE_READY -> {
                        loadingView.visibility = View.GONE
                        errorView.visibility = View.GONE
                        wasPlaying = true
                        retries = 0
                    }
                    else -> loadingView.visibility = View.GONE
                }
            }

            override fun onPlayerError(error: PlaybackException) = handleError(error)
        })
        playerView.player = p
        player = p
    }

    /** Cambia al canal número [i] de la lista actual. */
    private fun tune(i: Int) {
        val n = channels.size
        index = ((i % n) + n) % n
        useHls = false
        triedHls = false
        wasPlaying = false
        retries = 0
        handler.removeCallbacks(retry)
        errorView.visibility = View.GONE
        showOverlay()
        startStream()
    }

    private fun startStream() {
        val p = player ?: return
        val ch = channels[index]
        val url = client.liveUrl(ch, if (useHls) "m3u8" else "ts")
        val item = MediaItem.Builder()
            .setUri(url)
            .apply { if (useHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        loadingView.visibility = View.VISIBLE
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
    }

    private fun handleError(error: PlaybackException) {
        when {
            // Se estaba viendo y se cortó: reintentar el mismo formato.
            wasPlaying && retries < MAX_RETRIES -> {
                retries++
                handler.postDelayed(retry, 2000)
            }
            // Nunca arrancó en .ts: probar en formato HLS (.m3u8).
            !triedHls -> {
                triedHls = true
                useHls = true
                startStream()
            }
            else -> {
                loadingView.visibility = View.GONE
                errorView.text = "No se pudo reproducir «${channels[index].name}».\n" +
                    "(${error.errorCodeName})\n\n▲ ▼ para cambiar de canal · OK para reintentar"
                errorView.visibility = View.VISIBLE
            }
        }
    }

    private fun showOverlay() {
        val ch = channels[index]
        overlayName.text = "${index + 1}  ${if (ch.id in prefs.favorites) "★ " else ""}${ch.name}"
        overlayLogo.load(ch.icon) {
            error(R.drawable.ic_launcher)
            fallback(R.drawable.ic_launcher)
        }
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, OVERLAY_MS)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        val isCenter = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER

        // Mantener OK = favorito
        if (isCenter && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 1) {
            val ch = channels[index]
            val added = prefs.toggleFavorite(ch.id)
            Toast.makeText(
                this,
                if (added) "★ Agregado a favoritos" else "Quitado de favoritos",
                Toast.LENGTH_SHORT,
            ).show()
            showOverlay()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0 && isCenter) {
            return if (isCenter) true else super.dispatchKeyEvent(event)
        }

        return when (key) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> { tune(index - 1); true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> { tune(index + 1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (errorView.visibility == View.VISIBLE) tune(index) else showOverlay()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                showOverlay(); true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }
}
