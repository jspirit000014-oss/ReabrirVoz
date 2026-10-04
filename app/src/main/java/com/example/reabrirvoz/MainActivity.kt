package com.example.reabrirvoz

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var etPk: EditText
    private lateinit var etStart: EditText
    private lateinit var etActive: EditText
    private lateinit var etEnd: EditText

    private val refresh = object : Runnable {
        override fun run() {
            paint()
            h.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(16), dp(16), dp(16))
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this)
        title.text = "Reabrir voz de Copilot"
        title.textSize = 22f
        root.addView(title)

        status = TextView(this)
        status.setPadding(0, dp(8), 0, dp(8))
        root.addView(status)

        root.addView(button("1) Activar el servicio de accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(note("Entra a Reabrir Voz (puede estar en Apps instaladas) y actívalo."))

        val swOn = Switch(this)
        swOn.text = "Reabrir la voz automáticamente"
        swOn.isChecked = Prefs.enabled(this)
        swOn.setOnCheckedChangeListener { _, v -> Prefs.setEnabled(this, v) }
        root.addView(swOn)

        val swSmart = Switch(this)
        swSmart.text = "Solo si hace poco había una sesión de voz activa"
        swSmart.isChecked = Prefs.smart(this)
        swSmart.setOnCheckedChangeListener { _, v -> Prefs.setSmart(this, v) }
        root.addView(swSmart)
        root.addView(note("Desactivado: abre la voz siempre que vea el botón en Copilot. Apaga el interruptor de arriba cuando quieras escribir en vez de hablar."))

        val swBg = Switch(this)
        swBg.text = "Reabrir también cuando estoy en otra app"
        swBg.isChecked = Prefs.background(this)
        swBg.setOnCheckedChangeListener { _, v -> Prefs.setBackground(this, v) }
        root.addView(swBg)
        val swAlways = Switch(this)
        swAlways.text = "Siempre activo en otras apps"
        swAlways.isChecked = Prefs.always(this)
        swAlways.setOnCheckedChangeListener { _, v -> Prefs.setAlways(this, v) }
        root.addView(swAlways)
        root.addView(note("Si estás en otra app y el micrófono lleva 5 s sin usarse, abre la voz de Copilot y vuelve contigo. Apaga el interruptor de arriba (o este) cuando no la quieras."))
        root.addView(note("Si saliste de Copilot con la voz activa y el micrófono se apaga, trae Copilot al frente, reabre la voz y te devuelve a la app donde estabas."))

        root.addView(button("2) Abrir Copilot") { openTarget() })
        root.addView(button("3) Quitar ahorro de batería a esta app") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        root.addView(button("4) Permitir mostrar sobre otras apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
            )
        })
        root.addView(note("Ayuda a que Android deje abrir Copilot desde otra app (necesario para el modo segundo plano)."))

        root.addView(note("Textos que busca (separados por comas):"))
        etPk = field("Paquete de la app", Prefs.rawPackages(this), root)
        etStart = field("Botón para iniciar la voz", Prefs.rawStart(this), root)
        etActive = field("Señal de sesión activa", Prefs.rawActive(this), root)
        etEnd = field("Botón de terminar (pausa 60 s)", Prefs.rawEnd(this), root)
        root.addView(button("Guardar textos") {
            Prefs.saveTexts(
                this, etPk.text.toString(), etStart.text.toString(),
                etActive.text.toString(), etEnd.text.toString()
            )
            toast("Guardado")
        })

        root.addView(note("Si no toca el botón correcto: abre Copilot con la voz activa, espera a que se apague, vuelve aquí y comparte el volcado."))
        root.addView(button("Copiar volcado de pantalla") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("volcado", dump()))
            toast("Copiado")
        })
        root.addView(button("Compartir volcado") {
            val send = Intent(Intent.ACTION_SEND)
            send.type = "text/plain"
            send.putExtra(Intent.EXTRA_TEXT, dump())
            startActivity(Intent.createChooser(send, "Compartir volcado"))
        })

        val logTitle = TextView(this)
        logTitle.text = "Registro"
        logTitle.textSize = 18f
        logTitle.setPadding(0, dp(16), 0, dp(4))
        root.addView(logTitle)
        logView = TextView(this)
        logView.textSize = 12f
        logView.setTextIsSelectable(true)
        root.addView(logView)
    }

    override fun onResume() {
        super.onResume()
        h.post(refresh)
    }

    override fun onPause() {
        h.removeCallbacks(refresh)
        super.onPause()
    }

    private fun paint() {
        status.text = (if (serviceOn()) "Servicio: ACTIVADO" else "Servicio: DESACTIVADO (usa el botón 1)") +
            "\nMicrófono en uso ahora: " + (if (State.mic) "sí" else "no") +
            "\nMostrar sobre otras apps: " + (if (Settings.canDrawOverlays(this)) "permitido" else "no permitido (botón 4)")
        logView.text = Reg.text()
    }

    private fun serviceOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = packageName + "/" + MicService::class.java.name
        return s.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun openTarget() {
        val pkg = Prefs.packages(this).firstOrNull()
        val i = if (pkg != null) packageManager.getLaunchIntentForPackage(pkg) else null
        if (i != null) startActivity(i) else toast("No encuentro la app: $pkg")
    }

    private fun dump(): String =
        "=== ULTIMA PANTALLA ===\n" + Snap.last + "\n=== PANTALLA ANTERIOR ===\n" + Snap.prev +
            "\n=== REGISTRO ===\n" + Reg.text()

    private fun field(hint: String, value: String, parent: LinearLayout): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.isSingleLine = false
        parent.addView(e)
        return e
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun note(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 13f
        t.setPadding(0, dp(4), 0, dp(8))
        return t
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
