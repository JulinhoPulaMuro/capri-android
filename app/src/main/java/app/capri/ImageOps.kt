package app.capri

import android.graphics.Bitmap

/** Funções de imagem usadas no recorte do boneco. */
object ImageOps {

    /** True se a imagem já tem uma parte transparente relevante (PNG recortado). */
    fun hasAlpha(b: Bitmap): Boolean {
        if (!b.hasAlpha()) return false
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var n = 0
        for (p in px) {
            if ((p ushr 24) < 240) n++
        }
        return n > (w * h) / 100
    }

    /** Tira 1 pixel da borda (restos do fundo) e remove pontinhos soltos. */
    fun clean(px: IntArray, w: Int, h: Int, keepSmall: Boolean = false) {
        val solid = BooleanArray(w * h)
        for (i in 0 until w * h) {
            solid[i] = (px[i] ushr 24) > 10
        }
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val p = y * w + x
                if (solid[p] && (!solid[p - 1] || !solid[p + 1] || !solid[p - w] || !solid[p + w])) {
                    px[p] = 0
                }
            }
        }
        keepBig(px, w, h, keepSmall)
    }

    /** Mantém só as partes grandes e conectadas da imagem. */
    private fun keepBig(px: IntArray, w: Int, h: Int, keepSmall: Boolean) {
        val n = w * h
        val label = IntArray(n)
        val stack = IntArray(n)
        val sizes = ArrayList<Int>()
        sizes.add(0)
        var count = 0
        for (s in 0 until n) {
            if (label[s] != 0 || (px[s] ushr 24) <= 10) continue
            count++
            var sp = 0
            var size = 0
            stack[sp++] = s
            label[s] = count
            while (sp > 0) {
                val p = stack[--sp]
                size++
                val x = p % w
                if (x > 0 && label[p - 1] == 0 && (px[p - 1] ushr 24) > 10) {
                    label[p - 1] = count
                    stack[sp++] = p - 1
                }
                if (x < w - 1 && label[p + 1] == 0 && (px[p + 1] ushr 24) > 10) {
                    label[p + 1] = count
                    stack[sp++] = p + 1
                }
                if (p >= w && label[p - w] == 0 && (px[p - w] ushr 24) > 10) {
                    label[p - w] = count
                    stack[sp++] = p - w
                }
                if (p < n - w && label[p + w] == 0 && (px[p + w] ushr 24) > 10) {
                    label[p + w] = count
                    stack[sp++] = p + w
                }
            }
            sizes.add(size)
        }
        var big = 0
        for (i in 1 until sizes.size) {
            if (sizes[i] > big) big = sizes[i]
        }
        // com keepSmall (cabelo cheio de pontas soltas) só some o que é pó mesmo
        val min = if (keepSmall) maxOf(8, (big * 0.0003).toInt()) else maxOf(30, (big * 0.005).toInt())
        for (q in 0 until n) {
            val l = label[q]
            if (l != 0 && sizes[l] < min) px[q] = 0
        }
    }

    /** Corta a imagem na menor caixa que contém a parte visível. Null se ficou vazia. */
    fun trim(px: IntArray, w: Int, h: Int): Bitmap? {
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if ((px[y * w + x] ushr 24) > 10) {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
        }
        if (x1 < 0) return null
        val pad = maxOf(w, h) / 100
        x0 = maxOf(0, x0 - pad)
        y0 = maxOf(0, y0 - pad)
        x1 = minOf(w - 1, x1 + pad)
        y1 = minOf(h - 1, y1 + pad)
        val ow = x1 - x0 + 1
        val oh = y1 - y0 + 1
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        out.setPixels(px, y0 * w + x0, w, 0, 0, ow, oh)
        return out
    }

    fun trim(b: Bitmap): Bitmap? {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        return trim(px, w, h)
    }
}
