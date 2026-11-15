package com.mitv.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var categoryList: RecyclerView
    private lateinit var channelList: RecyclerView
    private lateinit var categoryTitle: TextView
    private lateinit var empty: TextView
    private lateinit var btnSettings: TextView
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private var currentChannels: List<Channel> = emptyList()
    private var renderedKey: String? = null
    private var renderedLayout = -1
    private var loading = false
    private var openedSettingsOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        categoryList = findViewById(R.id.categoryList)
        channelList = findViewById(R.id.channelList)
        categoryTitle = findViewById(R.id.categoryTitle)
        empty = findViewById(R.id.empty)
        btnSettings = findViewById(R.id.btnSettings)

        categoryAdapter = CategoryAdapter(
            onSelect = ::selectCategory,
            onLongPress = ::askHideCategory,
            onRight = ::focusChannels,
        )
        channelAdapter = ChannelAdapter(
            onPlay = ::play,
            onLongPress = ::toggleFavorite,
            onLeft = ::focusSelectedCategory,
        )
        categoryList.layoutManager = LinearLayoutManager(this)
        categoryList.adapter = categoryAdapter
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = channelAdapter
        channelList.itemAnimator = null
        categoryList.itemAnimator = null

        btnSettings.setOnClickListener { openSettings() }
        findViewById<View>(R.id.btnReload).setOnClickListener { load(force = true) }
    }

    override fun onResume() {
        super.onResume()
        if (!prefs.isConfigured) {
            showMessage("Primero configura tu servidor en «Ajustes».")
            status.text = ""
            btnSettings.requestFocus()
            if (!openedSettingsOnce) {
                openedSettingsOnce = true
                openSettings()
            }
            return
        }
        load(force = false)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun load(force: Boolean) {
        if (loading) return
        val key = prefs.accountKey
        if (!force && ChannelRepo.loadedKey == key) {
            if (renderedKey != key || renderedLayout != prefs.layoutVersion) {
                renderCategories()
            } else {
                refreshFavorites()
            }
            return
        }
        loading = true
        progress.visibility = View.VISIBLE
        status.text = "Cargando canales…"
        lifecycleScope.launch {
            try {
                val client = XtreamClient(prefs.server, prefs.username, prefs.password)
                ChannelRepo.load(client, key)
                renderCategories()
            } catch (e: Exception) {
                status.text = ""
                categoryAdapter.submit(emptyList())
                channelAdapter.items = emptyList()
                categoryTitle.text = ""
                showMessage("No se pudo cargar la lista.\n\n${XtreamClient.friendlyError(e)}\n\nRevisa «Ajustes» o toca «Recargar».")
                btnSettings.requestFocus()
            } finally {
                loading = false
                progress.visibility = View.GONE
            }
        }
    }

    private fun renderCategories() {
        val hidden = prefs.hiddenCategories
        val favs = prefs.favorites
        val all = ChannelRepo.channels
        val counts = all.groupingBy { it.categoryId }.eachCount()

        val items = mutableListOf(
            CategoryItem(ChannelRepo.FAVORITES, "★ Favoritos", all.count { it.id in favs }),
            CategoryItem(ChannelRepo.ALL, "Todos los canales", all.count { it.categoryId !in hidden }),
        )
        ChannelRepo.categories
            .filter { it.id !in hidden }
            .forEach { items += CategoryItem(it.id, it.name, counts[it.id] ?: 0) }

        categoryAdapter.selected = -1
        categoryAdapter.submit(items)
        renderedKey = ChannelRepo.loadedKey
        renderedLayout = prefs.layoutVersion

        val hiddenText = if (hidden.isNotEmpty()) " · ${hidden.size} categorías ocultas" else ""
        status.text = "${all.size} canales$hiddenText"

        val last = items.indexOfFirst { it.id == prefs.lastCategory }
        val start = when {
            last >= 0 -> last
            favs.isNotEmpty() -> 0
            else -> 1
        }
        selectCategory(start)
        categoryList.scrollToPosition(start)
        categoryList.post { focusSelectedCategory() }
    }

    private fun selectCategory(position: Int) {
        val item = categoryAdapter.items.getOrNull(position) ?: return
        categoryAdapter.selected = position
        prefs.lastCategory = item.id
        currentChannels = ChannelRepo.channelsFor(item.id, prefs)
        channelAdapter.favorites = prefs.favorites
        channelAdapter.items = currentChannels
        channelList.scrollToPosition(0)
        categoryTitle.text = item.name
        if (currentChannels.isEmpty()) {
            showMessage(
                if (item.id == ChannelRepo.FAVORITES)
                    "Aún no tienes favoritos.\nMantén presionado OK sobre un canal para agregarlo."
                else "Esta categoría no tiene canales."
            )
        } else {
            empty.visibility = View.GONE
        }
    }

    private fun showMessage(text: String) {
        empty.text = text
        empty.visibility = View.VISIBLE
    }

    private fun focusChannels(): Boolean {
        if (currentChannels.isEmpty()) return false
        val lm = channelList.layoutManager as LinearLayoutManager
        val pos = lm.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 } ?: 0
        val view = lm.findViewByPosition(pos)
        if (view != null) {
            view.requestFocus()
        } else {
            channelList.scrollToPosition(0)
            channelList.post { lm.findViewByPosition(0)?.requestFocus() }
        }
        return true
    }

    private fun focusSelectedCategory(): Boolean {
        val pos = categoryAdapter.selected
        if (pos < 0) return false
        val lm = categoryList.layoutManager as LinearLayoutManager
        val view = lm.findViewByPosition(pos)
        if (view != null) {
            view.requestFocus()
        } else {
            categoryList.scrollToPosition(pos)
            categoryList.post { lm.findViewByPosition(pos)?.requestFocus() }
        }
        return true
    }

    private fun play(position: Int) {
        ChannelRepo.current = currentChannels
        startActivity(
            Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, position)
        )
    }

    private fun toggleFavorite(position: Int) {
        val ch = currentChannels.getOrNull(position) ?: return
        val added = prefs.toggleFavorite(ch.id)
        Toast.makeText(
            this,
            if (added) "★ ${ch.name} agregado a favoritos" else "${ch.name} quitado de favoritos",
            Toast.LENGTH_SHORT,
        ).show()
        refreshFavorites()
    }

    /** Actualiza estrellas y el contador de Favoritos sin mover el foco. */
    private fun refreshFavorites() {
        val favs = prefs.favorites
        channelAdapter.favorites = favs
        channelAdapter.notifyItemRangeChanged(0, channelAdapter.itemCount)
        val i = categoryAdapter.items.indexOfFirst { it.id == ChannelRepo.FAVORITES }
        if (i >= 0) categoryAdapter.updateCount(i, ChannelRepo.channels.count { it.id in favs })
    }

    private fun askHideCategory(position: Int) {
        val item = categoryAdapter.items.getOrNull(position) ?: return
        if (item.id == ChannelRepo.FAVORITES || item.id == ChannelRepo.ALL) return
        AlertDialog.Builder(this)
            .setTitle("¿Ocultar «${item.name}»?")
            .setMessage("Puedes volver a mostrarla en Ajustes → «Mostrar categorías ocultas».")
            .setPositiveButton("Ocultar") { _, _ ->
                prefs.hideCategory(item.id)
                if (prefs.lastCategory == item.id) prefs.lastCategory = ChannelRepo.ALL
                renderCategories()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
