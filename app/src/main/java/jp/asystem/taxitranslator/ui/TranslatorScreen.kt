package jp.asystem.taxitranslator.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.asystem.taxitranslator.model.AppMode
import jp.asystem.taxitranslator.model.Direction
import jp.asystem.taxitranslator.model.TargetLanguage
import jp.asystem.taxitranslator.model.TranslatorUiState

/** モック「案2改」の配色(車内視認性優先のダーク固定) */
private object Palette {
    val Background = Color(0xFF0D1218)
    val Pane = Color(0xFF141C24)
    val Border = Color(0xFF232E39)
    val Text = Color(0xFFEEF1F4)
    val TextDim = Color(0xFF8B98A5)
    val Amber = Color(0xFFF0B429)
    val AmberDark = Color(0xFF1A1200)
    val Blue = Color(0xFF5FC3E8)
    val BlueBg = Color(0xFF12303F)
    val Green = Color(0xFF3ECF7A)
    val GreenBg = Color(0xFF12301F)
    val AmberBg = Color(0xFF3A2F10)
    val Red = Color(0xFFE5484D)
}

@Composable
fun TranslatorScreen(
    ui: TranslatorUiState,
    onModeSelect: (AppMode) -> Unit,
    onChangeMode: () -> Unit,
    onDirectionChange: (Direction) -> Unit,
    onTargetChange: (TargetLanguage) -> Unit,
    onToggleTts: () -> Unit,
    onSetOffline: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    val isPortrait =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    if (ui.mode == null) {
        ModeChooser(isPortrait, onModeSelect)
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TopBar(ui, isPortrait, onDirectionChange, onToggleTts, onSetOffline)

        val compactHeight = LocalConfiguration.current.screenHeightDp < 600
        if (isPortrait) {
            // 縦向き: ペインを縦積みし、言語選択は下部の横並びに
            SourcePane(ui, modifier = Modifier.weight(1f))
            TranslationPane(ui, modifier = Modifier.weight(1.15f))
            LanguageRow(ui, onTargetChange)
        } else if (compactHeight) {
            // スマホ横向き: 上下積みでは各ペインが低すぎて読めないため左右に分割する
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SourcePane(ui, modifier = Modifier.weight(1f).fillMaxHeight())
                TranslationPane(ui, modifier = Modifier.weight(1f).fillMaxHeight())
                LanguageRail(ui, onTargetChange)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SourcePane(ui, modifier = Modifier.weight(1f))
                    TranslationPane(ui, modifier = Modifier.weight(1.15f))
                }
                LanguageRail(ui, onTargetChange)
            }
        }

        Footer(ui, isPortrait, onChangeMode, onReset)
    }
}

