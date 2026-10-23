package com.sunday.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SundayScreen()
        }
    }

    @Composable
    private fun SundayScreen() {
        val cyan = Color(0xFF35E7FF)
        val navy = Color(0xFF06111F)
        val panel = Color(0xFF101F32)

        var command by remember { mutableStateOf("") }
        var response by remember {
            mutableStateOf("Systems online. Hello, I'm Sunday.")
        }
        var listening by remember { mutableStateOf(false) }

        var wakeWordEnabled by remember { mutableStateOf(false) }
        var wakeStatus by remember { mutableStateOf("Wake word is off") }
        var awaitingCommand by remember { mutableStateOf(false) }

        var pendingContactName by remember { mutableStateOf("") }
        var pendingContactAction by remember { mutableStateOf("find") }
        var pendingCallName by remember { mutableStateOf("") }
        var pendingCallNumber by remember {
            mutableStateOf<String?>(null)
        }

        var ttsReady by remember { mutableStateOf(false) }

        val tts = remember {
            TextToSpeech(this@MainActivity) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
            }
        }

        DisposableEffect(tts) {
            onDispose {
                tts.stop()
                tts.shutdown()
            }
        }

        fun speak(message: String) {
            response = message

            if (ttsReady) {
                tts.language = Locale.US
                tts.speak(
                    message,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "sunday-response"
                )
            }
        }

        fun searchContact(name: String): Pair<String, String>? {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI

            return contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    Pair(
                        cursor.getString(0) ?: "Unknown contact",
                        cursor.getString(1) ?: ""
                    )
                } else {
                    null
                }
            }
        }

        fun handleContactRequest() {
            val requestedName = pendingContactName

            val match = try {
                searchContact(requestedName)
            } catch (_: Exception) {
                speak("I couldn't search your contacts.")
                return
            }

            if (match == null || match.second.isBlank()) {
                pendingCallName = ""
                pendingCallNumber = null
                speak("I couldn't find a phone number for $requestedName.")
                return
            }

            val (name, number) = match

            if (pendingContactAction == "call") {
                pendingCallName = name
                pendingCallNumber = number

                speak(
                    "I found $name. Please say confirm call " +
                    "to place the call, or cancel call to cancel."
                )
            } else {
                speak("I found $name. The number is $number.")
            }
        }

        val contactsPermissionLauncher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    handleContactRequest()
                } else {
                    speak("Contacts permission was denied.")
                }
            }

        val callPermissionLauncher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                val number = pendingCallNumber

                if (granted && !number.isNullOrBlank()) {
                    try {
                        startActivity(
                            Intent(
                                Intent.ACTION_CALL,
                                Uri.parse("tel:$number")
                            )
                        )
                        speak("Calling $pendingCallName.")
                        pendingCallName = ""
                        pendingCallNumber = null
                    } catch (_: Exception) {
                        speak("I couldn't start the call. Please try again.")
                    }
                } else {
                    speak("Phone permission was denied.")
                }
            }

        val wakeRecognizer = remember {
            try {
                if (SpeechRecognizer.isRecognitionAvailable(this@MainActivity)) {
                    SpeechRecognizer.createSpeechRecognizer(this@MainActivity)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }

        val wakeHandler = remember {
            Handler(Looper.getMainLooper())
        }

        val latestWakeEnabled = rememberUpdatedState(wakeWordEnabled)
        val latestAwaitingCommand = rememberUpdatedState(awaitingCommand)

        val wakeIntent = remember {
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    Locale.getDefault().toLanguageTag()
                )
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
        }

        fun startWakeListening() {
            if (
                !latestWakeEnabled.value ||
                latestAwaitingCommand.value ||
                wakeRecognizer == null
            ) {
                return
            }

            try {
                wakeStatus = "Listening for Hey Sunday..."
                wakeRecognizer.startListening(wakeIntent)
            } catch (_: Exception) {
                wakeStatus = "Listening stopped. Tap to try again."
            }
        }

        fun runCommand(raw: String) {
            val text = raw.trim().lowercase(Locale.ROOT)

            if (text.isBlank()) {
                speak("Please tell me what you would like me to do.")
                return
            }

            when {
                text.startsWith("find contact ") ||
                    text.startsWith("find ") -> {

                    val prefix = if (text.startsWith("find contact ")) {
                        "find contact "
                    } else {
                        "find "
                    }

                    val name = raw.trim().drop(prefix.length).trim()

                    if (name.isBlank()) {
                        speak("Say find contact followed by the person's name.")
                    } else {
                        pendingContactName = name
                        pendingContactAction = "find"

                        if (
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.READ_CONTACTS
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            handleContactRequest()
                        } else {
                            contactsPermissionLauncher.launch(
                                Manifest.permission.READ_CONTACTS
                            )
                        }
                    }
                }

                text.startsWith("call ") -> {
                    val name = raw.trim().drop("call ".length).trim()

                    if (name.isBlank()) {
                        speak("Say call followed by the contact's name.")
                    } else {
                        pendingContactName = name
                        pendingContactAction = "call"
                        pendingCallName = ""
                        pendingCallNumber = null

                        if (
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.READ_CONTACTS
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            handleContactRequest()
                        } else {
                            contactsPermissionLauncher.launch(
                                Manifest.permission.READ_CONTACTS
                            )
                        }
                    }
                }

                text == "confirm call" ||
                    text == "yes confirm call" -> {

                    val number = pendingCallNumber

                    if (number.isNullOrBlank()) {
                        speak("No call is waiting for confirmation.")
                    } else if (
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.CALL_PHONE
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        try {
                            startActivity(
                                Intent(
                                    Intent.ACTION_CALL,
                                    Uri.parse("tel:$number")
                                )
                            )
                            speak("Calling $pendingCallName.")
                            pendingCallName = ""
                            pendingCallNumber = null
                        } catch (_: Exception) {
                            speak("I couldn't start the call.")
                        }
                    } else {
                        callPermissionLauncher.launch(
                            Manifest.permission.CALL_PHONE
                        )
                    }
                }

                text == "cancel call" || text == "cancel" -> {
                    pendingCallName = ""
                    pendingCallNumber = null
                    speak("Call cancelled. I won't place the call.")
                }

                text == "hello" ||
                    text == "hi sunday" ||
                    text == "hello sunday" -> {
                    speak("Hello. Sunday is ready.")
                }

                text.contains("what time") || text == "time" -> {
                    val time = SimpleDateFormat(
                        "h:mm a",
                        Locale.getDefault()
                    ).format(Date())

                    speak("The time is $time.")
                }

                text.contains("who are you") ||
                    text.contains("introduce yourself") -> {
                    speak(
                        "I am Sunday, your personal Android assistant. " +
                        "I can respond to commands, search contacts, " +
                        "and open apps."
                    )
                }

                text.startsWith("open ") || text.startsWith("launch ") -> {
                    val appName = text
                        .removePrefix("open ")
                        .removePrefix("launch ")
                        .trim()

                    val packages = mapOf(
                        "youtube" to "com.google.android.youtube",
                        "whatsapp" to "com.whatsapp",
                        "instagram" to "com.instagram.android",
                        "spotify" to "com.spotify.music",
                        "chrome" to "com.android.chrome",
                        "gmail" to "com.google.android.gm",
                        "maps" to "com.google.android.apps.maps",
                        "google maps" to "com.google.android.apps.maps",
                        "calendar" to "com.google.android.calendar"
                    )

                    val packageName = packages[appName]

                    if (packageName == null) {
                        speak("I'm not sure which app you mean. Please say its exact name.")
                    } else {
                        val launchIntent =
                            packageManager.getLaunchIntentForPackage(packageName)

                        if (launchIntent != null) {
                            try {
                                speak("Opening $appName.")
                                startActivity(launchIntent)
                            } catch (_: Exception) {
                                speak("I couldn't open that app.")
                            }
                        } else {
                            speak("$appName may not be installed.")
                        }
                    }
                }

                else -> {
                    speak(
                        "I don't understand that command yet. Try open YouTube, " +
                        "find contact followed by a name, or call followed by a name."
                    )
                }
            }
        }        val speechLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            listening = false
            awaitingCommand = false

            if (result.resultCode == Activity.RESULT_OK) {
                val spokenText = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()

                if (!spokenText.isNullOrBlank()) {
                    command = spokenText
                    runCommand(spokenText)
                } else {
                    speak("I didn't catch that. Please try again.")
                }
            } else {
                speak("Voice input was cancelled or unavailable.")
            }

            if (wakeWordEnabled) {
                wakeHandler.postDelayed(
                    { startWakeListening() },
                    700
                )
            }
        }

        val microphonePermissionLauncher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    val intent = Intent(
                        RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                    ).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE,
                            Locale.getDefault().toLanguageTag()
                        )
                        putExtra(
                            RecognizerIntent.EXTRA_PROMPT,
                            "Speak to Sunday"
                        )
                    }

                    try {
                        listening = true
                        speechLauncher.launch(intent)
                    } catch (_: Exception) {
                        listening = false
                        speak("Voice recognition is unavailable.")
                    }
                } else {
                    speak("Microphone permission is needed for voice input.")
                }
            }

        val wakePermissionLauncher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    if (wakeRecognizer != null) {
                        wakeWordEnabled = true
                        wakeStatus = "Starting wake-word listener..."

                        wakeHandler.postDelayed(
                            { startWakeListening() },
                            300
                        )
                    } else {
                        speak("Speech recognition is unavailable on this device.")
                    }
                } else {
                    wakeWordEnabled = false
                    wakeStatus = "Microphone permission is required."
                    speak("Please allow microphone access to use Hey Sunday.")
                }
            }

        DisposableEffect(wakeRecognizer) {
            val recognizer = wakeRecognizer

            if (recognizer != null) {
                val listener = object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        if (latestWakeEnabled.value) {
                            wakeStatus = "Listening for Hey Sunday..."
                        }
                    }

                    override fun onBeginningOfSpeech() = Unit

                    override fun onRmsChanged(rmsdB: Float) = Unit

                    override fun onBufferReceived(buffer: ByteArray?) = Unit

                    override fun onEndOfSpeech() = Unit

                    override fun onError(error: Int) {
                        if (
                            latestWakeEnabled.value &&
                            !latestAwaitingCommand.value
                        ) {
                            wakeHandler.postDelayed(
                                { startWakeListening() },
                                1000
                            )
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                        val heard = matches
                            ?.firstOrNull()
                            .orEmpty()
                            .lowercase(Locale.ROOT)

                        val wakePhraseDetected =
                            heard.contains("hey sunday") ||
                            heard.contains("hey, sunday") ||
                            heard.contains("hey sundae")

                        if (
                            wakePhraseDetected &&
                            latestWakeEnabled.value
                        ) {
                            awaitingCommand = true
                            wakeStatus = "Wake phrase detected..."

                            try {
                                recognizer.cancel()

                                val commandIntent = Intent(
                                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                                ).apply {
                                    putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                    )
                                    putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE,
                                        Locale.getDefault().toLanguageTag()
                                    )
                                    putExtra(
                                        RecognizerIntent.EXTRA_PROMPT,
                                        "Yes? What can I do for you?"
                                    )
                                }

                                listening = true
                                speechLauncher.launch(commandIntent)
                            } catch (_: Exception) {
                                listening = false
                                awaitingCommand = false
                                wakeStatus = "Tap the microphone to speak."

                                wakeHandler.postDelayed(
                                    { startWakeListening() },
                                    1000
                                )
                            }
                        } else if (latestWakeEnabled.value) {
                            wakeHandler.postDelayed(
                                { startWakeListening() },
                                500
                            )
                        }
                    }

                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) = Unit

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) = Unit
                }

                recognizer.setRecognitionListener(listener)
            } else {
                wakeStatus = "Speech recognition is unavailable."
            }

            onDispose {
                wakeHandler.removeCallbacksAndMessages(null)

                try {
                    recognizer?.cancel()
                    recognizer?.destroy()
                } catch (_: Exception) {
                    // Safe cleanup when the screen closes.
                }
            }
        }

        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = navy
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(18.dp))

                    Text(
                        text = "S U N D A Y",
                        color = cyan,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "PERSONAL AI ASSISTANT",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        letterSpacing = 3.sp
                    )

                    Spacer(Modifier.height(34.dp))

                    Box(
                        modifier = Modifier
                            .size(190.dp)
                            .background(
                                Color(0xFF0C2B40),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(145.dp)
                                .background(
                                    Color(0xFF10506A),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(95.dp)
                                    .background(cyan, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "S",
                                    color = navy,
                                    fontSize = 48.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    Text(
                        text = if (listening) {
                            "LISTENING..."
                        } else {
                            "SYSTEM ONLINE"
                        },
                        color = cyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(22.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = panel
                        ),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text(
                                text = "SUNDAY",
                                color = cyan,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(Modifier.height(8.dp))

                            Text(
                                text = response,
                                color = Color.White,
                                fontSize = 16.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Give Sunday a command") },
                        placeholder = { Text("Try: Open YouTube") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = { runCommand(command) }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = cyan,
                            unfocusedBorderColor = Color.Gray,
                            focusedLabelColor = cyan,
                            unfocusedLabelColor = Color.LightGray,
                            cursorColor = cyan
                        )
                    )

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = { runCommand(command) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cyan,
                            contentColor = navy
                        )
                    ) {
                        Text(
                            text = "EXECUTE COMMAND",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            if (
                                ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                val intent = Intent(
                                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                                ).apply {
                                    putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                    )
                                    putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE,
                                        Locale.getDefault().toLanguageTag()
                                    )
                                    putExtra(
                                        RecognizerIntent.EXTRA_PROMPT,
                                        "Speak to Sunday"
                                    )
                                }

                                try {
                                    listening = true
                                    speechLauncher.launch(intent)
                                } catch (_: Exception) {
                                    listening = false
                                    speak("Voice recognition is unavailable.")
                                }
                            } else {
                                microphonePermissionLauncher.launch(
                                    Manifest.permission.RECORD_AUDIO
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = cyan
                        )
                    ) {
                        Text("🎙  SPEAK TO SUNDAY")
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (wakeWordEnabled) {
                                wakeWordEnabled = false
                                awaitingCommand = false
                                wakeStatus = "Wake word is off"
                                wakeHandler.removeCallbacksAndMessages(null)

                                try {
                                    wakeRecognizer?.cancel()
                                } catch (_: Exception) {
                                    // The recognizer may already be stopped.
                                }
                            } else {
                                if (
                                    ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    if (wakeRecognizer == null) {
                                        speak(
                                            "Speech recognition is unavailable on this device."
                                        )
                                    } else {
                                        wakeWordEnabled = true
                                        wakeStatus = "Starting wake-word listener..."

                                        wakeHandler.postDelayed(
                                            { startWakeListening() },
                                            300
                                        )
                                    }
                                } else {
                                    wakePermissionLauncher.launch(
                                        Manifest.permission.RECORD_AUDIO
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (wakeWordEnabled) {
                                Color(0xFFB93838)
                            } else {
                                cyan
                            },
                            contentColor = navy
                        )
                    ) {
                        Text(
                            text = if (wakeWordEnabled) {
                                "STOP HEY SUNDAY"
                            } else {
                                "ENABLE HEY SUNDAY"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = wakeStatus,
                        color = if (wakeWordEnabled) {
                            cyan
                        } else {
                            Color.LightGray
                        },
                        fontSize = 12.sp
                    )

                    Spacer(Modifier.height(22.dp))

                    Text(
                        text = "QUICK LAUNCH",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        letterSpacing = 2.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    val apps = listOf(
                        "YouTube",
                        "WhatsApp",
                        "Chrome",
                        "Spotify",
                        "Instagram",
                        "Gmail",
                        "Maps",
                        "Calendar"
                    )

                    apps.chunked(2).forEach { rowApps ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowApps.forEach { app ->
                                OutlinedButton(
                                    onClick = {
                                        runCommand("open $app")
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = cyan
                                    )
                                ) {
                                    Text(app)
                                }
                            }

                            if (rowApps.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }

                    Spacer(Modifier.height(22.dp))

                    Text(
                        text = "SUNDAY • EARLY DEVELOPMENT BUILD",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}