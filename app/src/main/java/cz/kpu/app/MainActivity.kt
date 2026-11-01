package cz.kpu.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val Yellow = Color(0xFFFFC928)
private val Black = Color(0xFF101112)
private val Panel = Color(0xFF1C1F21)
private val Green = Color(0xFF20B85A)
private val Red = Color(0xFFE64B43)
private val Gray = Color(0xFF555B60)
private val dateFmt = DateTimeFormatter.ofPattern("d.M.yyyy")
private val syncTimeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
private fun syncClock(): String = LocalTime.now(ZoneId.systemDefault()).format(syncTimeFmt)

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KpuRoot(this) }
    }
}

private data class GithubReleaseApk(val tag: String, val name: String, val url: String)

@Suppress("DEPRECATION")
private fun archiveVersionCode(context: Context, file: File): Long? {
    val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}

/** Finds and downloads the APK from the latest public GitHub release, if it is newer. */
private suspend fun downloadLatestKpuUpdate(context: Context): File? = withContext(Dispatchers.IO) {
    val api = URL("https://api.github.com/repos/radekklemsa/KPU/releases/latest")
    val apiConnection = (api.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15000
        readTimeout = 20000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        setRequestProperty("User-Agent", "KPU-Android-Updater")
    }
    val release = try {
        val code = apiConnection.responseCode
        if (code == 404) return@withContext null
        if (code !in 200..299) throw IllegalStateException("GitHub vrátil odpověď $code.")
        val json = apiConnection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        val assets = json.optJSONArray("assets") ?: JSONArray()
        val apkAssets = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        val preferred = apkAssets.firstOrNull { it.optString("name").equals("app-release.apk", true) }
            ?: apkAssets.firstOrNull { it.optString("name").contains("kpu", true) && !it.optString("name").contains("debug", true) }
            ?: apkAssets.firstOrNull { !it.optString("name").contains("debug", true) }
            ?: apkAssets.firstOrNull()
            ?: return@withContext null
        val downloadUrl = preferred.optString("browser_download_url")
        if (!downloadUrl.startsWith("https://github.com/radekklemsa/KPU/releases/download/")) {
            throw IllegalStateException("APK v posledním vydání má neplatný odkaz.")
        }
        GithubReleaseApk(json.optString("tag_name", "nová verze"), preferred.optString("name"), downloadUrl)
    } finally {
        apiConnection.disconnect()
    }

    val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
    val safeTag = release.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val apk = File(updatesDir, "kpu-update-$safeTag.apk")
    fun isNewCompatibleApk(file: File): Boolean {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return false
        val code = archiveVersionCode(context, file) ?: return false
        return info.packageName == context.packageName && code > BuildConfig.VERSION_CODE.toLong()
    }
    if (!apk.isFile || !isNewCompatibleApk(apk)) {
        apk.delete()
        val part = File(updatesDir, "${apk.name}.part")
        part.delete()
        val connection = (URL(release.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 60000
            setRequestProperty("User-Agent", "KPU-Android-Updater")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Stažení aktualizace selhalo (HTTP $code).")
            val declaredSize = connection.contentLengthLong
            if (declaredSize > 250L * 1024 * 1024) throw IllegalStateException("APK je neobvykle velké.")
            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 250L * 1024 * 1024) throw IllegalStateException("APK je neobvykle velké.")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (declaredSize > 0 && part.length() != declaredSize) throw IllegalStateException("Stažený APK soubor není úplný.")
            if (!part.renameTo(apk)) throw IllegalStateException("Stažený APK se nepodařilo uložit.")
        } finally {
            connection.disconnect()
            part.delete()
        }
    }
    if (!isNewCompatibleApk(apk)) {
        apk.delete()
        return@withContext null
    }
    apk
}

private fun startKpuUpdateInstall(context: Context, apk: File): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(settingsIntent)
        return "V nastavení povol instalaci aplikací z tohoto zdroje. Potom se vrať do KPU a klepni znovu na Nainstalovat."
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(installIntent)
    return null
}


private data class KpuSession(val accessToken: String, val refreshToken: String, val email: String, val userId: String)

private suspend fun signInSupabase(email: String, password: String): KpuSession = withContext(Dispatchers.IO) {
    val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    val apiKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    require(baseUrl.startsWith("https://") && apiKey.isNotBlank()) {
        "Chybí SUPABASE_URL nebo SUPABASE_PUBLISHABLE_KEY. Zkontroluj android/local.properties a znovu synchronizuj Gradle."
    }
    val url = URL("$baseUrl/auth/v1/token?grant_type=password")
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15000
        readTimeout = 15000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("apikey", apiKey)
    }
    try {
        val payload = JSONObject().put("email", email.trim()).put("password", password)
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (code !in 200..299) {
            val message = json?.optString("msg")?.takeIf { it.isNotBlank() }
                ?: json?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json?.optString("error_description")?.takeIf { it.isNotBlank() }
                ?: "Přihlášení se nezdařilo (HTTP $code). Zkontroluj e-mail, heslo a nastavení Supabase Auth."
            throw IllegalStateException(message)
        }
        val user = json?.optJSONObject("user") ?: throw IllegalStateException("Supabase nevrátil uživatelský účet.")
        KpuSession(
            accessToken = json.optString("access_token"),
            refreshToken = json.optString("refresh_token"),
            email = user.optString("email", email.trim()),
            userId = user.optString("id")
        )
    } finally { conn.disconnect() }
}

private suspend fun refreshSupabase(refreshToken: String): KpuSession = withContext(Dispatchers.IO) {
    val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    val apiKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    require(baseUrl.startsWith("https://") && apiKey.isNotBlank()) { "Chybí nastavení Supabase." }
    val conn = (URL("$baseUrl/auth/v1/token?grant_type=refresh_token").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 15000; readTimeout = 15000; doOutput = true
        setRequestProperty("Content-Type", "application/json"); setRequestProperty("apikey", apiKey)
    }
    try {
        val payload = JSONObject().put("refresh_token", refreshToken)
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (code !in 200..299) throw IllegalStateException(json?.optString("message")?.takeIf { it.isNotBlank() } ?: "Uložené přihlášení vypršelo. Přihlas se e-mailem a heslem.")
        val user = json?.optJSONObject("user") ?: throw IllegalStateException("Supabase nevrátil uživatelský účet.")
        KpuSession(json.optString("access_token"), json.optString("refresh_token", refreshToken), user.optString("email"), user.optString("id"))
    } finally { conn.disconnect() }
}

