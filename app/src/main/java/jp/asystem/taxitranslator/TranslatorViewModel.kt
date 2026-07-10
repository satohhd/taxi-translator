package jp.asystem.taxitranslator

import android.app.Application
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale
import jp.asystem.taxitranslator.model.AppMode
import jp.asystem.taxitranslator.model.Direction
import jp.asystem.taxitranslator.model.TargetLanguage
import jp.asystem.taxitranslator.model.TranslatorUiState
import jp.asystem.taxitranslator.net.ConnectivityObserver
import jp.asystem.taxitranslator.net.NearbyLink
import jp.asystem.taxitranslator.speech.SpeechRecognizerManager
import jp.asystem.taxitranslator.speech.TtsManager
import jp.asystem.taxitranslator.translate.ClaudeTranslator
import jp.asystem.taxitranslator.translate.MlKitTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class TranslatorViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(
        TranslatorUiState(claudeConfigured = BuildConfig.ANTHROPIC_API_KEY.isNotBlank())
    )
    val ui = _ui.asStateFlow()

    private val speech = SpeechRecognizerManager(app)
    private val claude: ClaudeTranslator? =
        BuildConfig.ANTHROPIC_API_KEY.takeIf { it.isNotBlank() }
            ?.let { ClaudeTranslator(it, BuildConfig.CLAUDE_MODEL) }
    private val mlKit = MlKitTranslator()
    private val tts = TtsManager(app) { speaking ->
        _ui.update { it.copy(ttsSpeaking = speaking) }
    }
    private val connectivity = ConnectivityObserver(app)

    /** 直近の会話(訳語の一貫性のためAPIに渡す)。乗車リセットで破棄。 */
    private val history = ArrayDeque<ClaudeTranslator.Exchange>()

    /** 2台モードの相手タブレットとの通信。SOLOではnull。 */
    private var nearby: NearbyLink? = null

    private var listenJob: Job? = null
    private var micGranted = false
    private var started = false

    /** RECORD_AUDIO権限が取れた後に一度だけ呼ぶ。モード選択が済むまで待受は始めない。 */
    fun start() {
        micGranted = true
        maybeBegin()
    }

    /** モード選択画面で選ばれたときに呼ぶ(2台モードはNearby権限の取得後)。 */
    fun setMode(mode: AppMode) {
        nearby?.stop()
        nearby = null
        tts.stop()
        history.clear()
        _ui.update {
            it.copy(
                mode = mode,
                peerConnected = false,
                direction =
                    if (mode == AppMode.GUEST) Direction.GUEST_TO_DRIVER
                    else Direction.DRIVER_TO_GUEST,
                interimText = "",
                sourceText = "",
                isCorrected = false,
                translationText = "",
                statusMessage = null,
            )
        }
        if (mode != AppMode.SOLO) {
            nearby = NearbyLink(
                getApplication(),
                onPeerChanged = { connected ->
                    _ui.update { it.copy(peerConnected = connected) }
                    // 接続確立時、お客様用の言語選択を運転手用にも合わせる
                    if (connected && mode == AppMode.GUEST) {
                        sendMessage("target") { put("x", _ui.value.target.name) }
                    }
                },
                onMessage = ::onRemoteMessage,
            ).also {
                it.start(
                    if (mode == AppMode.DRIVER) NearbyLink.Role.DRIVER
                    else NearbyLink.Role.GUEST
                )
            }
        }
        maybeBegin()
        if (started) restartListening()
    }

    /** モード選択画面に戻る。 */
    fun changeMode() {
        listenJob?.cancel()
        nearby?.stop()
        nearby = null
        tts.stop()
        _ui.update { it.copy(mode = null, peerConnected = false, listening = false) }
    }

    fun onNearbyPermissionDenied() {
        _ui.update { it.copy(statusMessage = "2台連携には「付近のデバイス」権限が必要です") }
    }

    private fun maybeBegin() {
        if (started || !micGranted || _ui.value.mode == null) return
        started = true

        viewModelScope.launch {
            connectivity.observe().collect { online ->
                _ui.update { it.copy(online = online) }
            }
        }
        // オフライン翻訳モデルの事前ダウンロード(日⇔有効言語すべて)
        viewModelScope.launch(Dispatchers.IO) {
            mlKit.preload(
                TargetLanguage.entries.filter { it.enabled }.flatMap { lang ->
                    listOf(
                        TranslateLanguage.JAPANESE to lang.mlKitCode,
                        lang.mlKitCode to TranslateLanguage.JAPANESE,
                    )
                }
            )
        }

        if (!speech.isAvailable()) {
            _ui.update { it.copy(statusMessage = "この端末では音声認識を利用できません") }
            return
        }
        // 圏外時の端末内認識に備えてオフライン認識モデルのDLを要求(日本語+有効言語)
        speech.requestOfflineModels(
            listOf(Locale.JAPANESE) + TargetLanguage.entries.filter { it.enabled }.map { it.locale }
        )
        if (claude == null) {
            _ui.update { it.copy(statusMessage = "APIキー未設定のため常に簡易モードで動作します") }
        }
        restartListening()
    }

    fun onPermissionDenied() {
        _ui.update { it.copy(statusMessage = "マイク権限が必要です。設定から許可してください") }
    }

    /** 1台モード専用: 話す側の手動切替。 */
    fun setDirection(direction: Direction) {
        if (_ui.value.direction == direction) return
        tts.stop()
        _ui.update {
            it.copy(
                direction = direction,
                interimText = "",
                sourceText = "",
                isCorrected = false,
                translationText = "",
                statusMessage = null,
            )
        }
        restartListening()
    }

    fun setTarget(target: TargetLanguage) = applyTarget(target, fromRemote = false)

    /** 対象言語の切替。訳語の一貫性履歴は言語が混ざるためリセットする。 */
    private fun applyTarget(target: TargetLanguage, fromRemote: Boolean) {
        if (!target.enabled || _ui.value.target == target) return
        tts.stop()
        history.clear()
        _ui.update {
            it.copy(
                target = target,
                interimText = "",
                sourceText = "",
                isCorrected = false,
                translationText = "",
                statusMessage = null,
            )
        }
        if (!fromRemote) sendMessage("target") { put("x", target.name) }
        // お客様側の認識ロケールが変わるため待受をやり直す
        restartListening()
    }

    /** 高精度(オンライン)⇔簡易(オフライン)の手動切替。実際に圏外の場合はオンラインにできない。 */
    fun toggleForcedOffline() {
        val state = _ui.value
        val forcing = !state.forcedOffline
        if (!forcing && !state.online) {
            _ui.update { it.copy(statusMessage = "圏外のためオンラインに切替できません") }
            return
        }
        if (!forcing && !state.claudeConfigured) {
            _ui.update { it.copy(statusMessage = "APIキー未設定のためオンラインに切替できません") }
            return
        }
        _ui.update {
            it.copy(
                forcedOffline = forcing,
                statusMessage = if (forcing) "簡易モードに固定しました(タップで解除)" else null,
            )
        }
        restartListening()
    }

    fun toggleTts() {
        val enabled = !_ui.value.ttsEnabled
        if (!enabled) tts.stop()
        _ui.update { it.copy(ttsEnabled = enabled) }
    }

    /** 乗車終了などで会話をリセットする(履歴も破棄=保存しない方針)。 */
    fun reset() = resetInternal(fromRemote = false)

    private fun resetInternal(fromRemote: Boolean) {
        tts.stop()
        history.clear()
        _ui.update {
            it.copy(
                interimText = "",
                sourceText = "",
                isCorrected = false,
                translationText = "",
                statusMessage = null,
            )
        }
        if (!fromRemote) sendMessage("reset")
        restartListening()
    }

    private fun restartListening() {
        if (!started) return
        listenJob?.cancel()
        listenJob = viewModelScope.launch { listenLoop() }
    }

    /** このタブレットのマイクが聞き取る言語。2台モードでは役割で固定。 */
    private fun listenLocale(state: TranslatorUiState): Locale = when (state.mode) {
        AppMode.DRIVER -> Locale.JAPANESE
        AppMode.GUEST -> state.target.locale
        else ->
            if (state.direction == Direction.DRIVER_TO_GUEST) Locale.JAPANESE
            else state.target.locale
    }

    /** 連続待受: 1発話聞き取り → 処理 → 再待受。SpeechRecognizerの都合でメインスレッドで回す。 */
    private suspend fun listenLoop() {
        try {
            listenLoopInner()
        } finally {
            speech.setBeepMuted(false)
        }
    }

    private suspend fun listenLoopInner() {
        // 認識ビープ音の抑止。合間に解除すると終了音が漏れるためループ全体で維持する
        speech.setBeepMuted(true)
        while (currentCoroutineContext().isActive) {
            val state = _ui.value
            val locale = listenLocale(state)

            var finalText: String? = null
            var errorCode: Int? = null

            _ui.update { it.copy(listening = true) }
            // 手動の簡易モードは翻訳経路のみ切替え、認識は使えるほう(圏内ならクラウド)を使う。
            // 端末内認識の強制は本当に圏外のときだけ(オフライン言語パックが無い端末があるため)。
            speech.listenOnce(locale, preferOffline = !state.online).collect { event ->
                when (event) {
                    is SpeechRecognizerManager.Event.Partial -> {
                        _ui.update { it.copy(interimText = event.text) }
                        sendMessage("interim") { put("x", event.text) }
                    }
                    is SpeechRecognizerManager.Event.Final -> finalText = event.text
                    is SpeechRecognizerManager.Event.Error -> errorCode = event.code
                }
            }
            _ui.update { it.copy(listening = false) }

            val text = finalText
            when {
                text != null -> processUtterance(text)
                // 無音・不一致・認識サービスの瞬断は正常系として静かに待受を続ける
                errorCode == null ||
                    errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
                    errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> delay(200)
                errorCode == SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> delay(500)
                errorCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    onPermissionDenied()
                    return
                }
                errorCode == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    errorCode == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    _ui.update {
                        it.copy(
                            statusMessage =
                                "この言語のオフライン音声認識パックが未ダウンロードです" +
                                    "(設定 → Google → 音声認識のオフライン言語)",
                        )
                    }
                    delay(1000)
                }
                else -> {
                    _ui.update { it.copy(statusMessage = "音声認識エラー(code=$errorCode)") }
                    delay(1000)
                }
            }
        }
    }

    private suspend fun processUtterance(raw: String) {
        val state = _ui.value
        val jaToForeign = when (state.mode) {
            AppMode.DRIVER -> true
            AppMode.GUEST -> false
            else -> state.direction == Direction.DRIVER_TO_GUEST
        }
        val localDirection =
            if (jaToForeign) Direction.DRIVER_TO_GUEST else Direction.GUEST_TO_DRIVER

        _ui.update {
            it.copy(
                processing = true,
                direction = localDirection,
                interimText = "",
                sourceText = raw,
                isCorrected = false,
                translationText = "",
                statusMessage = null,
            )
        }
        sendMessage("source") {
            put("x", raw)
            put("corrected", false)
            put("dir", localDirection.name)
        }

        var translation: String? = null
        val useOnline = state.online && claude != null && !state.forcedOffline

        if (useOnline) {
            try {
                val exchange = claude!!.correctAndTranslate(
                    utterance = raw,
                    sourceName = if (jaToForeign) "Japanese" else state.target.promptName,
                    targetName = if (jaToForeign) state.target.promptName else "Japanese",
                    history = history.toList(),
                    onCorrected = { corrected ->
                        _ui.update { it.copy(sourceText = corrected, isCorrected = true) }
                        sendMessage("source") {
                            put("x", corrected)
                            put("corrected", true)
                            put("dir", localDirection.name)
                        }
                    },
                    onTranslationDelta = { delta ->
                        _ui.update { it.copy(translationText = it.translationText + delta) }
                    },
                )
                _ui.update {
                    it.copy(
                        sourceText = exchange.corrected,
                        isCorrected = true,
                        translationText = exchange.translation,
                    )
                }
                translation = exchange.translation
                history.addLast(exchange)
                while (history.size > MAX_HISTORY) history.removeFirst()
            } catch (e: CancellationException) {
                // 言語切替・リセット等による中断はエラーではないのでそのまま伝播させる
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(statusMessage = "オンライン翻訳に失敗したため簡易翻訳に切替えました") }
                translation = translateOffline(raw, jaToForeign, state.target)
            }
        } else {
            translation = translateOffline(raw, jaToForeign, state.target)
        }

        _ui.update { it.copy(processing = false) }

        if (!translation.isNullOrBlank()) {
            sendMessage("trans") {
                put("x", translation)
                put("dir", localDirection.name)
            }
        }

        // 1台モード: お客様→運転手 方向は日本語訳を読み上げる。
        // 2台モードでは受信した側が読み上げるため、話した側では読み上げない。
        // 読み上げ中にマイクを開くとTTS音声を拾ってしまうため、終了まで待受を止める。
        if (state.mode == AppMode.SOLO && !jaToForeign &&
            !translation.isNullOrBlank() && _ui.value.ttsEnabled
        ) {
            // TTSはミュート中のストリームで再生されるため、読み上げの間だけ解除する
            speech.setBeepMuted(false)
            if (tts.speakJapanese(translation)) {
                withTimeoutOrNull(20_000) {
                    delay(300)
                    _ui.first { !it.ttsSpeaking }
                }
            }
            speech.setBeepMuted(true)
        }
    }

    private suspend fun translateOffline(
        text: String,
        jaToForeign: Boolean,
        target: TargetLanguage,
    ): String? = try {
        val result =
            if (jaToForeign) mlKit.translate(text, TranslateLanguage.JAPANESE, target.mlKitCode)
            else mlKit.translate(text, target.mlKitCode, TranslateLanguage.JAPANESE)
        _ui.update { it.copy(translationText = result) }
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _ui.update {
            it.copy(statusMessage = "簡易翻訳に失敗しました(翻訳モデルが未ダウンロードの可能性)")
        }
        null
    }

    /** 相手タブレットからのメッセージ(Nearbyのコールバックはメインスレッドで呼ばれる)。 */
    private fun onRemoteMessage(msg: JSONObject) {
        val dir = runCatching { Direction.valueOf(msg.optString("dir")) }.getOrNull()
        when (msg.optString("t")) {
            "interim" -> _ui.update { it.copy(interimText = msg.optString("x")) }
            "source" -> {
                val corrected = msg.optBoolean("corrected")
                _ui.update {
                    it.copy(
                        interimText = "",
                        sourceText = msg.optString("x"),
                        isCorrected = corrected,
                        // 新しい発話(校正前)なら前の翻訳を消す
                        translationText = if (corrected) it.translationText else "",
                        direction = dir ?: it.direction,
                        statusMessage = null,
                    )
                }
            }
            "trans" -> {
                val text = msg.optString("x")
                _ui.update {
                    it.copy(translationText = text, direction = dir ?: it.direction)
                }
                speakIncoming(text)
            }
            "target" -> runCatching { TargetLanguage.valueOf(msg.optString("x")) }
                .getOrNull()
                ?.let { applyTarget(it, fromRemote = true) }
            "reset" -> resetInternal(fromRemote = true)
        }
    }

    /** 受信した翻訳の読み上げ: 運転手用=日本語訳 / お客様用=選択言語の訳。 */
    private fun speakIncoming(text: String) {
        val state = _ui.value
        if (!state.ttsEnabled || text.isBlank()) return
        val locale = if (state.mode == AppMode.GUEST) state.target.locale else Locale.JAPANESE
        // 読み上げ中に自分のマイクがTTS音声を拾わないよう待受を止める
        // (待受停止でビープ音ミュートも解除されるためTTSはそのまま聞こえる)
        listenJob?.cancel()
        _ui.update { it.copy(listening = false) }
        viewModelScope.launch {
            if (tts.speak(text, locale)) {
                withTimeoutOrNull(20_000) {
                    delay(300)
                    _ui.first { !it.ttsSpeaking }
                }
            }
            restartListening()
        }
    }

    private inline fun sendMessage(type: String, build: JSONObject.() -> Unit = {}) {
        val link = nearby ?: return
        val msg = JSONObject().put("t", type)
        msg.build()
        link.send(msg)
    }

    override fun onCleared() {
        nearby?.stop()
        tts.shutdown()
        mlKit.close()
    }

    private companion object {
        const val MAX_HISTORY = 3
    }
}
