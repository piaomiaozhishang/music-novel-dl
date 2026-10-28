package com.wang.sonovel.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.wang.sonovel.R

@Composable
fun StartupScreen(error: String?, onRetry: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            verticalArrangement=Arrangement.Center, horizontalAlignment=Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.app_logo), null, Modifier.size(112.dp))
            Text("书歌", style=MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(20.dp))
            if(error == null) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth=2.dp)
                Spacer(Modifier.height(12.dp))
                Text("正在载入书源与收藏…", color=MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(error)
                TextButton(onClick=onRetry) { Text("重试") }
            }
        }
    }
}
