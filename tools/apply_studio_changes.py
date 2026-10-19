#!/usr/bin/env python3
"""Dennis Photo Studio v0.1 source patch, pinned upstream c6fac28.
Retains the original LightCraft colour pipeline, White Balance and licences.
Strict anchors: stop if the upstream source has changed.
"""
from pathlib import Path

ROOT=Path(__file__).resolve().parent
BASE=ROOT/"apps/lightcraft-android/android/app"
PKG=BASE/"src/main/java/ai/storyteller/lightcraft"
UI=PKG/"ui"

def edit(path,changes):
    s=path.read_text(encoding="utf-8")
    for old,new in changes:
        if s.count(old)!=1:
            raise RuntimeError(f"{path}: patch anchor count={s.count(old)} for {old[:100]!r}")
        s=s.replace(old,new)
    path.write_text(s,encoding="utf-8")
    print("Updated",path.relative_to(ROOT),flush=True)

edit(BASE/"src/main/res/values/strings.xml",[
    ('<string name="app_name">LightCraft</string>','<string name="app_name">Dennis Photo Studio</string>'),
    ('Import into LightCraft','Import into Dennis Photo Studio'),
    ('Edit in LightCraft','Edit in Dennis Photo Studio')
])

edit(BASE/"build.gradle.kts",[
    ('applicationId = "io.github.artcraftandroid.lightcraft"',
     'applicationId = "com.dennis.photostudio"')
])

edit(UI/"Tokens.kt",[
    ('val selected = Color(0xFF4B9CF5)','val selected = Color(0xFF73B9AD)'),
    ('val choice = Color(0xFF4096F3)','val choice = Color(0xFF73B9AD)'),
    ('val check = Color(0xFF2680EB)','val check = Color(0xFF73B9AD)'),
    ('val highlight = Color(0xFF5EAAF7)','val highlight = Color(0xFF73B9AD)'),
    ('val primary = Color(0xFF066CE7)','val primary = Color(0xFF2C5952)'),
    ('val toolChosen = Color(0xFF2680EB)','val toolChosen = Color(0xFF303B39)'),
    ('val primaryBlue = Color(0xFF1473E6)','val primaryBlue = Color(0xFF73B9AD)')
])

# Small, unobtrusive dark notification: show action and dismiss affordances.
(UI/"Toast.kt").write_text('''package ai.storyteller.lightcraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LcToast(data: SnackbarData) {
    val shape=RoundedCornerShape(12.dp)
    Row(
        Modifier.padding(horizontal=16.dp,vertical=6.dp)
            .widthIn(max=440.dp)
            .clip(shape).background(Color(0xFF282F2E))
            .border(1.dp,Color(0xFF42504D),shape)
            .padding(start=12.dp,end=4.dp,top=8.dp,bottom=8.dp),
        verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(6.dp)
    ) {
        Text(data.visuals.message,
            color=Color(0xFFE7EEEC),
            fontFamily=Inter,fontSize=13.sp,
            maxLines=2,overflow=TextOverflow.Ellipsis,
            modifier=Modifier.weight(1f,fill=false))
        data.visuals.actionLabel?.let { label ->
            Text(label,color=Lc.selected,fontFamily=Inter,
                fontWeight=FontWeight.SemiBold,fontSize=12.sp,
                modifier=Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable(role=Role.Button){ data.performAction() }
                    .padding(horizontal=8.dp,vertical=8.dp))
        }
        Box(Modifier.size(32.dp)
            .clickable(role=Role.Button){ data.dismiss() }
            .semantics{ contentDescription="Close" },
            contentAlignment=Alignment.Center){
            MdIcon(MdIcon.Close,null,tint=Color(0xFFB8C2BF),size=16.dp)
        }
    }
}
''',encoding="utf-8")

edit(UI/"EditorScreen.kt",[
    ('import androidx.compose.material3.Scaffold',
     'import androidx.compose.material3.Scaffold\nimport androidx.compose.material3.Slider'),
    ('        primary -> Color.White\n        else -> Lc.sectionChosenContent',
     '        primary -> Lc.text\n        else -> Lc.sectionChosenContent'),
    ('        }\n    }\n}\n\n@Composable\nprivate fun SectionButton(vm: AppViewModel, ed: EditorState, s: EditSection, modifier: Modifier, column: Boolean = false) {',
     '''        }
        if (selected && primary) {
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom=3.dp).width(20.dp).height(2.dp)
                    .background(Lc.selected,RoundedCornerShape(1.dp))
            )
        }
    }
}

@Composable
private fun SectionButton(vm: AppViewModel, ed: EditorState, s: EditSection, modifier: Modifier, column: Boolean = false) {'''),
    ('Row(Modifier.clip(RoundedCornerShape(4.dp)).background(Lc.toolRows).height(68.1.dp)) {',
     'Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Lc.toolRows).height(62.dp)) {'),
    ('.padding(bottom = 7.8.dp)\n            .height(53.5.dp)',
     '.padding(bottom = 5.dp)\n            .height(48.dp)'),
    ('ToolButton(t.icon, t.label, showLabel = false, selected = ed.tool == t, primary = true, modifier = Modifier.width(53.5.dp).fillMaxHeight())',
     'ToolButton(t.icon, t.label, showLabel = false, selected = ed.tool == t, primary = true, modifier = Modifier.width(48.dp).fillMaxHeight())'),
    ('    if (sharing) {\n        ShareSheet(',
     '    if (ed.autoAmountDialog) AutoAmountDialog(ed, onDismiss = { ed.autoAmountDialog = false }) { amount -> vm.applyAuto(amount) }\n    if (sharing) {\n        ShareSheet('),
    ("/** The tools, centred; labelled (68 dp) until one is open (53.5 dp), as the reference app's. */",
     '''/** A small non-destructive Auto Amount dialog. */
@Composable
private fun AutoAmountDialog(ed: EditorState, onDismiss: () -> Unit, onApply: (Float) -> Unit) {
    var value by remember(ed) { mutableFloatStateOf(ed.autoAmount) }
    AlertDialog(
        onDismissRequest=onDismiss,
        containerColor=Lc.dialog,
        title={ Text("Auto adjustment", color=Lc.text, fontFamily=Inter) },
        text={
            Column {
                Text("Choose how much of the suggested correction to apply.",
                    color=Lc.panelTextSoft,fontSize=13.sp)
                Spacer(Modifier.height(12.dp))
                Text("Amount: \${value.roundToInt()}%",color=Lc.text,fontSize=14.sp)
                Slider(value=value,onValueChange={value=it},valueRange=0f..100f)
                Text("100% matches original LightCraft Auto. White Balance is unchanged.",
                    color=Lc.panelTextDim,fontSize=12.sp)
            }
        },
        confirmButton={TextButton(onClick={onApply(value)}) {Text("Apply",color=Lc.selected)}},
        dismissButton={TextButton(onClick=onDismiss){Text("Cancel",color=Lc.text)}}
    )
}

/** The tools, centred; labelled (68 dp) until one is open (53.5 dp), as the reference app's. */''')
])

