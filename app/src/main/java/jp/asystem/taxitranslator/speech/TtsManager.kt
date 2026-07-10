package jp.asystem.taxitranslator.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * 翻訳結果の読み上げ。1台モードは日本語のみ、2台モードのお客様用は選択言語も読み上げる。
 * AndroidのTTSはオフラインでも動作する(対象言語の音声データを端末に事前インストールしておくこと)。
 */
class TtsManager(
    context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit,
) {
    private var ready = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts.setSpeechRate(1.0f)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = onSpeakingChanged(true)
                    override fun onDone(utteranceId: String?) = onSpeakingChanged(false)
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = onSpeakingChanged(false)
                    override fun onError(utteranceId: String?, errorCode: Int) =
                        onSpeakingChanged(false)
                })
            }
        }
    }

    /** @return 読み上げを開始できたか(TTS未初期化・対象言語の音声なしの場合false) */
    fun speak(text: String, locale: java.util.Locale): Boolean {
        if (!ready || text.isBlank()) return false
        val lang = tts.setLanguage(locale)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            return false
        }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt-${System.nanoTime()}")
        return result == TextToSpeech.SUCCESS
    }

    fun speakJapanese(text: String): Boolean = speak(text, java.util.Locale.JAPANESE)

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
