package ai.storyteller.lightcraft.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** One of a photo's versions (`photo.inspect`'s `versions`): [index] as `version.restore` takes it, [settings] its whole develop JSON. */
data class VersionInfo(val index: Int, val name: String, val created: String, val auto: Boolean, val settings: String)

/** `photo.inspect`'s result's versions, newest first as the reference lists them. */
fun parseVersions(json: String): List<VersionInfo> = runCatching {
    val a = JSONObject(json).optJSONArray("versions") ?: return emptyList()
    (0 until a.length()).mapNotNull { i ->
        val v = a.optJSONObject(i) ?: return@mapNotNull null
        VersionInfo(i, v.optString("name"), v.optString("created"), v.optBoolean("auto"), v.optJSONObject("settings")?.toString() ?: "{}")
    }.reversed()
}.getOrDefault(emptyList())

private val versionText = Color(0xFFA2A2A2)

/**
 * Versions (the undo card's), as the reference's: a bar with Cancel, "Versions" and Apply; the photo,
 * Create Version over its foot; then Auto and Named as tabs over a strip of the versions (a tap shows
 * one on the photo, Apply keeps it; a long press: rename, update with the current settings, delete)
 * and a menu to delete all named versions or all autosaves.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VersionsPage(vm: AppViewModel, ed: EditorState, onDismiss: () -> Unit) {
    LaunchedEffect(ed.photoId) { vm.loadVersions() }
    var tab by remember { mutableStateOf("auto") }
    var shown by remember { mutableStateOf<VersionInfo?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var creating by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf<VersionInfo?>(null) }
    var renaming by remember { mutableStateOf<VersionInfo?>(null) }
    LaunchedEffect(shown) {
        preview = null
        shown?.let { preview = vm.settingsPreview(it.settings, 1600) }
    }
    if (creating) {
        NameDialog("Create Version", "OK", initial = "Version ${vm.versions.count { !it.auto } + 1}", onDismiss = { creating = false }) { name ->
            creating = false
            vm.createVersion(name)
            tab = "named"
        }
    }
    renaming?.let { v ->
        NameDialog("Rename Version", "Rename", initial = v.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            vm.renameVersion(v.index, name)
        }
    }
    LcOverlay(onDismiss, Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().background(Lc.canvas).windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                BarButton(MdIcon.Close, "Cancel", size = 56.dp, tint = Color.White, onClick = onDismiss)
                Text("Versions", color = Color.White, fontFamily = Inter, fontSize = 20.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
                BarButton(MdIcon.Check, "Apply", size = 56.dp, tint = Color.White, enabled = shown != null) {
                    shown?.let { vm.restoreVersion(it.index) }
                    onDismiss()
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                (preview ?: ed.image)?.let { Image(it, ed.info.fileName, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                // Create Version over the photo's foot, with the version on show
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        shown?.let { "${it.name} · ${versionWhen(it.created)}" } ?: "Current",
                        color = Color.White,
                        fontFamily = Inter,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 16.dp),
                    )
                    Box(
                        Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Lc.primary)
                            .combinedClickable(role = Role.Button) { creating = true }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Create Version", color = Color.White, fontFamily = Inter, fontSize = 14.sp)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().background(Lc.panel)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(150.dp)) { Tabs(listOf("auto" to "Auto", "named" to "Named"), tab, textSize = 15f) { tab = it } }
                    Spacer(Modifier.weight(1f))
                    Box {
                        BarButton(MdIcon.MoreVert, "Versions options", size = 48.dp, tint = Lc.panelTextSoft) { menu = true }
                        LcMenu(expanded = menu, onDismiss = { menu = false }) {
                            Text("DELETE", color = versionText, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            LcMenuItem(LcIcon.Trash, "All Named Versions", enabled = vm.versions.any { !it.auto }) {
                                menu = false
                                vm.deleteVersions(auto = false)
                            }
                            LcMenuItem(LcIcon.Trash, "All Autosaves", enabled = vm.versions.any { it.auto }) {
                                menu = false
                                vm.deleteVersions(auto = true)
                            }
                        }
                    }
                }
                val list = vm.versions.filter { it.auto == (tab == "auto") }
                if (list.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (tab == "auto") "Versions are saved here as you move to another photo." else "No named versions yet. Create one to come back to this look.",
                            color = versionText,
                            fontFamily = Inter,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                } else {
                    LazyRow(Modifier.fillMaxWidth().height(170.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(list, key = { it.index }) { v ->
                            var thumb by remember(v.index, v.settings) { mutableStateOf<ImageBitmap?>(null) }
                            LaunchedEffect(v.index, v.settings) { thumb = vm.settingsPreview(v.settings, AppViewModel.LOOK_EDGE) }
                            Box {
                                Column(
                                    Modifier
                                        .padding(top = 14.dp)
                                        .width(120.dp)
                                        .combinedClickable(role = Role.Button, onLongClick = { options = v }) { shown = if (shown == v) null else v }
                                        .semantics { contentDescription = v.name },
                                ) {
                                    Box(
                                        Modifier
                                            .size(120.dp)
                                            .background(Color.Black)
                                            .then(if (shown == v) Modifier.border(2.dp, Lc.primary) else Modifier),
                                    ) {
                                        thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                    }
                                    Text(v.name, color = versionText, fontFamily = Inter, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                    Text(versionWhen(v.created), color = versionText, fontFamily = Inter, fontSize = 12.sp, maxLines = 1)
                                }
                                LcMenu(expanded = options == v, onDismiss = { options = null }) {
                                    LcMenuItem(LcIcon.Pencil, "Rename") {
                                        options = null
                                        renaming = v
                                    }
                                    LcMenuItem(LcIcon.Versions, "Update with Current Settings") {
                                        options = null
                                        vm.updateVersion(v.index)
                                    }
                                    LcMenuItem(LcIcon.Trash, "Delete") {
                                        options = null
                                        if (shown == v) shown = null
                                        vm.deleteVersion(v.index)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A version's time as the reference lists it, in the phone's time: `Oct 10, 2026, 4:45 PM` (empty when
 * it can't be read). The library writes its times in UTC without a zone.
 */
internal fun versionWhen(iso: String): String {
    val utc = if (iso.endsWith("Z") || iso.length > 19) iso else iso.take(19) + "Z"
    return takenDayAndTime(utc)?.let { (day, time) -> "$day, ${time.replace(Regex(":\\d\\d(?= )"), "")}" } ?: ""
}
