package ai.storyteller.lightcraft.ui

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
