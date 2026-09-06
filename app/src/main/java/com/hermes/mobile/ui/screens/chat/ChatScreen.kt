package com.hermes.mobile.ui.screens.chat

import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyListener
import androidx.compose.foundation.text.KeyEvent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.mobile.ui.theme.HermesMobileTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@AndroidEntryPoint
class ChatActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HermesMobileTheme {
                ChatScreenContent(
                    onSettingsClick = { 
                        // Navigate to settings
                        val intent = android.content.Intent(this, SettingsActivity::class.java)
                        startActivity(intent)
                    },
                    viewModel = viewModel
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreenContent(
    onSettingsClick: () -> Unit,
    viewModel: ChatViewModel
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val isSending by remember { mutableStateOf(false) }
    val error by remember { mutableStateOf<String?>(null) }

    val keyListener = remember {
        KeyListener { _, event ->
            if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                if (!inputText.isBlank() && !isSending) {
                    viewModel.sendMessage(inputText)
                    return@KeyListener true
                }
            }
            false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hermes Chat") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            ChatInputBar(
                text = inputText,
                onTextChange = viewModel::updateInputText,
                onSend = { viewModel.sendMessage(inputText) },
                isSending = isSending,
                modifier = Modifier.fillMaxWidth(),
                keyListener = keyListener
            )
        }
    ) { padding ->
        // Connection status indicator at top
        if (!isConnected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Disconnected from Hermes Gateway",
                    color = MaterialTheme.colorScheme.onError,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp, top = if (isConnected) 0.dp else 48.dp),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            reverseAllLayout = true
        ) {
            items(messages.size) { index ->
                // Reverse index since we're showing latest at bottom
                val message = messages[messages.size - 1 - index]
                MessageBubble(message = message)
            }
            
            // Loading indicator when sending
            if (isSending && messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        
        // Error snackbar (simplified)
        error?.let { err ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.onError,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun MessageBubble(message: com.hermes.mobile.data.network.ChatEvent) {
    // Determine if this is a user message or assistant message based on event type
    val isUserMessage = when (message) {
        is com.hermes.mobile.data.network.ChatEvent.TextChunk -> false // Assistant message
        else -> true // For now, treat everything except TextChunk as user-related
    }
    
    // For simplicity in this demo, we'll show TextChunk as assistant messages
    // and create a simple user message wrapper
    val displayMessage = when (message) {
        is com.hermes.mobile.data.network.ChatEvent.TextChunk -> {
            // Assistant message
            com.hermes.mobile.ui.screens.chat.Message(
                role = "assistant",
                content = message.content
            )
        }
        else -> {
            // For other event types, we'll show as system messages or simplify
            com.hermes.mobile.ui.screens.chat.Message(
                role = "system",
                content = message::class.simpleName
            )
        }
    }
    
    val isUser = displayMessage.role == "user"
    val isAssistant = displayMessage.role == "assistant"
    val isSystem = displayMessage.role == "system"
    
    // Choose bubble color based on message type
    val bubbleColor = when {
        isUser -> MaterialTheme.colorScheme.primary
        isAssistant -> MaterialTheme.colorScheme.secondaryContainer
        isSystem -> MaterialTheme.colorScheme.tertiaryContainer
    }
    
    val textColor = when {
        isUser -> MaterialTheme.colorScheme.onPrimary
        isAssistant -> MaterialTheme.colorScheme.onSecondaryContainer
        isSystem -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    
    // Alignment
    val horizontalAlignment = when {
        isUser -> androidx.compose.ui.Alignment.End
        isAssistant -> androidx.compose.ui.Alignment.Start
        isSystem -> androidx.compose.ui.Alignment.Start
    }
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = horizontalAlignment
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .padding(4.dp),
            color = bubbleColor,
            shape = MaterialTheme.shapes.medium
        ) {
            // Handle different message types
            when (message) {
                is com.hermes.mobile.data.network.ChatEvent.TextChunk -> {
                    Text(
                        text = message.content,
                        modifier = Modifier.padding(12.dp),
                        color = textColor
                    )
                }
                is com.hermes.mobile.data.network.ChatEvent.ToolCall -> {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "🔧 Tool Call: ${message.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = message.args,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontStyle = androidx.compose.ui.text.fontStyle.Italic
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is com.hermes.mobile.data.network.ChatEvent.ToolResult -> {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "✅ Tool Result",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.success
                        )
                        Text(
                            text = message.result,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                is com.hermes.mobile.data.network.ChatEvent.Reasoning -> {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "💭 Reasoning:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontStyle = androidx.compose.ui.text.fontStyle.Italic
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is com.hermes.mobile.data.network.ChatEvent.TurnEnd -> {
                    Text(
                        text = "↩️ Turn End",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                is com.hermes.mobile.data.network.ChatEvent.Error -> {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "❌ Error:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = message.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onError
                        )
                    }
                }
                else -> {
                    Text(
                        text = "Unknown message type",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    modifier: Modifier = Modifier,
    keyListener: KeyListener? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            placeholder = { Text("Type a message...") },
            maxLines = 4,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.foundation.text.ImeAction.Send
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSend = { onSend() }
            ),
            keyListener = keyListener
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (isSending) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            FilledTonalIconButton(
                onClick = onSend,
                enabled = text.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}

// ViewModel for chat functionality
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: com.hermes.mobile.data.repository.HermesRepository
) : androidx.lifecycle.ViewModel() {

    // Messages state
    private val _messages = androidx.lifecycle.MutableLiveData<List<com.hermes.mobile.data.network.ChatEvent>>(emptyList())
    val messages: androidx.lifecycle.LiveData<List<com.hermes.mobile.data.network.ChatEvent>> = _messages

    // Connection state
    private val _isConnected = androidx.lifecycle.MutableLiveData(true) // Simplified
    val isConnected: androidx.lifecycle.LiveData<Boolean> = _isConnected

    // Input text
    private val _inputText = androidx.lifecycle.MutableLiveData("")
    val inputText: androidx.lifecycle.LiveData<String> = _inputText

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        
        // Add user message to UI immediately
        val userMessage = com.hermes.mobile.data.network.ChatEvent.Error(message = text) // Using Error as placeholder for user msg
        val currentMessages = _messages.value ?: emptyList()
        _messages.value = currentMessages + userMessage
        
        // Clear input
        _inputText.value = ""
        
        // Send to repository (this would actually call the API)
        viewModelScope.launch {
            try {
                // In production, this would collect the chat stream
                // For now, we'll simulate a response
                repository.chatStream(null, text).collect { event ->
                    // Add each event to messages
                    val current = _messages.value ?: emptyList()
                    _messages.value = current + event
                }
            } catch (e: Exception) {
                // Show error
                val errorEvent = com.hermes.mobile.data.network.ChatEvent.Error(
                    message = "Failed to send message: ${e.message}"
                )
                val current = _messages.value ?: emptyList()
                _messages.value = current + errorEvent
            }
        }
    }
}

// Simple message data class for UI
data class Message(
    val role: String, // "user", "assistant", or "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)