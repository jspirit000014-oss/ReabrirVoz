package com.example.reabrirvoz

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Configuración guardada en el teléfono. */
object Prefs {
    const val DEF_PACKAGES = "com.microsoft.copilot"
    const val DEF_START = "voz, voice, hablar, talk, modo de voz, voice mode, iniciar voz, start voice"
    const val DEF_ACTIVE = "finalizar, terminar, colgar, silenciar, end call, hang up, mute"
    const val DEF_END = "finalizar, terminar, colgar, end call, hang up"

    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    /** true = solo reabrir si hace poco había una sesión de voz activa. */
    fun smart(c: Context) = sp(c).getBoolean("smart", false)
    fun setSmart(c: Context, v: Boolean) = sp(c).edit().putBoolean("smart", v).apply()

    /** true = si sales de Copilot con la voz activa y el micrófono se apaga, la reabre. */
    fun background(c: Context) = sp(c).getBoolean("background", true)
    fun setBackground(c: Context, v: Boolean) = sp(c).edit().putBoolean("background", v).apply()

    /** true = siempre mantiene la voz abierta, también desde otras apps, sin esperar a que hubiera una sesión. */
    fun always(c: Context) = sp(c).getBoolean("always", true)
    fun setAlways(c: Context, v: Boolean) = sp(c).edit().putBoolean("always", v).apply()

    fun rawPackages(c: Context) = sp(c).getString("packages", DEF_PACKAGES) ?: DEF_PACKAGES
    fun rawStart(c: Context) = sp(c).getString("start", DEF_START) ?: DEF_START
    fun rawActive(c: Context) = sp(c).getString("active", DEF_ACTIVE) ?: DEF_ACTIVE
    fun rawEnd(c: Context) = sp(c).getString("end", DEF_END) ?: DEF_END

    fun saveTexts(c: Context, pk: String, start: String, active: String, end: String) {
        sp(c).edit()
            .putString("packages", pk)
            .putString("start", start)
            .putString("active", active)
            .putString("end", end)
            .apply()
    }

    private fun split(s: String) = s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun packages(c: Context) = split(rawPackages(c))
    fun startPatterns(c: Context) = split(rawStart(c))
    fun activePatterns(c: Context) = split(rawActive(c))
    fun endPatterns(c: Context) = split(rawEnd(c))
}

/** Registro corto de lo que hace el servicio, para mostrarlo en pantalla. */
object Reg {
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(msg: String) {
        lines.add(fmt.format(Date()) + "  " + msg)
        while (lines.size > 60) lines.removeAt(0)
    }

    @Synchronized
    fun text(): String = if (lines.isEmpty()) "(sin eventos todavía)" else lines.joinToString("\n")
}

/** Estado compartido con la pantalla principal. */
object State {
    @Volatile var mic: Boolean = false
}

/** Últimas capturas del árbol de la pantalla de Copilot, para calibrar los textos. */
object Snap {
    @Volatile var last: String = ""
    @Volatile var prev: String = ""
}
