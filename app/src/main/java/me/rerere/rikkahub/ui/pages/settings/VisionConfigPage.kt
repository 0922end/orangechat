package me.rerere.rikkahub.ui.pages.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 视觉模型配置页面 - 动态管理视觉模型链
 * 
 * 功能：
 * 1. 显示已配置的模型列表（百炼、Groq、用户自定义）
 * 2. 添加/编辑/删除模型
 * 3. 启用/禁用模型
 * 4. 实时保存到 SharedPreferences
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionConfigPage(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var backends by remember { mutableStateOf(loadVisionBackends(context)) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("视觉模型配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    backends = backends + VisionBackendConfigUI(
                        name = "",
                        apiKey = "",
                        baseUrl = "",
                        model = "",
                        enabled = true
                    )
                }
            ) {
                Icon(Icons.Default.Add, "添加模型")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "配置视觉模型降级链：从上到下依次尝试，第一个成功的模型返回结果。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            if (backends.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "还没有配置任何模型\n点击右下角 + 添加",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            backends.forEachIndexed { index, backend ->
                VisionBackendCard(
                    backend = backend,
                    onUpdate = { updated ->
                        backends = backends.toMutableList().apply { set(index, updated) }
                        saveVisionBackends(context, backends)
                    },
                    onDelete = {
                        backends = backends.toMutableList().apply { removeAt(index) }
                        saveVisionBackends(context, backends)
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(80.dp)) // FAB占位
        }
    }
}

@Composable
fun VisionBackendCard(
    backend: VisionBackendConfigUI,
    onUpdate: (VisionBackendConfigUI) -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = backend.name.ifEmpty { "新模型" },
                    style = MaterialTheme.typography.titleMedium
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = backend.enabled,
                        onCheckedChange = { onUpdate(backend.copy(enabled = it)) }
                    )
                    
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                    }
                    
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "收起" else "展开")
                    }
                }
            }
            
            if (expanded) {
                // 模型名称
                OutlinedTextField(
                    value = backend.name,
                    onValueChange = { onUpdate(backend.copy(name = it)) },
                    label = { Text("模型名称") },
                    placeholder = { Text("如：百炼、Groq、DeepSeek") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                // API Key
                OutlinedTextField(
                    value = backend.apiKey,
                    onValueChange = { onUpdate(backend.copy(apiKey = it)) },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-xxx 或 ghp_xxx") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showPassword) 
                        VisualTransformation.None 
                    else 
                        PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "隐藏" else "显示")
                        }
                    }
                )
                
                // Base URL
                OutlinedTextField(
                    value = backend.baseUrl,
                    onValueChange = { onUpdate(backend.copy(baseUrl = it)) },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.xxx.com/v1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                // Model
                OutlinedTextField(
                    value = backend.model,
                    onValueChange = { onUpdate(backend.copy(model = it)) },
                    label = { Text("模型名") },
                    placeholder = { Text("qwen-vl-max") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                // 状态提示
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val status = when {
                        !backend.enabled -> "已禁用"
                        backend.apiKey.isEmpty() -> "未配置 Key"
                        else -> "已启用"
                    }
                    val color = when {
                        !backend.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        backend.apiKey.isEmpty() -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                    
                    Text(
                        text = "状态：$status",
                        style = MaterialTheme.typography.bodySmall,
                        color = color
                    )
                }
            } else {
                // 折叠时显示简要信息
                Text(
                    text = "模型：${backend.model.ifEmpty { "未配置" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Key：${if (backend.apiKey.isEmpty()) "未配置" else "已配置"}"  ,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (backend.apiKey.isEmpty()) 
                        MaterialTheme.colorScheme.error 
                    else 
                        MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ========== 数据持久化 ==========

data class VisionBackendConfigUI(
    val name: String,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val enabled: Boolean
)

private fun loadVisionBackends(context: Context): List<VisionBackendConfigUI> {
    val prefs = context.getSharedPreferences("vision_config", Context.MODE_PRIVATE)
    val json = prefs.getString("backends", null)
    
    if (json != null) {
        try {
            val array = JSONArray(json)
            val list = mutableListOf<VisionBackendConfigUI>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    VisionBackendConfigUI(
                        name = obj.getString("name"),
                        apiKey = obj.getString("apiKey"),
                        baseUrl = obj.getString("baseUrl"),
                        model = obj.getString("model"),
                        enabled = obj.optBoolean("enabled", true)
                    )
                )
            }
            return list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // 默认配置：百炼 + Groq占位
    val qwenKey = prefs.getString("qwen_key", "") ?: ""
    return listOf(
        VisionBackendConfigUI(
            name = "阿里云百炼",
            apiKey = qwenKey,
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            model = "qwen-vl-max",
            enabled = qwenKey.isNotEmpty()
        ),
        VisionBackendConfigUI(
            name = "Groq Vision",
            apiKey = "",
            baseUrl = "https://api.groq.com/openai/v1",
            model = "llama-3.2-90b-vision-preview",
            enabled = false
        )
    )
}

private fun saveVisionBackends(context: Context, backends: List<VisionBackendConfigUI>) {
    val prefs = context.getSharedPreferences("vision_config", Context.MODE_PRIVATE)
    val array = JSONArray()
    
    backends.forEach { backend ->
        array.put(JSONObject().apply {
            put("name", backend.name)
            put("apiKey", backend.apiKey)
            put("baseUrl", backend.baseUrl)
            put("model", backend.model)
            put("enabled", backend.enabled)
        })
    }
    
    prefs.edit().putString("backends", array.toString()).apply()
}