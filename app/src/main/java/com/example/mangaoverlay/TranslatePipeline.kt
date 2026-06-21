package com.example.mangaoverlay

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions

/** 화면 한 장 → 말풍선별 (위치 + 한글) */
data class Bubble(val box: Rect, val ko: String)

object TranslatePipeline {

    private val ocr = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build()
    )

    @Volatile private var modelReady = false
    @Volatile private var busy = false

    fun warmUp() {
        translator.downloadModelIfNeeded()
            .addOnSuccessListener { modelReady = true }
    }

    /** 비트맵 처리. 이미 처리 중이면 건너뜀(최신 페이지 우선). */
    fun process(bmp: Bitmap, onResult: (List<Bubble>) -> Unit) {
        if (busy) return
        busy = true
        ocr.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { result ->
                val blocks = result.textBlocks.filter { (it.boundingBox != null) && it.text.isNotBlank() }
                if (blocks.isEmpty()) { busy = false; onResult(emptyList()); return@addOnSuccessListener }

                val out = ArrayList<Bubble>(blocks.size)
                var remaining = blocks.size
                for (b in blocks) {
                    val jp = b.text.replace("\n", " ").trim()
                    val rect = b.boundingBox!!
                    translator.translate(jp)
                        .addOnSuccessListener { ko -> out.add(Bubble(rect, ko)) }
                        .addOnCompleteListener {
                            if (--remaining == 0) { busy = false; onResult(out) }
                        }
                }
            }
            .addOnFailureListener { busy = false; onResult(emptyList()) }
    }
}
