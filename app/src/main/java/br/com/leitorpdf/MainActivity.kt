package br.com.leitorpdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.leitorpdf.reader.ReaderViewModel
import br.com.leitorpdf.ui.reader.ReaderScreen
import br.com.leitorpdf.ui.theme.LeitorPdfTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LeitorPdfTheme {
                LeitorPdfApp(initialUri = intent?.data)
            }
        }
    }
}

@Composable
private fun LeitorPdfApp(initialUri: Uri?) {
    var selectedUri by remember { mutableStateOf(initialUri) }
    var selectedName by remember { mutableStateOf("Documento PDF") }
    val readerViewModel: ReaderViewModel = viewModel()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedName = uri.lastPathSegment?.substringAfterLast('/') ?: "Documento PDF"
        }
    }

    if (selectedUri != null) {
        ReaderScreen(
            uri = selectedUri!!,
            fileName = selectedName,
            viewModel = readerViewModel,
            onBack = {
                readerViewModel.stopSpeech()
                selectedUri = null
            }
        )
    } else {
        HomeScreen(onOpenPdf = {
            picker.launch(arrayOf("application/pdf"))
        })
    }
}

@Composable
private fun HomeScreen(onOpenPdf: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 22.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Text(
                        text = "Leitor PDF",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "Leia ou ouça seus documentos no celular",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 8.dp, bottom = 22.dp)
                    )
                    Button(
                        onClick = onOpenPdf,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AutoStories, contentDescription = null)
                        Text("  Abrir PDF")
                    }
                }
            }

            Text(
                text = "Leitura confortável • Áudio em português • Offline",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 20.dp)
            )
        }
    }
}
