package ru.anisimov.keenwg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.ui.KeenWgNav
import ru.anisimov.keenwg.ui.theme.KeenWgTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceLocator.init(this)
        setContent {
            KeenWgTheme {
                Surface { KeenWgNav() }
            }
        }
    }
}
