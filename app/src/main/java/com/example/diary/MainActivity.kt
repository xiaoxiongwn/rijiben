package com.example.diary

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.rememberAsyncImagePainter
import com.example.diary.data.DiaryDatabase
import com.example.diary.data.DiaryEntry
import com.example.diary.notification.DiaryForegroundService
import com.example.diary.notification.ReminderScheduler
import com.example.diary.ui.theme.DiaryAppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = DiaryDatabase.getDatabase(this)
        val dao = db.diaryDao()

        // 启动前台服务保活
        DiaryForegroundService.start(this)

        setContent {
            DiaryAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DiaryApp(dao)
                }
            }
        }
    }
}

enum class Screen { PASSWORD, HOME, ADD, DETAIL, SETTINGS, CATEGORY_MGMT }

private const val PREFS_NAME = "diary_prefs"
private const val KEY_PASSWORD = "diary_password"
private const val KEY_UNLOCKED = "diary_unlocked"
private const val KEY_CATEGORIES = "diary_categories"

val DEFAULT_CATEGORIES = listOf("工作", "投资", "生活", "其它")

fun savePassword(context: Context, password: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(KEY_PASSWORD, password).apply()
}

fun getPassword(context: Context): String? {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_PASSWORD, null)
}

fun setUnlocked(context: Context, unlocked: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_UNLOCKED, unlocked).apply()
}

fun isUnlocked(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_UNLOCKED, false)
}

fun clearUnlocked(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_UNLOCKED, false).apply()
}

fun getCategories(context: Context): List<String> {
    val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_CATEGORIES, null)
    return saved?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_CATEGORIES
}

fun saveCategories(context: Context, categories: List<String>) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(KEY_CATEGORIES, categories.joinToString(",")).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryApp(dao: com.example.diary.data.DiaryDao) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.HOME) }
    var selectedEntry by remember { mutableStateOf<DiaryEntry?>(null) }
    // 加密分区会话级解锁，跨页面保持，App 关闭后重置
    var encryptedUnlocked by rememberSaveable { mutableStateOf(false) }

    when (screen) {
        Screen.PASSWORD -> PasswordScreen(
            onUnlock = { screen = Screen.HOME },
            onNoPassword = { screen = Screen.HOME }
        )
        Screen.HOME -> HomeScreen(
            dao = dao,
            encryptedUnlocked = encryptedUnlocked,
            onEncryptedUnlock = { encryptedUnlocked = true },
            onAdd = { screen = Screen.ADD; selectedEntry = null },
            onDetail = { screen = Screen.DETAIL; selectedEntry = it },
            onSettings = { screen = Screen.SETTINGS }
        )
        Screen.ADD -> AddEditScreen(
            entry = selectedEntry,
            dao = dao,
            onBack = { screen = Screen.HOME },
            context = context
        )
        Screen.DETAIL -> DetailScreen(
            entry = selectedEntry!!,
            dao = dao,
            onBack = { screen = Screen.HOME },
            onEdit = { screen = Screen.ADD; selectedEntry = it },
            context = context
        )
        Screen.SETTINGS -> SettingsScreen(
            onBack = { screen = Screen.HOME },
            onCategoryMgmt = { screen = Screen.CATEGORY_MGMT },
            context = context
        )
        Screen.CATEGORY_MGMT -> CategoryMgmtScreen(
            onBack = { screen = Screen.SETTINGS },
            context = context
        )
    }
}