private fun compressPhotoToAbout1Mb(context: Context, uri: Uri): ByteArray {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // Při inJustDecodeBounds=true BitmapFactory.decodeStream správně vrací null;
    // rozměry se ale zapíší do Options. Null proto nesmí být vyhodnocen jako chyba.
    try {
        val input = resolver.openInputStream(uri)
            ?: throw IllegalStateException("Telefon neotevřel vybranou fotografii.")
        input.use { BitmapFactory.decodeStream(it, null, bounds) }
    } catch (e: SecurityException) {
        throw IllegalStateException("Telefon nepovolil přístup k fotografii. Vyber ji znovu z galerie.", e)
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: Exception) {
        throw IllegalStateException("Fotografii se nepodařilo otevřít (${e.javaClass.simpleName}). Vyber ji znovu z galerie.", e)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("Soubor není platná nebo čitelná fotografie.")
    var sample = 1
    while (bounds.outWidth / sample > 1920 || bounds.outHeight / sample > 1920) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: throw IllegalStateException("Fotografii se nepodařilo dekódovat.")
    try {
        var quality = 88
        var bytes: ByteArray
        do {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) throw IllegalStateException("Komprese fotografie selhala.")
            bytes = output.toByteArray()
            if (bytes.size <= 1_000_000 || quality <= 45) break
            quality -= 7
        } while (true)
        return bytes
    } finally { bitmap.recycle() }
}

private fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private fun mergeOfflineChanges(base: Store, local: Store, remote: Store): Store {
    val mergedCells = remote.cells.toMutableMap()
    (base.cells.keys + local.cells.keys).forEach { key ->
        if (base.cells[key] != local.cells[key]) {
            val value = local.cells[key]
            if (value == null) mergedCells.remove(key) else mergedCells[key] = value
        }
    }
    val mergedMachines = remote.machines.toMutableMap()
    (base.machines.keys + local.machines.keys).forEach { group ->
        val baseList = base.machines[group].orEmpty().associateBy { it.id }
        val localList = local.machines[group].orEmpty().associateBy { it.id }
        val result = mergedMachines[group].orEmpty().associateBy { it.id }.toMutableMap()
        (baseList.keys + localList.keys).forEach { id ->
            if (baseList[id] != localList[id]) {
                val value = localList[id]
                if (value == null) result.remove(id) else result[id] = value
            }
        }
        mergedMachines[group] = result.values.sortedBy { it.id }
    }
    val mergedPhotos = (remote.photos + local.photos.filterNot { it in base.photos }).distinct()
    return Store(mergedMachines, mergedCells, mergedPhotos)
}

private suspend fun uploadKpuPhoto(context: Context, session: KpuSession, uri: Uri): String = withContext(Dispatchers.IO) {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val apiKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    val contentType = "image/jpeg"
    val ext = "jpg"
    val path = "${session.userId}/${System.currentTimeMillis()}-${UUID.randomUUID()}.$ext"
    val bytes = compressPhotoToAbout1Mb(context, uri)
    val conn = (URL("$base/storage/v1/object/kpu-photos/$path").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 30000; readTimeout = 30000; doOutput = true
        setRequestProperty("apikey", apiKey)
        setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        setRequestProperty("Content-Type", contentType)
        setRequestProperty("x-upsert", "false")
    }
    try {
        conn.outputStream.use { it.write(bytes) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("Nahrání fotografie selhalo (HTTP $code): ${body.take(250)}")
        path
    } finally { conn.disconnect() }
}

private fun showBiometric(activity: FragmentActivity, onResult: (Boolean, String?) -> Unit) {
    val manager = BiometricManager.from(activity)
    val availability = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
        onResult(false, "Telefon nemá nastavený podporovaný otisk prstu nebo zámek obrazovky.")
        return
    }
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onResult(true, null) }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onResult(false, errString.toString()) }
        override fun onAuthenticationFailed() { /* uživatel může zkusit znovu */ }
    })
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Přihlášení do KPU")
        .setSubtitle("Ověř svou totožnost")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        .build()
    prompt.authenticate(info)
}

