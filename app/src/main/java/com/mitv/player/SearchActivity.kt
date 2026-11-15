package com.mitv.player

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Buscar con teclado grande en pantalla, voz y resultados con pósters. */
class SearchActivity : AppCompatActivity() {


    private lateinit var profile: Profile
    private lateinit var prefs: Prefs
    private lateinit var query: EditText
    private lateinit var results: RecyclerView
    private lateinit var resultsTitle: TextView
    private lateinit var empty: TextView

    private var entries: List<SearchIndex.Entry> = emptyList()
    private var suggestions: List<Card> = emptyList()
    private var searchJob: Job? = null

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            query.setText(text)
            query.setSelection(query.text.length)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Profiles.byId(this, intent.getStringExtra(Nav.EXTRA_PROFILE))
        if (p == null) {
            finish()
            return
        }
        profile = p
        prefs = Prefs(this)
        setContentView(R.layout.activity_search)

        query = findViewById(R.id.query)
        results = findViewById(R.id.results)
        resultsTitle = findViewById(R.id.resultsTitle)
        empty = findViewById(R.id.empty)

        findViewById<View>(R.id.btnVoice).setOnClickListener { startVoice() }
        setupKeyboard()

        results.layoutManager = GridLayoutManager(this, 4)
        results.itemAnimator = null

        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = schedule()
        })

        lifecycleScope.launch {
            try {
                Catalog.ensureLoaded(prefs)
                withContext(Dispatchers.Default) { index() }
                schedule()
            } catch (e: Exception) {
                empty.text = XtreamClient.friendlyError(e)
                empty.visibility = View.VISIBLE
            }
        }
    }

    /** Usa el índice ya preparado desde el Inicio (o lo arma si aún no existe). */
    private fun index() {
        val d = SearchIndex.get(prefs, profile)
        entries = d.entries
        suggestions = d.suggestions
    }

    private fun schedule() {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(250)
            val text = query.text.toString()
            val q = SearchMatcher.tokens(text)
            if (q.isEmpty()) {
                show("Te sugerimos", suggestions, null)
                return@launch
            }
            val found = withContext(Dispatchers.Default) {
                entries.asSequence()
                    .map { it to SearchMatcher.score(q, it.prepared) }
                    .filter { it.second > 0 }
                    .sortedWith(
                        compareByDescending<Pair<SearchIndex.Entry, Int>> { it.second + it.first.boost }
                            .thenBy { it.first.card.title.length }
                    )
                    .take(60)
                    .map { it.first.card }
                    .toList()
            }
            show("Resultados para «${text.trim()}»", found, "No encontramos «${text.trim()}».\nPrueba con menos letras o con la voz.")
        }
    }

    private fun show(title: String, cards: List<Card>, emptyText: String?) {
        resultsTitle.text = title
        val row = Row(title, cards, wide = false)
        results.adapter = CardAdapter(
            row,
            onClick = { r, i -> Nav.open(this, profile, r, i) },
            store = ProfileStore(this, profile.id),
        )
        empty.visibility = if (cards.isEmpty() && emptyText != null) View.VISIBLE else View.GONE
        empty.text = emptyText ?: ""
    }

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "¿Qué quieres ver?")
        try {
            voice.launch(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Este aparato no tiene búsqueda por voz. Usa las letras.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Teclado en pantalla ----------

    private fun setupKeyboard() {
        val keys = ("ABCDEFG" + "HIJKLMN" + "ÑOPQRST" + "UVWXYZ0" + "123456789").map { it.toString() } +
            listOf(SPACE, DELETE, CLEAR)
        val grid = findViewById<RecyclerView>(R.id.keyboard)
        val lm = GridLayoutManager(this, 7)
        // Las teclas especiales son más anchas: los 9 números ocupan 7+2, luego Espacio (3), Borrar (2), Limpiar (2).
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = when (keys[position]) {
                SPACE -> 3
                DELETE, CLEAR -> 2
                else -> 1
            }
        }
        grid.layoutManager = lm
        grid.itemAnimator = null
        grid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_key, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val key = keys[position]
                val tv = holder.itemView as TextView
                tv.text = when (key) {
                    SPACE -> "Espacio"
                    DELETE -> "⌫"
                    CLEAR -> "Borrar"
                    else -> key
                }
                tv.setOnClickListener { press(key) }
            }

            override fun getItemCount() = keys.size
        }
        grid.post { grid.layoutManager?.findViewByPosition(0)?.requestFocus() }
    }

    private fun press(key: String) {
        val t = query.text
        when (key) {
            SPACE -> if (t.isNotEmpty() && !t.endsWith(" ")) t.append(" ")
            DELETE -> if (t.isNotEmpty()) t.delete(t.length - 1, t.length)
            CLEAR -> t.clear()
            else -> t.append(key.lowercase())
        }
    }

    companion object {
        private const val SPACE = "␣"
        private const val DELETE = "⌫"
        private const val CLEAR = "✕"
    }
}
