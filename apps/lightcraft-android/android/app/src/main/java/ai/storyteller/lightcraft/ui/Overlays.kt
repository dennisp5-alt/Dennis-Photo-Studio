package ai.storyteller.lightcraft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * The reference app's motion for its sheets and panels, measured from screen recordings of it
 * (where its sheets' edges and lines were, frame by frame): a sheet fades in while rising into
 * place and fades out while rising a little further; the dim behind it fades on its own clock.
 */
object LcMotion {
    /** Coming in: 170 ms, decelerating, from 20 dp below. */
    const val ENTER_MS = 170
    val enterEasing = LinearOutSlowInEasing
    val enterRise = 20.dp

    /** Going: 155 ms, accelerating, 10 dp up. */
    const val EXIT_MS = 155
    val exitEasing = FastOutLinearInEasing
    val exitRise = 10.dp

    /** The dim behind: in over 235 ms, out over 155 ms, linearly. */
    const val DIM_IN_MS = 235
    const val DIM_OUT_MS = 155

    /** The side drawer opens over 295 ms (fast out, slow in) and closes at once. */
    const val DRAWER_OPEN_MS = 295
}

/** The dim behind a sheet or panel (the reference's, sampled: 62 % black). */
val overlayDim = Color.Black.copy(alpha = 0.62f)

/** Whether the overlay being shown is coming in or going ([OverlayHost] sets it). */
private val LocalOverlayVisible = staticCompositionLocalOf<MutableTransitionState<Boolean>?> { null }

private class Last<T>(var value: T? = null)

/**
 * Shows [key]'s overlay ([content]; none while [key] is null) and keeps the last one composed
 * while it animates out, so a sheet closed from anywhere (a row, Back, the dim) leaves as the
 * reference's do.
 */
@Composable
fun <T : Any> OverlayHost(key: T?, content: @Composable (T) -> Unit) {
    val visible = remember { MutableTransitionState(false) }
    val last = remember { Last<T>() }
    if (key != null) last.value = key
    SideEffect { visible.targetState = key != null }
    val shown = last.value
    if (shown != null && (visible.currentState || visible.targetState)) {
        CompositionLocalProvider(LocalOverlayVisible provides visible) { content(shown) }
    }
}

/**
 * A panel over the screen, on a dim that closes it when tapped (and Back), coming and going with
 * the reference's motion. [content] is the panel, placed at [alignment].
 */
@Composable
fun LcOverlay(onDismiss: () -> Unit, alignment: Alignment, content: @Composable BoxScope.() -> Unit) {
    // shown without an OverlayHost: in at once (and gone when no longer composed)
    val own = remember { MutableTransitionState(false) }
    LaunchedEffect(own) { own.targetState = true }
    val visible = LocalOverlayVisible.current ?: own
    BackHandler(enabled = visible.targetState, onBack = onDismiss)
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible,
            enter = fadeIn(tween(LcMotion.DIM_IN_MS, easing = LinearEasing)),
            exit = fadeOut(tween(LcMotion.DIM_OUT_MS, easing = LinearEasing)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(overlayDim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                    .semantics { contentDescription = "Close sheet" },
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(alignment),
            enter = fadeIn(tween(LcMotion.ENTER_MS, easing = LcMotion.enterEasing)) +
                slideInVertically(tween(LcMotion.ENTER_MS, easing = LcMotion.enterEasing)) { with(density) { LcMotion.enterRise.roundToPx() } },
            exit = fadeOut(tween(LcMotion.EXIT_MS, easing = LcMotion.exitEasing)) +
                slideOutVertically(tween(LcMotion.EXIT_MS, easing = LcMotion.exitEasing)) { -with(density) { LcMotion.exitRise.roundToPx() } },
        ) {
            // a tap on the panel's own empty parts stays there (only the dim around it closes it)
            Box(Modifier.pointerInput(Unit) { detectTapGestures() }, content = content)
        }
    }
}

/**
 * Pulling a sheet down, as the reference's can be: it follows the finger once its content is at
 * the top, and on letting go closes (a quarter of the way down, or flung) or goes back up.
 * [offset] is how far down it is (px), [height] its height; [connection] goes on the parent of
 * its scrolling content.
 */
class SheetDrag(private val onDismiss: () -> Unit) {
    var offset by mutableFloatStateOf(0f)
        private set
    var height by mutableIntStateOf(0)

    private suspend fun settle(velocity: Float) {
        if (offset > height / 4f || velocity > 1500f) {
            animate(offset, height.toFloat(), animationSpec = tween(LcMotion.EXIT_MS, easing = LcMotion.exitEasing)) { v, _ -> offset = v }
            onDismiss()
        } else {
            animate(offset, 0f, animationSpec = tween(LcMotion.ENTER_MS, easing = LcMotion.enterEasing)) { v, _ -> offset = v }
        }
    }

    val connection = object : NestedScrollConnection {
        // a pulled-down sheet goes back up before its content scrolls
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y < 0 && offset > 0) {
                val used = max(available.y, -offset)
                offset += used
                return Offset(0f, used)
            }
            return Offset.Zero
        }

        // past the content's top the sheet follows the finger
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y > 0) {
                offset += available.y
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (offset > 0) {
                settle(available.y)
                return available
            }
            return Velocity.Zero
        }
    }
}

@Composable
fun rememberSheetDrag(onDismiss: () -> Unit): SheetDrag {
    val dismiss by rememberUpdatedState(onDismiss)
    return remember { SheetDrag { dismiss() } }
}

/**
 * Where a sheet shows as a card instead (sideways, as the reference's filter, sort and more): its
 * right edge and top in the window, and its width.
 */
class PopupPlacement(val right: Dp, val top: Dp, val width: Dp)

/** Set by the screen that shows a sheet sideways ([LcSheet] then draws [LcPopupCard]). */
val LocalPopupPlacement = staticCompositionLocalOf<PopupPlacement?> { null }

/**
 * A sheet as the reference shows it sideways: a card with 8 dp corners by the button that opened
 * it, on no dim (a tap beside it closes it), there and gone at once (recorded).
 */
@Composable
fun LcPopupCard(onDismiss: () -> Unit, at: PopupPlacement, content: @Composable ColumnScope.() -> Unit) {
    val own = remember { MutableTransitionState(false) }
    LaunchedEffect(own) { own.targetState = true }
    val visible = LocalOverlayVisible.current ?: own
    BackHandler(enabled = visible.targetState, onBack = onDismiss)
    // no motion, but the host still learns when it's gone
    AnimatedVisibility(visible, enter = EnterTransition.None, exit = ExitTransition.None) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .semantics { contentDescription = "Close sheet" },
        ) {
            Column(
                Modifier
                    .offset(x = (at.right - at.width).coerceAtLeast(0.dp), y = at.top)
                    .width(at.width)
                    .heightIn(max = (maxHeight - at.top - 8.dp).coerceAtLeast(120.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(Lc.bar)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
}