@Composable
private fun KpuRoot(context: Context) {
    val prefs = remember { context.getSharedPreferences("kpu_auth", Context.MODE_PRIVATE) }
    var updateApk by remember { mutableStateOf<File?>(null) }
    var updateVersion by remember { mutableStateOf("") }
    var updateMessage by remember { mutableStateOf("") }
    var checkingUpdate by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<KpuSession?>(null) }
    var email by remember { mutableStateOf(prefs.getString("email", "") ?: "") }
    var password by remember { mutableStateOf("") }
    var rememberLogin by remember { mutableStateOf(prefs.getBoolean("remember_login", true)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun checkForUpdates() {
        if (checkingUpdate) return
        checkingUpdate = true
        updateApk = null
        showUpdateDialog = false
        updateMessage = "Kontroluji GitHub Releases…"
        scope.launch {
            try {
                val apk = downloadLatestKpuUpdate(context)
                if (apk == null) {
                    updateMessage = "Nebylo nalezeno novější APK v posledním vydání."
                } else {
                    updateApk = apk
                    updateVersion = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.versionName ?: "nová verze"
                    updateMessage = "Je dostupná nová verze KPU ($updateVersion). Klepni na Nainstalovat a potvrď instalaci v Androidu."
                }
            } catch (e: Exception) {
                updateMessage = "Kontrolu aktualizací se nepodařilo dokončit: ${e.message ?: "zkontroluj připojení"}"
            } finally {
                checkingUpdate = false
                showUpdateDialog = true
            }
        }
    }
    val activity = context as? FragmentActivity
    var hasSavedLogin by remember { mutableStateOf(!prefs.getString("refresh_token", null).isNullOrBlank() && prefs.getBoolean("remember_login", true)) }
    MaterialTheme(colorScheme = darkColorScheme(primary = Yellow, onPrimary = Color.Black, background = Black, surface = Panel, onBackground = Color.White, onSurface = Color.White)) {
        if (session == null) {
            Column(
                Modifier.fillMaxSize().background(Black).statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("KPU", color = Yellow, fontSize = 42.sp, fontWeight = FontWeight.Black)
                Text("Přihlášení", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
                OutlinedTextField(value = email, onValueChange = { email = it; error = "" }, label = { Text("E-mail") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = password, onValueChange = { password = it; error = "" }, label = { Text("Heslo") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberLogin, onCheckedChange = { rememberLogin = it; prefs.edit().putBoolean("remember_login", it).apply(); if (!it) { prefs.edit().remove("refresh_token").apply(); hasSavedLogin = false } else { hasSavedLogin = !prefs.getString("refresh_token", null).isNullOrBlank() } }, colors = CheckboxDefaults.colors(checkedColor = Yellow, checkmarkColor = Color.Black))
                    Text("Zapamatovat přihlášení", color = Color.White, fontSize = 14.sp)
                }
                if (error.isNotBlank()) Text(error, color = Red, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                Button(
                    onClick = {
                        if (email.isBlank() || password.isBlank()) { error = "Vyplň e-mail a heslo." }
                        else {
                            loading = true; error = ""
                            scope.launch {
                                try {
                                    val loggedIn = signInSupabase(email, password)
                                    prefs.edit().putString("email", loggedIn.email).putBoolean("remember_login", rememberLogin).apply()
                                    if (rememberLogin && loggedIn.refreshToken.isNotBlank()) { prefs.edit().putString("refresh_token", loggedIn.refreshToken).apply(); hasSavedLogin = true }
                                    else { prefs.edit().remove("refresh_token").apply(); hasSavedLogin = false }
                                    session = loggedIn
                                    password = ""
                                } catch (e: Exception) { error = e.message ?: "Přihlášení se nezdařilo." }
                                finally { loading = false }
                            }
                        }
                    }, enabled = !loading, colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Color.Black), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) { Text(if (loading) "Přihlašuji…" else "PŘIHLÁSIT SE", fontWeight = FontWeight.Bold) }
                if (hasSavedLogin && activity != null) {
                    OutlinedButton(onClick = {
                        loading = true; error = ""
                        showBiometric(activity) { ok, message ->
                            if (!ok) { loading = false; error = message ?: "Ověření se nezdařilo." }
                            else scope.launch {
                                try {
                                    val token = prefs.getString("refresh_token", null) ?: throw IllegalStateException("Uložené přihlášení chybí. Přihlas se e-mailem a heslem.")
                                    val loggedIn = refreshSupabase(token)
                                    prefs.edit().putString("refresh_token", loggedIn.refreshToken).putString("email", loggedIn.email).apply()
                                    session = loggedIn
                                } catch (e: Exception) { error = e.message ?: "Přihlášení otiskem se nezdařilo." }
                                finally { loading = false }
                            }
                        }
                    }, enabled = !loading, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Text(if (loading) "Ověřuji…" else "OTISKEM PRSTU / ZÁMKEM TELEFONU")
                    }
                }
                Text("Použij účet vytvořený v Supabase Auth.", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
                OutlinedButton(onClick = ::checkForUpdates, enabled = !checkingUpdate, modifier = Modifier.padding(top = 10.dp)) {
                    Text(if (checkingUpdate) "Kontroluji…" else "Zkontrolovat aktualizace")
                }
            }
        } else {
            KpuApp(context, session!!, onCheckUpdates = ::checkForUpdates, checkingUpdate = checkingUpdate) {
                // Odhlášení ukončí aktuální relaci, ale ponechá obnovovací token,
                // pokud si uživatel zvolil zapamatování přihlášení, aby mohl příště použít biometrii.
                hasSavedLogin = prefs.getBoolean("remember_login", true) && !prefs.getString("refresh_token", null).isNullOrBlank()
                session = null
            }
        }
        if (showUpdateDialog) {
            AlertDialog(
                onDismissRequest = { showUpdateDialog = false },
                title = { Text("Aktualizace KPU") },
                text = { Text(updateMessage) },
                confirmButton = {
                    TextButton(onClick = {
                        val apk = updateApk
                        if (apk == null) {
                            showUpdateDialog = false
                        } else {
                            try {
                                val instruction = startKpuUpdateInstall(context, apk)
                                if (instruction != null) updateMessage = instruction
                            } catch (e: Exception) {
                                updateMessage = "Instalaci se nepodařilo spustit: ${e.message ?: "zkontroluj oprávnění telefonu"}"
                            }
                        }
                    }) { Text(if (updateApk == null) "Zavřít" else "Nainstalovat", color = Yellow) }
                },
                dismissButton = { if (updateApk != null) TextButton(onClick = { showUpdateDialog = false }) { Text("Později") } }
            )
        }
    }
}

private enum class Tab(val label: String) { WEEK("Týdenní KPU"), MONTH("Měsíční KPU"), QUARTER("Kvartální KPU"), OTHER("Ostatní KPU"), FILTERS("Filtry") }
private data class Machine(val id: Long, val name: String, val every14: Boolean = false)
private data class Cell(val date: String = "", val done: Boolean = false, val inactive: Boolean = false)
private data class Store(val machines: Map<String, List<Machine>> = emptyMap(), val cells: Map<String, Cell> = emptyMap(), val photos: List<String> = emptyList())

private fun storeToJson(store: Store): JSONObject {
    val root = JSONObject(); val mo = JSONObject(); val co = JSONObject()
    store.machines.forEach { (key, list) -> mo.put(key, JSONArray().apply { list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("every14", it.every14)) } }) }
    store.cells.forEach { (key, cell) -> co.put(key, JSONObject().put("date", cell.date).put("done", cell.done).put("inactive", cell.inactive)) }
    root.put("machines", mo).put("cells", co).put("photos", JSONArray().apply { store.photos.forEach { put(it) } })
    return root
}

private fun storeFromJson(root: JSONObject): Store {
    val machineObj = root.optJSONObject("machines") ?: JSONObject()
    val machines = mutableMapOf<String, List<Machine>>()
    machineObj.keys().forEach { key ->
        val arr = machineObj.optJSONArray(key) ?: JSONArray()
        machines[key] = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Machine(it.optLong("id"), it.optString("name"), it.optBoolean("every14")) } }
    }
    val cellObj = root.optJSONObject("cells") ?: JSONObject()
    val cells = mutableMapOf<String, Cell>()
    cellObj.keys().forEach { key -> cellObj.optJSONObject(key)?.let { cells[key] = Cell(it.optString("date"), it.optBoolean("done"), it.optBoolean("inactive")) } }
    val photosArray = root.optJSONArray("photos") ?: JSONArray()
    val photos = (0 until photosArray.length()).mapNotNull { i -> photosArray.optString(i).takeIf { it.isNotBlank() } }
    return Store(machines, cells, photos)
}

private fun loadStore(context: Context): Store = try {
    storeFromJson(JSONObject(context.getSharedPreferences("kpu_local", Context.MODE_PRIVATE).getString("store", "{}") ?: "{}"))
} catch (_: Exception) { Store() }

private fun saveStore(context: Context, store: Store) {
    context.getSharedPreferences("kpu_local", Context.MODE_PRIVATE).edit().putString("store", storeToJson(store).toString()).apply()
}

// Shared workspace is anchored to the administrator account's existing row.
private const val KPU_SHARED_OWNER_ID = "17c5cd22-599b-47d6-aaa1-32f8fd1ca9fd"

