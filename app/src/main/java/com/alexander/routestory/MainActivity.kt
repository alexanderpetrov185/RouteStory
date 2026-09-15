package com.alexander.routestory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.alexander.routestory.ui.navigation.RouteStoryNavHost
import com.alexander.routestory.ui.theme.RouteStoryTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RouteStoryTheme {
                RouteStoryNavHost()
            }
        }
    }
}
