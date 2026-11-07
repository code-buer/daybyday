package com.zfz.dbd

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val reminders by lazy { Reminders(this) }
    private val store by lazy { ReminderStore(this) }
    private var items by mutableStateOf(emptyList<CheckIn>())
    private var history by mutableStateOf(emptyMap<String, Map<String, String>>())
    private var today by mutableStateOf(dayKey())
    private var hasNotifications by mutableStateOf(false)
    private var hasExactAlarms by mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    private val changes = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LaunchedEffect(Unit) {
                while (true) {
                    delay(15_000)
                    val currentDay = dayKey()
                    if (today != currentDay) refresh()
                }
            }
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF086A55),
                primaryContainer = Color(0xFFD8F3E8),
                onPrimaryContainer = Color(0xFF123A30),
                background = Color(0xFFF4F7F4),
                surface = Color.White
            )) {
                CheckInScreen(items, history, today, hasNotifications, hasExactAlarms,
                    onNotifications = ::enableNotifications,
                    onExact = ::enableExactAlarms,
                    onSave = {
                        reminders.save(it)
                        if (!hasNotifications && Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onDone = { reminders.complete(it.id, today) },
                    onDelete = { reminders.delete(it.id) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("daybyday", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changes)
        reminders.reconcile()
        refresh()
    }

    override fun onPause() {
        getSharedPreferences("daybyday", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(changes)
        super.onPause()
    }

    private fun refresh() {
        items = store.read().sortedBy { it.timeLabel }
        history = store.readHistory()
        today = dayKey()
        hasNotifications = reminders.notificationsEnabled()
        hasExactAlarms = reminders.canScheduleExactly()
    }

    private fun enableNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun enableExactAlarms() {
        if (Build.VERSION.SDK_INT >= 31) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
    }
}

internal enum class HomeTab(val label: String, val icon: Int) {
    TODAY("今日打卡", R.drawable.ic_check_in),
    CALENDAR("日历", R.drawable.ic_calendar),
    PROFILE("我的", R.drawable.ic_profile)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckInScreen(
    items: List<CheckIn>,
    history: Map<String, Map<String, String>>,
    today: String,
    hasNotifications: Boolean,
    hasExactAlarms: Boolean,
    onNotifications: () -> Unit,
    onExact: () -> Unit,
    onSave: (CheckIn) -> Unit,
    onDone: (CheckIn) -> Unit,
    onDelete: (CheckIn) -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.TODAY) }
    val tabState = rememberSaveableStateHolder()
    BackHandler(enabled = tab != HomeTab.TODAY) { tab = HomeTab.TODAY }

    var addMenuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CheckIn?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CheckIn?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(tab.label, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    if (tab == HomeTab.TODAY) Box {
                        IconButton(onClick = { addMenuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = "添加")
                        }
                        DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("新建打卡") },
                                onClick = {
                                    addMenuOpen = false
                                    editing = null
                                    showEditor = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        val backdrop = rememberGraphicsLayer()
        var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
        var dockHeight by remember { mutableIntStateOf(0) }
        val bottomClearance = with(LocalDensity.current) { dockHeight.toDp() }

        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Box(
                Modifier.matchParentSize()
                    .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
                    .drawWithContent {
                        if (Build.VERSION.SDK_INT >= 31) {
                            backdrop.record { this@drawWithContent.drawContent() }
                            drawLayer(backdrop)
                        } else {
                            drawContent()
                        }
                    }
                    .background(MaterialTheme.colorScheme.background)
            ) {
                tabState.SaveableStateProvider(tab.name) {
                    when (tab) {
                        HomeTab.TODAY -> TodayScreen(
                            items, today, bottomClearance, hasNotifications, hasExactAlarms,
                            onNotifications, onExact, onDone,
                            onEdit = { editing = it; showEditor = true },
                            onDelete = { deleting = it }
                        )
                        HomeTab.CALENDAR -> Column(
                            Modifier.fillMaxSize()
                                .calendarBackdrop(MaterialTheme.colorScheme.background)
                                .verticalScroll(rememberScrollState())
                                .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp + bottomClearance)
                        ) {
                            CheckInCalendar(today, history)
                        }
                        HomeTab.PROFILE -> ProfileScreen(bottomClearance)
                    }
                }
            }
            FloatingTabBar(
                selected = tab,
                onSelect = { tab = it },
                backdrop = backdrop,
                backdropOrigin = { backdropOrigin },
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { dockHeight = it.height }
            )
        }
    }

    if (showEditor) CheckInEditor(editing, onDismiss = { showEditor = false }) {
        showEditor = false
        onSave(it)
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${item.title}」？") },
            text = { Text("将删除该事项及其提醒和所有打卡记录。") },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(item) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun TodayScreen(
    items: List<CheckIn>,
    today: String,
    bottomClearance: Dp,
    hasNotifications: Boolean,
    hasExactAlarms: Boolean,
    onNotifications: () -> Unit,
    onExact: () -> Unit,
    onDone: (CheckIn) -> Unit,
    onEdit: (CheckIn) -> Unit,
    onDelete: (CheckIn) -> Unit
) {
    val completed = items.count { it.completedDay == today }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp + bottomClearance),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(DateFormat.getDateInstance(DateFormat.FULL, Locale.SIMPLIFIED_CHINESE).format(Date()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("今日进度", style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(
                        progress = { if (items.isEmpty()) 0f else completed.toFloat() / items.size },
                        modifier = Modifier.weight(1f)
                    )
                    Text("$completed / ${items.size}", style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Text("每日事项", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        if (items.isEmpty()) {
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("添加第一项打卡", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("点右上角的 +，添加想每天完成的事项。")
                    }
                }
            }
        }
        items(items, key = { it.id }) { item ->
            CheckInRow(
                item = item,
                done = item.completedDay == today,
                onDone = { onDone(item) },
                onEdit = { onEdit(item) },
                onDelete = { onDelete(item) }
            )
        }
        if (items.isNotEmpty() && (!hasNotifications || !hasExactAlarms)) {
            item {
                Card {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        if (!hasNotifications) TextButton(onClick = onNotifications) { Text("开启通知提醒") }
                        if (!hasExactAlarms) TextButton(onClick = onExact) { Text("允许精确定时 · 当前提醒可能延后") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(bottomClearance: Dp) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("profile", Context.MODE_PRIVATE) }
    var nickname by rememberSaveable { mutableStateOf(preferences.getString("nickname", "").orEmpty()) }
    var draft by rememberSaveable { mutableStateOf(nickname) }
    var editing by rememberSaveable { mutableStateOf(false) }

    var widgetSettings by rememberSaveable { mutableStateOf(false) }
    var showWidgetInstructions by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp + bottomClearance),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(painterResource(R.drawable.ic_profile), contentDescription = null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(nickname.ifBlank { "我的资料" }, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text("本机资料", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { draft = nickname; editing = true }) { Text("编辑昵称") }
            }
        }
        Text("昵称和打卡记录保存在这台设备上。", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            ListItem(
                headlineContent = { Text("桌面小部件") },
                supportingContent = { Text("在桌面查看今日进度和逐项打卡") },
                leadingContent = { Icon(painterResource(R.drawable.ic_calendar), contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { showWidgetInstructions = false; widgetSettings = true }
            )
        }
    }

    if (widgetSettings) {
        AlertDialog(
            onDismissRequest = { widgetSettings = false },
            title = { Text("桌面小部件") },
            text = {
                Text(if (showWidgetInstructions) {
                    "长按桌面空白处，在小组件列表中找到 Daybyday 的「今日打卡」，然后拖到桌面。"
                } else {
                    "显示今日打卡进度。每页最多 3 个事项，可以用箭头翻页并直接确认打卡。"
                })
            },
            confirmButton = {
                if (showWidgetInstructions) {
                    TextButton(onClick = { widgetSettings = false }) { Text("知道了") }
                } else {
                    TextButton(onClick = {
                        if (CheckInWidget.requestPin(context)) widgetSettings = false
                        else showWidgetInstructions = true
                    }) { Text("添加小部件") }
                }
            },
            dismissButton = {
                if (!showWidgetInstructions) {
                    TextButton(onClick = { widgetSettings = false }) { Text("取消") }
                }
            }
        )
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("编辑昵称") },
            text = {
                OutlinedTextField(draft, { draft = it }, label = { Text("昵称") }, singleLine = true,
                    supportingText = { Text("最多 30 个字符，留空使用默认名称") })
            },
            confirmButton = {
                TextButton(enabled = draft.trim().length <= 30, onClick = {
                    nickname = draft.trim()
                    preferences.edit().putString("nickname", nickname).apply()
                    editing = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun CheckInRow(
    item: CheckIn,
    done: Boolean,
    onDone: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "${item.timeLabel} · 每 ${item.intervalLabel}提醒 · ${item.sound.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (done) {
                Text("✓ 已打卡", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            } else {
                TextButton(onClick = onDone) { Text("确认打卡") }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(painterResource(R.drawable.ic_more), contentDescription = "管理${item.title}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("编辑") }, onClick = { menuOpen = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun CheckInEditor(initial: CheckIn?, onDismiss: () -> Unit, onSave: (CheckIn) -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var hour by remember { mutableIntStateOf(initial?.hour ?: 8) }
    var minute by remember { mutableIntStateOf(initial?.minute ?: 0) }
    val initialInterval = initial?.interval ?: 30
    var hours by remember { mutableStateOf(initialInterval % 60 == 0) }
    var count by remember { mutableStateOf((if (hours) initialInterval / 60 else initialInterval).toString()) }
    var sound by remember { mutableStateOf(initial?.sound ?: ReminderSound.DEFAULT) }
    var soundsExpanded by remember { mutableStateOf(false) }
    val units = count.toIntOrNull() ?: 0
    val valid = units in 1..(if (hours) 23 else 1439)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建打卡" else "编辑打卡") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it.take(60) }, label = { Text("事项名称") }, singleLine = true)
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, h, m -> hour = h; minute = m }, hour, minute, true).show()
                }) { Text("每天 " + String.format(Locale.ROOT, "%02d:%02d", hour, minute)) }
                OutlinedTextField(count, { count = it.filter { ch -> ch in '0'..'9' }.take(4) },
                    label = { Text("未打卡时的提醒间隔") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !hours, onClick = { hours = false }, label = { Text("分钟") })
                    FilterChip(selected = hours, onClick = { hours = true }, label = { Text("小时") })
                }
                Box {
                    OutlinedButton(onClick = { soundsExpanded = true }) { Text("提醒声音 · ${sound.label}") }
                    DropdownMenu(expanded = soundsExpanded, onDismissRequest = { soundsExpanded = false }) {
                        ReminderSound.entries.forEach { choice ->
                            DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                                sound = choice
                                soundsExpanded = false
                            })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && valid, onClick = {
                onSave(CheckIn(initial?.id ?: UUID.randomUUID().toString(), title.trim(), hour, minute,
                    if (hours) units * 60 else units, sound, initial?.completedDay.orEmpty()))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