private suspend fun fetchRemoteStore(session: KpuSession): Store? = withContext(Dispatchers.IO) {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val conn = (URL("$base/rest/v1/kpu_app_state?select=data&user_id=eq.$KPU_SHARED_OWNER_ID&limit=1").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 15000; readTimeout = 15000
        setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
        setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        setRequestProperty("Accept", "application/json")
    }
    try {
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("Načtení dat ze Supabase selhalo (HTTP $code): ${body.take(300)}")
        val arr = JSONArray(body)
        if (arr.length() == 0) null else storeFromJson(arr.getJSONObject(0).optJSONObject("data") ?: JSONObject())
    } finally { conn.disconnect() }
}

private suspend fun fetchCurrentRole(session: KpuSession): String = withContext(Dispatchers.IO) {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val conn = (URL("$base/rest/v1/kpu_profiles?select=role&user_id=eq.${session.userId}&limit=1").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 15000; readTimeout = 15000
        setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
        setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        setRequestProperty("Accept", "application/json")
    }
    try {
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("Ověření role selhalo (HTTP $code): ${body.take(250)}")
        val rows = JSONArray(body)
        if (rows.length() == 0) throw IllegalStateException("Pro účet nebyla nalezena role KPU.")
        rows.getJSONObject(0).optString("role")
    } finally { conn.disconnect() }
}