edit(UI/"AppViewModel.kt",[
    ('    var action by mutableStateOf<QuickAction?>(null)',
     '    var action by mutableStateOf<QuickAction?>(null)\n    var autoAmountDialog by mutableStateOf(false)\n    var autoAmount by mutableFloatStateOf(100f)'),
    ('QuickAction.AUTO -> edit(after = { say("Auto settings applied") }) { it.execute("develop.auto", "{}") }',
     'QuickAction.AUTO -> { ed.autoAmountDialog = true }'),
    ('EditSection.AUTO -> edit(after = { say("Auto settings applied") }) { it.execute("develop.auto", "{}") }',
     'EditSection.AUTO -> { ed.autoAmountDialog = true }'),
    ("    /** Crop › Geometry's switch: crop the corners an Upright or transform leaves blank (one step Cancel takes back). */",
     '''    /** Apply fraction of suggested Auto SETTINGS (not a blend of pixels). */
    fun applyAuto(amount: Float) {
        val ed=editor ?: return
        val safe=amount.coerceIn(0f,100f)
        ed.autoAmount=safe
        ed.autoAmountDialog=false
        edit(after={say("Auto applied at \${safe.roundToInt()}%")}) {
            it.execute("develop.auto",
                JSONObject().put("amount",safe.toDouble()/100.0).toString())
        }
    }

    /** Crop › Geometry's switch: crop the corners an Upright or transform leaves blank (one step Cancel takes back). */'''),
    ('"Saved \${file.fileName} (\${file.width}×\${file.height}) to Pictures/LightCraft"',
     '"Photo saved • \${file.width}×\${file.height}"')
])

edit(ROOT/"crates/engine/src/cmd/develop.rs",[
    ('cmd!("develop.auto", "Auto Settings", ["Photo"], Some("Shift+A"), "{}", has_active, |s, _| {',
     'cmd!("develop.auto", "Auto Settings", ["Photo"], Some("Shift+A"), "{amount?: 0..1}", has_active, |s, p| {'),
    ('''            let a = lightcraft_pipeline::auto::auto_tone(&src, &info, &d);
            edit(s, "develop.auto", "Auto", |d| {
                d.light.exposure = a.exposure;
                d.light.contrast = a.contrast;
                d.light.highlights = a.highlights;
                d.light.shadows = a.shadows;
                d.light.whites = a.whites;
                d.light.blacks = a.blacks;
                d.color.vibrance = a.vibrance;
                d.color.saturation = a.saturation;
                Ok(())
            })?;''',
     '''            let a = lightcraft_pipeline::auto::auto_tone(&src, &info, &d);
            // At 100%, exactly original Auto. At 0%, no adjustment.
            // Interpolate settings; do not distort white balance or merge
            // images with different gamma/colour spaces.
            let amount = f64_or(p, "amount", 1.0).clamp(0.0, 1.0);
            edit(s, "develop.auto", "Auto", |d| {
                d.light.exposure += (a.exposure-d.light.exposure)*amount;
                d.light.contrast += (a.contrast-d.light.contrast)*amount;
                d.light.highlights += (a.highlights-d.light.highlights)*amount;
                d.light.shadows += (a.shadows-d.light.shadows)*amount;
                d.light.whites += (a.whites-d.light.whites)*amount;
                d.light.blacks += (a.blacks-d.light.blacks)*amount;
                d.color.vibrance += (a.vibrance-d.color.vibrance)*amount;
                d.color.saturation += (a.saturation-d.color.saturation)*amount;
                Ok(())
            })?;''')
])

photo=PKG/"engine/PhotoFiles.kt"
text=photo.read_text(encoding="utf-8")
if 'Environment.DIRECTORY_PICTURES}/LightCraft' not in text:
    raise RuntimeError("Export path changed; do not patch wrong version")
photo.write_text(text.replace('Pictures/LightCraft','Pictures/Dennis Photo Studio')
    .replace('Environment.DIRECTORY_PICTURES}/LightCraft',
             'Environment.DIRECTORY_PICTURES}/Dennis Photo Studio'),encoding="utf-8")
print("Updated",photo.relative_to(ROOT))
print("Dennis Photo Studio patch applied; baseline Rust photographic pipeline unchanged.")
