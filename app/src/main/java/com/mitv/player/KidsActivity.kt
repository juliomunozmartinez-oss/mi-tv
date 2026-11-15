package com.mitv.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/** Elegir qué categorías ven los perfiles infantiles. */
class KidsActivity : AppCompatActivity() {

    private sealed class Item {
        data class Header(val text: String) : Item()
        data class Check(val category: Category) : Item()
    }

    private lateinit var prefs: Prefs
    private lateinit var list: RecyclerView
    private var items: List<Item> = emptyList()
    private var selected: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kids)
        prefs = Prefs(this)
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null

        findViewById<TextView>(R.id.btnAuto).setOnClickListener {
            prefs.kidsCategories = Catalog.autoKidsCategories()
            selected = prefs.kidsCategories
            list.adapter?.notifyDataSetChanged()
            Toast.makeText(this, "Se marcaron ${selected.size} categorías", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            try {
                Catalog.ensureLoaded(prefs)
                build()
            } catch (e: Exception) {
                Toast.makeText(this@KidsActivity, XtreamClient.friendlyError(e), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun build() {
        selected = prefs.kidsCategories
        val out = mutableListOf<Item>()
        fun section(title: String, cats: List<Category>) {
            if (cats.isEmpty()) return
            out += Item.Header(title)
            // Primero las marcadas, para encontrarlas fácil.
            cats.sortedBy { if (it.key in selected) 0 else 1 }.forEach { out += Item.Check(it) }
        }
        section("TV EN VIVO", Catalog.liveCats)
        section("PELÍCULAS", Catalog.vodCats)
        section("SERIES", Catalog.seriesCats)
        items = out
        list.adapter = Adapter()
        list.post { list.layoutManager?.findViewByPosition(1)?.requestFocus() }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == 0) R.layout.item_header else R.layout.item_check
            val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val tv = holder.itemView as TextView
            when (val item = items[position]) {
                is Item.Header -> tv.text = item.text
                is Item.Check -> {
                    val on = item.category.key in selected
                    tv.text = (if (on) "☑  " else "☐  ") + item.category.name
                    tv.alpha = if (on) 1f else 0.7f
                    tv.setOnClickListener {
                        prefs.toggleKidsCategory(item.category.key)
                        selected = prefs.kidsCategories
                        notifyItemChanged(holder.bindingAdapterPosition)
                    }
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
