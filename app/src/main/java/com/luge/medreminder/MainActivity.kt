package com.luge.medreminder

import com.luge.medreminder.ui.Overlay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.luge.medreminder.ui.*

class MainActivity : ComponentActivity() {
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        Alarms.ensureChannel(this)
        Alarms.rescheduleAll(this)
        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        enableEdgeToEdge()
        setContent { AppTheme { AppRoot() } }
    }
}

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val data by Repo.data.collectAsState()
    LaunchedEffect(Unit) { if (data.medicines.isEmpty()) tab = 2 }
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
    Scaffold(containerColor = PageBg, bottomBar = {
        NavigationBar(containerColor = CardBg, tonalElevation = 0.dp) {
            listOf(Triple("打卡", Icons.Filled.CheckCircle, 0), Triple("统计", Icons.Filled.DateRange, 1), Triple("设置", Icons.Filled.Settings, 2)).forEach { (t, ic, i) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {
                    val sc by androidx.compose.animation.core.animateFloatAsState(if (tab == i) 1.12f else 1f,
                        androidx.compose.animation.core.spring(0.6f, androidx.compose.animation.core.Spring.StiffnessLow), label = "ni")
                    Icon(ic, null, Modifier.graphicsLayer { scaleX = sc; scaleY = sc })
                }, label = { Text(t) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Blue, selectedTextColor = Blue,
                        indicatorColor = Blue.copy(alpha = 0.10f), unselectedIconColor = TextSub, unselectedTextColor = TextSub))
            }
        }
    }) { pad ->
        val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        val off = dpPx(24)
        AnimatedContent(tab, Modifier.padding(bottom = pad.calculateBottomPadding()), label = "tab",
            transitionSpec = { fadeThrough(if (targetState > initialState) 1 else -1, off) }) { t -> holder.SaveableStateProvider(t) {
            val m = if (t == 0) Modifier else Modifier.statusBarsPadding()
            when (t) {
                0 -> CheckInScreen(data, m)
                1 -> StatsScreen(data, m)
                else -> SettingsScreen(data, m)
            }
        } }
    }
    // status-bar scrim: scrolled content never shows through behind the system icons
    val scrim by androidx.compose.animation.animateColorAsState(if (tab == 0) Blue else PageBg, label = "scrim")
    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.fillMaxWidth()
        .windowInsetsTopHeight(WindowInsets.statusBars).background(scrim))
    Overlay.content?.let { layer -> androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) { layer() } }
    }
}
