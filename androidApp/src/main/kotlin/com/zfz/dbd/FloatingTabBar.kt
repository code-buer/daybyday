package com.zfz.dbd

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun FloatingTabBar(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    backdrop: GraphicsLayer,
    backdropOrigin: () -> Offset,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val blurLayer = rememberGraphicsLayer()
    val radius = with(LocalDensity.current) { 18.dp.toPx() }
    val blur = remember(radius) { BlurEffect(radius, radius, TileMode.Clamp) }
    SideEffect {
        if (Build.VERSION.SDK_INT >= 31) blurLayer.renderEffect = blur
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val selection by animateFloatAsState(selected.ordinal.toFloat(), spring(dampingRatio = 0.83f))
    val blurredBackdrop = Modifier
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawWithContent {
            if (Build.VERSION.SDK_INT >= 31) {
                val offset = backdropOrigin() - origin
                blurLayer.record {
                    translate(offset.x, offset.y) { drawLayer(backdrop) }
                }
                drawLayer(blurLayer)
            }
            drawContent()
        }

    Box(
        modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 6.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            Modifier.widthIn(max = 380.dp).fillMaxWidth()
                .glassSurface(CircleShape, blurredBackdrop)
                .padding(5.dp)
                .selectableGroup()
        ) {
            val tabWidth = maxWidth / HomeTab.entries.size
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .offset { IntOffset((tabWidth.toPx() * selection).roundToInt(), 0) }
                        .width(tabWidth).fillMaxHeight()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.92f), colors.primaryContainer)
                            ),
                            CircleShape
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.94f), CircleShape)
                )
            }
            Row(Modifier.fillMaxWidth()) {
                HomeTab.entries.forEach { tab ->
                    val active = tab == selected
                    val tint = if (active) colors.primary else colors.onSurfaceVariant
                    Column(
                        Modifier.weight(1f).clip(CircleShape)
                            .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) })
                            .heightIn(min = 52.dp).padding(horizontal = 4.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
                    ) {
                        Icon(painterResource(tab.icon), contentDescription = null, tint = tint,
                            modifier = Modifier.size(21.dp))
                        Text(tab.label, color = tint, style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}


@Composable
internal fun Modifier.glassSurface(shape: Shape, backdrop: Modifier = Modifier): Modifier {
    val tint = MaterialTheme.colorScheme.primary
    return this
        .dropShadow(
            shape = shape,
            shadow = Shadow(
                radius = 14.dp,
                color = Color(0xFF153F32).copy(alpha = 0.14f),
                offset = DpOffset(0.dp, 5.dp)
            )
        )
        .clip(shape)
        .then(backdrop)
        .background(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.75f), Color(0xFFDCEEE8).copy(alpha = 0.60f))
            )
        )
        .border(
            1.dp,
            Brush.verticalGradient(
                listOf(Color.White, tint.copy(alpha = 0.18f), Color.White.copy(alpha = 0.92f))
            ),
            shape
        )
}