private suspend fun saveRemoteStore(session: KpuSession, store: Store) = withContext(Dispatchers.IO) {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val conn = (URL("$base/rest/v1/kpu_app_state?on_conflict=user_id").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 15000; readTimeout = 15000; doOutput = true
        setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
        setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
    }
    try {
        val payload = JSONArray().put(JSONObject().put("user_id", KPU_SHARED_OWNER_ID).put("data", storeToJson(store)))
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("Uložení dat do Supabase selhalo (HTTP $code): ${body.take(300)}")
    } finally { conn.disconnect() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KpuApp(context: Context, session: KpuSession, onCheckUpdates: () -> Unit, checkingUpdate: Boolean, onLogout: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.WEEK) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    val localPrefs = remember { context.getSharedPreferences("kpu_local", Context.MODE_PRIVATE) }
    val ownerKey = "store_owner_workspace_id"
    val storedOwner = remember { localPrefs.getString(ownerKey, null) }
    val initialLocalStore = remember {
        if (storedOwner == null || storedOwner == KPU_SHARED_OWNER_ID) loadStore(context) else Store()
    }
    var store by remember { mutableStateOf(initialLocalStore) }
    var syncReady by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf("Připojuji synchronizaci…") }
    var currentRole by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }
    var lastCloudRead by remember { mutableStateOf("—") }
    var lastCloudWrite by remember { mutableStateOf("—") }
    val syncScope = rememberCoroutineScope()
    val syncMutex = remember { Mutex() }
    LaunchedEffect(session.userId, session.accessToken) {
        syncReady = false
        syncStatus = "Načítám online data…"
        try {
            currentRole = fetchCurrentRole(session)
            if (currentRole !in listOf("admin", "user", "viewer")) throw IllegalStateException("Účet nemá platnou roli KPU.")
            if (!isNetworkAvailable(context)) {
                val pendingJson = localPrefs.getString("store_pending_sync", null)
                val pending = pendingJson?.let { runCatching { storeFromJson(JSONObject(it)) }.getOrNull() }
                val local = pending ?: initialLocalStore
                store = local
                saveStore(context, local)
                syncStatus = if (pending != null) "Offline změny čekají na synchronizaci" else "Offline režim – změny se uloží k synchronizaci"
                syncReady = true
                return@LaunchedEffect
            }
            val remote = fetchRemoteStore(session)
            lastCloudRead = syncClock()
            val pendingJson = localPrefs.getString("store_pending_sync", null)
            val baseJson = localPrefs.getString("store_last_cloud", null)
            val pending = pendingJson?.let { runCatching { storeFromJson(JSONObject(it)) }.getOrNull() }
            val base = baseJson?.let { runCatching { storeFromJson(JSONObject(it)) }.getOrNull() } ?: remote ?: initialLocalStore
            val selected = when {
                pending != null && remote != null -> mergeOfflineChanges(base, pending, remote)
                pending != null -> pending
                remote != null -> remote
                else -> initialLocalStore
            }
            if (remote != null && storedOwner == null) localPrefs.edit().putString("store_pre_sync_backup", storeToJson(initialLocalStore).toString()).apply()
            store = selected
            saveStore(context, selected)
            localPrefs.edit().putString(ownerKey, KPU_SHARED_OWNER_ID).apply()
            if (pending != null || remote == null) {
                localPrefs.edit().putString("store_pending_sync", storeToJson(selected).toString()).apply()
                saveRemoteStore(session, selected)
                localPrefs.edit().remove("store_pending_sync").putString("store_last_cloud", storeToJson(selected).toString()).apply()
                lastCloudWrite = syncClock()
                syncStatus = "Offline změny synchronizovány"
            } else {
                localPrefs.edit().putString("store_last_cloud", storeToJson(selected).toString()).apply()
                syncStatus = "Synchronizováno se Supabase"
            }
            syncReady = true
        } catch (e: Exception) {
            syncStatus = "Čeká na synchronizaci – ${e.message ?: "zkontroluj připojení"}"
            syncReady = true
        }
    }
    var addTarget by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var every14 by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Pair<String, Machine>?>(null) }
    var editCell by remember { mutableStateOf<Pair<String, String>?>(null) }
    var editDate by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }

    fun update(next: Store) {
        store = next
        saveStore(context, next)
        // Persist pending changes before attempting the network so an app restart cannot discard them.
        localPrefs.edit().putString("store_pending_sync", storeToJson(next).toString()).apply()
        if (syncReady) {
            syncStatus = "Ukládám online…"
            syncScope.launch {
                try {
                    syncMutex.withLock {
                        val remote = fetchRemoteStore(session)
                        val baseJson = localPrefs.getString("store_last_cloud", null)
                        val base = baseJson?.let { runCatching { storeFromJson(JSONObject(it)) }.getOrNull() } ?: remote ?: next
                        val merged = if (remote != null) mergeOfflineChanges(base, next, remote) else next
                        saveRemoteStore(session, merged)
                        store = merged
                        saveStore(context, merged)
                        localPrefs.edit().remove("store_pending_sync").putString("store_last_cloud", storeToJson(merged).toString()).apply()
                    }
                    lastCloudWrite = syncClock()
                    syncStatus = "Synchronizováno se Supabase"
                } catch (e: Exception) {
                    syncStatus = "Čeká na synchronizaci – ${e.message ?: "zkontroluj připojení"}"
                }
            }
        }
    }

    // Retry pending offline changes when connectivity returns; merge only locally changed fields.
    LaunchedEffect(session.userId, session.accessToken) {
        while (true) {
            delay(10_000)
            val pendingJson = localPrefs.getString("store_pending_sync", null) ?: continue
            if (!isNetworkAvailable(context)) continue
            try {
                syncMutex.withLock {
                    val pending = storeFromJson(JSONObject(pendingJson))
                    val remote = fetchRemoteStore(session) ?: Store()
                    val baseJson = localPrefs.getString("store_last_cloud", null)
                    val base = baseJson?.let { runCatching { storeFromJson(JSONObject(it)) }.getOrNull() } ?: remote
                    val merged = mergeOfflineChanges(base, pending, remote)
                    saveRemoteStore(session, merged)
                    store = merged
                    saveStore(context, merged)
                    localPrefs.edit().remove("store_pending_sync").putString("store_last_cloud", storeToJson(merged).toString()).apply()
                }
                lastCloudRead = syncClock()
                lastCloudWrite = syncClock()
                syncStatus = "Offline změny synchronizovány"
            } catch (e: Exception) {
                syncStatus = "Čeká na synchronizaci – ${e.message ?: "zkontroluj připojení"}"
            }
        }
    }
    suspend fun refreshFromCloud() {
        if (!isNetworkAvailable(context)) {
            syncStatus = "Bez připojení – data nelze obnovit"
            return
        }
        if (isRefreshing) return
        isRefreshing = true
        try {
            syncMutex.withLock {
                val pendingJson = localPrefs.getString("store_pending_sync", null)
                val remote = fetchRemoteStore(session)
                if (remote != null) {
                    val baseJson = localPrefs.getString("store_last_cloud", null)
                    val base = baseJson?.let { runCatching { storeFromJson(JSONObject(it)) }.getOrNull() } ?: remote
                    val local = store
                    val merged = if (pendingJson != null) {
                        val pending = runCatching { storeFromJson(JSONObject(pendingJson)) }.getOrNull() ?: local
                        mergeOfflineChanges(base, pending, remote)
                    } else mergeOfflineChanges(base, local, remote)
                    store = merged
                    saveStore(context, merged)
                    if (pendingJson == null) {
                        localPrefs.edit().putString("store_last_cloud", storeToJson(remote).toString()).apply()
                        syncStatus = "Automaticky synchronizováno"
                    } else {
                        localPrefs.edit().putString("store_pending_sync", storeToJson(merged).toString()).apply()
                        syncStatus = "Načtena data; čekají místní změny k synchronizaci"
                    }
                }
                lastCloudRead = syncClock()
            }
        } catch (e: Exception) {
            syncStatus = "Obnovení se nezdařilo – ${e.message ?: "zkontroluj připojení"}"
        } finally { isRefreshing = false }
    }

    // Backup polling while the app is open; manual refresh is also available in the header.
    LaunchedEffect(session.userId, session.accessToken, syncReady) {
        if (!syncReady) return@LaunchedEffect
        while (true) {
            delay(60_000)
            if (localPrefs.getString("store_pending_sync", null) == null) refreshFromCloud()
        }
    }

    var photoUploadStatus by remember { mutableStateOf("") }
    suspend fun uploadSelectedPhoto(uri: Uri) {
        photoUploadStatus = "Nahrávám fotografii do cloudu…"
        try {
            val path = uploadKpuPhoto(context, session, uri)
            update(store.copy(photos = store.photos + path))
            photoUploadStatus = "Fotografie zmenšena (cílově cca 1 MB) a uložena do cloudu."
        } catch (e: Exception) {
            photoUploadStatus = "Chyba fotografie: ${e.message ?: "nepodařilo se načíst nebo nahrát fotografii"}"
        }
    }
    // OpenDocument poskytuje URI vybrané fotografie; uchováme oprávnění ke čtení,
    // aby bylo možné fotografii bezpečně zpracovat i po návratu z výběru.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            syncScope.launch { uploadSelectedPhoto(uri) }
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) syncScope.launch {
            photoUploadStatus = "Zpracovávám fotografii z fotoaparátu…"
            var temp: File? = null
            try {
                temp = File.createTempFile("kpu_camera_", ".jpg", context.cacheDir)
                temp.outputStream().use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) throw IllegalStateException("Fotografii z fotoaparátu se nepodařilo zpracovat.")
                }
                uploadSelectedPhoto(Uri.fromFile(temp))
            } catch (e: Exception) {
                photoUploadStatus = "Chyba fotoaparátu: ${e.message ?: "fotografii se nepodařilo zpracovat"}"
            } finally {
                temp?.delete()
                bitmap.recycle()
            }
        } else {
            photoUploadStatus = "Fotografování bylo zrušeno."
        }
    }

    fun machines(key: String) = store.machines[key] ?: emptyList()
    fun addMachine(key: String, machine: Machine) = update(store.copy(machines = store.machines + (key to (machines(key) + machine))))
    fun removeMachine(key: String, machine: Machine) = update(store.copy(machines = store.machines + (key to machines(key).filterNot { it.id == machine.id })))
    fun setCell(key: String, cell: Cell) = update(store.copy(cells = store.cells + (key to cell)))

    MaterialTheme(colorScheme = darkColorScheme(primary = Yellow, onPrimary = Color.Black, background = Black, surface = Panel, onBackground = Color.White, onSurface = Color.White)) {
        Scaffold(containerColor = Black, topBar = {
            Column(Modifier.fillMaxWidth().background(Black).statusBarsPadding().padding(horizontal = 12.dp, vertical = 3.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("KPU Rohlenka ✓", color = Yellow, fontSize = 23.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCheckUpdates, enabled = !checkingUpdate, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(if (checkingUpdate) "Kontrola…" else "Aktualizace", color = Yellow, fontSize = 11.sp)
                    }
                    TextButton(onClick = onLogout, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("Odhlásit", color = Yellow, fontSize = 12.sp)
                    }
                }
                Text(syncStatus, color = if (syncStatus.startsWith("Chyba") || syncStatus.startsWith("Neuloženo")) Red else Color.LightGray, fontSize = 10.sp, maxLines = 2)
                Text("Cloud načten: $lastCloudRead  •  Cloud uložen: $lastCloudWrite", color = Color.Gray, fontSize = 9.sp, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { syncScope.launch { refreshFromCloud() } }, enabled = !isRefreshing, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text(if (isRefreshing) "Obnovuji…" else "Obnovit data", fontSize = 11.sp) }
                    OutlinedButton(onClick = { month = month.minusMonths(1) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) { Text("‹") }
                    Text("${monthName(month.monthValue)} ${month.year}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { month = month.plusMonths(1) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) { Text("›") }
                    IconButton(onClick = { showMonthPicker = true }, modifier = Modifier.size(42.dp)) { Icon(Icons.Default.CalendarMonth, "Vybrat měsíc a rok", tint = Yellow, modifier = Modifier.size(26.dp)) }
                }
            }
        }, bottomBar = {
            NavigationBar(containerColor = Panel) {
                Tab.entries.forEach { t ->
                    val icon = when (t) { Tab.WEEK -> Icons.Default.ViewWeek; Tab.MONTH -> Icons.Default.CalendarMonth; Tab.QUARTER -> Icons.Default.PieChart; Tab.OTHER -> Icons.Default.Image; Tab.FILTERS -> Icons.Default.GridOn }
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(icon, t.label) }, label = { Text(t.label.removeSuffix(" KPU"), fontSize = 9.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Yellow, selectedTextColor = Yellow, indicatorColor = Color(0xFF39331C)))
                }
            }
        }, floatingActionButton = {
            if (tab != Tab.OTHER && tab != Tab.FILTERS) FloatingActionButton(onClick = { addTarget = when(tab) { Tab.WEEK -> "week"; Tab.MONTH -> "month"; Tab.QUARTER -> "quarter"; Tab.FILTERS -> "filter_week"; else -> "" }; newName = ""; every14 = false; showAdd = true }, modifier = Modifier.size(68.dp), containerColor = Yellow, contentColor = Color.Black) { Icon(Icons.Default.Add, "Přidat stroj", modifier = Modifier.size(34.dp)) }
        }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize().pointerInput(tab) {
            detectHorizontalDragGestures(onDragStart = { drag = 0f }, onHorizontalDrag = { _, amount -> drag += amount }, onDragEnd = { if (drag < -70) tab = Tab.entries[(tab.ordinal + 1).coerceAtMost(Tab.entries.lastIndex)]; if (drag > 70) tab = Tab.entries[(tab.ordinal - 1).coerceAtLeast(0)]; drag = 0f })
            }) {
                when(tab) {
                    Tab.WEEK -> MachineGrid("Týdenní KPU", "week", machines("week"), month, true, store, ::setCell, { deleteTarget = "week" to it }, { editCell = it; val c = store.cells[it.second] ?: Cell(); editDate = c.date.ifBlank { LocalDate.now().format(dateFmt) } }, currentRole != "user")
                    Tab.MONTH -> MachineGrid("Měsíční KPU", "month", machines("month"), month, false, store, ::setCell, { deleteTarget = "month" to it }, { editCell = it; val c = store.cells[it.second] ?: Cell(); editDate = c.date.ifBlank { LocalDate.now().format(dateFmt) } }, currentRole != "user")
                    Tab.QUARTER -> QuarterGrid(month, machines("quarter"), store, ::setCell, { deleteTarget = "quarter" to it }, { editCell = it; val c = store.cells[it.second] ?: Cell(); editDate = c.date.ifBlank { LocalDate.now().format(dateFmt) } }, currentRole != "user")
                    Tab.OTHER -> OtherGrid(month, machines("other_half1"), machines("other_half2"), machines("other_year"), store, ::setCell, { key, m -> deleteTarget = key to m }, { key -> addTarget = key; newName = ""; every14 = false; showAdd = true }, { editCell = it; val c = store.cells[it.second] ?: Cell(); editDate = c.date.ifBlank { LocalDate.now().format(dateFmt) } }, store.photos, photoUploadStatus, { photoPicker.launch(arrayOf("image/*")) }, { cameraLauncher.launch(null) }, currentRole != "user")
                    Tab.FILTERS -> FiltersGrid(month, ::machines, store, ::setCell, { key, m -> deleteTarget = key to m }, { key -> addTarget = key; newName = ""; every14 = false; showAdd = true }, { editCell = it; val c = store.cells[it.second] ?: Cell(); editDate = c.date.ifBlank { LocalDate.now().format(dateFmt) } }, currentRole != "user")
                }
            }
        }
        if (showAdd) AlertDialog(onDismissRequest = { showAdd = false }, title = { Text("Přidat stroj") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(newName, { newName = it }, label = { Text("Název stroje") }, singleLine = true); if (addTarget == "week" || addTarget == "filter_14") Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(every14, { every14 = it }); Text("KPU každých 14 dní") } } }, confirmButton = { TextButton(onClick = { if (newName.isNotBlank() && addTarget.isNotBlank()) addMachine(addTarget, Machine(System.currentTimeMillis(), newName.trim(), every14)); showAdd = false }) { Text("Přidat", color = Yellow) } }, dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Zrušit") } })
        deleteTarget?.let { (key, machine) -> AlertDialog(onDismissRequest = { deleteTarget = null }, icon = { Icon(Icons.Default.Delete, null, tint = Red) }, title = { Text("Opravdu smazat stroj?") }, text = { Text("Smazat „${machine.name}“ pouze ze sekce, ve které je veden?") }, confirmButton = { TextButton(onClick = { removeMachine(key, machine); deleteTarget = null }) { Text("Smazat", color = Red) } }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Zrušit") } }) }
        if (showMonthPicker) {
            val dateState = rememberDatePickerState(initialSelectedDateMillis = month.atDay(1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
            DatePickerDialog(onDismissRequest = { showMonthPicker = false }, confirmButton = { TextButton(onClick = { dateState.selectedDateMillis?.let { month = YearMonth.from(java.time.Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()) }; showMonthPicker = false }) { Text("Vybrat", color = Yellow) } }, dismissButton = { TextButton(onClick = { showMonthPicker = false }) { Text("Zrušit") } }) { DatePicker(state = dateState) }
        }
        editCell?.let { (key, _) ->
            if (showDatePicker) {
                val parsed = runCatching { LocalDate.parse(editDate, dateFmt) }.getOrDefault(LocalDate.now())
                val picker = rememberDatePickerState(initialSelectedDateMillis = parsed.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
                DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = { TextButton(onClick = { picker.selectedDateMillis?.let { editDate = java.time.Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate().format(dateFmt) }; showDatePicker = false }) { Text("Použít datum", color = Yellow) } }, dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Zrušit") } }) { DatePicker(state = picker) }
            }
            AlertDialog(onDismissRequest = { editCell = null }, title = { Text("Datum KPU") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(editDate, { editDate = it }, label = { Text("Datum") }, singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = { IconButton(onClick = { showDatePicker = true }) { Icon(Icons.Default.CalendarMonth, "Vybrat datum", tint = Yellow) } })
                TextButton(onClick = { editDate = LocalDate.now().format(dateFmt) }) { Text("Použít dnešní datum", color = Yellow) }
                if (key.substringBefore("|") == "week") {
                    Text("Datum označí políčko zeleně. Pokud je úkol neaktivní, můžeš ho splnit bez data.", fontSize = 12.sp, color = Color.LightGray)
                    OutlinedButton(onClick = {
                        setCell(key, Cell(done = true, inactive = true))
                        editCell = null
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray)) {
                        Text("Neaktivní")
                    }
                } else {
                    Text("Po uložení data se políčko automaticky označí zeleně.", fontSize = 12.sp, color = Color.LightGray)
                }
            } }, confirmButton = { TextButton(onClick = {
                val date = runCatching { LocalDate.parse(editDate.trim(), dateFmt) }.getOrNull()
                if (date != null) {
                    val parts = key.split("|").toMutableList()
                    if (parts.size >= 3) {
                        val section = parts[0]
                        when (section) {
                            "week", "filter_week", "filter_14", "filter_month", "month" -> {
                                if (parts.size >= 4) { val ym = YearMonth.from(date); parts[2] = ym.toString(); if (section == "week") { val offset = ym.atDay(1).dayOfWeek.value % 7; parts[3] = (((date.dayOfMonth + offset - 1) / 7).coerceIn(0, weekSlots(ym)-1)).toString() } else if (section == "month" || section == "filter_month") parts[3] = "0" }
                            }
                            "quarter" -> { parts[2] = date.year.toString(); if (parts.size >= 4) parts[3] = ((date.monthValue - 1) / 3).toString() }
                            else -> { parts[2] = date.year.toString() }
                        }
                        val targetKey = parts.joinToString("|")
                        val cells = store.cells.toMutableMap(); cells.remove(key); cells[targetKey] = Cell(date.format(dateFmt), true); update(store.copy(cells = cells))
                    }
                }
                editCell = null
            }) { Text("Uložit", color = Yellow) } }, dismissButton = { TextButton(onClick = { editCell = null }) { Text("Zrušit") } })
        }
    }
}

@Composable
private fun MachineGrid(title: String, key: String, machines: List<Machine>, month: YearMonth, weekly: Boolean, store: Store, setCell: (String, Cell) -> Unit, onDelete: (Machine) -> Unit, onEdit: (Pair<String, String>) -> Unit, canDeleteMachines: Boolean = true) {
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Text(title, color = Yellow, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 7.dp))
        Text("${monthName(month.monthValue)} ${month.year} · klepni na políčko pro datum a stav", color = Color.LightGray, fontSize = 11.sp)
        if (weekly) {
            val count = weekSlots(month)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Stroj", Modifier.weight(1.25f), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                repeat(count) { i -> Text("${i + 1}.\n${weekRange(month, i)}", Modifier.weight(1f), fontSize = 8.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                Spacer(Modifier.width(28.dp))
            }
        } else Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Text("Stroj", Modifier.weight(1f), fontWeight = FontWeight.Bold); Text("Stav / datum", Modifier.weight(1f), fontWeight = FontWeight.Bold) }
        HorizontalDivider(color = Gray)
        if (machines.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Zatím žádné stroje. Přidej je tlačítkem +.", color = Color.LightGray, fontSize = 13.sp) }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 5.dp)) {
            items(machines, key = { it.id }) { machine ->
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(machine.name, Modifier.weight(if (weekly) 1.25f else 1f), fontSize = 11.sp, lineHeight = 13.sp)
                    if (weekly) repeat(weekSlots(month)) { i ->
                        val slotKey = "$key|${machine.id}|${month}|$i"
                        val cell = store.cells[slotKey]
                        val scheduled = !machine.every14 || ((weekIndex(month, i) % 2) == 0)
                        val slotEndDay = weekRange(month, i).substringAfter("–").toIntOrNull() ?: month.lengthOfMonth()
                        val slotExpired = month < YearMonth.now() || (month == YearMonth.now() && month.atDay(slotEndDay).isBefore(LocalDate.now()))
                        val color = if (!scheduled) Gray else if (cell?.inactive == true) Color(0xFF666C71) else if (cell?.date?.isNotBlank() == true) Green else if (slotExpired) Red else Color(0xFF34393D)
                        val symbol = if (!scheduled) "–" else if (cell?.inactive == true) "×" else if (cell?.date?.isNotBlank() == true) "✓" else "＋"
                        Box(Modifier.weight(1f).padding(horizontal = 1.dp).height(46.dp).background(color, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) { TextButton(onClick = { onEdit(slotKeyPair(slotKey)) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.fillMaxSize()) { Text(symbol, color = Color.White, fontSize = 14.sp) } }
                    } else {
                        val slotKey = "$key|${machine.id}|${month}|0"; val cell = store.cells[slotKey]
                        val monthExpired = month < YearMonth.now()
                        val monthColor = if (cell?.date?.isNotBlank() == true) Green else if (monthExpired) Red else Color.LightGray
                        TextButton(onClick = { onEdit(slotKeyPair(slotKey)) }, modifier = Modifier.weight(1f).heightIn(min = 46.dp), contentPadding = PaddingValues(4.dp)) { Text((if (cell?.done == true) "✓ " else if (cell?.date?.isNotBlank() == true) "! " else "＋ ") + (cell?.date ?: ""), color = monthColor, fontSize = 12.sp) }
                    }
                    if (canDeleteMachines) IconButton(onClick = { onDelete(machine) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Delete, null, tint = Red, modifier = Modifier.size(16.dp)) }
                }
            }
        }
    }
}
private fun slotKeyPair(key: String) = key to key

