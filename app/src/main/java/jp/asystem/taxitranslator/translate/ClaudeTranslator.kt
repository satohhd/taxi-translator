package jp.asystem.taxitranslator.translate

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * オンライン経路: Claude APIで「校正+翻訳」を1コールで行う。
 *
 * 出力はタグ形式(<corrected>...</corrected><translation>...</translation>)を
 * ストリーミング受信し、校正文の確定と翻訳文の逐次表示をコールバックで通知する。
 * JSONではなくタグ形式なのは、部分受信中でも安全に増分パースできるため。
 */
class ClaudeTranslator(
    apiKey: String,
    private val model: String,
) {
    /** 1往復ぶんの結果。直近数件を会話コンテキストとして次リクエストに含める。 */
    data class Exchange(val source: String, val corrected: String, val translation: String)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(15))
        .build()

    suspend fun correctAndTranslate(
        utterance: String,
        sourceName: String,
        targetName: String,
        history: List<Exchange>,
        onCorrected: (String) -> Unit,
        onTranslationDelta: (String) -> Unit,
    ): Exchange = withContext(Dispatchers.IO) {
        val system = buildSystemPrompt(sourceName, targetName)

        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(1024L)
            .system(system)
        history.forEach {
            builder.addUserMessage(it.source)
            builder.addAssistantMessage(
                "<corrected>${it.corrected}</corrected>\n<translation>${it.translation}</translation>"
            )
        }
        builder.addUserMessage(utterance)

        val parser = TagStreamParser(onCorrected, onTranslationDelta)
        client.messages().createStreaming(builder.build()).use { streamResponse ->
            streamResponse.stream().forEach { event ->
                event.contentBlockDelta().ifPresent { deltaEvent ->
                    deltaEvent.delta().text().ifPresent { textDelta ->
                        parser.feed(textDelta.text())
                    }
                }
            }
        }
        Exchange(
            source = utterance,
            corrected = parser.corrected().ifBlank { utterance },
            translation = parser.translation(),
        )
    }

    private fun buildSystemPrompt(sourceName: String, targetName: String): String = """
        You are a real-time interpreter on a tablet installed in a Japanese taxi. You translate
        conversation between the Japanese driver and an inbound passenger.

        The user message is a raw automatic-speech-recognition transcript of one utterance
        spoken in $sourceName. Do two things:
        1. Clean up the transcript: remove fillers and hesitations, add natural punctuation,
           and fix obvious mis-recognitions using context. Preserve the meaning exactly —
           never add or omit information.
        2. Translate the cleaned utterance into natural, polite spoken $targetName suitable
           for a driver-passenger conversation.

        Output EXACTLY this format, with no other text before, between, or after the tags:
        <corrected>cleaned utterance in $sourceName</corrected>
        <translation>translation in $targetName</translation>
    """.trimIndent()
}

/**
 * ストリーミングテキストからタグを増分パースする。
 * 翻訳部分は「閉じタグの一部かもしれない末尾」を保留しつつ逐次emitする。
 */
internal class TagStreamParser(
    private val onCorrected: (String) -> Unit,
    private val onTranslationDelta: (String) -> Unit,
) {
    private companion object {
        const val C_OPEN = "<corrected>"
        const val C_CLOSE = "</corrected>"
        const val T_OPEN = "<translation>"
        const val T_CLOSE = "</translation>"
    }

    private val buf = StringBuilder()
    private var correctedText: String? = null
    private var translationStart = -1
    private var translationEnd = -1
    private var emittedLength = 0

    fun feed(chunk: String) {
        buf.append(chunk)

        if (correctedText == null) {
            val open = buf.indexOf(C_OPEN)
            val close = buf.indexOf(C_CLOSE)
            if (open >= 0 && close > open) {
                correctedText = buf.substring(open + C_OPEN.length, close).trim()
                onCorrected(correctedText!!)
            }
        }

        if (correctedText != null && translationStart < 0) {
            val open = buf.indexOf(T_OPEN)
            if (open >= 0) translationStart = open + T_OPEN.length
        }

        if (translationStart in 0 until Int.MAX_VALUE && translationEnd < 0) {
            val close = buf.indexOf(T_CLOSE, translationStart)
            // 閉じタグ未着の間は、末尾が閉じタグの途中である可能性ぶんを保留する
            val safeEnd = if (close >= 0) close else maxOf(translationStart, buf.length - T_CLOSE.length)
            if (safeEnd > translationStart + emittedLength) {
                val delta = buf.substring(translationStart + emittedLength, safeEnd)
                emittedLength += delta.length
                onTranslationDelta(delta)
            }
            if (close >= 0) translationEnd = close
        }
    }

    fun corrected(): String = correctedText ?: ""

    fun translation(): String {
        if (translationStart < 0) return ""
        val end = if (translationEnd >= 0) translationEnd else buf.length
        return buf.substring(translationStart, end).trim()
    }
}
