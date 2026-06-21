package com.example.mangaoverlay

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions

data class Bubble(val box: Rect, val ko: String)

object TranslatePipeline {

    private val ocr = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build()
    )

    @Volatile private var busy = false

    fun warmUp() { translator.downloadModelIfNeeded() }

    private data class LineItem(val text: String, val box: Rect)

    fun process(bmp: Bitmap, onResult: (List<Bubble>) -> Unit) {
        if (busy) return
        busy = true
        ocr.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { result ->
                // 1) 줄 단위로 모으면서 일본어가 없는 줄(워터마크·UI 텍스트)은 버림
                val lines = ArrayList<LineItem>()
                for (block in result.textBlocks) for (line in block.lines) {
                    val r = line.boundingBox ?: continue
                    val t = line.text.trim()
                    if (t.isEmpty() || !hasJapanese(t)) continue
                    lines.add(LineItem(t, r))
                }
                if (lines.isEmpty()) { finish(onResult, emptyList()); return@addOnSuccessListener }

                // 2) 글자 크기 밴드 필터: 너무 작은 후리가나 / 너무 큰 효과음 제거
                val hs = lines.map { it.box.height() }.sorted()
                val med = hs[hs.size / 2].toFloat()
                val kept = lines.filter {
                    it.box.height() >= med * 0.55f && it.box.height() <= med * 2.6f
                }
                val use = if (kept.isEmpty()) lines else kept

                // 3) 같은 말풍선 조각끼리 묶기
                val groups = cluster(use)
                if (groups.isEmpty()) { finish(onResult, emptyList()); return@addOnSuccessListener }

                // 4) 묶음을 통째로 번역 (세로면 오른쪽→왼쪽 순서로 재조립)
                val out = ArrayList<Bubble>()
                var remaining = groups.size
                for (g in groups) {
                    val box = unionBox(g)
                    val vertical = box.height() > box.width() * 1.2 && g.size > 1
                    val ordered =
                        if (vertical) g.sortedWith(compareByDescending<LineItem> { it.box.centerX() }.thenBy { it.box.top })
                        else g.sortedWith(compareBy<LineItem> { it.box.top }.thenBy { it.box.left })
                    val jp = ordered.joinToString("") { it.text }
                    translator.translate(jp)
                        .addOnSuccessListener { ko -> if (ko.isNotBlank()) out.add(Bubble(box, ko)) }
                        .addOnCompleteListener { if (--remaining == 0) finish(onResult, out) }
                }
            }
            .addOnFailureListener { finish(onResult, emptyList()) }
    }

    private fun finish(onResult: (List<Bubble>) -> Unit, list: List<Bubble>) {
        busy = false
        onResult(list)
    }

    private fun hasJapanese(s: String): Boolean =
        s.any { c -> c in '\u3040'..'\u30FF' || c in '\u4E00'..'\u9FFF' }

    /** 가까이 있는 줄들을 하나의 말풍선으로 묶음 (union-find) */
    private fun cluster(items: List<LineItem>): List<List<LineItem>> {
        val n = items.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var a = x; while (parent[a] != a) a = parent[a]; parent[x] = a; return a }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

        val exp = items.map {
            val m = (it.box.height() * 0.8f).toInt().coerceAtLeast(12)
            RectF(
                (it.box.left - m).toFloat(), (it.box.top - m).toFloat(),
                (it.box.right + m).toFloat(), (it.box.bottom + m).toFloat()
            )
        }
        for (i in 0 until n) for (j in i + 1 until n)
            if (RectF.intersects(exp[i], exp[j])) union(i, j)

        val map = HashMap<Int, ArrayList<LineItem>>()
        for (i in 0 until n) map.getOrPut(find(i)) { ArrayList() }.add(items[i])
        return map.values.toList()
    }

    private fun unionBox(g: List<LineItem>): Rect {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
        for (it in g) {
            l = minOf(l, it.box.left); t = minOf(t, it.box.top)
            r = maxOf(r, it.box.right); b = maxOf(b, it.box.bottom)
        }
        return Rect(l, t, r, b)
    }
}
