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

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            Ui.askPin(this, prefs) { startActivity(Intent(this, SettingsActivity::class.java)) }
        }
    }

    /** Dibuja los perfiles (se vuelve a hacer al regresar de Ajustes por si se editaron). */
    private fun buildProfiles() {
        val container = findViewById<LinearLayout>(R.id.profiles)
        container.removeAllViews()
        firstAvatar = null
        val inf = LayoutInflater.from(this)
        Profiles.all(this).forEach { profile ->
            val item = inf.inflate(R.layout.item_profile, container, false)
            val avatar = item.findViewById<View>(R.id.avatar)
            val name = item.findViewById<TextView>(R.id.name)
            Avatars.bind(avatar, item.findViewById(R.id.initial), item.findViewById(R.id.avatarImage), profile)
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
    }

    override fun onResume() {
        super.onResume()
        buildProfiles()
        firstAvatar?.requestFocus()
        MiTvApp.takeLastCrash(this)?.let { report ->
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("La app se cerró por un error")
                .setMessage("Tómale foto a este mensaje y mándaselo a Claude para arreglarlo:\n\n$report")
                .setPositiveButton("Entendido", null)
                .show()
        }
        if (!prefs.isConfigured) {
            if (!askedSetup) {
                askedSetup = true
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            return
        }
        Updater.checkOnStart(this, lifecycleScope)
        // Ir cargando el catálogo mientras eligen perfil.
        lifecycleScope.launch { runCatching { Catalog.ensureLoaded(prefs) } }
    }

    private fun enter(profile: Profile) {
        if (profile.kids) Nav.home(this, profile) else Ui.askPin(this, prefs) { Nav.home(this, profile) }
    }
}