@Composable
private fun QuarterGrid(month: YearMonth, machines: List<Machine>, store: Store, setCell: (String, Cell) -> Unit, onDelete: (Machine) -> Unit, onEdit: (Pair<String, String>) -> Unit, canDeleteMachines: Boolean = true) {
    val quarter = (month.monthValue - 1) / 3
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Text("Kvartální KPU", color = Yellow, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 8.dp)) { listOf("1Q", "2Q", "3Q", "4Q").forEachIndexed { i, label -> Button(onClick = { /* selected quarter follows the chosen month */ }, colors = ButtonDefaults.buttonColors(containerColor = if (quarter == i) Yellow else Panel, contentColor = if (quarter == i) Color.Black else Color.White), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label) } } }
        if (machines.isEmpty()) Text("Zatím žádné stroje. Použij +.", color = Color.LightGray)
        LazyColumn { items(machines, key = { it.id }) { m -> val k = "quarter|${m.id}|${month.year}|$quarter"; val c = store.cells[k]; Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Panel, RoundedCornerShape(6.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Text(m.name, Modifier.weight(1f)); TextButton(onClick = { onEdit(slotKeyPair(k)) }) { Text((if (c?.done == true) "✓ " else "＋ ") + (c?.date ?: ""), color = if (c?.done == true) Green else Color.LightGray) }; if (canDeleteMachines) IconButton(onClick = { onDelete(m) }) { Icon(Icons.Default.Delete, null, tint = Red) } } } }
    }
}

