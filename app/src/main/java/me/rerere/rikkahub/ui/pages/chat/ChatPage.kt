/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Voice
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.message.ChatEmbeddedWebView
import me.rerere.rikkahub.ui.components.message.LocalEmbedWebView
import androidx.compose.runtime.CompositionLocalProvider
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null, autoStartVoice: Boolean = false) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Hide keyboard when drawer is open
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    autoStartVoice = autoStartVoice,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }

        else -> {
            // Track split-screen state to disable drawer gesture
            var splitScreenActive by rememberSaveable { mutableStateOf(false) }
            ModalNavigationDrawer(
                gesturesEnabled = !splitScreenActive,
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    autoStartVoice = autoStartVoice,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                    onSplitScreenChanged = { splitScreenActive = it },
                )
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
        }
    }
}

@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    autoStartVoice: Boolean = false,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onSplitScreenChanged: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()

    // Fullscreen WebView state
    var embedWebViewUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val embedWebViewVisible = embedWebViewUrl != null
    var embeddedWebView by androidx.compose.runtime.remember { mutableStateOf<android.webkit.WebView?>(null) }

    // Disable drawer gesture when WebView is open
    androidx.compose.runtime.LaunchedEffect(embedWebViewVisible) {
        onSplitScreenChanged(embedWebViewVisible)
    }

    // Bridge: 1s debounce, direct send
    val bridgeDebounceJob = androidx.compose.runtime.remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val bridgePromptSent = androidx.compose.runtime.remember { mutableStateOf(false) }
    val executedWebActions = androidx.compose.runtime.remember { mutableSetOf<String>() }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    // Reverse channel: intercept [WebAction]{...}[/WebAction] from AI responses (only after generation completes)
    val lastMsg = conversation.currentMessages.lastOrNull()
    val reverseCtx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(lastMsg?.id, loadingJob) {
        if (loadingJob != null) return@LaunchedEffect  // still generating, wait
        val wv = embeddedWebView
        if (lastMsg != null && lastMsg.role == me.rerere.ai.core.MessageRole.ASSISTANT) {
            val textParts = lastMsg.parts.filterIsInstance<UIMessagePart.Text>()
            val fullText = textParts.joinToString("") { it.text }
            if (wv != null) {
                // Handle [WebAction] - AI executes JS on web page
                if (fullText.contains("[WebAction]")) {
                    val regex = Regex("""\[WebAction](.*?)\[/WebAction]""", RegexOption.DOT_MATCHES_ALL)
                    regex.findAll(fullText).forEach { match ->
                        val actionJson = match.groupValues[1].trim()
                        if (!executedWebActions.contains(actionJson)) {
                            executedWebActions.add(actionJson)
                            val encoded = android.util.Base64.encodeToString(actionJson.toByteArray(), android.util.Base64.NO_WRAP)
                            val js = "try { window.ElianExecute(atob('$encoded')); } catch(e) { console.error('ElianExecute error', e); }"
                            wv.handler.post { wv.evaluateJavascript(js, null) }
                            // Show description as blue toast on webpage
                            val desc = Regex(""""description"\s*:\s*"([^"]*)"""").find(actionJson)?.groupValues?.get(1)
                            if (!desc.isNullOrBlank()) {
                                val descEncoded = android.util.Base64.encodeToString(desc.toByteArray(), android.util.Base64.NO_WRAP)
                                wv.handler.post { wv.evaluateJavascript("try { window.ElianToast(atob('$descEncoded'), 'ai'); } catch(e) {}", null) }
                            }
                        }
                    }
                }
                // Handle [WebQuery] - AI requests page state
                if (fullText.contains("[WebQuery]")) {
                    val qRegex = Regex("""\[WebQuery](.*?)\[/WebQuery]""", RegexOption.DOT_MATCHES_ALL)
                    qRegex.findAll(fullText).forEach { match ->
                        val queryId = match.groupValues[1].trim()
                        if (!executedWebActions.contains("query_$queryId")) {
                            executedWebActions.add("query_$queryId")
                            wv.handler.post { wv.evaluateJavascript("try { window.ElianQueryState(); } catch(e) {}", null) }
                        }
                    }
                }
                // Auto-bubble: show AI plain text on webpage when WebView is open
                val plainText = fullText
                    .replace(Regex("""\[WebAction].*?\[/WebAction]""", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("""\[WebQuery].*?\[/WebQuery]""", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("""\[WebEmbed].*""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                    .replace(Regex("""\[WebBridge]\n?""", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (plainText.isNotBlank() && !executedWebActions.contains("bubble_${lastMsg.id}")) {
                    executedWebActions.add("bubble_${lastMsg.id}")
                    val encoded = android.util.Base64.encodeToString(plainText.take(200).toByteArray(), android.util.Base64.NO_WRAP)
                    wv.handler.post {
                        wv.evaluateJavascript(
                            "try { window.ElianShowBubble(atob('$encoded'), 'ai'); } catch(e) {}",
                            null
                        )
                    }
                }
                // Show 【description】 as blue toast on webpage
                val descRegex = Regex("\u3010([^\u3011]+)\u3011")
                descRegex.findAll(fullText).forEach { match ->
                    val descText = match.groupValues[1]
                    val descKey = "desc_${lastMsg.id}_${descText.hashCode()}"
                    if (!executedWebActions.contains(descKey)) {
                        executedWebActions.add(descKey)
                        val descEncoded = android.util.Base64.encodeToString(descText.toByteArray(), android.util.Base64.NO_WRAP)
                        wv.handler.post {
                            wv.evaluateJavascript("try { window.ElianToast(atob('$descEncoded'), 'ai'); } catch(e) {}", null)
                        }
                    }
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalEmbedWebView provides { url -> embedWebViewUrl = url }
    ) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
        AssistantBackground(setting = setting)
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    conversation = conversation,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    previewMode = previewMode,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    },
                    onVoiceCall = {
                        val activeId = VoiceCallService.activeConversationId.value
                        when {
                            activeId == null -> navController.navigate(
                                Screen.VoiceCall(conversation.id.toString())
                            )
                            activeId == conversation.id.toString() -> navController.navigate(
                                Screen.VoiceCall(conversation.id.toString())
                            )
                            else -> {
                                toaster.show("当前有通话进行中，请先挂断", type = ToastType.Warning)
                            }
                        }
                    },
                )
            },
            bottomBar = {
                ChatInput(
                    state = inputState,
                    loading = loadingJob != null,
                    settings = setting,
                    conversation = conversation,
                    mcpManager = vm.mcpManager,
                    hazeState = hazeState,
                    autoStartVoice = autoStartVoice,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    enableSearch = enableWebSearch,
                    onToggleSearch = {
                        vm.updateSettings(setting.copy(enableWebSearch = !enableWebSearch))
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(inputState.getContents())
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onVoiceMessage = { url, duration, transcript ->
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        vm.handleMessageSend(
                            listOf(
                                UIMessagePart.VoiceMessage(
                                    url = url,
                                    duration = duration,
                                    transcript = transcript,
                                )
                            )
                        )
                        scope.launch {
                            chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                        }
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
                    },
                    onUpdateAssistant = {
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == it.id) {
                                        it
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
                    onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                        vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
                    },
                )
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                state = chatListState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.updateConversation(
                        conversation.copy(
                            messageNodes = conversation.messageNodes.map { node ->
                                if (node.id == newNode.id) {
                                    newNode
                                } else {
                                    node
                                }
                            }
                        ))
                    vm.saveConversationAsync()
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        chatListState.animateScrollToItem(index)
                    }
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },
            )
        }

        // Fullscreen WebView overlay
        ChatEmbeddedWebView(
            url = embedWebViewUrl ?: "",
            visible = embedWebViewVisible,
            onDismiss = {
                // Clear idle timer when leaving WebView
                embeddedWebView?.handler?.post {
                    embeddedWebView?.evaluateJavascript("try { window.ElianClearIdle(); } catch(e) {}", null)
                }
                // Clear WebView proxy
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        android.webkit.ProxyController.getInstance().clearProxyOverride(
                            { it.run() },
                            { }
                        )
                    }
                } catch (_: Exception) {}
                embedWebViewUrl = null
                embeddedWebView = null
            },
            onBridgeMessage = { message ->
                // Filter out ai_action feedback to prevent infinite loop
                val isAiAction = try { message.contains("\"type\":\"ai_action\"") } catch(_: Exception) { false }
                if (isAiAction) return@ChatEmbeddedWebView
                // 1s debounce then direct send
                bridgeDebounceJob.value?.cancel()
                bridgeDebounceJob.value = scope.launch {
                    kotlinx.coroutines.delay(1000L)
                    val hint = if (!bridgePromptSent.value) {
                        bridgePromptSent.value = true
                        "[WebEmbed] IMPORTANT: You are co-browsing a webpage with the user RIGHT NOW. You MUST use [WebAction] tags in EVERY reply. You should PROACTIVELY use js to interact with the page. Don't wait for the user to ask.\nFormat: [WebAction]{\"bubble\":{\"text\":\"msg\",\"type\":\"talk\"},\"js\":\"code\",\"description\":\"what you did\"}[/WebAction]\nbubble types: talk(pink) action(blue). js: any JS.\nChat text optional, [WebAction] REQUIRED."
                    } else {
                        "[WebEmbed] Use [WebAction] with bubble. Be proactive."
                    }
                    val bridgeText = "[WebBridge]\n$message\n\n$hint"
                    vm.handleMessageSend(
                        content = listOf(
                            UIMessagePart.Text(text = bridgeText)
                        ),
                        answer = true,
                    )
                }
            },
            onWebViewReady = { wv -> embeddedWebView = wv },
        )
        } // Box
    } // Surface
    } // CompositionLocalProvider
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit,
    onVoiceCall: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                IconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    }
                ) {
                    Icon(HugeIcons.Menu03, "Messages")
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val assistant = settings.getCurrentAssistant()
                    val model = settings.getCurrentChatModel()
                    val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${model.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(
                onClick = {
                    onVoiceCall()
                }
            ) {
                Icon(HugeIcons.Voice, "Voice Call")
            }

            IconButton(
                onClick = {
                    onClickMenu()
                }
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            IconButton(
                onClick = {
                    onNewChat()
                }
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}