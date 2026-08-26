package com.moronigranja.localttsreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.moronigranja.localttsreader.featurelibrary.LibraryScreen
import com.moronigranja.localttsreader.featureplayer.ui.ReaderScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var openBookId by rememberSaveable { mutableStateOf<String?>(null) }
                val bookId = openBookId
                if (bookId == null) {
                    LibraryScreen(onOpenBook = { openBookId = it })
                } else {
                    ReaderScreen(bookId = bookId, onClose = { openBookId = null })
                }
            }
        }
    }
}