@Composable
fun PasswordScreen(onUnlock: () -> Unit, onNoPassword: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🔒", fontSize = 64.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text("请输入密码", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; error = false },
            label = { Text("密码") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = error,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (error) {
            Text("密码错误", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                if (input == getPassword(context)) {
                    setUnlocked(context, true)
                    onUnlock()
                } else {
                    error = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("解锁") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    dao: com.example.diary.data.DiaryDao,
    encryptedUnlocked: Boolean,
    onEncryptedUnlock: () -> Unit,
    onAdd: () -> Unit,
    onDetail: (DiaryEntry) -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var encryptedPasswordInput by remember { mutableStateOf("") }
    var encryptedPasswordError by remember { mutableStateOf("") }
    val savedPassword = remember { getPassword(context) }
    var searchQuery by remember { mutableStateOf("") }
    var searchMode by remember { mutableStateOf(false) }
    var startDate by remember { mutableStateOf<Long?>(null) }
    var endDate by remember { mutableStateOf<Long?>(null) }
    var showDateSearch by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    val categories = remember { getCategories(context) }

    // "加密"分区如果未解锁，不读取数据
    val showHiddenContent = tab == 2 && (savedPassword.isNullOrBlank() || encryptedUnlocked)

    val entriesFlow = when {
        tab == 2 && !showHiddenContent -> kotlinx.coroutines.flow.flowOf(emptyList())
        selectedCategory != null -> dao.getByCategory(selectedCategory!!, tab == 2)
        isSearching && searchQuery.isNotBlank() -> dao.searchByKeyword(searchQuery, tab == 2)
        isSearching && startDate != null && endDate != null -> dao.searchByDateRange(startDate!!, endDate!!, tab == 2)
        tab == 1 -> dao.getVisible()
        tab == 2 -> dao.getHidden()
        // tab == 0 "全部"：不显示加密日记
        else -> dao.getVisible()
    }
    val entries by entriesFlow.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日记本") },
                actions = {
                    IconButton(onClick = { searchMode = !searchMode; if (!searchMode) { isSearching = false; searchQuery = "" } }) {
                        Icon(if (searchMode) Icons.Default.Close else Icons.Default.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "新增")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (searchMode) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("关键字搜索") },
                        trailingIcon = {
                            IconButton(onClick = { isSearching = true }) {
                                Icon(Icons.Default.Search, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showDateSearch = !showDateSearch }) {
                            Text(if (showDateSearch) "隐藏日期搜索" else "按日期搜索")
                        }
                        TextButton(onClick = {
                            isSearching = false
                            searchQuery = ""
                            startDate = null
                            endDate = null
                            selectedCategory = null
                        }) { Text("清除搜索") }
                    }
                    if (showDateSearch) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val cal = Calendar.getInstance()
                                    DatePickerDialog(context, { _, y, m, d ->
                                        cal.set(y, m, d, 0, 0, 0)
                                        startDate = cal.timeInMillis
                                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (startDate != null) formatDate(startDate!!) else "开始日期") }
                            OutlinedButton(
                                onClick = {
                                    val cal = Calendar.getInstance()
                                    DatePickerDialog(context, { _, y, m, d ->
                                        cal.set(y, m, d, 23, 59, 59)
                                        endDate = cal.timeInMillis
                                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (endDate != null) formatDate(endDate!!) else "结束日期") }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { isSearching = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("按日期搜索") }
                    }
                }
            }

            // 分类标签
            if (categories.isNotEmpty() && !searchMode) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedCategory == null,
                            onClick = { selectedCategory = null },
                            label = { Text("全部") }
                        )
                    }
                    items(categories) { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                            label = { Text(cat) }
                        )
                    }
                }
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }) { Text("全部", modifier = Modifier.padding(12.dp)) }
                Tab(selected = tab == 1, onClick = { tab = 1 }) { Text("普通", modifier = Modifier.padding(12.dp)) }
                Tab(selected = tab == 2, onClick = {
                    tab = 2
                    encryptedPasswordInput = ""
                    encryptedPasswordError = ""
                }) { Text("加密", modifier = Modifier.padding(12.dp)) }
            }

            if (tab == 2 && !savedPassword.isNullOrBlank() && !encryptedUnlocked) {
                // 加密分区密码门
                Column(
                    modifier = Modifier.weight(1f).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("加密日记", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("请输入密码以查看", color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = encryptedPasswordInput,
                        onValueChange = { encryptedPasswordInput = it; encryptedPasswordError = "" },
                        label = { Text("密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (encryptedPasswordError.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(encryptedPasswordError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (encryptedPasswordInput == savedPassword) {
                                onEncryptedUnlock()
                                encryptedPasswordInput = ""
                                encryptedPasswordError = ""
                            } else {
                                encryptedPasswordError = "密码错误"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("解锁") }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { tab = 0 }, modifier = Modifier.fillMaxWidth()) { Text("返回") }
                }
            } else if (entries.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("暂无日记", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(entries) { entry ->
                        EntryCard(entry = entry, onClick = { onDetail(entry) })
                    }
                }
            }
        }
    }
}

