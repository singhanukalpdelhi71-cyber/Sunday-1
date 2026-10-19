 
package com.sunday.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognizerIntent
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

        var pendingContactName by remember { mutableStateOf("") }
        var pendingContactAction by remember { mutableStateOf("find") }
        var pendingCallName by remember { mutableStateOf("") }
        var pendingCallNumber by remember {
            mutableStateOf<String?>(null)
        }

        val tts = remember {
            TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsLanguageReady = true
                }
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
            tts.language = Locale.US
            tts.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "sunday-response"
            )
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
                    Pair(cursor.getString(0), cursor.getString(1))
                } else {
                    null
                }
            }
        }

        fun handleContactRequest() {
            val requestedName = pendingContactName
            val match = try {
                searchContact(requestedName)
            } catch (_: SecurityException) {
                speak("I don't have permission to read contacts.")
                return
            } catch (_: Exception) {
                speak("I couldn't search your contacts.")
                return
            }

            if (match == null) {
                pendingCallName = ""
                pendingCallNumber = null
                speak("I couldn't find a contact matching $requestedName.")
                return
            }

            val (name, number) = match

            if (pendingContactAction == "call") {
                pendingCallName = name
                pendingCallNumber = number
                speak(
                    "I found $name, number $number. " +
                    "Say confirm call to place the call, " +
                    "or cancel call to cancel."
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
                    speak(
                        "Contacts permission was denied. " +
                        "I can't search your contacts."
                    )
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
                } else if (!granted) {
                    speak(
                        "Phone permission was denied. " +
                        "I won't place the call."
                    )
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

                    val name = if (text.startsWith("find contact ")) {
                        raw.trim().substringAfter("find contact ").trim()
                    } else {
                        raw.trim().substringAfter("find ").trim()
                    }

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
                    val name = raw.trim().substringAfter("call ").trim()

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
                        speak(
                            "There is no call waiting for confirmation. " +
                            "First say call followed by a contact's name."
                        )
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
                            speak("I couldn't start the call. Please try again.")
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

                text in listOf("hello", "hi sunday", "hello sunday") -> {
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
                        "and help open apps."
                    )
                }

                text.startsWith("open ") ||
                    text.startsWith("launch ") -> {

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
                        speak(
                            "I'm not sure which app you mean. " +
                            "Please tell me the exact app name."
                        )
                    } else {
                        val launchIntent =
                            packageManager.getLaunchIntentForPackage(
                                packageName
                            )

                        if (launchIntent != null) {
                            speak("Opening $appName.")
                            try {
                                startActivity(launchIntent)
                            } catch (_: Exception) {
                                speak("I couldn't open that app.")
                            }
                        } else {
                            speak(
                                "$appName may not be installed, " +
                                "or Android may not allow me to open it."
                            )
                        }
                    }
                }

                else -> {
                    speak(
                        "I don't understand that command yet. " +
                        "Try open YouTube, find contact followed by a name, " +
                        "or call followed by a name."
                    )
                }
            }
        }

        val speechLauncher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                listening = false

                if (result.resultCode == Activity.RESULT_OK) {
                    val spokenText = result.data
                        ?.getStringArrayListExtra(
                            RecognizerIntent.EXTRA_RESULTS
                        )
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
            }

        val permissionLauncher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    try {
                        listening = true
                        val intent = Intent(
                            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                        ).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE,
                                Locale.getDefault()
                            )
                            putExtra(
                                RecognizerIntent.EXTRA_PROMPT,
                                "Speak to Sunday"
                            )
                        }
                        speechLauncher.launch(intent)
                    } catch (_: Exception) {
                        listening = false
                        speak("Voice recognition is unavailable.")
                    }
                } else {
                    speak("Microphone permission is needed for voice input.")
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
                            .background(Color(0xFF0C2B40), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(145.dp)
                                .background(Color(0xFF10506A), CircleShape),
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
                        text = if (listening) "LISTENING..." else "SYSTEM ONLINE",
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

                    Spacer(Modifier.height(24.dp))

                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {Text("Give Sunday a command") },
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
                        onClick = { runCommand(command) },modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cyan,
                            contentColor = navy
                        )
                    ) {
                        Text("EXECUTE COMMAND", fontWeight = FontWeight.Bold)
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
                                try {
                                    listening = true
                                    val intent = Intent(
                                        RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                                    ).apply {
                                        putExtra(
                                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                        )
                                        putExtra(
                                            RecognizerIntent.EXTRA_LANGUAGE,
                                            Locale.getDefault()
                                        )
                                        putExtra(
                                            RecognizerIntent.EXTRA_PROMPT,
                                            "Speak to Sunday"
                                        )
                                    }
                                    speechLauncher.launch(intent)
                                } catch (_: Exception) {
                                    listening = false
                                    speak("Voice recognition is unavailable.")
                                }
                            } else {
                                permissionLauncher.launch(
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
                    }Spacer(Modifier.height(22.dp))

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
                                    onClick = { runCommand("open $app") },
                                    modifier = Modifier.weight(1f),
                                    colors =ButtonDefaults.outlinedButtonColors(
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

    private var ttsLanguageReady: Boolean = false
}
 