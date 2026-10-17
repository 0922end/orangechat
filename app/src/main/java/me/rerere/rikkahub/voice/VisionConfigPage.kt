package me.rerere.rikkahub.voice

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionConfigPage(currentBackends: List<VisionChain.VisionBackend>, onSave: (List<VisionChain.VisionBackend>) -> Unit, onBack: () -> Unit) {
    var backends by remember { mutableStateOf(currentBackends.ifEmpty { listOf(VisionChain.VisionBackend("qwen-vl", "https://dashscope.aliyuncs.com/compatible-mode/v1", "", "qwen-vl-max")) }) }
    Scaffold(topBar = { TopAppBar(title = { Text("\u89c6\u89c9\u6a21\u578b\u8bbe\u7f6e") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "\u8fd4\u56de") } },
        actions = { TextButton(onClick = { onSave(backends.filter { it.baseUrl.isNotBlank() && it.apiKey.isNotBlank() && it.model.isNotBlank() }) }) { Text("\u4fdd\u5b58") } }) }
    ) { padding -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("\u89c6\u89c9\u6a21\u578b\u6309\u987a\u5e8f\u5c1d\u8bd5\uff0c\u7b2c\u4e00\u5bb6\u6210\u529f\u5c31\u7528\u3002\u5355\u5bb6\u8d85\u65f6 ${VisionChain.VISION_TIMEOUT} \u79d2\u81ea\u52a8\u6362\u4e0b\u4e00\u5bb6\u3002", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        backends.forEachIndexed { index, backend -> BackendCard(index + 1, backend, { backends = backends.toMutableList().also { it[index] = backend } },
            if (backends.size > 1) {{ backends = backends.toMutableList().also { it.removeAt(index) } }} else null); Spacer(Modifier.height(12.dp)) }
        OutlinedButton(onClick = { backends = backends + VisionChain.VisionBackend("backend-${backends.size + 1}", "", "", "") }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.Visibility, null); Spacer(Modifier.width(8.dp)); Text("\u6dfb\u52a0\u89c6\u89c9\u6a21\u578b") }
    } }
}

@Composable
private fun BackendCard(index: Int, backend: VisionChain.VisionBackend, onUpdate: (VisionChain.VisionBackend) -> Unit, onDelete: (() -> Unit)?) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("\u6a21\u578b #$index", style = MaterialTheme.typography.titleSmall); onDelete?.let { TextButton(it) { Text("\u5220\u9664", color = MaterialTheme.colorScheme.error) } } }
        Spacer(Modifier.height(8.dp)); OutlinedTextField(backend.name, { onUpdate(backend.copy(name = it)) }, label = { Text("\u540d\u79f0") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp)); OutlinedTextField(backend.baseUrl, { onUpdate(backend.copy(baseUrl = it)) }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp)); OutlinedTextField(backend.apiKey, { onUpdate(backend.copy(apiKey = it)) }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp)); OutlinedTextField(backend.model, { onUpdate(backend.copy(model = it)) }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    } }
}