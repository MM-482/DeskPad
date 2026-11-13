package com.deskpad.app.ui.components

import android.content.Context
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.deskpad.app.model.ModifierState
import com.deskpad.app.model.SpecialKey
import com.deskpad.app.ui.theme.LocalDeskPadPalette

private enum class DeckTab {
    PC_KEYS,
    FN_NUM,
    SYSTEM_IME
}

/**
 * Streamlined PC keyboard deck offering both a built-in tactical PC QWERTY/Function layout
 * (with sticky Ctrl/Alt/Shift/Win modifiers) and an Android System IME bridge for swipe/voice typing.
 */
@Composable
fun KeyboardDeck(
    hapticEnabled: Boolean,
    onSendText: (String) -> Unit,
    onSendSpecialKey: (SpecialKey, ModifierState) -> Unit,
    onSendCharKey: (Char, ModifierState) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalDeskPadPalette.current
    val view = LocalView.current
    var activeTab by remember { mutableStateOf(DeckTab.PC_KEYS) }
    var modifiers by remember { mutableStateOf(ModifierState()) }

    fun tapHaptic() {
        if (hapticEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun dispatchChar(ch: Char) {
        tapHaptic()
        if (modifiers.hasAny) {
            onSendCharKey(ch.lowercaseChar(), modifiers)
            modifiers = ModifierState()
        } else {
            onSendText(ch.toString())
        }
    }

    fun dispatchSpecial(key: SpecialKey) {
        tapHaptic()
        onSendSpecialKey(key, modifiers)
        if (modifiers.hasAny) {
            modifiers = ModifierState()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = palette.padCornerRadius, topEnd = palette.padCornerRadius))
            .background(palette.surface)
            .border(
                1.dp,
                palette.border,
                RoundedCornerShape(topStart = palette.padCornerRadius, topEnd = palette.padCornerRadius)
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Mode switcher & quick PC shortcuts bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabChip("ABC", activeTab == DeckTab.PC_KEYS) { activeTab = DeckTab.PC_KEYS }
            TabChip("Fn/Nav", activeTab == DeckTab.FN_NUM) { activeTab = DeckTab.FN_NUM }
            TabChip("Phone IME", activeTab == DeckTab.SYSTEM_IME) { activeTab = DeckTab.SYSTEM_IME }
            Spacer(modifier = Modifier.weight(1f))
            KeyCap("Esc", modifier = Modifier.width(42.dp), isSecondary = true) { dispatchSpecial(SpecialKey.ESC) }
            KeyCap("Tab", modifier = Modifier.width(42.dp), isSecondary = true) { dispatchSpecial(SpecialKey.TAB) }
            KeyCap("Del", modifier = Modifier.width(42.dp), isSecondary = true) { dispatchSpecial(SpecialKey.DELETE) }
        }

        // Sticky PC modifier row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            ModifierCap("Ctrl", modifiers.ctrl, Modifier.weight(1f)) {
                tapHaptic()
                modifiers = modifiers.copy(ctrl = !modifiers.ctrl)
            }
            ModifierCap("Alt", modifiers.alt, Modifier.weight(1f)) {
                tapHaptic()
                modifiers = modifiers.copy(alt = !modifiers.alt)
            }
            ModifierCap("Win", modifiers.win, Modifier.weight(1f)) {
                tapHaptic()
                modifiers = modifiers.copy(win = !modifiers.win)
            }
            ModifierCap("Shift", modifiers.shift, Modifier.weight(1f)) {
                tapHaptic()
                modifiers = modifiers.copy(shift = !modifiers.shift)
            }
            KeyCap("←", Modifier.weight(0.75f), isSecondary = true) { dispatchSpecial(SpecialKey.LEFT) }
            KeyCap("↑", Modifier.weight(0.75f), isSecondary = true) { dispatchSpecial(SpecialKey.UP) }
            KeyCap("↓", Modifier.weight(0.75f), isSecondary = true) { dispatchSpecial(SpecialKey.DOWN) }
            KeyCap("→", Modifier.weight(0.75f), isSecondary = true) { dispatchSpecial(SpecialKey.RIGHT) }
        }

        when (activeTab) {
            DeckTab.PC_KEYS -> {
                val shiftActive = modifiers.shift
                val row1 = listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
                val row2 = listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l')
                val row3 = listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row1.forEach { ch ->
                        val label = if (shiftActive) ch.uppercase() else ch.toString()
                        KeyCap(label, Modifier.weight(1f)) { dispatchChar(if (shiftActive) ch.uppercaseChar() else ch) }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    row2.forEach { ch ->
                        val label = if (shiftActive) ch.uppercase() else ch.toString()
                        KeyCap(label, Modifier.weight(1f)) { dispatchChar(if (shiftActive) ch.uppercaseChar() else ch) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    KeyCap("⇧", Modifier.weight(1.3f), isSecondary = true) {
                        tapHaptic()
                        modifiers = modifiers.copy(shift = !modifiers.shift)
                    }
                    row3.forEach { ch ->
                        val label = if (shiftActive) ch.uppercase() else ch.toString()
                        KeyCap(label, Modifier.weight(1f)) { dispatchChar(if (shiftActive) ch.uppercaseChar() else ch) }
                    }
                    KeyCap("⌫", Modifier.weight(1.4f), isSecondary = true) { dispatchSpecial(SpecialKey.BACKSPACE) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    KeyCap("123", Modifier.weight(1.1f), isSecondary = true) { activeTab = DeckTab.FN_NUM }
                    KeyCap(",", Modifier.weight(0.8f), isSecondary = true) { dispatchChar(',') }
                    KeyCap("Space", Modifier.weight(3.8f)) {
                        if (modifiers.hasAny) dispatchSpecial(SpecialKey.SPACE) else {
                            tapHaptic()
                            onSendText(" ")
                        }
                    }
                    KeyCap(".", Modifier.weight(0.8f), isSecondary = true) { dispatchChar('.') }
                    KeyCap("Enter", Modifier.weight(1.6f), isAccent = true) { dispatchSpecial(SpecialKey.ENTER) }
                }
            }

            DeckTab.FN_NUM -> {
                val nums = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')
                val syms = listOf('-', '=', '[', ']', '\\', ';', '\'', '/', '@', '#')
                val fKeys1 = listOf(SpecialKey.F1, SpecialKey.F2, SpecialKey.F3, SpecialKey.F4, SpecialKey.F5, SpecialKey.F6)
                val fKeys2 = listOf(SpecialKey.F7, SpecialKey.F8, SpecialKey.F9, SpecialKey.F10, SpecialKey.F11, SpecialKey.F12)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    nums.forEach { ch -> KeyCap(ch.toString(), Modifier.weight(1f)) { dispatchChar(ch) } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    syms.forEach { ch -> KeyCap(ch.toString(), Modifier.weight(1f)) { dispatchChar(ch) } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    fKeys1.forEach { fk -> KeyCap(fk.label, Modifier.weight(1f), isSecondary = true) { dispatchSpecial(fk) } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    fKeys2.forEach { fk -> KeyCap(fk.label, Modifier.weight(1f), isSecondary = true) { dispatchSpecial(fk) } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    KeyCap("Home", Modifier.weight(1f), isSecondary = true) { dispatchSpecial(SpecialKey.HOME) }
                    KeyCap("End", Modifier.weight(1f), isSecondary = true) { dispatchSpecial(SpecialKey.END) }
                    KeyCap("PgUp", Modifier.weight(1f), isSecondary = true) { dispatchSpecial(SpecialKey.PAGE_UP) }
                    KeyCap("PgDn", Modifier.weight(1f), isSecondary = true) { dispatchSpecial(SpecialKey.PAGE_DOWN) }
                    KeyCap("Win", Modifier.weight(1f), isAccent = true) { dispatchSpecial(SpecialKey.WIN) }
                }
            }

            DeckTab.SYSTEM_IME -> {
                SystemImeBridge(
                    onSendText = onSendText,
                    onBackspace = { dispatchSpecial(SpecialKey.BACKSPACE) },
                    onEnter = { dispatchSpecial(SpecialKey.ENTER) }
                )
            }
        }
    }
}

@Composable
private fun SystemImeBridge(
    onSendText: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit
) {
    val palette = LocalDeskPadPalette.current
    val context = LocalContext.current
    val latestSendText by rememberUpdatedState(onSendText)
    val latestBackspace by rememberUpdatedState(onBackspace)
    val latestEnter by rememberUpdatedState(onEnter)
    var composingPreview by remember { mutableStateOf("") }
    var lastSentPreview by remember { mutableStateOf("") }
    var inputViewRef by remember { mutableStateOf<ImeBridgeView?>(null) }

    fun openSoftKeyboard(target: View?) {
        target ?: return
        target.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    }

    DisposableEffect(Unit) {
        onDispose {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            inputViewRef?.let { view ->
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.padSurface)
            .border(1.dp, palette.border, RoundedCornerShape(14.dp))
            .clickable { openSoftKeyboard(inputViewRef) }
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Live Phone Keyboard Bridge Active",
                color = palette.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Tap here to type, swipe, or dictate with Samsung Keyboard / Gboard.",
                color = palette.textMuted,
                fontSize = 12.sp
            )
            if (composingPreview.isNotEmpty() || lastSentPreview.isNotEmpty()) {
                Text(
                    text = if (composingPreview.isNotEmpty()) "Typing: $composingPreview" else "Sent: $lastSentPreview",
                    color = palette.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            AndroidView(
                factory = { ctx ->
                    ImeBridgeView(
                        context = ctx,
                        onCommit = { text ->
                            composingPreview = ""
                            if (text == "\n") {
                                lastSentPreview = "Enter"
                                latestEnter()
                            } else if (text.isNotEmpty()) {
                                val normalized = text.replace("\r\n", "\n")
                                if (normalized.endsWith("\n") && normalized.length > 1) {
                                    val body = normalized.dropLast(1)
                                    lastSentPreview = body
                                    latestSendText(body)
                                    latestEnter()
                                } else {
                                    lastSentPreview = normalized.takeLast(24)
                                    latestSendText(normalized)
                                }
                            }
                        },
                        onComposing = { partial -> composingPreview = partial },
                        onBackspace = {
                            lastSentPreview = "Backspace"
                            latestBackspace()
                        },
                        onEnter = {
                            composingPreview = ""
                            lastSentPreview = "Enter"
                            latestEnter()
                        }
                    ).also { view ->
                        inputViewRef = view
                        view.post { openSoftKeyboard(view) }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
            )
        }
    }
}

private class ImeBridgeView(
    context: Context,
    private val onCommit: (String) -> Unit,
    private val onComposing: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onEnter: () -> Unit
) : View(context) {
    private var composingBuffer: String = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DEL -> {
                        if (composingBuffer.isNotEmpty()) {
                            composingBuffer = composingBuffer.dropLast(1)
                            onComposing(composingBuffer)
                        } else {
                            onBackspace()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_ENTER -> {
                        flushComposing()
                        onEnter()
                        true
                    }
                    else -> {
                        val unicode = event.unicodeChar
                        if (unicode > 0) {
                            flushComposing()
                            onCommit(unicode.toChar().toString())
                            true
                        } else {
                            false
                        }
                    }
                }
            } else {
                false
            }
        }
    }

    private fun flushComposing() {
        if (composingBuffer.isNotEmpty()) {
            val pending = composingBuffer
            composingBuffer = ""
            onCommit(pending)
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composingBuffer = text?.toString().orEmpty()
                onComposing(composingBuffer)
                return true
            }

            override fun finishComposingText(): Boolean {
                flushComposing()
                onComposing("")
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composingBuffer = ""
                onComposing("")
                val str = text?.toString().orEmpty()
                if (str.isNotEmpty()) {
                    onCommit(str)
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    repeat(beforeLength) {
                        if (composingBuffer.isNotEmpty()) {
                            composingBuffer = composingBuffer.dropLast(1)
                            onComposing(composingBuffer)
                        } else {
                            onBackspace()
                        }
                    }
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL -> {
                            deleteSurroundingText(1, 0)
                            return true
                        }
                        KeyEvent.KEYCODE_ENTER -> {
                            flushComposing()
                            onEnter()
                            return true
                        }
                        else -> {
                            val ch = event.unicodeChar
                            if (ch > 0) {
                                flushComposing()
                                onCommit(ch.toChar().toString())
                                return true
                            }
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                flushComposing()
                onEnter()
                return true
            }
        }
    }
}

@Composable
private fun TabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalDeskPadPalette.current
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) palette.accent else palette.surfaceElevated)
            .border(1.dp, if (selected) palette.accent else palette.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) palette.onAccent else palette.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ModifierCap(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalDeskPadPalette.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(shape)
            .background(if (active) palette.accent else palette.surfaceElevated)
            .border(1.dp, if (active) palette.accent else palette.keyBorder, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (active) palette.onAccent else palette.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun KeyCap(
    label: String,
    modifier: Modifier = Modifier,
    isSecondary: Boolean = false,
    isAccent: Boolean = false,
    onClick: () -> Unit
) {
    val palette = LocalDeskPadPalette.current
    val shape = RoundedCornerShape(10.dp)
    val bg = when {
        isAccent -> palette.accent
        isSecondary -> palette.surfaceElevated
        else -> palette.keyBackground
    }
    val fg = when {
        isAccent -> palette.onAccent
        isSecondary -> palette.textMuted
        else -> palette.textPrimary
    }

    Box(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (isAccent) palette.accent else palette.keyBorder, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = if (label.length > 2) 11.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}
