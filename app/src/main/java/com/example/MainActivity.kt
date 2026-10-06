package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.engine.AutomationState
import com.example.service.OverlayService
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.RedBackground
import com.example.ui.theme.RedCardGradientBottom
import com.example.ui.theme.RedCardGradientTop
import com.example.ui.theme.RedGradientEnd
import com.example.ui.theme.RedGradientStart
import com.example.ui.theme.RedOutline
import com.example.ui.theme.RedPrimary
import com.example.ui.theme.RedSecondary
import com.example.ui.theme.RedSurface
import com.example.ui.theme.RedSurfaceVariant
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                RedAutomationMainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedAutomationMainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // State observers from AutomationState
    val updateTick by AutomationState.stateUpdateTick.collectAsState()
    val logsList by AutomationState.logsFlow.collectAsState()

    var targetPackage by remember { mutableStateOf(AutomationState.target(context)) }
    var installedApps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showAppPicker by remember { mutableStateOf(false) }
    var appFilterQuery by remember { mutableStateOf("") }
    var showAiSettingsDialog by remember { mutableStateOf(false) }
    var overlayActive by remember { mutableStateOf(OverlayService.active) }

    // Query installed applications and detect root on launch
    LaunchedEffect(Unit) {
        com.example.engine.RootEngine.detectRoot { granted ->
            if (granted) {
                com.example.engine.RootEngine.enableAccessibilityViaRoot(context)
            }
        }
        val pm = context.packageManager
        installedApps = pm.getInstalledApplications(PackageManager.MATCH_ALL)
            .filter { it.packageName != context.packageName }
            .map {
                val label = it.loadLabel(pm)?.toString()?.ifBlank { it.packageName } ?: it.packageName
                label to it.packageName
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase(Locale.ROOT) }
    }

    val statusColor by animateColorAsState(
        targetValue = when (AutomationState.status) {
            "RUNNING" -> Color(0xFFFF1744)
            "PAUSED" -> Color(0xFFFF9100)
            else -> Color(0xFF8A1322)
        },
        label = "statusColor"
    )

    // Pulsing indicator for active state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(RedBackground),
        contentWindowInsets = WindowInsets.statusBars
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1B0306),
                            Color(0xFF0C0204),
                            Color(0xFF140205)
                        )
                    )
                )
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: Cyber Red Automator Branding
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (AutomationState.running && !AutomationState.paused)
                                        statusColor.copy(alpha = pulseAlpha)
                                    else statusColor
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "P.R AUTOMATION",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp,
                            color = Color(0xFFFF2A55)
                        )
                    }
                    Text(
                        text = "Advanced Target Quiz Engine • Full Red Edition",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFFF99A8)
                    )
                }

                // Status chip
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = statusColor.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.8f))
                ) {
                    Text(
                        text = AutomationState.status,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // 1. TARGET APP CARD
            RedCyberCard(title = "TARGET APP", icon = Icons.Default.Apps) {
                OutlinedTextField(
                    value = targetPackage,
                    onValueChange = {
                        targetPackage = it
                        AutomationState.setTarget(context, it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("target_package_input"),
                    label = { Text("Target App Package", color = Color(0xFFFFB4BF)) },
                    placeholder = { Text("e.g. com.example.quizapp", color = Color(0xFF8A5A60)) },
                    trailingIcon = {
                        IconButton(onClick = { showAppPicker = true }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Pick Installed App",
                                tint = RedPrimary
                            )
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RedPrimary,
                        unfocusedBorderColor = RedOutline,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color(0xFF1F0509),
                        unfocusedContainerColor = Color(0xFF160306)
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (targetPackage.isBlank()) {
                                Toast.makeText(context, "Please select or type target package", Toast.LENGTH_SHORT).show()
                            } else {
                                AutomationState.setTarget(context, targetPackage)
                                Toast.makeText(context, "Target Saved: $targetPackage", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_target_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF990D24)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("SAVE TARGET", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val pkg = targetPackage.trim()
                            if (pkg.isBlank()) {
                                Toast.makeText(context, "Select target app first", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                            if (intent != null) {
                                context.startActivity(intent)
                            } else {
                                Toast.makeText(context, "App cannot be launched directly: $pkg", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("open_target_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RedPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("LAUNCH APP", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }

            // 2. AUTOMATION CONTROLS CARD
            RedCyberCard(title = "AUTOMATION CONTROLS", icon = Icons.Default.Settings) {
                // Root vs Standard mode banner
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (AutomationState.isRooted) Color(0xFF2E050B) else Color(0xFF1D0407),
                    border = BorderStroke(1.dp, if (AutomationState.isRooted) Color(0xFFFF1744) else Color(0xFF4C0E16)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (AutomationState.isRooted) "⚡ ROOT MODE:" else "🛡 MODE:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = if (AutomationState.isRooted) Color(0xFFFF3355) else Color(0xFFFF99A8)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (AutomationState.isRooted)
                                "SuperSU / Root Active (Zero-Permission Auto Mode)"
                            else
                                "Non-Root Mode (Accessibility Service)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Live status display
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF220509),
                    border = BorderStroke(1.dp, Color(0xFF4C0E16)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Current Status",
                                fontSize = 11.sp,
                                color = Color(0xFFFFB4BD)
                            )
                            Text(
                                text = "● ${AutomationState.status}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = statusColor
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Action",
                                fontSize = 11.sp,
                                color = Color(0xFFFFB4BD)
                            )
                            Text(
                                text = AutomationState.action.ifBlank { "Idle" },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFFFE0E4)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Large Launch / Start primary button
                Button(
                    onClick = {
                        val pkg = targetPackage.trim()
                        if (com.example.engine.RootEngine.isRootGranted) {
                            com.example.engine.RootEngine.enableAccessibilityViaRoot(context)
                        } else {
                            if (com.example.service.QuizAccessibilityService.instance == null) {
                                Toast.makeText(context, "Turn ON Accessibility for P.R Automation", Toast.LENGTH_SHORT).show()
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        }
                        AutomationState.start()
                        if (pkg.isNotBlank()) {
                            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                            if (intent != null) {
                                context.startActivity(intent)
                            } else {
                                Toast.makeText(context, "Cannot launch app directly: $pkg", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("open_quiz_app_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RedPrimary
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "▶  OPEN QUIZ APP & START",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 4 Controls Row: START | PAUSE | RESUME | STOP
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RedActionButton("▶ START", Color(0xFFD50020), Modifier.weight(1f)) {
                        if (com.example.engine.RootEngine.isRootGranted) {
                            com.example.engine.RootEngine.enableAccessibilityViaRoot(context)
                        }
                        AutomationState.start()
                    }
                    RedActionButton("⏸ PAUSE", Color(0xFFC75000), Modifier.weight(1f)) {
                        AutomationState.pause()
                    }
                    RedActionButton("▶ RESUME", Color(0xFFB51025), Modifier.weight(1f)) {
                        AutomationState.resume()
                    }
                    RedActionButton("■ STOP", Color(0xFF550810), Modifier.weight(1f)) {
                        AutomationState.stop()
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Floating HUD & Settings Toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(context, OverlayService::class.java)
                            if (OverlayService.active) {
                                context.stopService(intent)
                                overlayActive = false
                            } else {
                                if (com.example.engine.RootEngine.isRootGranted) {
                                    com.example.engine.RootEngine.executeSu("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
                                }
                                if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context) && !com.example.engine.RootEngine.isRootGranted) {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                } else {
                                    if (Build.VERSION.SDK_INT >= 26) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                    overlayActive = true
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("floating_hud_button"),
                        border = BorderStroke(1.dp, RedOutline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFFF6680)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (overlayActive) "HUD: ON" else "FLOATING HUD",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            if (com.example.engine.RootEngine.isRootGranted) {
                                com.example.engine.RootEngine.enableAccessibilityViaRoot(context)
                                Toast.makeText(context, "⚡ Root Active: Accessibility auto-enabled via SuperSU without opening settings!", Toast.LENGTH_LONG).show()
                            } else {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("accessibility_settings_button"),
                        border = BorderStroke(1.dp, RedOutline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFFF6680)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Accessibility, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ACCESSIBILITY", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedButton(
                    onClick = { showAiSettingsDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("multi_ai_settings_button"),
                    border = BorderStroke(1.dp, Color(0xFFFF2A4D)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF23050A),
                        contentColor = Color(0xFFFF8598)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = RedPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("🤖 MULTI-AI ENGINE SETTINGS (GROQ & GEMINI)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // 3. QUESTIONS STATS CARD
            RedCyberCard(title = "SOLVED STATISTICS", icon = Icons.Default.AutoAwesome) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "SOLVED",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF99AA)
                        )
                        Text(
                            text = "${AutomationState.solved}",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFFF1744)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val total = AutomationState.solved + AutomationState.failed
                        val winRate = if (total > 0) (AutomationState.solved * 100) / total else 100
                        Text(
                            text = "ACCURACY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF99AA)
                        )
                        Text(
                            text = "$winRate%",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFFF5252)
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "FAILED",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF99AA)
                        )
                        Text(
                            text = "${AutomationState.failed}",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF751520)
                        )
                    }
                }

                if (AutomationState.lastQuestion.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1C0407),
                        border = BorderStroke(1.dp, Color(0xFF4C0F17)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "LAST QUESTION:",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF8595)
                            )
                            Text(
                                text = AutomationState.lastQuestion,
                                fontSize = 12.sp,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (AutomationState.lastAnswer.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "SELECTED: ${AutomationState.lastAnswer}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFF2A55)
                                )
                            }
                        }
                    }
                }
            }

            // 4. ENGINE LOGS CARD (Scrollable & auto-scrolling terminal)
            RedCyberCard(title = "ENGINE LOGS", icon = Icons.Default.Refresh) {
                val listState = rememberLazyListState()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${logsList.size} events recorded",
                        fontSize = 11.sp,
                        color = Color(0xFFFF99A8)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = { AutomationState.clearLogs() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("CLEAR", fontSize = 11.sp, color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F0204))
                        .border(1.dp, Color(0xFF4C0E16), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    if (logsList.isEmpty()) {
                        Text(
                            text = "Engine ready. Click START to begin processing quiz questions...",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF7A474D),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(logsList) { logLine ->
                                Text(
                                    text = logLine,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = when {
                                        logLine.contains("Answer", true) -> Color(0xFFFF4968)
                                        logLine.contains("Question", true) -> Color(0xFFFFB4BD)
                                        logLine.contains("Error", true) || logLine.contains("failed", true) -> Color(0xFFFF3B30)
                                        logLine.contains("started", true) -> Color(0xFFFF2A55)
                                        else -> Color(0xFFE8D0D3)
                                    }
                                )
                            }
                        }

                        // Auto-scroll to bottom when new logs arrive
                        LaunchedEffect(logsList.size) {
                            if (logsList.isNotEmpty()) {
                                listState.animateScrollToItem(logsList.size - 1)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // App Picker Dialog
    if (showAppPicker) {
        Dialog(onDismissRequest = { showAppPicker = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1C0508),
                border = BorderStroke(2.dp, RedPrimary)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "SELECT INSTALLED APP",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = appFilterQuery,
                        onValueChange = { appFilterQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search by name or package...", color = Color.Gray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RedPrimary,
                            unfocusedBorderColor = RedOutline,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    val filtered = installedApps.filter {
                        it.first.contains(appFilterQuery, ignoreCase = true) ||
                                it.second.contains(appFilterQuery, ignoreCase = true)
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filtered) { (name, pkg) ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        targetPackage = pkg
                                        AutomationState.setTarget(context, pkg)
                                        showAppPicker = false
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF2B070D)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(text = name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                                    Text(text = pkg, color = Color(0xFFFF99A8), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { showAppPicker = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("CLOSE", color = RedPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Multi-AI Settings Dialog
    if (showAiSettingsDialog) {
        RedMultiAiSettingsDialog(context = context) {
            showAiSettingsDialog = false
        }
    }
}

@Composable
fun RedCyberCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF170407)
        ),
        border = BorderStroke(1.dp, Color(0xFF4C0E16))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = RedPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = Color(0xFFFF8595)
                )
            }
            content()
        }
    }
}

@Composable
fun RedActionButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
fun RedMultiAiSettingsDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val prefs = AutomationState.prefs(context)

    // Load Groq Keys
    val groqKeys = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            runCatching {
                val a = JSONArray(prefs.getString("groq_keys", "[]") ?: "[]")
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val k = o.optString("key", "").trim()
                    val m = o.optString("model", "openai/gpt-oss-20b").trim()
                    if (k.isNotBlank()) add(k to m)
                }
            }
            if (isEmpty()) {
                val old = prefs.getString("groq_key", "")?.trim().orEmpty()
                if (old.isNotBlank()) add(old to (prefs.getString("groq_model", "openai/gpt-oss-20b") ?: "openai/gpt-oss-20b"))
                else if (com.example.BuildConfig.GROQ_API_KEY.isNotBlank() && com.example.BuildConfig.GROQ_API_KEY != "your_groq_api_key") add(com.example.BuildConfig.GROQ_API_KEY to "openai/gpt-oss-20b")
                else add("" to "openai/gpt-oss-20b")
            }
        }
    }

    // Load Gemini Keys
    val geminiKeys = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            runCatching {
                val a = JSONArray(prefs.getString("gemini_keys", "[]") ?: "[]")
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val k = o.optString("key", "").trim()
                    val m = o.optString("model", "gemini-3.8-flash").trim()
                    if (k.isNotBlank()) add(k to m)
                }
            }
            if (isEmpty()) {
                val old = prefs.getString("gemini_key", "")?.trim().orEmpty()
                if (old.isNotBlank()) add(old to (prefs.getString("gemini_model", "gemini-3.8-flash") ?: "gemini-3.8-flash"))
                else if (com.example.BuildConfig.GEMINI_API_KEY.isNotBlank() && com.example.BuildConfig.GEMINI_API_KEY != "your_gemini_api_key") add(com.example.BuildConfig.GEMINI_API_KEY to "gemini-3.8-flash")
                else add("" to "gemini-3.8-flash")
            }
        }
    }

    fun saveAll() {
        val groqArr = JSONArray()
        groqKeys.filter { it.first.isNotBlank() }.forEach {
            groqArr.put(JSONObject().put("key", it.first.trim()).put("model", it.second.trim().ifBlank { "openai/gpt-oss-20b" }))
        }
        val geminiArr = JSONArray()
        geminiKeys.filter { it.first.isNotBlank() }.forEach {
            geminiArr.put(JSONObject().put("key", it.first.trim()).put("model", it.second.trim().ifBlank { "gemini-3.8-flash" }))
        }
        prefs.edit()
            .putString("groq_keys", groqArr.toString())
            .putString("gemini_keys", geminiArr.toString())
            .apply()
        AutomationState.log("Saved AI keys: Groq (${groqArr.length()}), Gemini (${geminiArr.length()})")
        Toast.makeText(context, "AI Settings Saved!", Toast.LENGTH_SHORT).show()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF160306),
            border = BorderStroke(2.dp, RedPrimary)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "MULTI-AI SOLVER SETTINGS",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFFF2A55)
                )
                Text(
                    text = "Keys stored securely on device. Engine tries Groq → Gemini → Local Cache.",
                    fontSize = 11.sp,
                    color = Color(0xFFFFB4BD)
                )

                // 1. GROQ SECTION
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("GROQ API KEYS", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    IconButton(onClick = { groqKeys.add("" to "openai/gpt-oss-20b") }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Groq Key", tint = RedPrimary)
                    }
                }

                groqKeys.forEachIndexed { idx, pair ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF24060B),
                        border = BorderStroke(1.dp, Color(0xFF4C0E16)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Key #${idx + 1}", fontSize = 11.sp, color = Color(0xFFFF99A8), fontWeight = FontWeight.Bold)
                                if (groqKeys.size > 1) {
                                    IconButton(
                                        onClick = { groqKeys.removeAt(idx) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = pair.first,
                                onValueChange = { groqKeys[idx] = it to pair.second },
                                label = { Text("Groq API Key", fontSize = 11.sp) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = RedPrimary,
                                    unfocusedBorderColor = RedOutline,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = pair.second,
                                onValueChange = { groqKeys[idx] = pair.first to it },
                                label = { Text("Model (e.g. openai/gpt-oss-20b)", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = RedPrimary,
                                    unfocusedBorderColor = RedOutline,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                        }
                    }
                }

                // 2. GEMINI SECTION
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("GEMINI API KEYS", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    IconButton(onClick = { geminiKeys.add("" to "gemini-3.8-flash") }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Gemini Key", tint = RedPrimary)
                    }
                }

                geminiKeys.forEachIndexed { idx, pair ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF24060B),
                        border = BorderStroke(1.dp, Color(0xFF4C0E16)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Key #${idx + 1}", fontSize = 11.sp, color = Color(0xFFFF99A8), fontWeight = FontWeight.Bold)
                                if (geminiKeys.size > 1) {
                                    IconButton(
                                        onClick = { geminiKeys.removeAt(idx) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = pair.first,
                                onValueChange = { geminiKeys[idx] = it to pair.second },
                                label = { Text("Gemini API Key", fontSize = 11.sp) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = RedPrimary,
                                    unfocusedBorderColor = RedOutline,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = pair.second,
                                onValueChange = { geminiKeys[idx] = pair.first to it },
                                label = { Text("Model (e.g. gemini-3.8-flash)", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = RedPrimary,
                                    unfocusedBorderColor = RedOutline,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                        }
                    }
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("CANCEL", color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            saveAll()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RedPrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("SAVE KEYS", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
