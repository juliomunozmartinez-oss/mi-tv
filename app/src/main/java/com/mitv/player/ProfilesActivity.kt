package com.mitv.player

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Primera pantalla: «¿Quién está viendo?» */
class ProfilesActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var askedSetup = false
    private var firstAvatar: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)
        prefs = Prefs(this)

        val container = findViewById<LinearLayout>(R.id.profiles)
        val inf = LayoutInflater.from(this)
        Profiles.all.forEach { profile ->
            val item = inf.inflate(R.layout.item_profile, container, false)
            val avatar = item.findViewById<View>(R.id.avatar)
            val name = item.findViewById<TextView>(R.id.name)
            avatar.background = Ui.circle(profile.color)
            item.findViewById<TextView>(R.id.initial).text = profile.initial
            name.text = profile.name
            item.findViewById<View>(R.id.badge).visibility = if (profile.kids) View.VISIBLE else View.INVISIBLE
            avatar.setOnFocusChangeListener { v, has ->
                val s = if (has) 1.12f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(140).start()
                name.setTextColor(getColor(if (has) R.color.text else R.color.text_dim))
            }
            avatar.setOnClickListener { enter(profile) }
            container.addView(item)
            if (firstAvatar == null) firstAvatar = avatar
        }

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            Ui.askPin(this, prefs) { startActivity(Intent(this, SettingsActivity::class.java)) }
        }
        firstAvatar?.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (!prefs.isConfigured) {
            if (!askedSetup) {
                askedSetup = true
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            return
        }
        // Ir cargando el catálogo mientras eligen perfil.
        lifecycleScope.launch { runCatching { Catalog.ensureLoaded(prefs) } }
    }

    private fun enter(profile: Profile) {
        if (profile.kids) Nav.home(this, profile) else Ui.askPin(this, prefs) { Nav.home(this, profile) }
    }
}
