package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.R
import androidx.compose.animation.core.Easing
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos

/**
 * The phone's colours: the reference app's dark theme on a phone, observed from the running app
 * (sampled from its screens), as LightCraft's desktop theme was observed from the desktop app.
 * The controls it doesn't let us sample keep LightCraft's desktop values (`crates/ui-egui/src/theme.rs`).
 */
object Lc {
    /** Behind the status bar (the top bar's colour, as the reference app's home screen). */
    val statusBar = Color(0xFF1D1D1D)

    /** Behind the navigation bar. */
    val navBar = Color(0xFF1D1D1D)

    /** Top bar, editor panels and tool rows. */
    val bar = Color(0xFF1D1D1D)

    /** The library's bottom area. */
    val bottomBar = Color(0xFF232323)

    /** Behind the photos and the photo being edited. */
    val canvas = Color(0xFF000000)

    /** A thumbnail cell while it loads. */
    val cell = Color(0xFF303030)
    val text = Color(0xFFD0D0D0)
    val textLabel = Color(0xFFC8C8C8)
    val textDim = Color(0xFF8E8E8E)
    val textDisabled = Color(0xFF5C5C5C)
    val icon = Color(0xFFA2A2A2)

    /** An active control's blue (a toggle that's on, the chosen crop tile). */
    val selected = Color(0xFF73B9AD)

    /** A sheet's current choice: its icon and label (sampled). */
    val choice = Color(0xFF73B9AD)

    /** The check beside a sheet's current choice (sampled). */
    val check = Color(0xFF73B9AD)

    /** The chosen tab of the bottom navigation, and a switch that's on (sampled). */
    val highlight = Color(0xFF73B9AD)

    /** The primary action (the add button). */
    val primary = Color(0xFF2C5952)

    /** A line between a sheet's groups. */
    val divider = Color(0xFF4B4B4B)

    // The editor: sampled from the reference app's own editor on this phone (its current release lets
    // its editor be screenshotted), sizes from its layouts

    /** A tool's or section's icon at rest. */
    val toolIcon = Color(0xFF8A8A8A)

    /** A tool's or section's name (it stays this light whether chosen or not). */
    val toolLabel = Color(0xFFEBEBEB)

    /** The chosen tool's box (the first row: Edit, Crop…); its icon and name turn white. */
    val toolChosen = Color(0xFF303B39)

    /** The chosen section's or action's box (the second row: Light, Color…). */
    val sectionChosen = Color(0xFF303030)

    /** The chosen section's or action's icon and name. */
    val sectionChosenContent = Color(0xFFEBEBEB)

    /** The mark of a tool or section that holds edits (white while it's chosen). */
    val toolMark = Color(0xFFEBEBEB)

    /** The line that sets Auto apart from the other sections. */
    val toolDivider = Color(0xFF5C5C5C)

    /** A pressed tool's ripple: this grey at 12 %. */
    val toolPress = Color(0xFF8F8C8C)

    /** Behind the editor's rows of sections and tools. */
    val toolRows = Color(0xFF1D1D1D)

    /** The line along the top of the editor's rows while a tool is open. */
    val toolRowsEdge = Color(0xFF161616)

    /** The hairline over the tools (under Edit's sections), and between the columns sideways. */
    val toolRowsHairline = Color(0xFF4B4B4B)

    /** The editor's panel (a grey at 95 %), cleared while a slider is dragged. */
    val panel = Color(0xF21E1E1E)

    /** A panel's titles, the white balance row, switches and the open tab. */
    val panelText = Color(0xFFEBEBEB)

    /** A panel button's name (Grading, Mix, B&W), the other tabs, a slider's name and value. */
    val panelTextSoft = Color(0xFFD0D0D0)

    /** Quiet words: a sub-panel's Done, preset groups and their counts. */
    val panelTextDim = Color(0xFF8A8A8A)

    /** A panel button's fill (Curve, Grading, Mix, B&W)… */
    val panelButton = Color(0xFF0E0E0E)

    /** …and its 1 dp edge. */
    val panelButtonBorder = Color(0xFF4B4B4B)

    /** A sub-panel's Done and targeting buttons (Curve, Mix, Grading). */
    val subPanelButton = Color(0xFF151515)

    /** A slider's name and value. */
    val sliderText = Color(0xFFD0D0D0)

    /** A slider's ring thumb. */
    val sliderThumb = Color(0xFFEBEBEB)

    /** The bar from a slider's default to its thumb. */
    val sliderFill = Color(0xFFEBEBEB)

    /** A plain slider track: a ramp from this grey… */
    val sliderTrackFrom = Color(0xFF525252)

    /** …to this one. */
    val sliderTrackTo = Color(0xFF939393)

    /** The 1 dp edge either side of a track (it keeps the track readable while the panel clears). */
    val sliderTrackEdge = Color(0xFF303030)

    /** The pill that names a moving slider (or "Before", or the crop's aspect) on the top bar. */
    val pill = Color(0xFF1D1D1D)

    /** The editor's top bar icons (back, share, save, more). */
    val barIcon = Color(0xFFEBEBEB)

    /** The mask toggle while the mask shows over the photo (the reference greys its struck glyph). */
    val barIconOn = Color(0xFF838383)

