package com.example.reabrirvoz

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Vigila la app de Copilot. Cuando la sesión de voz se apaga y aparece el botón
 * de voz, lo toca para abrir otra sesión.
 *
 * Modo segundo plano: si saliste de Copilot con la voz activa y el micrófono
 * deja de usarse, trae Copilot al frente, reabre la voz y te devuelve a la app
 * donde estabas.
 */
class MicService : AccessibilityService() {

    private class Item(val n: AccessibilityNodeInfo, val depth: Int)

    private val handler = Handler(Looper.getMainLooper())
    private val regexCache = HashMap<String, Regex>()
    private val clickTimes = ArrayDeque<Long>()

    private var lastClick = 0L
    private var wasActiveAt = 0L
    private var pausedUntil = 0L
    private var lastPkg = ""
    private var lastActive: Boolean? = null

    // --- segundo plano ---
    private var foregroundPkg = ""
    private var micActive = false
    private var lastMicCount = -1
    private var armed = false
    private var armedAt = 0L
    private var bgMicSeen = false
    private var micLostAt = 0L
    private var micIdleAt = 0L
    private var blockedUntil = 0L
    private var launching = false
    private var launchedAt = 0L
    private var pressedAt = 0L
    private var confirmedAt = 0L
    private var returnPkg: String? = null
    private val reopenTimes = ArrayDeque<Long>()
    private var overlay: View? = null

