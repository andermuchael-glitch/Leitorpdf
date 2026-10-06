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
                LeitorPdfApp(
                    initialUri = intent?.data,
                    activity = this
                )
            }
        }
    }
}

@Composable
private fun LeitorPdfApp(
    initialUri: Uri?,
    activity: ComponentActivity
) {
    val prefs = activity.getSharedPreferences(
        "reading_progress",
        android.content.Context.MODE_PRIVATE
    )

    // Se o usuário já abriu um PDF anteriormente, ele volta direto para o
    // leitor. Não é mais necessário escolher o arquivo toda vez.
    val rememberedUri = remember {
        initialUri ?: prefs.getString("last_uri", null)?.let(Uri::parse)
    }

    var selectedUri by remember { mutableStateOf(rememberedUri) }
    var selectedName by remember {
        mutableStateOf(
            if (initialUri != null) {
                "Documento PDF"
            } else {
                prefs.getString("last_name", "Documento PDF") ?: "Documento PDF"
            }
        )
    }

    val readerViewModel: ReaderViewModel = viewModel()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Alguns provedores não oferecem permissão persistente.
            }

            val name = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() }
                ?: "Documento PDF"

            prefs.edit()
                .putString("last_uri", uri.toString())
                .putString("last_name", name)
                .apply()

            selectedUri = uri
            selectedName = name
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
            },
            onOpenAnotherPdf = {
                picker.launch(arrayOf("application/pdf"))
            }
        )
    } else {
        HomeScreen(
            onOpenPdf = {
                picker.launch(arrayOf("application/pdf"))
            }
        )
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
                        contentDescription = null
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