/** 起動時のモード選択。1台運用か、2台連携(運転手用/お客様用)かを選ぶ。 */
@Composable
private fun ModeChooser(isPortrait: Boolean, onSelect: (AppMode) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "使い方を選択してください",
            color = Palette.Text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        val cards: @Composable (Modifier) -> Unit = { cardModifier ->
            ModeCard(
                title = "1台で使う",
                description = "このタブレット1台で運転手・お客様の会話を翻訳します(話す側は手動切替)",
                modifier = cardModifier,
            ) { onSelect(AppMode.SOLO) }
            ModeCard(
                title = "運転手用(2台連携)",
                description = "2台連携の運転手側。日本語で聞き取り、会話をもう1台と共有します",
                modifier = cardModifier,
            ) { onSelect(AppMode.DRIVER) }
            ModeCard(
                title = "お客様用(2台連携)",
                description = "2台連携のお客様側。選択した言語で聞き取り、会話をもう1台と共有します",
                modifier = cardModifier,
            ) { onSelect(AppMode.GUEST) }
        }
        if (isPortrait) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                cards(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                cards(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .background(Palette.Pane, RoundedCornerShape(14.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = Palette.Amber, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(description, color = Palette.TextDim, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopBar(
    ui: TranslatorUiState,
    isPortrait: Boolean,
    onDirectionChange: (Direction) -> Unit,
    onToggleTts: () -> Unit,
    onSetOffline: (Boolean) -> Unit,
) {
    if (isPortrait) {
        // 縦向き: ブランド+マイク状態の行と、チップ類の折返し行の2段構成
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Brand()
                Spacer(Modifier.weight(1f))
                MicIndicator(listening = ui.listening, processing = ui.processing)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SpeakerChips(ui, onDirectionChange)
                Pill(
                    text = if (ui.ttsEnabled) "読み上げ ON" else "読み上げ OFF",
                    active = ui.ttsEnabled,
                    onClick = onToggleTts,
                )
                StatusPill(
                    highPrecision = ui.highPrecision,
                    onSetOffline = onSetOffline,
                )
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Brand()

            Spacer(Modifier.width(8.dp))

            SpeakerChips(ui, onDirectionChange)

            Spacer(Modifier.weight(1f))

            MicIndicator(listening = ui.listening, processing = ui.processing)

            Pill(
                text = if (ui.ttsEnabled) "読み上げ ON" else "読み上げ OFF",
                active = ui.ttsEnabled,
                onClick = onToggleTts,
            )
            StatusPill(
                highPrecision = ui.highPrecision,
                onSetOffline = onSetOffline,
            )
        }
    }
}

@Composable
private fun Brand() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(Palette.Amber, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("中", color = Palette.AmberDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Text("会話翻訳", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** 話す側の表示: 1台モードは手動切替チップ、2台モードは役割+接続状態。 */
@Composable
private fun SpeakerChips(ui: TranslatorUiState, onDirectionChange: (Direction) -> Unit) {
    if (ui.mode == AppMode.SOLO) {
        DirectionChip(
            label = "運転手が話す",
            selected = ui.direction == Direction.DRIVER_TO_GUEST,
        ) { onDirectionChange(Direction.DRIVER_TO_GUEST) }
        DirectionChip(
            label = "お客様が話す / Guest",
            selected = ui.direction == Direction.GUEST_TO_DRIVER,
        ) { onDirectionChange(Direction.GUEST_TO_DRIVER) }
    } else {
        Pill(
            text = if (ui.mode == AppMode.DRIVER) "運転手用" else "お客様用 / Guest",
            active = true,
        )
        PeerPill(connected = ui.peerConnected)
    }
}

@Composable
private fun DirectionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Palette.Amber else Palette.Pane
    val fg = if (selected) Palette.AmberDark else Palette.TextDim
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(50))
            .border(1.dp, if (selected) Palette.Amber else Palette.Border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Pill(text: String, active: Boolean, onClick: (() -> Unit)? = null) {
    val bg = if (active) Palette.Amber else Palette.Pane
    val fg = if (active) Palette.AmberDark else Palette.TextDim
    var modifier = Modifier
        .background(bg, RoundedCornerShape(50))
        .border(1.dp, if (active) Palette.Amber else Palette.Border, RoundedCornerShape(50))
    if (onClick != null) modifier = modifier.clickable(onClick = onClick)
    Box(modifier = modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PeerPill(connected: Boolean) {
    Row(
        modifier = Modifier
            .background(Palette.Pane, RoundedCornerShape(50))
            .border(1.dp, Palette.Border, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (connected) Palette.Green else Palette.Amber, CircleShape)
        )
        Text(
            if (connected) "相手と接続済み" else "相手を検索中…",
            color = Palette.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatusPill(highPrecision: Boolean, onSetOffline: (Boolean) -> Unit) {
    val compact = LocalConfiguration.current.smallestScreenWidthDp < 600
    if (compact) {
        // スマホ: 標準⇔高精度のセグメント切替ボタン
        Row(
            modifier = Modifier
                .background(Palette.Pane, RoundedCornerShape(50))
                .border(1.dp, Palette.Border, RoundedCornerShape(50))
                .padding(3.dp),
        ) {
            SegmentChip(
                text = "標準",
                selected = !highPrecision,
                selectedBg = Palette.Amber,
            ) { onSetOffline(true) }
            SegmentChip(
                text = "高精度",
                selected = highPrecision,
                selectedBg = Palette.Green,
            ) { onSetOffline(false) }
        }
    } else {
        // タブレット: 状態表示ピル(タップで切替)
        Row(
            modifier = Modifier
                .background(Palette.Pane, RoundedCornerShape(50))
                .border(1.dp, Palette.Border, RoundedCornerShape(50))
                .clickable { onSetOffline(highPrecision) }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (highPrecision) Palette.Green else Palette.Amber, CircleShape)
            )
            Text(
                if (highPrecision) "オンライン・高精度" else "オフライン・標準",
                color = Palette.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SegmentChip(
    text: String,
    selected: Boolean,
    selectedBg: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(if (selected) selectedBg else Color.Transparent, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text,
            color = if (selected) Palette.AmberDark else Palette.TextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MicIndicator(listening: Boolean, processing: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    when {
                        listening -> Palette.Red
                        processing -> Palette.Blue
                        else -> Palette.Border
                    },
                    CircleShape,
                )
        )
        Text(
            when {
                listening -> "聞き取り中"
                processing -> "翻訳中"
                else -> "待機中"
            },
            color = Palette.TextDim,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun SourcePane(ui: TranslatorUiState, modifier: Modifier = Modifier) {
    val speakerLabel: String
    val speakerBg: Color
    val speakerFg: Color
    val langLabel: String
    if (ui.direction == Direction.DRIVER_TO_GUEST) {
        speakerLabel = "運転手"
        speakerBg = Palette.AmberBg
        speakerFg = Palette.Amber
        langLabel = "日本語"
    } else {
        speakerLabel = "お客様"
        speakerBg = Palette.BlueBg
        speakerFg = Palette.Blue
        langLabel = ui.target.nativeName
    }

    Pane(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge(speakerLabel, speakerBg, speakerFg)
            Badge(langLabel, Palette.Border, Palette.TextDim)
            if (ui.isCorrected) Badge("✓ AI校正済み", Palette.GreenBg, Palette.Green)
            Spacer(Modifier.weight(1f))
            Text("認識テキスト", color = Palette.TextDim, fontSize = 11.sp)
        }
        val showInterim = ui.interimText.isNotBlank()
        Text(
            text = if (showInterim) ui.interimText else ui.sourceText,
            color = if (showInterim) Palette.TextDim else Palette.Text,
            fontSize = 26.sp,
            fontWeight = if (showInterim) FontWeight.Normal else FontWeight.SemiBold,
            lineHeight = 38.sp,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp),
        )
    }
}

@Composable
private fun TranslationPane(ui: TranslatorUiState, modifier: Modifier = Modifier) {
    val targetLabel =
        if (ui.direction == Direction.DRIVER_TO_GUEST) ui.target.nativeName else "日本語"

    Pane(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge("翻訳 → $targetLabel", Palette.Border, Palette.TextDim)
            if (ui.ttsSpeaking) Badge("♪ 読み上げ中", Palette.BlueBg, Palette.Blue)
            Spacer(Modifier.weight(1f))
            Text("Translation", color = Palette.TextDim, fontSize = 11.sp)
        }
        Text(
            text = ui.translationText,
            color = Palette.Text,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 44.sp,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp),
        )
    }
}

@Composable
private fun Pane(modifier: Modifier = Modifier, content: @Composable() (androidx.compose.foundation.layout.ColumnScope.() -> Unit)) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Palette.Pane, RoundedCornerShape(14.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(14.dp))
            .padding(14.dp),
        content = content,
    )
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color) {
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LanguageRail(ui: TranslatorUiState, onTargetChange: (TargetLanguage) -> Unit) {
    // スマホ横向きなど高さの低い画面では全高6等分だとボタンが潰れて押せないため、
    // 固定高+縦スクロールに切り替えてタップできる大きさを確保する
    val compact = LocalConfiguration.current.screenHeightDp < 600
    Column(
        modifier =
            if (compact) {
                Modifier
                    .width(130.dp)
                    .verticalScroll(rememberScrollState())
            } else {
                Modifier.width(130.dp)
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "LANGUAGE",
            color = Palette.TextDim,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        TargetLanguage.entries.forEach { lang ->
            val selected = lang == ui.target
            val alpha = if (lang.enabled) 1f else 0.35f
            val sizeModifier =
                if (compact) Modifier.fillMaxWidth().height(60.dp)
                else Modifier.fillMaxWidth().weight(1f)
            Column(
                modifier = sizeModifier
                    .background(
                        if (selected) Palette.Amber.copy(alpha = alpha) else Palette.Pane.copy(alpha = alpha),
                        RoundedCornerShape(10.dp),
                    )
                    .border(
                        1.dp,
                        (if (selected) Palette.Amber else Palette.Border).copy(alpha = alpha),
                        RoundedCornerShape(10.dp),
                    )
                    .clickable(enabled = lang.enabled) { onTargetChange(lang) },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    lang.nativeName,
                    color = if (selected) Palette.AmberDark else Palette.Text.copy(alpha = alpha),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (lang.enabled) lang.japaneseName else "${lang.japaneseName}(準備中)",
                    color = if (selected) Palette.AmberDark else Palette.TextDim.copy(alpha = alpha),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/** 縦向き用の言語選択: 下部の横スクロール列。 */
@Composable
private fun LanguageRow(ui: TranslatorUiState, onTargetChange: (TargetLanguage) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TargetLanguage.entries.forEach { lang ->
            val selected = lang == ui.target
            val alpha = if (lang.enabled) 1f else 0.35f
            Column(
                modifier = Modifier
                    .width(112.dp)
                    .background(
                        if (selected) Palette.Amber.copy(alpha = alpha) else Palette.Pane.copy(alpha = alpha),
                        RoundedCornerShape(10.dp),
                    )
                    .border(
                        1.dp,
                        (if (selected) Palette.Amber else Palette.Border).copy(alpha = alpha),
                        RoundedCornerShape(10.dp),
                    )
                    .clickable(enabled = lang.enabled) { onTargetChange(lang) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    lang.nativeName,
                    color = if (selected) Palette.AmberDark else Palette.Text.copy(alpha = alpha),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (lang.enabled) lang.japaneseName else "${lang.japaneseName}(準備中)",
                    color = if (selected) Palette.AmberDark else Palette.TextDim.copy(alpha = alpha),
                    fontSize = 9.sp,
                )
            }
        }
    }
}

@Composable
private fun Footer(
    ui: TranslatorUiState,
    isPortrait: Boolean,
    onChangeMode: () -> Unit,
    onReset: () -> Unit,
) {
    val statusText = ui.statusMessage
        ?: "マイクに向かってお話しください / Please speak toward the microphone"
    val modeText = when {
        ui.highPrecision -> "クラウド認識 + AI校正"
        ui.online -> "クラウド認識 + 端末内翻訳(校正なし)"
        else -> "端末内認識 + 端末内翻訳(校正なし)"
    }
    if (isPortrait) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                statusText,
                color = if (ui.statusMessage != null) Palette.Amber else Palette.TextDim,
                fontSize = 12.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(modeText, color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Pill(text = "モード変更", active = false, onClick = onChangeMode)
                Pill(text = "リセット", active = false, onClick = onReset)
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                statusText,
                color = if (ui.statusMessage != null) Palette.Amber else Palette.TextDim,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Text(modeText, color = Palette.TextDim, fontSize = 11.sp)
            Pill(text = "モード変更", active = false, onClick = onChangeMode)
            Pill(text = "会話をリセット", active = false, onClick = onReset)
        }
    }
}