@Composable
private fun OtherGrid(month: YearMonth, half1: List<Machine>, half2: List<Machine>, yearly: List<Machine>, store: Store, setCell: (String, Cell) -> Unit, onDelete: (String, Machine) -> Unit, onAdd: (String) -> Unit, onEdit: (Pair<String, String>) -> Unit, photos: List<String>, photoStatus: String, onAddPhoto: () -> Unit, onTakePhoto: () -> Unit, canDeleteMachines: Boolean = true) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) { Text("Ostatní KPU", color = Yellow, fontSize = 19.sp, fontWeight = FontWeight.Bold); Row(verticalAlignment = Alignment.CenterVertically) { Text("Fotodokumentace", Modifier.weight(1f), color = Color.LightGray); OutlinedButton(onClick = onAddPhoto, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Icon(Icons.Default.Image, null, tint = Yellow); Spacer(Modifier.width(4.dp)); Text("Vybrat") }; Spacer(Modifier.width(6.dp)); OutlinedButton(onClick = onTakePhoto, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text("Vyfotit") } }; if (photos.isNotEmpty()) Text("Fotografií uložených v cloudu: ${photos.size}", color = Green, fontSize = 12.sp); if (photoStatus.isNotBlank()) Text(photoStatus, color = if (photoStatus.startsWith("Chyba")) Red else Color.LightGray, fontSize = 11.sp); OtherSection("Pololetní KPU · 1–6", "other_half1", half1, month, store, onDelete, onAdd, onEdit, canDeleteMachines); OtherSection("Pololetní KPU · 7–12", "other_half2", half2, month, store, onDelete, onAdd, onEdit, canDeleteMachines); OtherSection("Roční KPU", "other_year", yearly, month, store, onDelete, onAdd, onEdit, canDeleteMachines) }
}
@Composable
private fun OtherSection(title: String, key: String, list: List<Machine>, month: YearMonth, store: Store, onDelete: (String, Machine) -> Unit, onAdd: (String) -> Unit, onEdit: (Pair<String, String>) -> Unit, canDeleteMachines: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), color = Yellow, fontWeight = FontWeight.Bold); IconButton(onClick = { onAdd(key) }) { Icon(Icons.Default.Add, "Přidat stroj", tint = Yellow) } }
    if (list.isEmpty()) Text("Žádné stroje", color = Color.LightGray, fontSize = 12.sp) else list.forEach { m -> val k = "$key|${m.id}|${month.year}"; val c = store.cells[k]; Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).background(Panel, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) { Text(m.name, Modifier.weight(1f), fontSize = 12.sp); TextButton(onClick = { onEdit(slotKeyPair(k)) }) { Text((if (c?.done == true) "✓ " else "＋ ") + (c?.date ?: ""), color = if (c?.done == true) Green else Color.LightGray, fontSize = 11.sp) }; if (canDeleteMachines) IconButton(onClick = { onDelete(key, m) }) { Icon(Icons.Default.Delete, null, tint = Red) } } }
}

