package com.zfz.dbd

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Calendar

private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CheckInCalendar(today: String, history: Map<String, Map<String, String>>) {
    val currentDate = remember(today) { Calendar.getInstance() }
    val currentMonth = remember(currentDate) {
        (currentDate.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
    }
    val pageCount = (currentMonth.get(Calendar.YEAR) - 1) * 12 + currentMonth.get(Calendar.MONTH) + 1
    val pager = rememberPagerState { pageCount }
    val scope = rememberCoroutineScope()
    var selectedDay by rememberSaveable(pager.settledPage, today) { mutableStateOf<String?>(null) }
    var selectedLabel by rememberSaveable(pager.settledPage, today) { mutableStateOf("") }
    fun monthAt(page: Int): Calendar = (currentMonth.clone() as Calendar).apply {
        add(Calendar.MONTH, -page)
    }
    val displayed = monthAt(pager.currentPage)
    val detailDay = selectedDay ?: today.takeIf { pager.settledPage == 0 }
    val detailLabel = if (selectedDay != null) selectedLabel else {
        "${currentDate.get(Calendar.MONTH) + 1} 月 ${currentDate.get(Calendar.DAY_OF_MONTH)} 日"
    }

    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(26.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${displayed.get(Calendar.YEAR)} 年 ${displayed.get(Calendar.MONTH) + 1} 月",
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            TextButton(
                enabled = pager.currentPage < pageCount - 1,
                onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
            ) { Text("上月") }
            TextButton(
                enabled = pager.currentPage > 0,
                onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }
            ) { Text("下月") }
        }

        Row(Modifier.fillMaxWidth()) {
            weekdays.forEach { weekday ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(weekday, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalPager(state = pager, reverseLayout = true) { page ->
            val month = monthAt(page)
            val leading = (month.get(Calendar.DAY_OF_WEEK) + 5) % 7
            val last = month.getActualMaximum(Calendar.DAY_OF_MONTH)
            Column {
                repeat(6) { week ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { weekday ->
                            val number = week * 7 + weekday - leading + 1
                            if (number !in 1..last) {
                                Spacer(Modifier.weight(1f).height(41.dp))
                            } else {
                                month.set(Calendar.DAY_OF_MONTH, number)
                                val key = dayKey(month.timeInMillis)
                                val label = "${month.get(Calendar.MONTH) + 1} 月 $number 日"
                                val count = history[key]?.size ?: 0
                                val future = month.after(Calendar.getInstance()) && key != today
                                CalendarDay(
                                    number, label, count, key == today, key == detailDay, future,
                                    Modifier.weight(1f)
                                ) {
                                    selectedDay = key
                                    selectedLabel = label
                                }
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 4.dp), color = colors.primary.copy(alpha = 0.14f))
        val titles = history[detailDay]?.values?.sorted().orEmpty()
        val detail = when {
            detailDay == null -> "有打卡的日期会标记数量，点击日期查看详情"
            titles.isEmpty() -> "$detailLabel · 暂无打卡记录"
            else -> "$detailLabel · 已打卡 ${titles.size} 项"
        }
        Text(detail, Modifier.padding(4.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (titles.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                titles.forEach { title ->
                    Text(
                        text = title,
                        modifier = Modifier
                            .background(colors.primaryContainer.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.78f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        color = colors.onPrimaryContainer,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(
    number: Int,
    label: String,
    count: Int,
    today: Boolean,
    isSelected: Boolean,
    future: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val description = when {
        future -> "未来日期"
        count > 0 -> "已打卡 $count 项"
        else -> "暂无打卡记录"
    }
    val shape = RoundedCornerShape(10.dp)
    val surface = when {
        isSelected -> Modifier
            .background(Brush.verticalGradient(listOf(colors.primary, Color(0xFF124F43))))
            .border(1.dp, Color.White.copy(alpha = 0.44f), shape)
        count > 0 || today -> Modifier
            .background(colors.primaryContainer.copy(alpha = 0.70f))
            .border(1.dp, Color.White.copy(alpha = 0.65f), shape)
        else -> Modifier
    }
    val foreground = when {
        isSelected -> colors.onPrimary
        future -> colors.onSurface.copy(alpha = 0.35f)
        today -> colors.primary
        else -> colors.onSurface
    }
    Box(
        modifier.height(41.dp).padding(2.dp).clip(shape).then(surface)
            .clickable(enabled = !future, onClick = onSelect)
            .semantics {
                contentDescription = "$label，${if (today) "今天，" else ""}$description"
                selected = isSelected
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(number.toString(), color = foreground, style = MaterialTheme.typography.labelLarge,
                fontWeight = if (today || isSelected) FontWeight.Bold else FontWeight.Normal)
            if (count > 0) {
                Text("✓ $count", color = foreground, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}


internal fun Modifier.calendarBackdrop(background: Color): Modifier = drawWithCache {
    val mint = Brush.radialGradient(
        colors = listOf(Color(0xFFB5E6D7).copy(alpha = 0.75f), Color.Transparent),
        center = Offset(size.width * 0.88f, size.height * 0.13f),
        radius = (size.width * 0.84f).coerceAtLeast(1f)
    )
    val blue = Brush.radialGradient(
        colors = listOf(Color(0xFFC5DDF4).copy(alpha = 0.66f), Color.Transparent),
        center = Offset(size.width * 0.08f, size.height * 0.62f),
        radius = (size.width * 0.76f).coerceAtLeast(1f)
    )
    val topFade = Brush.verticalGradient(
        colors = listOf(background, background.copy(alpha = 0f)),
        endY = 72.dp.toPx()
    )
    onDrawBehind {
        drawRect(blue)
        drawRect(mint)
        drawRect(topFade)
    }
}
