package com.jardim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.jardim.ui.Abertura
import com.jardim.ui.JardimNavegacao
import com.jardim.ui.JardimTema

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JardimTema {
                // Só na abertura: dobrar o celular ou girar a tela recria a activity,
                // mas o rememberSaveable lembra que a semente já brotou.
                var abrindo by rememberSaveable { mutableStateOf(true) }
                Box {
                    JardimNavegacao()
                    if (abrindo) Abertura(aoTerminar = { abrindo = false })
                }
            }
        }
    }
}
