package jp.asystem.taxitranslator.model

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/**
 * 動作モード。SOLOは1台運用(従来どおり方向を手動切替)。
 * DRIVER/GUESTは2台運用: 各タブレットが自分の話者専用マイクになり、
 * 認識・翻訳した結果をNearby Connectionsで相手タブレットと共有する。
 */
enum class AppMode { SOLO, DRIVER, GUEST }

/** 会話の方向。上ペイン=話した内容 / 下ペイン=翻訳 の役割は固定で、方向だけが切り替わる。 */
enum class Direction {
    /** 運転手(日本語)→ お客様(選択言語) */
    DRIVER_TO_GUEST,

    /** お客様(選択言語)→ 運転手(日本語)。翻訳結果をTTSで読み上げる。 */
    GUEST_TO_DRIVER,
}

/** 対象言語。全言語が有効(enabled)。 */
enum class TargetLanguage(
    val nativeName: String,
    val japaneseName: String,
    val locale: Locale,
    val mlKitCode: String,
    val promptName: String,
    val enabled: Boolean,
) {
    ENGLISH("English", "英語", Locale.ENGLISH, TranslateLanguage.ENGLISH, "English", true),
    CHINESE("中文", "中国語", Locale.SIMPLIFIED_CHINESE, TranslateLanguage.CHINESE, "Simplified Chinese", true),
    KOREAN("한국어", "韓国語", Locale.KOREAN, TranslateLanguage.KOREAN, "Korean", true),
    FRENCH("Français", "フランス語", Locale.FRENCH, TranslateLanguage.FRENCH, "French", true),
    VIETNAMESE("Tiếng Việt", "ベトナム語", Locale.forLanguageTag("vi-VN"), TranslateLanguage.VIETNAMESE, "Vietnamese", true),
    THAI("ไทย", "タイ語", Locale.forLanguageTag("th-TH"), TranslateLanguage.THAI, "Thai", true),
}

data class TranslatorUiState(
    /** 未選択(null)の間はモード選択画面を表示する */
    val mode: AppMode? = null,
    /** 2台モードで相手タブレットと接続済みか */
    val peerConnected: Boolean = false,
    val direction: Direction = Direction.DRIVER_TO_GUEST,
    val target: TargetLanguage = TargetLanguage.ENGLISH,
    /** マイクが聞き取り中か */
    val listening: Boolean = false,
    /** 聞き取り中の暫定テキスト(グレー表示) */
    val interimText: String = "",
    /** 確定した発話テキスト(校正後は置き換わる) */
    val sourceText: String = "",
    /** sourceText がAI校正済みか */
    val isCorrected: Boolean = false,
    /** 翻訳結果(ストリーミングで追記される) */
    val translationText: String = "",
    /** 通信可能か(オンライン=高精度モード) */
    val online: Boolean = false,
    /** 手動でオフライン(簡易モード)に固定しているか(検証用) */
    val forcedOffline: Boolean = false,
    /** APIキーが設定されているか(未設定なら常にオフライン経路) */
    val claudeConfigured: Boolean = true,
    val ttsEnabled: Boolean = true,
    val ttsSpeaking: Boolean = false,
    /** 校正+翻訳の処理中か */
    val processing: Boolean = false,
    /** 画面下部に出す補足メッセージ(エラー等) */
    val statusMessage: String? = null,
) {
    /** 高精度モード(Claude)で動作するか。手動オフライン固定中は常にfalse。 */
    val highPrecision: Boolean
        get() = online && claudeConfigured && !forcedOffline
}
