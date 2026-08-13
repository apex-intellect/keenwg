package ru.anisimov.keenwg

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import ru.anisimov.keenwg.data.ServiceLocator
import ru.anisimov.keenwg.ui.KeenWgNav
import ru.anisimov.keenwg.ui.components.KeenAppBackground
import ru.anisimov.keenwg.ui.theme.KeenWgTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceLocator.init(this)
        setContent {
            KeenWgTheme {
                KeenAppBackground { KeenWgNav() }
            }
        }
    }
}
