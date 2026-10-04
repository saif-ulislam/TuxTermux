package dev.tuxterm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Single activity, single content tree. The terminal is the app: there is no
 * launcher screen, navigation, or settings surface to route through.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TerminalApp()
        }
    }
}
