package net.meshsat.android.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Asking for a permission when Android may refuse without asking (MESHSAT-1295).
 *
 * Android answers a request at once, with no dialog, when it will not ask: after the question was
 * declined before, and - on Android 13 and later - for an app installed from a downloaded file,
 * where SMS is a "restricted setting" until the person allows it in the app's own settings page.
 * The app did not notice, so on Elli's phone "Allow SMS" did nothing and the card said "Not
 * allowed yet", and she found the way out herself. A visitor installing the APK at a stand would
 * hit exactly this.
 */
internal enum class AskOutcome { Granted, AskAgain, GoToSettings }

/**
 * What a request's answer means. [stillDenied] are the permissions not granted after it;
 * [mayExplain] those Android would still show a question for.
 */
internal fun askOutcome(stillDenied: List<String>, mayExplain: Set<String>): AskOutcome = when {
    stillDenied.isEmpty() -> AskOutcome.Granted
    stillDenied.any { it in mayExplain } -> AskOutcome.AskAgain
    else -> AskOutcome.GoToSettings
}

/** The words for a permission Android will not ask about, for [what] ("SMS"). */
internal fun goToSettingsText(what: String): String =
    "Android did not show the question. When MeshSat was installed from a downloaded file, $what " +
        "stays blocked until you allow it: tap Open MeshSat settings, then the three dots at the top " +
        "right, Allow restricted settings, and then Permissions, $what, Allow."

class PermissionAsk(
    /** Every permission asked for is granted. */
    val granted: Boolean,
    /** Android will not show the question: the way on is the app's own settings page. */
    val needsSettings: Boolean,
    /** Ask Android, or open the settings page when Android will not ask. */
    val ask: () -> Unit,
)

@Composable
fun rememberPermissionAsk(permissions: Array<String>): PermissionAsk {
    val context = LocalContext.current
    fun denied() = permissions.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(denied().isEmpty()) }
    var needsSettings by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val still = denied()
        val activity = context.findActivity()
        val mayExplain = still.filter { p ->
            activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, p)
        }.toSet()
        val outcome = askOutcome(still, mayExplain)
        granted = outcome == AskOutcome.Granted
        needsSettings = outcome == AskOutcome.GoToSettings
    }
    // Coming back from the settings page, read the answer again: nothing else would tell us.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = denied().isEmpty()
                if (granted) needsSettings = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return PermissionAsk(granted, needsSettings) {
        if (needsSettings) openAppSettings(context) else launcher.launch(permissions)
    }
}

/** This app's own page in the system settings, where permissions and restricted settings live. */
fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        // No settings app to open; the text on the card still says where to go.
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
