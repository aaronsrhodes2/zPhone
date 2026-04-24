package com.skippy.chat.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skippy.chat.compositor.ChatPalette
import com.skippy.chat.model.Message

/**
 * One scrollback bubble.
 *
 * Layout rules:
 *   - USER        : right-aligned, [ChatPalette.White] text, no fill.
 *                   If `failed == true`, text turns [ChatPalette.Red].
 *   - ASSISTANT   : left-aligned, [ChatPalette.Green] text, no fill.
 *                   If `tier == "cloud"`, a small violet dot trails.
 *   - SYSTEM      : centered, dim-green, for banners ("reconnected",
 *                   "cold boot…"). Phase 1 does not emit these; kept
 *                   in the [when] so Phase 2 log sync can light them up.
 *
 * Passthrough doctrine: no colored fills, no backgrounds — just text
 * on black. Colored fills dilute the additive-light read (carried
 * over from SkippyDroid even though SkippyChat never hits glasses).
 * Consistent visual grammar across surfaces.
 *
 * Long-press opens the [DevDetailSheet] via [onLongPress].
 */
@Composable
fun MessageBubble(
    message: Message,
    onLongPress: (Message) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        Message.Role.USER      -> UserBubble(message, onLongPress, modifier)
        Message.Role.ASSISTANT -> AssistantBubble(message, onLongPress, modifier)
        Message.Role.SYSTEM    -> SystemBubble(message, modifier)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    message: Message,
    onLongPress: (Message) -> Unit,
    modifier: Modifier,
) {
    val color = if (message.failed) ChatPalette.Red else ChatPalette.White
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(4.dp))
                .then(
                    if (message.failed)
                        Modifier.border(1.dp, ChatPalette.Red, RoundedCornerShape(4.dp))
                    else Modifier
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onLongPress(message) },
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = message.text,
                color = color,
                fontFamily = FontFamily.Default,
                fontSize = 16.sp,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBubble(
    message: Message,
    onLongPress: (Message) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onLongPress(message) },
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = message.text,
                color = ChatPalette.Green,
                fontFamily = FontFamily.Default,
                fontSize = 16.sp,
            )
        }
        if (message.tier == "cloud") {
            // Small violet dot marks cloud-tier replies, per plan.
            Spacer(Modifier.size(4.dp))
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(ChatPalette.Violet),
            )
        }
    }
}

@Composable
private fun SystemBubble(message: Message, modifier: Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message.text,
            color = ChatPalette.DimGreenHi,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}

