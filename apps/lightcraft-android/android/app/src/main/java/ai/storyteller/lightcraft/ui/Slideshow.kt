package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.DevelopControl
import ai.storyteller.lightcraft.core.SliderTrack
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import kotlinx.coroutines.delay

/** The slideshow's transitions, as the reference names them. */
val slideTransitions = listOf("none" to "No Transition", "fade" to "Cross Fade", "wipe" to "Wipe", "flip" to "Flip")

/**
 * The grid's photos full screen, wrapping: each stays the chosen seconds and gives way to the next
 * with the chosen transition (the reference's options: no transition, cross fade, wipe, flip). Tap to
 * pause (a bar shows: end, play and the options) or go on, swipe for the next or previous one, Back
 * to end.
 */
@Composable
fun Slideshow(vm: AppViewModel) {
    val index = vm.slideshow ?: return
    var paused by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    // each slide's image as it came (its thumbnail, then its render), so a transition shows the one leaving
    val images = remember { mutableStateMapOf<Int, ImageBitmap>() }
    LaunchedEffect(vm.slideshowImage, index) {
        val img = vm.slideshowImage ?: return@LaunchedEffect
        images[index] = img
        images.keys.filter { abs(it - index) > 1 }.forEach { images.remove(it) }
    }
    BackHandler { if (options) options = false else vm.stopSlideshow() }
    // full screen: the system bars step aside (a swipe from an edge shows them for a moment)
    val view = LocalView.current
    DisposableEffect(Unit) {
        val controller = (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(index, paused, vm.slideSeconds) {
        if (paused) return@LaunchedEffect
        delay(vm.slideSeconds * 1000L)
        vm.stepSlideshow(1)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { vm.onSlideshowSize(it) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var dx = 0f
                    var moved = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        dx = change.position.x - down.position.x
                        if (abs(dx) > viewConfiguration.touchSlop) moved = true
                    }
                    when {
                        moved && abs(dx) > 72.dp.toPx() -> vm.stepSlideshow(if (dx < 0) 1 else -1)
                        !moved -> paused = !paused
                    }
                }
            },
    ) {
        AnimatedContent(
            index,
            transitionSpec = {
                when (vm.slideTransition) {
                    "fade" -> fadeIn(tween(700)) togetherWith fadeOut(tween(700))
                    "wipe" -> slideInHorizontally(tween(600)) { it } togetherWith slideOutHorizontally(tween(600)) { -it }
                    "flip" -> fadeIn(tween(1, delayMillis = 300)) togetherWith fadeOut(tween(1, delayMillis = 299))
                    else -> EnterTransition.None togetherWith ExitTransition.None
                }
            },
            label = "slide",
        ) { i ->
            // a flip: the leaving photo turns away to its edge, the next turns in from it
            val turn by transition.animateFloat({ tween(600) }, label = "flip") { state ->
                when {
                    vm.slideTransition != "flip" -> 0f
                    state == EnterExitState.Visible -> 0f
                    state == EnterExitState.PreEnter -> -90f
                    else -> 90f
                }
            }
            images[i]?.let {
                Image(
                    it,
                    contentDescription = vm.photos.getOrNull(i)?.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        rotationY = turn
                        cameraDistance = 12f * density
                    },
                )
            }
        }
        if (options) {
            LcSheet({ options = false }) {
                SheetHeading("Slide show options")
                slideTransitions.forEach { (id, label) -> RadioRow(label, vm.slideTransition == id) { vm.setSlideshow(transition = id) } }
                DevelopSlider(
                    DevelopControl("slideshow.seconds", "Duration", "", "", 1.0, 10.0, 4.0, 1.0, 0u, vm.slideSeconds.toDouble(), SliderTrack.Plain),
                    onChange = { vm.setSlideshow(seconds = it.toInt()) },
                    onFinished = {},
                    onReset = { vm.setSlideshow(seconds = 4) },
                    label = "Duration (s)",
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
            }
        }
        if (paused) {
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                BarButton(MdIcon.Close, "End the slideshow", size = 48.dp, tint = Color.White) { vm.stopSlideshow() }
                Spacer(Modifier.weight(1f))
                BarButton(LcIcon.Play, "Play", size = 48.dp, tint = Color.White) { paused = false }
                BarButton(MdIcon.MoreVert, "Slideshow options", size = 48.dp, tint = Color.White) { options = true }
            }
            Text(
                "Paused · ${index + 1} of ${vm.photos.size}",
                color = Lc.text,
                fontFamily = Inter,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(24.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