@Composable
private fun FiltersGrid(month: YearMonth, machines: (String) -> List<Machine>, store: Store, setCell: (String, Cell) -> Unit, onDelete: (String, Machine) -> Unit, onAdd: (String) -> Unit, onEdit: (Pair<String, String>) -> Unit, canDeleteMachines: Boolean = true) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) { Text("Filtry", color = Yellow, fontSize = 19.sp, fontWeight = FontWeight.Bold); Text("Samostatná evidence filtrů", color = Color.LightGray, fontSize = 12.sp); FilterSection("Týdenní KPU filtrů", "filter_week", machines("filter_week"), month, store, onDelete, onAdd, onEdit, canDeleteMachines); FilterSection("14denní KPU filtrů", "filter_14", machines("filter_14"), month, store, onDelete, onAdd, onEdit, canDeleteMachines); FilterSection("Měsíční KPU filtrů", "filter_month", machines("filter_month"), month, store, onDelete, onAdd, onEdit, canDeleteMachines) }
}
@Composable
private fun FilterSection(title: String, key: String, list: List<Machine>, month: YearMonth, store: Store, onDelete: (String, Machine) -> Unit, onAdd: (String) -> Unit, onEdit: (Pair<String, String>) -> Unit, canDeleteMachines: Boolean = true) {
    val slots = when (key) { "filter_week" -> 4; "filter_14" -> 2; else -> 1 }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), color = Yellow, fontWeight = FontWeight.Bold, fontSize = 13.sp); IconButton(onClick = { onAdd(key) }) { Icon(Icons.Default.Add, "Přidat stroj", tint = Yellow) } }
    if (list.isEmpty()) Text("Žádné stroje", color = Color.LightGray, fontSize = 11.sp) else list.forEach { m ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).background(Panel, RoundedCornerShape(5.dp)).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(m.name, Modifier.weight(1f), fontSize = 11.sp, lineHeight = 13.sp)
            repeat(slots) { slot ->
                val k = "$key|${m.id}|${month}|$slot"
                val c = store.cells[k]
                TextButton(onClick = { onEdit(slotKeyPair(k)) }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 1.dp), modifier = Modifier.weight(1f)) {
                    Text((if (c?.date?.isNotBlank() == true) "✓\n" else "＋\n") + (c?.date ?: ""), color = if (c?.date?.isNotBlank() == true) Green else Color.LightGray, fontSize = 9.sp, lineHeight = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            if (canDeleteMachines) IconButton(onClick = { onDelete(key, m) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Delete, null, tint = Red, modifier = Modifier.size(15.dp)) }
        }
    }
}

private fun weekSlots(month: YearMonth): Int { val firstOffset = month.atDay(1).dayOfWeek.value % 7; return ((firstOffset + month.lengthOfMonth() + 6) / 7).coerceIn(4, 6) }
private fun weekRange(month: YearMonth, index: Int): String { val first = month.atDay(1); val offset = first.dayOfWeek.value % 7; val start = (1 - offset + index * 7).coerceAtLeast(1); val end = (start + 6).coerceAtMost(month.lengthOfMonth()); return "$start–$end" }
private fun weekIndex(month: YearMonth, index: Int): Int { val offset = month.atDay(1).dayOfWeek.value % 7; val firstSunday = month.atDay(1).minusDays(offset.toLong()); return ((firstSunday.toEpochDay() / 7L + index) % 2).toInt() }
private fun monthName(n: Int) = listOf("", "Leden", "Únor", "Březen", "Duben", "Květen", "Červen", "Červenec", "Srpen", "Září", "Říjen", "Listopad", "Prosinec")[n]
