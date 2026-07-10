# taxi-translator (PoC)

タクシー車内向け 会話自動多言語翻訳アプリのPoC実装。
計画・背景は親フォルダの `PoC実装計画.md` を参照。

## セットアップ

1. **Android Studio** (Ladybug以降) でこのフォルダを開く
2. ルートに `local.properties` を作成(Android Studioが `sdk.dir` を自動追記)し、APIキーを追記:

   ```properties
   anthropicApiKey=sk-ant-xxxxxxxx
   ```

   ※ PoC限定の埋め込み方式。**本番では中継サーバー経由に変更すること**。
   ※ 未設定でも起動するが、常にオフライン(簡易)経路で動作する。

3. タブレット側の事前準備:
   - **オフライン音声認識**: 設定 → Google音声認識 → オフライン音声認識 で「日本語」「英語」をダウンロード
   - **TTS日本語音声**: 設定 → テキスト読み上げ で日本語音声データを確認
   - ML Kitの翻訳モデル(日⇔英)は初回起動時にオンラインで自動ダウンロードされる

4. 実行: 横画面のAndroid 10+ 端末(タブレット推奨)にインストール。初回にマイク権限を許可

## 動作概要

- **常時待受**: 発話を検知すると暫定テキストをグレー表示 → 発話の区切りで確定
- **オンライン時**: Claude API(`claude-haiku-4-5`)が校正+翻訳を1コールで実施(ストリーミング表示、「✓ AI校正済み」バッジ)
- **オフライン時**: 端末内認識 + ML Kit翻訳(校正なし、「オフライン・簡易」表示)。切替は自動
- **方向切替**: 上部の「運転手が話す / お客様が話す」チップで手動切替(PoC仕様)
- **読み上げ**: お客様→運転手方向のみ日本語訳をTTS再生。再生中はマイクを止めてエコーを防止
- **プライバシー**: 会話は保存しない。「会話をリセット」でAPIに渡す直近履歴(最大3往復)も破棄

## 構成

```
app/src/main/java/jp/asystem/taxitranslator/
├── MainActivity.kt              権限取得・Compose起動
├── TranslatorViewModel.kt       待受ループ・経路振り分け・TTS制御
├── model/Models.kt              Direction / TargetLanguage / UiState
├── speech/SpeechRecognizerManager.kt  Android標準音声認識(ストリーミング)
├── speech/TtsManager.kt         日本語読み上げ
├── translate/ClaudeTranslator.kt  オンライン校正+翻訳(anthropic-java SDK, SSE)
├── translate/MlKitTranslator.kt   オフライン翻訳(ML Kit)
├── net/ConnectivityObserver.kt  オンライン/オフライン自動判定
└── ui/TranslatorScreen.kt       案2改UI(2ペイン+言語レール)
```

## PoC評価時のメモ

- モデルは `app/build.gradle.kts` の `CLAUDE_MODEL` で切替可能(品質比較時は `claude-sonnet-5` に変更)
- 対象言語の追加は `model/Models.kt` の `TargetLanguage.enabled` を有効化(+ML Kitモデル追加)
- 実車テストの計測項目は `PoC実装計画.md` 6章を参照