    private val micCb = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            updateMic(configs)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            safeEvaluate()
            handler.postDelayed(this, 3000)
        }
    }
    private val bgTicker = object : Runnable {
        override fun run() {
            try {
                bgTick()
            } catch (ex: Exception) {
                Reg.add("Error bg: ${ex.message}")
            }
            handler.postDelayed(this, 1000)
        }
    }
    private val soon = Runnable { safeEvaluate() }
    private val removeOv = Runnable { removeOverlay() }

    override fun onServiceConnected() {
        Reg.add("Servicio conectado")
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.registerAudioRecordingCallback(micCb, handler)
            updateMic(am.activeRecordingConfigurations)
        } catch (ex: Exception) {
            Reg.add("No pude vigilar el micrófono: ${ex.message}")
        }
        foregroundPkg = rootInActiveWindow?.packageName?.toString() ?: ""
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(bgTicker)
        handler.postDelayed(ticker, 3000)
        handler.postDelayed(bgTicker, 1000)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val pkg = e.packageName?.toString() ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) trackForeground(pkg)
        if (!isTarget(pkg)) return
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            onUserClick(e)
            return
        }
        handler.removeCallbacks(soon)
        handler.postDelayed(soon, 1200)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioRecordingCallback(micCb)
        } catch (ex: Exception) {
        }
        removeOverlay()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun isTarget(pkg: String) = Prefs.packages(this).any { it.equals(pkg, ignoreCase = true) }

    private fun safeEvaluate() {
        try {
            evaluate()
        } catch (ex: Exception) {
            Reg.add("Error: ${ex.message}")
        }
    }

    // ------------------------------------------------------------------
    // Micrófono y app en primer plano
    // ------------------------------------------------------------------

    private fun updateMic(configs: List<AudioRecordingConfiguration>) {
        // Las fuentes >= 1997 son internas del sistema (palabra de activación, etc.)
        val real = configs.filter { it.clientAudioSource < 1997 }
        val live = real.filter { !(Build.VERSION.SDK_INT >= 29 && it.isClientSilenced) }
        val now = live.isNotEmpty()
        if (now != micActive || real.size != lastMicCount) {
            Reg.add("Micrófono: ${live.size} activo(s), ${real.size - live.size} silenciado(s)")
        }
        lastMicCount = real.size
        micActive = now
        State.mic = now
    }

    private fun ignoredForeground(pkg: String) =
        pkg == packageName || pkg == "android" || pkg == "com.android.systemui" ||
            pkg.contains("inputmethod", ignoreCase = true) || pkg.contains("keyboard", ignoreCase = true)

    private fun trackForeground(pkg: String) {
        if (ignoredForeground(pkg) || pkg == foregroundPkg) return
        val old = foregroundPkg
        foregroundPkg = pkg
        if (isTarget(old) && !isTarget(pkg)) onLeftTarget()
        else if (!isTarget(old) && isTarget(pkg)) onEnteredTarget()
    }

    private fun onLeftTarget() {
        if (launching) return
        if (!Prefs.enabled(this) || !Prefs.background(this)) return
        val now = System.currentTimeMillis()
        if (now < pausedUntil) return
        if (!(micActive || lastActive == true)) {
            Reg.add("Saliste de Copilot sin voz activa: no vigilo")
            return
        }
        armed = true
        armedAt = now
        bgMicSeen = micActive
        micLostAt = 0L
        Reg.add("Saliste de Copilot con voz activa: vigilo el micrófono (ahora en $foregroundPkg)")
    }

    private fun onEnteredTarget() {
        if (armed && !launching) {
            armed = false
            Reg.add("Volviste a Copilot: dejo de vigilar el fondo")
        }
    }

    /** Se llama cada segundo. */
    private fun bgTick() {
        if (!Prefs.enabled(this) || !Prefs.background(this)) {
            armed = false
            launching = false
            return
        }
        val now = System.currentTimeMillis()
        if (launching) {
            tickLaunching(now)
            return
        }
        if (!armed) {
            alwaysOn(now)
            return
        }

        if (micActive) {
            if (!bgMicSeen) Reg.add("Copilot mantiene el micrófono en segundo plano")
            bgMicSeen = true
            micLostAt = 0L
            return
        }
        if (!bgMicSeen) {
            if (now - armedAt > 6_000) {
                armed = false
                blockedUntil = now + 600_000
                Reg.add(
                    "No vi el micrófono en uso en segundo plano (Copilot lo corta al salir, " +
                        "Android lo silencia o el teléfono no lo informa): no puedo reabrir desde otra app"
                )
            }
            return
        }
        if (micLostAt == 0L) {
            micLostAt = now
            return
        }
        if (now - micLostAt < 4_000) return
        reopenInBackground(now)
    }

    /** Modo "siempre activo": si estás en otra app y nadie usa el micrófono, abre la voz de Copilot. */
    private fun alwaysOn(now: Long) {
        if (!Prefs.always(this) || foregroundPkg.isEmpty() || isTarget(foregroundPkg) ||
            now < pausedUntil || now < blockedUntil
        ) {
            micIdleAt = 0L
            return
        }
        if (micActive) {
            micIdleAt = 0L
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            micIdleAt = 0L
            return
        }
        if (micIdleAt == 0L) {
            micIdleAt = now
            return
        }
        if (now - micIdleAt < 5_000) return
        micIdleAt = 0L
        reopenInBackground(now)
    }

    private fun reopenInBackground(now: Long) {
        while (reopenTimes.isNotEmpty() && now - reopenTimes.first() > 600_000) reopenTimes.removeFirst()
        if (reopenTimes.size >= 10) {
            armed = false
            blockedUntil = now + 600_000
            Reg.add("Límite: 10 reaperturas en 10 min, pauso 10 min")
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            armed = false
            Reg.add("Pantalla apagada: no reabro")
            return
        }
        reopenTimes.addLast(now)
        returnPkg = foregroundPkg.ifEmpty { null }
        armed = false
        launching = true
        launchedAt = now
        pressedAt = 0L
        confirmedAt = 0L
        lastActive = null
        Reg.add("Micrófono apagado en segundo plano: abro Copilot (después vuelvo a ${returnPkg ?: "inicio"})")
        showOverlay()
        if (!launch(Prefs.packages(this).firstOrNull() ?: "")) {
            launching = false
            Reg.add("No pude abrir Copilot")
        }
    }

    private fun tickLaunching(now: Long) {
        if (!isTarget(foregroundPkg)) {
            if (now - launchedAt > 8_000) {
                launching = false
                Reg.add(
                    "Copilot no llegó al frente: Android bloqueó abrirlo desde el fondo. " +
                        "Prueba el botón 4 (mostrar sobre otras apps)"
                )
            }
            return
        }
        safeEvaluate()
        val ok = lastActive == true || (pressedAt > 0 && micActive)
        if (ok) {
            if (confirmedAt == 0L) {
                confirmedAt = now
                return
            }
            if (now - confirmedAt >= 2_000) goBack()
            return
        }
        confirmedAt = 0L
        if (now - launchedAt > 25_000) {
            launching = false
            Reg.add("No logré reabrir la voz en 25 s: me quedo en Copilot")
        }
    }

    private fun goBack() {
        launching = false
        val r = returnPkg
        if (r == null || isHome(r)) {
            Reg.add("Voz reabierta: vuelvo al inicio")
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        Reg.add("Voz reabierta: vuelvo a $r")
        if (!launch(r)) performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed({
            if (isTarget(foregroundPkg) && !launching) {
                Reg.add("No pude volver a $r: te dejo en el inicio")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }, 3_000)
    }

    private fun launch(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(i)
            true
        } catch (ex: Exception) {
            Reg.add("Android bloqueó abrir $pkg: ${ex.message}")
            false
        }
    }

    private fun isHome(pkg: String): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(i, 0).any { it.activityInfo.packageName == pkg }
    }

    /** Ventana de 1 píxel que ayuda a que Android deje abrir Copilot desde el fondo. */
    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        try {
            removeOverlay()
            val v = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
            overlay = v
            handler.removeCallbacks(removeOv)
            handler.postDelayed(removeOv, 12_000)
        } catch (ex: Exception) {
            Reg.add("Overlay: ${ex.message}")
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (ex: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Con Copilot en pantalla
    // ------------------------------------------------------------------

    /** Si tocas "terminar" tú mismo, no se reabre durante 60 segundos. */
    private fun onUserClick(e: AccessibilityEvent) {
        val txt = (e.text.joinToString(" ") + " " + (e.contentDescription ?: "")).trim()
        if (matches(txt, Prefs.endPatterns(this))) {
            pausedUntil = System.currentTimeMillis() + 60_000
            armed = false
            Reg.add("Tocaste terminar (\"$txt\"): pausa de 60 s")
        }
    }

    private fun evaluate() {
        if (!Prefs.enabled(this)) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg != lastPkg) {
            lastPkg = pkg
            Reg.add("Ventana activa: $pkg")
        }
        if (!isTarget(pkg)) return

        val items = ArrayList<Item>()
        collect(root, items, 0)
        saveDump(pkg, items)

        val now = System.currentTimeMillis()
        val activeP = Prefs.activePatterns(this)
        val active = items.any {
            it.n.isVisibleToUser && isClickable(it.n) && matches(label(it.n), activeP)
        }
        if (active) {
            wasActiveAt = now
            if (lastActive != true) {
                lastActive = true
                Reg.add("Sesión de voz ACTIVA")
            }
            return
        }
        if (lastActive != false) {
            lastActive = false
            Reg.add("Sesión de voz apagada o no detectada")
        }

        if (Prefs.smart(this) && !launching && now - wasActiveAt > 25_000) return
        if (now < pausedUntil || now - lastClick < 4_000) return
        while (clickTimes.isNotEmpty() && now - clickTimes.first() > 60_000) clickTimes.removeFirst()
        if (clickTimes.size >= 8) return

        val startP = Prefs.startPatterns(this)
        val btn = items.map { it.n }.firstOrNull { n ->
            n.isVisibleToUser && isClickable(n) && label(n).length <= 40 &&
                (matches(label(n), startP) || matches(n.viewIdResourceName ?: "", startP))
        } ?: return

        press(btn)
    }

    private fun press(n: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        lastClick = now
        clickTimes.addLast(now)
        if (launching) pressedAt = now
        val target = if (n.isClickable) n else clickableAncestor(n)
        val ok = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        Reg.add("Toqué \"${label(n)}\": " + if (ok) "ok" else "falló, pruebo con toque en pantalla")
        if (!ok) tapCenter(n)
    }

    private fun tapCenter(n: AccessibilityNodeInfo) {
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return
        val path = Path()
        path.moveTo(r.exactCenterX(), r.exactCenterY())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<Item>, depth: Int) {
        if (n == null || out.size > 600 || depth > 40) return
        out.add(Item(n, depth))
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }

    private fun label(n: AccessibilityNodeInfo): String =
        ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).trim()

    private fun isClickable(n: AccessibilityNodeInfo) = n.isClickable || clickableAncestor(n) != null

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p = n.parent
        var i = 0
        while (p != null && i < 5) {
            if (p.isClickable) return p
            p = p.parent
            i++
        }
        return null
    }

    /** Coincidencia de palabra completa, sin importar mayúsculas. */
    private fun matches(s: String, patterns: List<String>): Boolean {
        if (s.isBlank()) return false
        return patterns.any { p ->
            val re = regexCache.getOrPut(p) {
                Regex("(?iu)(^|[^\\p{L}])" + Regex.escape(p) + "([^\\p{L}]|$)")
            }
            re.containsMatchIn(s)
        }
    }

    private fun saveDump(pkg: String, items: List<Item>) {
        val sb = StringBuilder()
        sb.append("paquete=").append(pkg).append('\n')
        for (it in items) {
            val n = it.n
            val t = n.text?.toString()?.take(80)
            val d = n.contentDescription?.toString()?.take(80)
            if (t.isNullOrBlank() && d.isNullOrBlank() && !n.isClickable) continue
            sb.append("  ".repeat(minOf(it.depth, 12)))
                .append(n.className?.toString()?.substringAfterLast('.') ?: "?")
                .append(" texto=").append(t)
                .append(" desc=").append(d)
                .append(" id=").append(n.viewIdResourceName)
            if (n.isClickable) sb.append(" [clic]")
            if (!n.isVisibleToUser) sb.append(" [oculto]")
            sb.append('\n')
        }
        val s = sb.toString().take(15000)
        if (s != Snap.last) {
            Snap.prev = Snap.last
            Snap.last = s
        }
    }
}
