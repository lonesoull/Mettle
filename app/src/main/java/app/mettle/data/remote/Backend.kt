package app.mettle.data.remote

import android.content.Context
import app.mettle.BuildConfig
import com.russhwolf.settings.SharedPreferencesSettings
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val role: String = "member",
    @SerialName("height_cm") val heightCm: Int? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("step_goal") val stepGoal: Int = 8000,
    val units: String = "metric",
) {
    val isAdmin: Boolean get() = role == "admin"
    /** A profile is complete once the member has entered their height. */
    val isComplete: Boolean get() = heightCm != null
}

@Serializable
private data class ProfileUpdate(
    @SerialName("display_name") val displayName: String,
    @SerialName("height_cm") val heightCm: Int,
    @SerialName("weight_kg") val weightKg: Double?,
    @SerialName("step_goal") val stepGoal: Int,
)

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String, val email: String?) : AuthState
}

/** Single connection to the Mettle server (Supabase). */
object Backend {
    private lateinit var appContext: Context

    /** Called once from MettleApplication so the login is stored in Mettle's own private storage. */
    fun init(context: Context) {
        appContext = context.applicationContext
        // One-time move of a login saved by older versions (default settings file) into Mettle's own file.
        val target = appContext.getSharedPreferences("mettle_session", Context.MODE_PRIVATE)
        if (target.all.isEmpty()) {
            val old = appContext.getSharedPreferences("${appContext.packageName}_preferences", Context.MODE_PRIVATE)
            val edit = target.edit()
            old.all.forEach { (k, v) -> if (v is String) edit.putString(k, v) }
            edit.apply()
        }
    }

    val client: SupabaseClient by lazy {
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
            install(Auth) {
                // Keep the login across restarts and updates; it only ends when you press Sign out.
                sessionManager = SettingsSessionManager(
                    SharedPreferencesSettings(appContext.getSharedPreferences("mettle_session", Context.MODE_PRIVATE)),
                )
                alwaysAutoRefresh = true
            }
            install(Postgrest)
            install(io.github.jan.supabase.storage.Storage)
            install(io.github.jan.supabase.functions.Functions)
        }
    }

    val authState: Flow<AuthState>
        get() = client.auth.sessionStatus.map { status ->
            when (status) {
                is SessionStatus.Authenticated -> {
                    val user = status.session.user
                    if (user != null) AuthState.SignedIn(user.id, user.email) else AuthState.Loading
                }
                is SessionStatus.NotAuthenticated -> AuthState.SignedOut
                // Offline or server slow: the saved login is still valid locally, so stay signed in.
                is SessionStatus.RefreshFailure -> client.auth.currentUserOrNull()?.let { AuthState.SignedIn(it.id, it.email) } ?: AuthState.Loading
                SessionStatus.Initializing -> AuthState.Loading
            }
        }

    /** Sends a 6-digit code. Only invited members get one (sign-ups are closed). */
    suspend fun sendCode(email: String) {
        client.auth.signInWith(OTP) {
            this.email = email.trim()
            createUser = false
        }
    }

    suspend fun verifyCode(email: String, code: String) {
        client.auth.verifyEmailOtp(type = OtpType.Email.EMAIL, email = email.trim(), token = code.trim())
    }

    suspend fun signOut() = client.auth.signOut()

    suspend fun loadProfile(userId: String): Profile? =
        client.from("profiles").select { filter { eq("id", userId) } }.decodeSingleOrNull<Profile>()

    suspend fun saveProfile(userId: String, name: String, heightCm: Int, weightKg: Double?, stepGoal: Int): Profile? {
        client.from("profiles").update(ProfileUpdate(name.trim(), heightCm, weightKg, stepGoal)) {
            filter { eq("id", userId) }
        }
        return loadProfile(userId)
    }
}
