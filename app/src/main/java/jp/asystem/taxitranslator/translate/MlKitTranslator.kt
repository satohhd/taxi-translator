package jp.asystem.taxitranslator.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.tasks.await

/**
 * オフライン経路: ML Kit オンデバイス翻訳。
 * 言語モデル(約30MB/言語)は preload() で事前ダウンロードしておく。
 * 圏外でも動作するが、校正なしの直訳になるため画面上は「簡易モード」と表示する。
 */
class MlKitTranslator {

    private val clients = ConcurrentHashMap<String, Translator>()

    private fun client(from: String, to: String): Translator =
        clients.getOrPut("$from>$to") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(from)
                    .setTargetLanguage(to)
                    .build()
            )
        }

    /** アプリ起動時にオンライン状態で呼び、必要な言語モデルを落としておく。 */
    suspend fun preload(pairs: List<Pair<String, String>>) {
        val conditions = DownloadConditions.Builder().build()
        pairs.forEach { (from, to) ->
            runCatching { client(from, to).downloadModelIfNeeded(conditions).await() }
        }
    }

    suspend fun translate(text: String, from: String, to: String): String {
        val translator = client(from, to)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return translator.translate(text).await()
    }

    fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
    }
}
