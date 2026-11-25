package com.example.googlemapsproject.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class NavigationVoiceCoach(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (_: Exception) {
            tts = null
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val idResult = engine.setLanguage(Locale.forLanguageTag("id-ID"))
            if (idResult == TextToSpeech.LANG_MISSING_DATA || idResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.US)
            }
            engine.setSpeechRate(1.02f)
            isReady = true
        }
    }

    fun speak(text: String) {
        if (!isReady || text.isBlank()) return
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "terrapulse_nav_${System.currentTimeMillis()}")
        } catch (_: Exception) {
            // Non-fatal if TTS service is unavailable
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
            // Ignore
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
            // Ignore
        } finally {
            tts = null
            isReady = false
        }
    }
}
