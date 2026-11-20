package app.mettle.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mettle.data.remote.Backend
import app.mettle.data.remote.Profile
import app.mettle.ui.theme.Inter
import app.mettle.ui.theme.MettleColors
import kotlinx.coroutines.launch

/** Turns server errors into plain-language messages. */
private fun friendly(e: Throwable): String {
    val m = (e.message ?: "").lowercase()
    return when {
        "signup" in m || "not allowed" in m || "user not found" in m -> "This email hasn't been invited to Mettle yet. Ask your group admin."
        "expired" in m || "invalid" in m || "token" in m -> "That code is wrong or has expired. Request a new one."
        "rate" in m || "too many" in m || "security purposes" in m -> "Too many attempts. Wait a minute and try again."
        "unable to resolve" in m || "network" in m || "timeout" in m || "failed to connect" in m -> "No internet connection. Check your connection and try again."
        else -> "Something went wrong. Please try again."
    }
}

private val fieldColors @Composable get() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MettleColors.Orange,
    focusedLabelColor = MettleColors.Orange,
    cursorColor = MettleColors.Orange,
)

@Composable
private fun AuthFrame(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MettleColors.Surface).statusBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text("METTLE", fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, fontSize = 34.sp, letterSpacing = (-0.8).sp)
        content()
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled && !busy,
        colors = ButtonDefaults.buttonColors(containerColor = MettleColors.Orange, contentColor = MettleColors.Surface),
        shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        if (busy) CircularProgressIndicator(color = MettleColors.Surface, strokeWidth = 2.dp, modifier = Modifier.height(22.dp))
        else Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SignInScreen() {
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    AuthFrame {
        Text(if (!codeSent) "Sign in" else "Check your email", style = MaterialTheme.typography.headlineLarge)
        Text(
            if (!codeSent) "Mettle is invite-only. Enter the email your group admin invited."
            else "We sent a sign-in code to $email. It may take a minute to arrive, and it works for 10 minutes.",
            style = MaterialTheme.typography.bodyLarge, color = MettleColors.Ink2,
        )
        if (!codeSent) {
            OutlinedTextField(
                value = email, onValueChange = { email = it; error = null }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), colors = fieldColors, modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton("Send code", enabled = "@" in email && "." in email, busy = busy) {
                busy = true; error = null
                scope.launch {
                    runCatching { Backend.sendCode(email) }
                        .onSuccess { codeSent = true }
                        .onFailure { error = friendly(it) }
                    busy = false
                }
            }
        } else {
            OutlinedTextField(
                value = code, onValueChange = { v -> code = v.filter(Char::isDigit).take(10); error = null },
                label = { Text("Code from the email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), colors = fieldColors, modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton("Sign in", enabled = code.length in 6..10, busy = busy) {
                busy = true; error = null
                scope.launch {
                    runCatching { Backend.verifyCode(email, code) }.onFailure { error = friendly(it) }
                    busy = false
                }
            }
            TextButton(onClick = { codeSent = false; code = ""; error = null }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Use a different email or resend", color = MettleColors.Orange)
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MettleColors.WarnText) }
    }
}

@Composable
fun ProfileSetupScreen(userId: String, existing: Profile?, onDone: (Profile) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(existing?.displayName.orEmpty()) }
    var height by rememberSaveable { mutableStateOf(existing?.heightCm?.toString().orEmpty()) }
    var weight by rememberSaveable { mutableStateOf(existing?.weightKg?.toString().orEmpty()) }
    var goal by rememberSaveable { mutableStateOf((existing?.stepGoal ?: 8000).toString()) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val h = height.toIntOrNull()
    val w = weight.replace(',', '.').toDoubleOrNull()
    val g = goal.toIntOrNull()
    val valid = name.isNotBlank() && h != null && h in 100..230 && (weight.isBlank() || (w != null && w in 20.0..300.0)) && g != null && g in 1000..100000

    AuthFrame {
        Text("Create your profile", style = MaterialTheme.typography.headlineLarge)
        Text("Height sets your stride, so step distance is accurate. Weight improves calorie estimates.",
            style = MaterialTheme.typography.bodyLarge, color = MettleColors.Ink2)
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name shown to your group") }, singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(height, { height = it.filter(Char::isDigit).take(3) }, label = { Text("Height (cm)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(weight, { weight = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) }, label = { Text("Weight (kg, optional)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = fieldColors, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(goal, { goal = it.filter(Char::isDigit).take(6) }, label = { Text("Daily step goal") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors, modifier = Modifier.fillMaxWidth())
        PrimaryButton("Save and continue", enabled = valid, busy = busy) {
            busy = true; error = null
            scope.launch {
                runCatching { Backend.saveProfile(userId, name, h!!, w, g!!) }
                    .onSuccess { p -> if (p != null) onDone(p) else error = "Couldn't load your profile. Try again." }
                    .onFailure { error = friendly(it) }
                busy = false
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MettleColors.WarnText) }
    }
}
