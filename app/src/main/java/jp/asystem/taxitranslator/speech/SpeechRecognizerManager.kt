package jp.asystem.taxitranslator.speech

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Android標準のSpeechRecognizerで1発話ぶんを聞き取る。
 * Flowは部分結果(Partial)を随時流し、確定(Final)またはエラーで完了する。
 * 収集側(ViewModel)がループで再収集することで連続待受を実現する。
 *
 * 注意: SpeechRecognizerはメインスレッドから操作する必要があるため、
 * このFlowはメインディスパッチャで収集すること。
 */
class SpeechRecognizerManager(private val context: Context) {

    private companion object {
        /** AudioManager.STREAM_ASSISTANT(一部端末で認識音の再生先)。定数は非公開のため値で指定 */
        const val STREAM_ASSISTANT = 11
    }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** このアプリ自身がミュートしたストリーム。解除はここにあるものだけに行う。 */
    private val mutedByApp = mutableSetOf<Int>()

    /**
     * 認識開始・終了のビープ音を消す。無効化APIが無いためミュートで抑える。
     * 鳴るストリームは端末により異なるためまとめて対象にする。
     * 聞き取りの合間に解除すると終了ビープが漏れるため、呼び出し側(ViewModel)が
     * 連続待受の間ずっとミュートを維持し、TTS読み上げの直前だけ解除する。
     * ユーザーが元々ミュートしていたストリームには触れない(勝手にONへ戻さない)。
     */
    fun setBeepMuted(muted: Boolean) {
        if (muted) {
            intArrayOf(
                AudioManager.STREAM_MUSIC,
                AudioManager.STREAM_SYSTEM,
                AudioManager.STREAM_NOTIFICATION,
                // OPPO等は通知音量が着信音量に連動し、通知ミュートだけでは認識音が漏れる
                AudioManager.STREAM_RING,
                AudioManager.STREAM_DTMF,
                STREAM_ASSISTANT,
            ).forEach { stream ->
                // 端末によってはDND権限が必要・未対応のストリームがあり得るため失敗は無視する
                runCatching {
                    if (!audioManager.isStreamMute(stream)) {
                        audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                        mutedByApp += stream
                    }
                }
            }
        } else {
            mutedByApp.forEach { stream ->
                runCatching {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                }
            }
            mutedByApp.clear()
        }
    }

    sealed interface Event {
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data class Error(val code: Int) : Event
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * 圏外時の端末内認識に備え、オフライン認識モデルのダウンロードを要求する(Android 13+)。
     * 対応していない端末・言語では黙って何もしない。アプリ起動時にオンライン状態で呼ぶ。
     */
    fun requestOfflineModels(locales: List<Locale>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try {
            locales.forEach { locale ->
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                }
                runCatching { recognizer.triggerModelDownload(intent) }
            }
        } finally {
            recognizer.destroy()
        }
    }

    fun listenOnce(locale: Locale, preferOffline: Boolean): Flow<Event> = callbackFlow {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { trySend(Event.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) trySend(Event.Final(text))
                close()
            }

            override fun onError(error: Int) {
                trySend(Event.Error(error))
                close()
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // 圏外時はオンデバイス認識を強制(事前に端末設定でオフライン言語のDLが必要)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer.startListening(intent)

        awaitClose {
            recognizer.cancel()
            recognizer.destroy()
        }
    }
}
