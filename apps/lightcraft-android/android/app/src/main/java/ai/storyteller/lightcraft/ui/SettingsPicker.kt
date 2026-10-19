package ai.storyteller.lightcraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A settings group as Copy Settings and Create Preset list it: [id] as the engine names it (`develop.copy`'s groups). */
data class SettingsChoice(val id: String, val label: String)

/** A line of the list: one group, or (more than one) a section that opens to its groups. */
data class SettingsSection(val label: String, val items: List<SettingsChoice>)

/** The engine's settings groups in the reference's sections and order. */
val settingsSections: List<SettingsSection> = listOf(
    SettingsSection("Profile", listOf(SettingsChoice("profile", "Profile"), SettingsChoice("treatment", "Black & White"))),
    SettingsSection("Light", listOf(SettingsChoice("light", "Light"), SettingsChoice("toneCurve", "Curve"))),
    SettingsSection(
        "Color",
        listOf(
            SettingsChoice("whiteBalance", "White Balance"),
            SettingsChoice("color", "Vibrance and Saturation"),
            SettingsChoice("colorMixer", "Color Mix"),
            SettingsChoice("colorGrading", "Color Grading"),
        ),
    ),
    SettingsSection("Effects", listOf(SettingsChoice("effects", "Effects"), SettingsChoice("vignette", "Vignette"), SettingsChoice("grain", "Grain"))),
    SettingsSection("Detail", listOf(SettingsChoice("detail", "Sharpening and Noise Reduction"))),
    SettingsSection("Optics", listOf(SettingsChoice("optics", "Optics"))),
    SettingsSection("Geometry", listOf(SettingsChoice("geometry", "Geometry"))),
    SettingsSection("Crop", listOf(SettingsChoice("crop", "Crop"))),
    SettingsSection("Masking", listOf(SettingsChoice("masks", "Masking"))),
    SettingsSection("Remove", listOf(SettingsChoice("spots", "Healing"), SettingsChoice("redEye", "Red Eye"))),
    SettingsSection("Calibration", listOf(SettingsChoice("calibration", "Calibration"))),
)

/** Every group id. */
val allSettingsGroups: Set<String> = settingsSections.flatMap { s -> s.items.map { it.id } }.toSet()

/** The groups copied unless chosen otherwise (the engine's `default_copy`: all but crop, masks and spot removal). */
val defaultSettingsGroups: Set<String> = allSettingsGroups - setOf("crop", "masks", "spots", "redEye")

/** How many of [section]'s groups are in [chosen], as the reference counts them (`2/4`). */
internal fun sectionCount(section: SettingsSection, chosen: Set<String>): String = "${section.items.count { it.id in chosen }}/${section.items.size}"

private val dialogFill = Color(0xFF1E1E1E)
private val optionText = Color(0xFFE8E8E8)
private val countText = Color(0xFF8A8A8A)

/**
 * The reference's settings picker (Copy Settings): a 56 dp bar with Cancel, its title (20 sp) and OK;
 * Select (All, Default, Modified, None); then each section with a check box (all, some or none of
 * its groups), its name (17 sp), how many of them are chosen and, for a section of several, a
 * chevron that opens it to its groups.
 */
@Composable
fun SettingsPickerPage(
    title: String,
    initial: Set<String>,
    modified: suspend () -> Set<String>,
    onDismiss: () -> Unit,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    onDone: (Set<String>) -> Unit,
) {
    var chosen by remember { mutableStateOf(initial) }
    var open by remember { mutableStateOf<SettingsSection?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var modifiedGroups by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(Unit) { modifiedGroups = modified() }
    LcOverlay({ if (open != null) open = null else onDismiss() }, Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().background(dialogFill).windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                val section = open
                if (section == null) {
                    BarButton(MdIcon.Close, "Cancel", size = 56.dp, tint = Color.White, onClick = onDismiss)
                    Text(title, color = Color.White, fontFamily = Inter, fontSize = 20.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    BarButton(MdIcon.Check, "OK", size = 56.dp, tint = Color.White) { onDone(chosen) }
                } else {
                    BarButton(MdIcon.ArrowBack, "Back", size = 56.dp, tint = Color.White) { open = null }
                    Text(section.label, color = Color.White, fontFamily = Inter, fontSize = 20.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
                }
            }
            val section = open
            if (section == null) {
                header?.invoke(this)
                // Select: all, the default groups, those this photo has changed, none
                Box(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
                    Row(
                        Modifier.clickable(role = Role.DropdownList) { selecting = true }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Select", color = optionText, fontFamily = Inter, fontSize = 17.sp)
                        LcIcon(LcIcon.ChevronDown, null, tint = optionText, size = 18.dp, modifier = Modifier.padding(start = 6.dp))
                    }
                    LcMenu(expanded = selecting, onDismiss = { selecting = false }) {
                        listOf(
                            "All" to allSettingsGroups,
                            "Default" to defaultSettingsGroups,
                            "Modified" to (modifiedGroups ?: emptySet()),
                            "None" to emptySet(),
                        ).forEach { (label, groups) ->
                            MenuItem(label) {
                                selecting = false
                                chosen = groups
                            }
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(settingsSections, key = { it.label }) { s ->
                        val ids = s.items.map { it.id }.toSet()
                        val on = ids.count { it in chosen }
                        val several = s.items.size > 1
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { if (several) open = s else chosen = toggled(chosen, ids, on) }
                                .padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CheckBox(
                                when (on) {
                                    0 -> false
                                    ids.size -> true
                                    else -> null
                                },
                                s.label,
                            ) { chosen = toggled(chosen, ids, on) }
                            Text(s.label, color = optionText, fontFamily = Inter, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            if (several) {
                                Text(sectionCount(s, chosen), color = countText, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp))
                                Box(Modifier.size(45.dp), contentAlignment = Alignment.Center) { MdIcon(MdIcon.ChevronRight, null, tint = countText, size = 24.dp) }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(top = 10.dp)) {
                    items(section.items, key = { it.id }) { item ->
                        val on = item.id in chosen
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { chosen = if (on) chosen - item.id else chosen + item.id }.padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CheckBox(on, item.label) { chosen = if (on) chosen - item.id else chosen + item.id }
                            Text(item.label, color = optionText, fontFamily = Inter, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** A section's groups all chosen when some or none were, else none. */
private fun toggled(chosen: Set<String>, ids: Set<String>, on: Int): Set<String> = if (on == ids.size) chosen - ids else chosen + ids

/** A 45 dp check box: ticked, empty or (null) some of its section's groups. */
@Composable
private fun CheckBox(checked: Boolean?, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(45.dp).clickable(role = Role.Checkbox, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        MdIcon(
            when (checked) {
                true -> MdIcon.CheckBox
                false -> MdIcon.CheckBoxOutline
                null -> MdIcon.CheckBoxSome
            },
            null,
            tint = if (checked == false) countText else Lc.primary,
            size = 24.dp,
        )
    }
}

/**
 * Create Preset, as the reference's: the settings picker titled New Preset, its name (hint "Untitled
 * Preset", a clear button) and its group (the user's groups, User Presets first, or a new one) over
 * the settings.
 */
@Composable
fun CreatePresetPage(vm: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val groups = remember(vm.presets) { (listOf("User Presets") + vm.presets.filter { !it.builtin }.map { it.group }).distinct() }
    var group by remember { mutableStateOf(groups.first()) }
    var choosing by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        NameDialog("New Preset Group", "Create", onDismiss = { naming = false }) { g ->
            naming = false
            if (g.isNotBlank()) group = g.trim()
        }
    }
    SettingsPickerPage(
        "New Preset",
        defaultSettingsGroups,
        modified = { vm.modifiedGroups() },
        onDismiss = onDismiss,
        header = {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Preset Name", color = Color(0xFF999999), fontFamily = Inter, fontSize = 15.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        name,
                        { name = it },
                        singleLine = true,
                        textStyle = TextStyle(color = optionText, fontFamily = Inter, fontSize = 17.sp),
                        cursorBrush = SolidColor(Lc.primary),
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp).semantics { contentDescription = "Preset Name" },
                        decorationBox = { inner ->
                            Box {
                                if (name.isEmpty()) Text("Untitled Preset", color = countText, fontFamily = Inter, fontSize = 17.sp)
                                inner()
                            }
                        },
                    )
                    if (name.isNotEmpty()) BarButton(MdIcon.Close, "Clear the name", size = 40.dp, iconSize = 18.dp, tint = countText) { name = "" }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF393939)))
            }
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Text("Preset Group", color = Color(0xFF999999), fontFamily = Inter, fontSize = 15.sp)
                Box {
                    Row(Modifier.clickable(role = Role.DropdownList) { choosing = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(group, color = optionText, fontFamily = Inter, fontSize = 17.sp)
                        LcIcon(LcIcon.ChevronDown, null, tint = optionText, size = 18.dp, modifier = Modifier.padding(start = 6.dp))
                    }
                    LcMenu(expanded = choosing, onDismiss = { choosing = false }) {
                        groups.forEach { g ->
                            MenuItem(g) {
                                choosing = false
                                group = g
                            }
                        }
                        MenuItem("New Preset Group") {
                            choosing = false
                            naming = true
                        }
                    }
                }
            }
        },
    ) { chosen ->
        onDismiss()
        vm.createPreset(name.ifBlank { "Untitled Preset" }, group, chosen)
    }
}
