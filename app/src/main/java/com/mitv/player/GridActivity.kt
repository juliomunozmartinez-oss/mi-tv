package com.mitv.player

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** «Ver todo»: la sección completa de una fila en cuadrícula. */
class GridActivity : AppCompatActivity() {

    companion object {
        /** La fila que se va a abrir (se pasa así porque puede tener miles de títulos). */
        var pending: Row? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profile = Profiles.byId(this, intent.getStringExtra(Nav.EXTRA_PROFILE))
        val source = pending
        if (profile == null || source == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_grid)
        val title = findViewById<TextView>(R.id.title)
        val count = findViewById<TextView>(R.id.count)
        val grid = findViewById<RecyclerView>(R.id.grid)
        val loading = findViewById<View>(R.id.loading)
        title.text = source.title
        grid.layoutManager = GridLayoutManager(this, if (source.wide) 3 else 6)
        grid.itemAnimator = null
        val gap = (20 * resources.displayMetrics.density).toInt()
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State,
            ) {
                outRect.bottom = gap
            }
        })

        lifecycleScope.launch {
            val cards = withContext(Dispatchers.Default) {
                (source.all?.invoke() ?: source.cards).filter { !it.more }
            }
            loading.visibility = View.GONE
            count.text = "${cards.size} títulos"
            val row = Row(source.title, cards, source.wide)
            grid.adapter = CardAdapter(
                row,
                onClick = { r, i -> Nav.open(this@GridActivity, profile, r, i) },
                store = ProfileStore(this@GridActivity, profile.id),
            )
            grid.post { grid.layoutManager?.findViewByPosition(0)?.requestFocus() }
        }
    }
}
