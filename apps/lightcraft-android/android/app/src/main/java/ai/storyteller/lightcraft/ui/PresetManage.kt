package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.PresetInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The top bar's More while Presets is open, as the reference's: a sheet with Create Preset, Manage
 * Presets and Import Presets, then Help. (Its "Show Partially Compatible Presets" is left out: every
 * LightCraft preset applies whole, so there is nothing to show or hide.)
 */
@Composable
fun PresetOptionsSheet(onCreate: () -> Unit, onManage: () -> Unit, onImport: () -> Unit, onHelp: () -> Unit, onDismiss: () -> Unit) {
    LcSheet(onDismiss, inset = 12.6.dp) {
        PresetOptionRow(LcIcon.CreatePreset, "Create Preset", onCreate)
        PresetOptionRow(LcIcon.Sliders, "Manage Presets", onManage)
        PresetOptionRow(LcIcon.Import, "Import Presets", onImport)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF393939)))
        PresetOptionRow(MdIcon.HelpOutline, "Help", onHelp)
    }
}

/** A row of the preset options: 64 dp, its icon then its name (16 sp). */
@Composable
private fun PresetOptionRow(icon: Glyph, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).clickable(role = Role.Button, onClick = onClick).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(icon, null, tint = Lc.panelText, size = 24.dp)
        Text(label, color = Lc.panelText, fontFamily = Inter, fontSize = 16.sp, modifier = Modifier.padding(start = 30.dp))
    }
}

/**
 * Manage Presets, as the reference's: a bar with Cancel, its title and Apply; Preset Groups; LightCraft's
 * and yours, each with how many of its groups show, opening to their groups, each with its preset count
 * and a switch that shows or hides it.
 */
@Composable
fun ManagePresetsPage(vm: AppViewModel, onDismiss: () -> Unit) {
    var hidden by remember { mutableStateOf(vm.hiddenPresetGroups) }
    var open by remember { mutableStateOf<Boolean?>(null) }
    val builtin = remember(vm.presets) { vm.presets.filter { it.builtin }.groupBy { it.group }.toList() }
    val yours = remember(vm.presets) { vm.presets.filter { !it.builtin }.groupBy { it.group }.toList() }
    LcOverlay({ if (open != null) open = null else onDismiss() }, Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().background(Lc.wbList).windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth().height(56.dp).background(Lc.primary), verticalAlignment = Alignment.CenterVertically) {
                if (open == null) {
                    BarButton(MdIcon.Close, "Cancel", size = 56.dp, tint = Color.White, onClick = onDismiss)
                    Text("Manage Presets", color = Color.White, fontFamily = Inter, fontSize = 19.sp, modifier = Modifier.padding(start = 16.dp).weight(1f))
                    BarButton(MdIcon.Check, "Apply", size = 56.dp, tint = Color.White) {
                        vm.hidePresetGroups(hidden)
                        onDismiss()
                    }
                } else {
                    BarButton(MdIcon.ArrowBack, "Back", size = 56.dp, tint = Color.White) { open = null }
                    Text("Preset Groups", color = Color.White, fontFamily = Inter, fontSize = 19.sp, modifier = Modifier.padding(start = 16.dp).weight(1f))
                }
            }
            val list = when (open) {
                true -> builtin
                false -> yours
                null -> null
            }
            if (list == null) {
                Text("Preset Groups", color = Lc.panelTextDim, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp))
                listOf(true to ("LightCraft" to builtin), false to ("Yours" to yours)).forEach { (key, named) ->
                    val (name, groups) = named
                    Row(
                        Modifier.fillMaxWidth().height(54.dp).clickable(enabled = groups.isNotEmpty(), role = Role.Button) { open = key }.padding(start = 24.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, color = Lc.panelTextSoft, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("${groups.count { it.first !in hidden }}/${groups.size}", color = Lc.panelTextDim, fontFamily = Inter, fontSize = 14.sp)
                        MdIcon(MdIcon.ChevronRight, null, tint = Lc.panelTextDim, size = 24.dp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.first }) { (g, ps) ->
                        val shown = g !in hidden
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .toggleable(shown, role = Role.Switch) { hidden = if (it) hidden - g else hidden + g }
                                .padding(start = 24.dp, end = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(g, color = Lc.panelTextSoft, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text("${ps.size}", color = Lc.panelTextSoft, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(end = 16.dp))
                            LcSwitch(shown, behind = Lc.wbList)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One of your presets, held, as the reference's: a sheet headed by its name with Replace (with this
 * photo's settings), Rename, Move to (a group, or a new one) and Delete (after asking).
 */
@Composable
fun PresetManageSheet(vm: AppViewModel, preset: PresetInfo, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (renaming) {
        NameDialog("Rename Preset", "Rename", initial = preset.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            onDismiss()
            vm.renamePreset(preset.id, name)
        }
    }
    if (naming) {
        NameDialog("New Preset Group", "Move", onDismiss = { naming = false }) { group ->
            naming = false
            onDismiss()
            vm.movePreset(preset.id, group)
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = Lc.dialog,
            title = { Text("Delete Preset", fontFamily = Inter, color = Lc.text) },
            text = { Text("Delete “${preset.name}”? This can't be undone.", fontFamily = Inter, color = Lc.textDim) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onDismiss()
                    vm.deletePreset(preset.id)
                }) { Text("Delete", fontFamily = Inter, color = Lc.selected) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel", fontFamily = Inter, color = Lc.text) } },
        )
    }
    LcSheet(onDismiss) {
        if (!moving) {
            SheetHeading(preset.name)
            SheetDivider()
            SheetRow(LcIcon.Versions, "Replace") {
                onDismiss()
                vm.updatePreset(preset.id)
            }
            SheetRow(LcIcon.Pencil, "Rename") { renaming = true }
            SheetRow(LcIcon.Folder, "Move to") { moving = true }
            SheetRow(LcIcon.Trash, "Delete") { deleting = true }
        } else {
            SheetHeading("Preset Groups")
            SheetDivider()
            val groups = remember(vm.presets) { (listOf("User Presets") + vm.presets.filter { !it.builtin }.map { it.group }).distinct() }
            groups.forEach { g ->
                SheetRow(LcIcon.Folder, g, selected = g == preset.group) {
                    onDismiss()
                    if (g != preset.group) vm.movePreset(preset.id, g)
                }
            }
            SheetRow(MdIcon.Add, "New Preset Group") { naming = true }
        }
        Spacer(Modifier.height(8.dp))
    }
}
