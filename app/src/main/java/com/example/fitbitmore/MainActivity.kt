package com.example.fitbitmore

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import coil.compose.AsyncImage
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

val HEALTH_PERMISSIONS = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    HealthPermission.getReadPermission(DistanceRecord::class)
)

enum class AppTheme { SYSTEM, LIGHT, DARK }

data class MetricBlockData(
    val id: String,
    val title: String,
    val value: String,
    val unit: String,
    val accentColor: Color,
    val isHeartRate: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var currentTheme by remember { mutableStateOf(AppTheme.SYSTEM) }

            val useDarkTheme = when (currentTheme) {
                AppTheme.SYSTEM -> isSystemInDarkTheme()
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
            }

            MaterialTheme(
                colorScheme = if (useDarkTheme) darkColorScheme() else lightColorScheme()
            ) {
                FitbitMoreApp(
                    currentTheme = currentTheme,
                    onThemeChange = { currentTheme = it }
                )
            }
        }
    }
}

@Composable
fun FitbitMoreApp(
    currentTheme: AppTheme,
    onThemeChange: (AppTheme) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Today", "AI Chat", "Watch")

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        label = { Text(title) },
                        icon = { }
                    )
                }
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            when (selectedTab) {
                0 -> StatsScreen(currentTheme = currentTheme, onThemeChange = onThemeChange)
                1 -> AIScreen()
                2 -> DeviceScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun StatsScreen(
    currentTheme: AppTheme,
    onThemeChange: (AppTheme) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var totalSteps by remember { mutableLongStateOf(0L) }
    var currentBpm by remember { mutableIntStateOf(0) }
    var avgBpm by remember { mutableIntStateOf(0) }
    var heartRateSamples by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    
    var isPermissionGranted by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showHeartRateDetails by remember { mutableStateOf(false) }

    var currentUser by remember {
        mutableStateOf<GoogleSignInAccount?>(GoogleSignIn.getLastSignedInAccount(context))
    }

    var metricBlocks by remember {
        mutableStateOf(
            listOf(
                MetricBlockData("steps", "Steps", "--", "steps", Color(0xFF4285F4)),
                MetricBlockData("hr", "Heart Rate", "--", "bpm (tap to open)", Color(0xFFEA4335), isHeartRate = true),
                MetricBlockData("sleep", "Sleep", "--", "last night", Color(0xFF5C6BC0)),
                MetricBlockData("energy", "Energy Burned", "--", "kcal", Color(0xFFFBBC05)),
                MetricBlockData("distance", "Distance", "--", "km", Color(0xFF34A853)),
                MetricBlockData("active", "Zone Mins", "--", "active mins", Color(0xFFFF6D00))
            )
        )
    }

    val sdkStatus = remember(context) { HealthConnectClient.getSdkStatus(context) }

    val healthConnectClient = remember(context, sdkStatus) {
        if (sdkStatus == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else null
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HEALTH_PERMISSIONS)) {
            isPermissionGranted = true
        }
    }

    val currentHour = remember { LocalTime.now(ZoneId.systemDefault()).hour }
    val timeGreeting = when (currentHour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }

    val fetchHealthData = suspend {
        if (healthConnectClient != null && isPermissionGranted) {
            try {
                val startTime = Instant.now().truncatedTo(ChronoUnit.DAYS)
                val endTime = Instant.now()
                val timeFilter = TimeRangeFilter.between(startTime, endTime)

                val stepsResponse = healthConnectClient.readRecords(
                    ReadRecordsRequest(recordType = StepsRecord::class, timeRangeFilter = timeFilter)
                )
                totalSteps = stepsResponse.records.sumOf { it.count }

                val hrResponse = healthConnectClient.readRecords(
                    ReadRecordsRequest(recordType = HeartRateRecord::class, timeRangeFilter = timeFilter)
                )
                val allSamples = hrResponse.records.flatMap { it.samples }
                if (allSamples.isNotEmpty()) {
                    currentBpm = allSamples.last().beatsPerMinute.toInt()
                    avgBpm = allSamples.map { it.beatsPerMinute }.average().toInt()
                    val startEpochSec = startTime.epochSecond
                    heartRateSamples = allSamples.map { sample ->
                        Pair((sample.time.epochSecond - startEpochSec) / 3600f, sample.beatsPerMinute.toFloat())
                    }
                } else {
                    currentBpm = 0
                    avgBpm = 0
                    heartRateSamples = emptyList()
                }

                val sleepStartTime = Instant.now().minus(24, ChronoUnit.HOURS)
                val sleepResponse = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(sleepStartTime, endTime)
                    )
                )
                val totalSleepMinutes = sleepResponse.records.sumOf { session ->
                    java.time.Duration.between(session.startTime, session.endTime).toMinutes()
                }
                val sleepString = if (totalSleepMinutes > 0) "${totalSleepMinutes / 60}h ${totalSleepMinutes % 60}m" else "--"

                val caloriesResponse = healthConnectClient.readRecords(
                    ReadRecordsRequest(recordType = TotalCaloriesBurnedRecord::class, timeRangeFilter = timeFilter)
                )
                val totalCalories = caloriesResponse.records.sumOf { it.energy.inKilocalories }.toInt()

                val distanceResponse = healthConnectClient.readRecords(
                    ReadRecordsRequest(recordType = DistanceRecord::class, timeRangeFilter = timeFilter)
                )
                val totalKm = distanceResponse.records.sumOf { it.distance.inKilometers }

                metricBlocks = metricBlocks.map { block ->
                    when (block.id) {
                        "steps" -> block.copy(value = if (totalSteps > 0) "$totalSteps" else "--")
                        "hr" -> block.copy(value = if (currentBpm > 0) "$currentBpm" else "--")
                        "sleep" -> block.copy(value = sleepString)
                        "energy" -> block.copy(value = if (totalCalories > 0) "$totalCalories" else "--")
                        "distance" -> block.copy(value = if (totalKm > 0.0) String.format("%.1f", totalKm) else "--")
                        else -> block
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = {
            coroutineScope.launch {
                isRefreshing = true
                fetchHealthData()
                isRefreshing = false
            }
        }
    )

    val healthScope = Scope("https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly")

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        if (task.isSuccessful) {
            currentUser = task.result
        }
    }

    LaunchedEffect(isPermissionGranted) {
        if (healthConnectClient != null) {
            try {
                val granted = healthConnectClient.permissionController.getGrantedPermissions()
                if (granted.containsAll(HEALTH_PERMISSIONS)) {
                    isPermissionGranted = true
                    fetchHealthData()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Settings") },
            text = {
                Column {
                    Text("App Theme", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = currentTheme == AppTheme.SYSTEM,
                            onClick = { onThemeChange(AppTheme.SYSTEM) }
                        )
                        Text("Device default", modifier = Modifier.clickable { onThemeChange(AppTheme.SYSTEM) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = currentTheme == AppTheme.LIGHT,
                            onClick = { onThemeChange(AppTheme.LIGHT) }
                        )
                        Text("Light", modifier = Modifier.clickable { onThemeChange(AppTheme.LIGHT) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = currentTheme == AppTheme.DARK,
                            onClick = { onThemeChange(AppTheme.DARK) }
                        )
                        Text("Dark", modifier = Modifier.clickable { onThemeChange(AppTheme.DARK) })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    AnimatedContent(
        targetState = showHeartRateDetails,
        transitionSpec = {
            if (targetState) {
                slideInHorizontally(animationSpec = tween(300), initialOffsetX = { fullWidth -> fullWidth }) togetherWith
                        slideOutHorizontally(animationSpec = tween(300), targetOffsetX = { fullWidth -> -fullWidth })
            } else {
                slideInHorizontally(animationSpec = tween(300), initialOffsetX = { fullWidth -> -fullWidth }) togetherWith
                        slideOutHorizontally(animationSpec = tween(300), targetOffsetX = { fullWidth -> fullWidth })
            }
        },
        label = "ScreenTransition"
    ) { isDetail ->
        if (isDetail) {
            HeartRateDetailScreen(
                currentBpm = currentBpm,
                avgBpm = avgBpm,
                heartRateSamples = heartRateSamples,
                onBackClick = { showHeartRateDetails = false }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pullRefresh(pullRefreshState)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column {
                            Text(
                                text = "$timeGreeting,",
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            currentUser?.displayName?.let { name ->
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Today",
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }

                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.size(48.dp)
                            ) {
                                currentUser?.photoUrl?.let { url ->
                                    AsyncImage(
                                        model = url,
                                        contentDescription = "Profile Picture",
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                    )
                                } ?: run {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "G",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                if (currentUser == null) {
                                    DropdownMenuItem(
                                        text = { Text("Sign in with Google") },
                                        onClick = {
                                            showMenu = false
                                            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                                .requestScopes(healthScope)
                                                .requestEmail()
                                                .build()
                                            val client = GoogleSignIn.getClient(context, gso)
                                            googleSignInLauncher.launch(client.signInIntent)
                                        }
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("Sign out") },
                                        onClick = {
                                            showMenu = false
                                            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                                            val client = GoogleSignIn.getClient(context, gso)
                                            client.signOut().addOnCompleteListener {
                                                currentUser = null
                                                totalSteps = 0L
                                                currentBpm = 0
                                                avgBpm = 0
                                                heartRateSamples = emptyList()
                                                metricBlocks = metricBlocks.map { it.copy(value = "--") }
                                                isPermissionGranted = false
                                            }
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = {
                                        showMenu = false
                                        showSettingsDialog = true
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // --- Health Connect Status & Permission Banners ---
                    when (sdkStatus) {
                        HealthConnectClient.SDK_UNAVAILABLE,
                        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Health Connect Required",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Text(
                                            text = "Please install or update Health Connect from the Play Store to sync data.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            val packageName = "com.google.android.apps.healthdata"
                                            try {
                                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$packageName"))
                                                intent.setPackage("com.android.vending")
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.onErrorContainer,
                                            contentColor = MaterialTheme.colorScheme.errorContainer
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(if (sdkStatus == HealthConnectClient.SDK_UNAVAILABLE) "Install" else "Update")
                                    }
                                }
                            }
                        }
                        HealthConnectClient.SDK_AVAILABLE -> {
                            if (!isPermissionGranted && currentUser != null) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 16.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .padding(16.dp)
                                            .fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Sync Local Device Health Data",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "Connect Health Connect to read steps and heart rate.",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        Button(
                                            onClick = { permissionLauncher.launch(HEALTH_PERMISSIONS) },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text("Connect")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(450.dp)
                    ) {
                        items(metricBlocks, key = { it.id }) { block ->
                            MetricTile(
                                title = block.title,
                                value = block.value,
                                unit = block.unit,
                                accentColor = block.accentColor,
                                onClick = if (block.isHeartRate) { { showHeartRateDetails = true } } else null
                            )
                        }
                    }
                }

                PullRefreshIndicator(
                    refreshing = isRefreshing,
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }
}

@Composable
fun HeartRateDetailScreen(
    currentBpm: Int,
    avgBpm: Int,
    heartRateSamples: List<Pair<Float, Float>>,
    onBackClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Heart Rate Details",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Heart Rate (12 AM – 12 AM)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))

                HeartRateZoneGraph(
                    samples = heartRateSamples,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Heart rate right now: ${if (currentBpm > 0) currentBpm else "--"} BPM",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFEA4335)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Average heart rate: ${if (avgBpm > 0) avgBpm else "--"} BPM",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun HeartRateZoneGraph(
    samples: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = modifier) {
        val yAxisPadding = 45.dp.toPx()
        val graphWidth = size.width - yAxisPadding
        val graphHeight = size.height - 20.dp.toPx()

        val minBpm = 40f
        val maxBpm = 200f

        fun bpmToY(bpm: Float): Float {
            val clamped = bpm.coerceIn(minBpm, maxBpm)
            return graphHeight - ((clamped - minBpm) / (maxBpm - minBpm) * graphHeight)
        }

        drawRect(color = Color(0xFF4285F4).copy(alpha = 0.12f), topLeft = Offset(yAxisPadding, bpmToY(100f)), size = androidx.compose.ui.geometry.Size(graphWidth, graphHeight - bpmToY(100f)))
        drawRect(color = Color(0xFF34A853).copy(alpha = 0.12f), topLeft = Offset(yAxisPadding, bpmToY(140f)), size = androidx.compose.ui.geometry.Size(graphWidth, bpmToY(100f) - bpmToY(140f)))
        drawRect(color = Color(0xFFFBBC05).copy(alpha = 0.15f), topLeft = Offset(yAxisPadding, bpmToY(170f)), size = androidx.compose.ui.geometry.Size(graphWidth, bpmToY(140f) - bpmToY(170f)))
        drawRect(color = Color(0xFFEA4335).copy(alpha = 0.15f), topLeft = Offset(yAxisPadding, 0f), size = androidx.compose.ui.geometry.Size(graphWidth, bpmToY(170f)))

        val bpmTicks = listOf(200, 160, 120, 80, 40)
        bpmTicks.forEach { tick ->
            val y = bpmToY(tick.toFloat())
            drawText(textMeasurer = textMeasurer, text = "$tick", style = TextStyle(fontSize = 10.sp, color = textColor), topLeft = Offset(4.dp.toPx(), y - 8.dp.toPx()))
        }

        if (samples.isNotEmpty()) {
            val highestPoint = samples.maxByOrNull { it.second }
            val lowestPoint = samples.minByOrNull { it.second }

            if (samples.size >= 2) {
                val path = Path()
                val points = samples.map { (hour, bpm) -> Offset(yAxisPadding + (hour / 24f) * graphWidth, bpmToY(bpm)) }
                path.moveTo(points.first().x, points.first().y)
                for (i in 0 until points.size - 1) {
                    val p1 = points[i]
                    val p2 = points[i + 1]
                    val controlX1 = p1.x + (p2.x - p1.x) / 2f
                    val controlY1 = p1.y
                    val controlX2 = p1.x + (p2.x - p1.x) / 2f
                    val controlY2 = p2.y
                    path.cubicTo(controlX1, controlY1, controlX2, controlY2, p2.x, p2.y)
                }
                drawPath(path = path, color = Color(0xFFEA4335), style = Stroke(width = 3.dp.toPx()))

                highestPoint?.let { (hour, bpm) ->
                    val x = yAxisPadding + (hour / 24f) * graphWidth
                    val y = bpmToY(bpm)
                    drawCircle(Color(0xFFEA4335), radius = 5.dp.toPx(), center = Offset(x, y))
                    drawText(textMeasurer = textMeasurer, text = "High: ${bpm.toInt()}", style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEA4335)), topLeft = Offset(x - 15.dp.toPx(), y - 18.dp.toPx()))
                }

                lowestPoint?.let { (hour, bpm) ->
                    val x = yAxisPadding + (hour / 24f) * graphWidth
                    val y = bpmToY(bpm)
                    drawCircle(Color(0xFF4285F4), radius = 5.dp.toPx(), center = Offset(x, y))
                    drawText(textMeasurer = textMeasurer, text = "Low: ${bpm.toInt()}", style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4285F4)), topLeft = Offset(x - 15.dp.toPx(), y + 6.dp.toPx()))
                }
            }
        }
    }
}

@Composable
fun MetricTile(
    title: String,
    value: String,
    unit: String,
    accentColor: Color,
    onClick: (() -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(accentColor))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(text = unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AIScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("AI Chat Screen")
    }
}

@Composable
fun DeviceScreen() {
    val context = LocalContext.current
    
    // Safely wrap Bluetooth call in a try/catch block to prevent crash if permission is denied
    val isBluetoothEnabled = remember {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothManager.adapter?.isEnabled == true
        } catch (e: Exception) {
            false
        }
    }
    
    val myWatchName = "My Smartwatch" 
    var batteryLevel by remember { mutableIntStateOf(84) } 
    var lastSyncTime by remember { mutableStateOf(if (isBluetoothEnabled) "Just now" else "Never") }
    var isSyncing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(text = "My Watch", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))

        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(100.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Surface(modifier = Modifier.size(54.dp, 64.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("10:09", color = MaterialTheme.colorScheme.primaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = myWatchName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                
                FilterChip(
                    selected = isBluetoothEnabled,
                    onClick = { /* Read only from hardware status */ },
                    label = { Text(if (isBluetoothEnabled) "Bluetooth Enabled" else "Bluetooth Disabled") },
                    leadingIcon = {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (isBluetoothEnabled) Color(0xFF34A853) else Color.Red))
                    }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Device Information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                DeviceInfoRow(label = "Battery Level", value = "$batteryLevel%")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                DeviceInfoRow(label = "Registered Date", value = "January 14, 2024")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                DeviceInfoRow(label = "Model Number", value = "Watch-BLE-521")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(text = "Sync Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(text = if (isBluetoothEnabled) "Awaiting Health Connect sync" else "Bluetooth offline", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(
                        onClick = {
                            if (isBluetoothEnabled) {
                                isSyncing = true
                                lastSyncTime = "Just now"
                                isSyncing = false
                            }
                        },
                        enabled = isBluetoothEnabled
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Sync Now")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = if (isBluetoothEnabled) Color(0xFF34A853) else Color.Gray, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Last Synced: $lastSyncTime", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun DeviceInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}