@Composable
fun EntryCard(entry: DiaryEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.title.ifBlank { "无标题" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (entry.isHidden) {
                    Icon(Icons.Default.Lock, contentDescription = "加密", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (entry.category.isNotBlank()) {
                Text(
                    text = entry.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = entry.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatDate(entry.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                if (entry.reminderType != "none") {
                    Text("⏰ ${reminderLabel(entry.reminderType)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditScreen(
    entry: DiaryEntry?,
    dao: com.example.diary.data.DiaryDao,
    onBack: () -> Unit,
    context: Context
) {
    var title by remember { mutableStateOf(entry?.title ?: "") }
    var content by remember { mutableStateOf(entry?.content ?: "") }
    var date by remember { mutableStateOf(entry?.date ?: System.currentTimeMillis()) }
    var isHidden by remember { mutableStateOf(entry?.isHidden ?: false) }
    var category by remember { mutableStateOf(entry?.category ?: "") }
    var reminderType by remember { mutableStateOf(entry?.reminderType ?: "none") }
    var reminderTime by remember { mutableStateOf(entry?.reminderTime ?: 0L) }
    var reminderInterval by remember { mutableStateOf(entry?.reminderInterval ?: 0) }
    var imagePaths by remember { mutableStateOf(entry?.imagePaths?.split(",")?.filter { it.isNotBlank() } ?: emptyList()) }

    val categories = remember { getCategories(context) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val newPaths = imagePaths.toMutableList()
        uris.forEach { uri ->
            val path = copyImageToInternal(context, uri)
            if (path != null) newPaths.add(path)
        }
        imagePaths = newPaths
    }

    val scope = rememberCoroutineScope()
    var showCategoryDropdown by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (entry == null) "新增日记" else "编辑日记") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    TextButton(onClick = {
                        scope.launch {
                            val newEntry = DiaryEntry(
                                id = entry?.id ?: 0,
                                title = title,
                                content = content,
                                date = date,
                                imagePaths = imagePaths.joinToString(","),
                                isHidden = isHidden,
                                category = category,
                                reminderType = reminderType,
                                reminderTime = reminderTime,
                                reminderInterval = reminderInterval,
                                createdAt = entry?.createdAt ?: System.currentTimeMillis()
                            )
                            if (entry == null) {
                                val id = dao.insert(newEntry)
                                if (reminderType != "none" && reminderTime > 0) {
                                    ReminderScheduler.schedule(context, newEntry.copy(id = id))
                                }
                            } else {
                                dao.update(newEntry)
                                ReminderScheduler.cancel(context, entry.id)
                                if (reminderType != "none" && reminderTime > 0) {
                                    ReminderScheduler.schedule(context, newEntry)
                                }
                            }
                            onBack()
                        }
                    }) { Text("保存") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val cal = Calendar.getInstance().apply { timeInMillis = date }
                        DatePickerDialog(context, { _, y, m, d ->
                            cal.set(y, m, d)
                            date = cal.timeInMillis
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(formatDate(date)) }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // 分类选择
            ExposedDropdownMenuBox(
                expanded = showCategoryDropdown,
                onExpandedChange = { showCategoryDropdown = it }
            ) {
                OutlinedTextField(
                    value = category,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("分类") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCategoryDropdown) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = showCategoryDropdown,
                    onDismissRequest = { showCategoryDropdown = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("无分类") },
                        onClick = { category = ""; showCategoryDropdown = false }
                    )
                    categories.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(cat) },
                            onClick = { category = cat; showCategoryDropdown = false }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("内容") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp),
                minLines = 5
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("设为隐藏日记")
                Switch(checked = isHidden, onCheckedChange = { isHidden = it })
            }
            Spacer(modifier = Modifier.height(12.dp))

            Text("提醒设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = reminderLabel(reminderType),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("提醒方式") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf(
                        "none" to "不提醒",
                        "once" to "一次性",
                        "hourly" to "每小时一次",
                        "daily" to "每天一次",
                        "weekly" to "每周循环",
                        "monthly" to "每月循环",
                        "yearly" to "每年循环",
                        "custom_days" to "每隔 N 天"
                    ).forEach { (type, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { reminderType = type; expanded = false })
                    }
                }
            }
            if (reminderType == "custom_days") {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = if (reminderInterval > 0) reminderInterval.toString() else "",
                    onValueChange = { v ->
                        reminderInterval = v.filter { it.isDigit() }.toIntOrNull() ?: 0
                    },
                    label = { Text("间隔天数（>0）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (reminderType != "none") {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val cal = Calendar.getInstance()
                        if (reminderTime > 0) cal.timeInMillis = reminderTime
                        DatePickerDialog(context, { _, y, m, d ->
                            cal.set(y, m, d)
                            TimePickerDialog(context, { _, hour, minute ->
                                cal.set(Calendar.HOUR_OF_DAY, hour)
                                cal.set(Calendar.MINUTE, minute)
                                cal.set(Calendar.SECOND, 0)
                                reminderTime = cal.timeInMillis
                            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (reminderTime > 0) formatDateTime(reminderTime) else "选择${if (reminderType == "custom_days" || reminderType == "hourly" || reminderType == "daily") "首次" else ""}提醒时间")
                }
                if (!ReminderScheduler.canScheduleExact(context)) {
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = { ReminderScheduler.openAlarmSettings(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("⚠️ 需要授权精确闹钟权限，点击去设置", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            Text("图片", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            if (imagePaths.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(imagePaths) { path ->
                        Box {
                            Image(
                                painter = rememberAsyncImagePainter(File(path)),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { imagePaths = imagePaths.filter { it != path } },
                                modifier = Modifier
                                    .size(24.dp)
                                    .align(Alignment.TopEnd)
                                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(12.dp))
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "删除", tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            OutlinedButton(
                onClick = { imagePicker.launch("image/*") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("添加图片") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    entry: DiaryEntry,
    dao: com.example.diary.data.DiaryDao,
    onBack: () -> Unit,
    onEdit: (DiaryEntry) -> Unit,
    context: Context
) {
    val scope = rememberCoroutineScope()
    var showDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日记详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = { onEdit(entry) }) { Icon(Icons.Default.Edit, contentDescription = "编辑") }
                    IconButton(onClick = { showDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "删除") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(entry.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDate(entry.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                if (entry.category.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        entry.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (entry.isHidden) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("已加密", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (entry.reminderType != "none") {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("⏰ ${reminderLabel(entry.reminderType)} ${formatDateTime(entry.reminderTime)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(entry.content, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(16.dp))

            val paths = entry.imagePaths.split(",").filter { it.isNotBlank() }
            if (paths.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(paths) { path ->
                        Image(
                            painter = rememberAsyncImagePainter(File(path)),
                            contentDescription = null,
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这篇日记吗？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        ReminderScheduler.cancel(context, entry.id)
                        dao.delete(entry)
                        onBack()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onCategoryMgmt: () -> Unit,
    context: Context
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isIgnoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm.isIgnoringBatteryOptimizations(context.packageName) else true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 电池优化
            Text("后台提醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            if (!isIgnoring) {
                Text("当前未关闭电池优化，提醒可能不准确", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("关闭电池优化（推荐）") }
            } else {
                Text("已关闭电池优化，提醒将正常触发", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(24.dp))

            // 分类管理
            Text("分类管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onCategoryMgmt,
                modifier = Modifier.fillMaxWidth()
            ) { Text("管理分类标签") }
            Spacer(modifier = Modifier.height(24.dp))

            // 数据备份
            Text("数据备份", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            val scope = rememberCoroutineScope()
            var exportMsg by remember { mutableStateOf("") }
            val db = remember { DiaryDatabase.getDatabase(context) }
            Button(
                onClick = {
                    scope.launch {
                        val entries = db.diaryDao().getAll().first()
                        val path = exportDiaries(context, entries)
                        exportMsg = if (path != null) "已导出到: $path" else "导出失败"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("导出日记备份") }
            if (exportMsg.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(exportMsg, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            var importMsg by remember { mutableStateOf("") }
            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    scope.launch {
                        val count = importDiaries(context, uri, db.diaryDao())
                        importMsg = if (count >= 0) "成功导入 $count 篇日记" else "导入失败"
                    }
                }
            }
            Button(
                onClick = { importLauncher.launch("application/json") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("导入日记备份") }
            if (importMsg.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(importMsg, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(24.dp))

            // 密码设置
            Text("密码设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("设置密码后，隐藏日记将需要密码才能查看", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; msg = "" },
                label = { Text("新密码") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it; msg = "" },
                label = { Text("确认密码") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (msg.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(msg, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    if (password.length < 4) {
                        msg = "密码至少4位"
                    } else if (password != confirm) {
                        msg = "两次密码不一致"
                    } else {
                        savePassword(context, password)
                        msg = "密码设置成功"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("设置密码") }

            if (getPassword(context) != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        savePassword(context, "")
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_PASSWORD).apply()
                        msg = "密码已清除"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("清除密码") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryMgmtScreen(onBack: () -> Unit, context: Context) {
    var categories by remember { mutableStateOf(getCategories(context)) }
    var newCat by remember { mutableStateOf("") }
    var editIndex by remember { mutableStateOf(-1) }
    var editValue by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分类管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("现有分类", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            categories.forEachIndexed { index, cat ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (editIndex == index) {
                            OutlinedTextField(
                                value = editValue,
                                onValueChange = { editValue = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = {
                                if (editValue.isNotBlank()) {
                                    categories = categories.toMutableList().apply { set(index, editValue) }
                                    saveCategories(context, categories)
                                }
                                editIndex = -1
                            }) { Icon(Icons.Default.Check, contentDescription = "保存") }
                        } else {
                            Text(cat, modifier = Modifier.weight(1f))
                            Row {
                                IconButton(onClick = { editIndex = index; editValue = cat }) {
                                    Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(20.dp))
                                }
                                IconButton(onClick = {
                                    categories = categories.toMutableList().apply { removeAt(index) }
                                    saveCategories(context, categories)
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("添加新分类", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newCat,
                    onValueChange = { newCat = it },
                    label = { Text("分类名称") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = {
                    if (newCat.isNotBlank() && !categories.contains(newCat)) {
                        categories = categories + newCat
                        saveCategories(context, categories)
                        newCat = ""
                    }
                }) {
                    Icon(Icons.Default.Add, contentDescription = "添加")
                }
            }
        }
    }
}

fun copyImageToInternal(context: Context, uri: Uri): String? {
    return try {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val dir = File(context.filesDir, "images").apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { output ->
            input.copyTo(output)
        }
        input.close()
        file.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun formatDate(time: Long): String {
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(time))
}

fun formatDateTime(time: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
}

fun reminderLabel(type: String): String {
    return when (type) {
        "once" -> "一次性"
        "hourly" -> "每小时一次"
        "daily" -> "每天一次"
        "weekly" -> "每周循环"
        "monthly" -> "每月循环"
        "yearly" -> "每年循环"
        "custom_days" -> "每隔 N 天"
        else -> "不提醒"
    }
}

fun exportDiaries(context: Context, entries: List<DiaryEntry>): String? {
    return try {
        val jsonArray = org.json.JSONArray()
        entries.forEach { entry ->
            val obj = org.json.JSONObject().apply {
                put("title", entry.title)
                put("content", entry.content)
                put("date", entry.date)
                put("imagePaths", entry.imagePaths)
                put("isHidden", entry.isHidden)
                put("category", entry.category)
                put("reminderType", entry.reminderType)
                put("reminderTime", entry.reminderTime)
                put("reminderInterval", entry.reminderInterval)
                put("createdAt", entry.createdAt)
            }
            jsonArray.put(obj)
        }

        val backupDir = File(context.getExternalFilesDir(null), "DiaryBackup")
        backupDir.mkdirs()
        val file = File(backupDir, "diary_backup_${System.currentTimeMillis()}.json")
        file.writeText(jsonArray.toString(2))
        file.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

suspend fun importDiaries(context: Context, uri: Uri, dao: com.example.diary.data.DiaryDao): Int {
    return try {
        val jsonText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return -1
        val jsonArray = org.json.JSONArray(jsonText)
        var count = 0
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val entry = DiaryEntry(
                id = 0,
                title = obj.optString("title", ""),
                content = obj.optString("content", ""),
                date = obj.optLong("date", System.currentTimeMillis()),
                imagePaths = obj.optString("imagePaths", ""),
                isHidden = obj.optBoolean("isHidden", false),
                category = obj.optString("category", ""),
                reminderType = obj.optString("reminderType", "none"),
                reminderTime = obj.optLong("reminderTime", 0),
                reminderInterval = obj.optInt("reminderInterval", 0),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            )
            dao.insert(entry)
            count++
        }
        count
    } catch (e: Exception) {
        e.printStackTrace()
        -1
    }
}
