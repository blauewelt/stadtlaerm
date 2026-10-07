package ch.stadtlaerm.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.stadtlaerm.app.BuildConfig
import ch.stadtlaerm.app.container
import ch.stadtlaerm.app.update.UpdateCheck
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Snackbar host of the app's root Scaffold. */
val LocalSnackbarHost = staticCompositionLocalOf { SnackbarHostState() }

/**
 * Opens the update page in the browser. The app has no internet permission; the browser does
 * the request, and the version in the URL fragment never reaches the server.
 * Returns false if no app can open the link.
 */
fun openUpdatePage(context: Context): Boolean {
    val url = UpdateCheck.updateUrl(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    // No resolveActivity() pre-check: on Android 11+ it needs a <queries> manifest entry and
    // returns null without one. Starting the activity and catching the failure is equivalent.
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** Opens the update page, or shows «Kein Browser gefunden». */
@Composable
fun rememberUpdateAction(): () -> Unit {
    val context = LocalContext.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    return {
        if (!openUpdatePage(context)) scope.launch { snackbar.showSnackbar("Kein Browser gefunden") }
    }
}

/** Age of the installed build in days if the reminder should show today, else null. */
@Composable
fun updateReminderAgeDays(): Long? {
    val store = LocalContext.current.container.updateReminder
    val dismissedOn by store.dismissedOn.collectAsStateWithLifecycle()
    val buildDate = UpdateCheck.parseBuildDate(BuildConfig.BUILD_DATE) ?: return null
    val today = LocalDate.now()
    return if (UpdateCheck.shouldRemind(buildDate, today, dismissedOn)) UpdateCheck.ageDays(buildDate, today) else null
}

/**
 * «Diese App-Version ist n Tage alt. Nach Update suchen?» as a tappable line with «Später».
 * Shows nothing unless the build is old enough and not snoozed.
 */
@Composable
fun UpdateReminderLine(modifier: Modifier = Modifier) {
    val days = updateReminderAgeDays() ?: return
    val store = LocalContext.current.container.updateReminder
    val open = rememberUpdateAction()
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Diese App-Version ist $days Tage alt. Nach Update suchen?",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.weight(1f).clickable(onClickLabel = "Nach Update suchen") { open() },
        )
        TextButton(onClick = { store.dismiss() }) { Text("Später") }
    }
}

/** Settings card «App-Version». */
@Composable
fun AppVersionCard() {
    val open = rememberUpdateAction()
    SectionCard("App-Version") {
        Text(
            "Stadtlärm ${BuildConfig.VERSION_NAME} (Build vom ${UpdateCheck.displayBuildDate(BuildConfig.BUILD_DATE)})",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = open) { Text("Nach Update suchen") }
        Text(
            "Öffnet stadtlaerm.ch im Browser. Die App selbst hat keinen Internetzugang.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
