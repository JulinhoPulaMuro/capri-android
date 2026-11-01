package app.capri

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Deixa a Capri apertar botões do sistema, tocar em itens da tela, rolar e digitar.
 * Só funciona depois que a pessoa liga "Capri" em Configurações, Acessibilidade.
 * Ela só age quando recebe um pedido; não lê nem guarda o que aparece na tela.
 */
class A11yService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---------- botões do sistema ----------

    fun global(kind: String): String {
        val code: Int
        val say: String
        when (kind) {
            "back" -> {
                code = GLOBAL_ACTION_BACK
                say = "Voltei."
            }
            "home" -> {
                code = GLOBAL_ACTION_HOME
                say = "Tela inicial."
            }
            "recents" -> {
                code = GLOBAL_ACTION_RECENTS
                say = "Abri os apps recentes."
            }
            "notifications" -> {
                code = GLOBAL_ACTION_NOTIFICATIONS
                say = "Abri as notificações."
            }
            "quick" -> {
                code = GLOBAL_ACTION_QUICK_SETTINGS
                say = "Abri as configurações rápidas."
            }
            "screenshot" -> {
                if (Build.VERSION.SDK_INT < 28) return "Não consigo tirar print neste Android."
                code = GLOBAL_ACTION_TAKE_SCREENSHOT
                say = "Tirei o print."
            }
            "lock" -> {
                if (Build.VERSION.SDK_INT < 28) return "Não consigo bloquear a tela neste Android."
                code = GLOBAL_ACTION_LOCK_SCREEN
                say = "Bloqueei a tela."
            }
            else -> return "Não sei esse botão."
        }
        return if (performGlobalAction(code)) say else "Não consegui apertar esse botão."
    }

    // ---------- janelas e nós ----------

    /** Janelas dos outros apps (e do sistema), da mais de cima para a de baixo. */
    private fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            val ws: List<AccessibilityWindowInfo>? = windows
            if (ws != null) {
                for (w in ws.sortedByDescending { it.layer }) {
                    if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                    val r = w.root ?: continue
                    if (r.packageName?.toString() == packageName) continue
                    out.add(r)
                }
            }
        } catch (e: Exception) {
            // sem acesso às janelas
        }
        if (out.isEmpty()) {
            val r = rootInActiveWindow
            if (r != null && r.packageName?.toString() != packageName) out.add(r)
        }
        return out
    }

    private fun walk(root: AccessibilityNodeInfo, deadline: Long, visit: (AccessibilityNodeInfo) -> Unit) {
        val stack = ArrayList<AccessibilityNodeInfo>()
        stack.add(root)
        var count = 0
        while (stack.isNotEmpty() && count < 2500 && System.nanoTime() < deadline) {
            val n = stack.removeAt(stack.size - 1)
            count++
            visit(n)
            for (i in n.childCount - 1 downTo 0) {
                val c = n.getChild(i) ?: continue
                stack.add(c)
            }
        }
    }

    private fun labelOf(n: AccessibilityNodeInfo): String {
        val t = n.text?.toString()
        if (!t.isNullOrBlank()) return t
        val d = n.contentDescription?.toString()
        if (!d.isNullOrBlank()) return d
        return ""
    }

    private fun score(n: AccessibilityNodeInfo, want: String): Int {
        if (!n.isVisibleToUser) return 0
        val cands = ArrayList<String>(3)
        n.text?.let { cands.add(it.toString()) }
        n.contentDescription?.let { cands.add(it.toString()) }
        if (Build.VERSION.SDK_INT >= 26) n.hintText?.let { cands.add(it.toString()) }
        var best = 0
        for (c in cands) {
            val p = Commands.plain(c)
            if (p.isEmpty()) continue
            val s = when {
                p == want -> 100
                p.startsWith(want) && want.length >= 2 -> 80
                want.length >= 3 && p.contains(want) -> 60
                p.length >= 4 && want.contains(p) -> 50
                else -> 0
            }
            if (s > best) best = s
        }
        if (best > 0 && n.isClickable) best += 5
        return best
    }

    private fun isDanger(plain: String): Boolean =
        DANGER.any { Regex("\\b" + it + "\\b").containsMatchIn(plain) }

    // ---------- tocar, rolar, digitar ----------

    fun click(target: String): String {
        val want = Commands.plain(target)
        if (want.isEmpty()) return "Não entendi em que tocar."
        if (isDanger(want)) return DANGER_MSG
        val deadline = System.nanoTime() + 1_800_000_000L
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        for (r in roots()) {
            walk(r, deadline) { n ->
                val s = score(n, want)
                if (s > bestScore) {
                    best = n
                    bestScore = s
                }
            }
            if (bestScore >= 100) break
        }
        val node = best ?: return "Não vi \"" + target + "\" na tela."
        val label = labelOf(node).ifEmpty { target }
        if (isDanger(Commands.plain(label))) return DANGER_MSG

        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.isEnabled && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "Toquei em " + label + "."
            }
            cur = cur.parent
            depth++
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty && tap(rect.centerX().toFloat(), rect.centerY().toFloat())) {
            return "Toquei em " + label + "."
        }
        return "Não consegui tocar em " + label + "."
    }

    private fun tap(x: Float, y: Float): Boolean {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 60L))
            .build()
        return dispatchGesture(g, null, null)
    }

    fun scroll(dir: String): String {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val h = dm.heightPixels.toFloat()
        val up = dir == "up"
        val p = Path()
        p.moveTo(x, if (up) h * 0.30f else h * 0.70f)
        p.lineTo(x, if (up) h * 0.70f else h * 0.30f)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 320L))
            .build()
        return if (dispatchGesture(g, null, null)) {
            if (up) "Rolei para cima." else "Rolei para baixo."
        } else {
            "Não consegui rolar a tela."
        }
    }

    fun type(text: String): String {
        if (text.isEmpty()) return "Não entendi o que digitar."
        val deadline = System.nanoTime() + 1_500_000_000L
        val all = roots()
        var target: AccessibilityNodeInfo? = null
        for (r in all) {
            val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null && f.isEditable) {
                target = f
                break
            }
        }
        if (target == null) {
            // sem campo selecionado: se houver só um campo de texto na tela, usa ele
            val edits = ArrayList<AccessibilityNodeInfo>()
            for (r in all) {
                walk(r, deadline) { n -> if (n.isEditable && n.isVisibleToUser) edits.add(n) }
            }
            if (edits.size == 1) {
                val one = edits[0]
                one.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                target = one
            }
        }
        val f = target ?: return "Não achei um campo de texto. Toque no campo e peça de novo."
        val old = if (f.isShowingHintText) "" else (f.text?.toString() ?: "")
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, old + text)
        return if (f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            "Digitei."
        } else {
            "Não consegui digitar nesse campo."
        }
    }

    companion object {
        @Volatile
        var instance: A11yService? = null

        private const val DANGER_MSG = "Isso mexe com dinheiro ou apaga coisas. Prefiro que você mesmo toque."

        /** Palavras (sem acento) que a Capri nunca aperta sozinha. */
        private val DANGER = listOf(
            "pagar", "pagamento", "comprar", "compra", "transferir", "transferencia", "pix",
            "enviar dinheiro", "finalizar pedido", "excluir conta", "apagar conta", "deletar conta",
            "desinstalar", "formatar", "redefinir", "restaurar padrao", "apagar tudo", "limpar tudo"
        )

        /** True se a Capri está ligada em Configurações, Acessibilidade. */
        fun enabled(ctx: Context): Boolean {
            if (instance != null) return true
            val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(ctx, A11yService::class.java)
            for (p in s.split(':')) {
                if (ComponentName.unflattenFromString(p) == me) return true
            }
            return false
        }
    }
}
