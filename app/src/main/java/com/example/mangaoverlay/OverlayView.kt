package com.example.mangaoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * OCR 로 찾은 말풍선 위치에 흰 배경을 깔고, 그 위에 한글을 "가로 / 좌→우" 로
 * 박스 크기에 맞춰(원본 글자 크기와 비슷하게) 줄바꿈하며 가운데 정렬해 그립니다.
 * 이 뷰 자체는 터치를 가로채지 않아(서비스에서 NOT_TOUCHABLE 설정) 만화는 평소처럼 조작됩니다.
 */
class OverlayView(ctx: Context) : View(ctx) {

    private var bubbles: List<Bubble> = emptyList()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 20, 20)
        isFakeBoldText = true
    }

    fun update(list: List<Bubble>) {
        bubbles = list
        invalidate()
    }

    fun clear() {
        bubbles = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (b in bubbles) {
            if (b.ko.isBlank()) continue
            val r = RectF(b.box)
            val pad = b.box.height() * 0.06f
            r.inset(-pad, -pad)
            val radius = b.box.height() * 0.35f
            canvas.drawRoundRect(r, radius, radius, bgPaint)   // 원문 가리기
            drawKorean(canvas, b.ko, b.box)
        }
    }

    private fun drawKorean(canvas: Canvas, text: String, box: Rect) {
        val maxW = box.width().toFloat()
        val maxH = box.height().toFloat()
        // 원본과 비슷하게: 박스 높이에서 시작해, 폭/높이에 다 들어갈 때까지 글자 크기를 줄임
        var size = maxH * 0.85f
        var lines: List<String> = listOf(text)
        while (size > 12f) {
            textPaint.textSize = size
            lines = wrap(text, maxW)
            val totalH = lines.size * size * 1.18f
            val widest = lines.maxOf { textPaint.measureText(it) }
            if (totalH <= maxH && widest <= maxW) break
            size -= 2f
        }
        textPaint.textSize = size
        val lh = size * 1.18f
        var y = box.exactCenterY() - lines.size * lh / 2f + size * 0.85f
        for (ln in lines) {
            val x = box.exactCenterX() - textPaint.measureText(ln) / 2f
            canvas.drawText(ln, x, y, textPaint)
            y += lh
        }
    }

    /** 띄어쓰기 우선, 안 되면 글자 단위로 가로 줄바꿈 */
    private fun wrap(text: String, maxW: Float): List<String> {
        val lines = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { lines.add(sb.toString()); sb.clear() } }
        for (word in text.split(" ")) {
            val cand = if (sb.isEmpty()) word else "$sb $word"
            if (textPaint.measureText(cand) <= maxW) {
                sb.clear(); sb.append(cand)
            } else {
                flush()
                if (textPaint.measureText(word) <= maxW) sb.append(word)
                else for (ch in word) {                       // 한 단어가 너무 길면 글자 단위
                    if (textPaint.measureText(sb.toString() + ch) <= maxW) sb.append(ch)
                    else { flush(); sb.append(ch) }
                }
            }
        }
        flush()
        return if (lines.isEmpty()) listOf(text) else lines
    }
}