    /** A popup menu (the editor's More): its fill, its 1 dp edge and the lines between its groups… */
    val menu = Color(0xFF1D1D1D)
    val menuEdge = Color(0xFF4B4B4B)

    /** …its words and icons. */
    val menuText = Color(0xFFEBEBEB)
    val menuIcon = Color(0xFFD0D0D0)

    /** Behind a top bar button whose menu is open. */
    val barButtonOpen = Color(0xFF4B4B4B)

    /** The reference's own blue: B&W's edge while on, a sub-panel toggle that's on, the profile in use, the remove tool's mode. */
    val primaryBlue = Color(0xFF73B9AD)

    /** The white balance modes' list (a shade lighter than the panel under it). */
    val wbList = Color(0xFF1D1D1D)

    /** A row's chevron (a preset group's). */
    val chevron = Color(0xFFA2A2A2)

    /** A switch's track while it's off. */
    val switchTrack = Color(0xFF303030)

    /** Crop's round buttons over the photo. */
    val cropButton = Color(0xFF303030)

    /** A dialog (naming an album or a preset). */
    val dialog = Color(0xFF1E1E1E)

    /** Masking's column of masks against the right edge. */
    val masksColumn = Color(0xFF1E1E1E)

    // LightCraft's own (desktop) values for what can't be sampled
    val button = Color(0xFF242424)
    val buttonBorder = Color(0xFF4C4C4C)
    val toolActive = Color(0xFF3F3F3F)
    val track = Color(0xFF5A5A5A)
    val thumb = Color(0xFFA0A0A0)
    val thumbActive = Color(0xFFE0E0E0)
    val star = Color(0xFFD8D8D8)
    val pick = Color(0xFFF0F0F0)
    val reject = Color(0xFFE04A4A)
    val caution = Color(0xFFE3A83C)

    /** The colour mixer's bands, as the reference's dots: red, orange, yellow, green, aqua, blue, purple, magenta. */
    val bandColors = listOf(0xFFEC1E24, 0xFFF5851F, 0xFFFFE600, 0xFF69BC45, 0xFF00D8EC, 0xFF3953A3, 0xFFA700FF, 0xFFFF00FF).map { Color(it) }
}

/** The reference's panels' slide: quick, then a long settle (quintic ease-out). */
val QuinticOut = Easing { x -> 1f - (1f - x).let { it * it * it * it * it } }

/** The reference's usual curve (Android's accelerate-decelerate): slow, fast, slow. */
val AccelerateDecelerate = Easing { x -> (cos((x + 1) * PI) / 2 + 0.5).toFloat() }

/** Inter, LightCraft's UI font (`assets/fonts`, OFL), copied into the app by the build. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private val colors = darkColorScheme(
    primary = Lc.primary,
    onPrimary = Color.White,
    primaryContainer = Lc.primary,
    onPrimaryContainer = Color.White,
    secondary = Lc.selected,
    onSecondary = Color.White,
    secondaryContainer = Lc.toolActive,
    onSecondaryContainer = Lc.text,
    background = Lc.canvas,
    onBackground = Lc.text,
    surface = Lc.bar,
    onSurface = Lc.text,
    surfaceVariant = Lc.bottomBar,
    onSurfaceVariant = Lc.textLabel,
    surfaceContainerLowest = Lc.canvas,
    surfaceContainerLow = Lc.bar,
    surfaceContainer = Lc.bottomBar,
    surfaceContainerHigh = Lc.bottomBar,
    surfaceContainerHighest = Lc.toolActive,
    inverseSurface = Lc.toolActive,
    inverseOnSurface = Lc.text,
    inversePrimary = Lc.selected,
    outline = Lc.buttonBorder,
    outlineVariant = Lc.bottomBar,
    error = Lc.reject,
)

/** Inter, untracked, as the reference's text has no letter spacing (Material's styles add up to 0.5 sp). */
private fun TextStyle.inter() = copy(fontFamily = Inter, letterSpacing = 0.sp)

private val typography = Typography().run {
    Typography(
        displayLarge = displayLarge.inter(),
        displayMedium = displayMedium.inter(),
        displaySmall = displaySmall.inter(),
        headlineLarge = headlineLarge.inter(),
        headlineMedium = headlineMedium.inter(),
        headlineSmall = headlineSmall.inter(),
        titleLarge = titleLarge.inter(),
        titleMedium = titleMedium.inter(),
        titleSmall = titleSmall.inter(),
        bodyLarge = bodyLarge.inter(),
        bodyMedium = bodyMedium.inter(),
        bodySmall = bodySmall.inter(),
        labelLarge = labelLarge.inter(),
        labelMedium = labelMedium.inter(),
        labelSmall = labelSmall.inter(),
    )
}

/**
 * The theme; light content by default, so presses ripple light on the dark screens (as the
 * reference's). Text is set in its font's own line height, as the reference's text views are, not
 * in the 24 sp lines of Material's body style.
 */
@Composable
fun LightcraftTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, typography = typography) {
    CompositionLocalProvider(
        LocalContentColor provides Lc.text,
        LocalTextStyle provides typography.bodyLarge.copy(lineHeight = TextUnit.Unspecified),
        content = content,
    )
}
