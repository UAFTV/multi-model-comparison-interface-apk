package com.uaftv.multiai.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** An installed app that accepts shared text (a candidate for "Other +"). */
data class ShareTarget(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: ImageBitmap?,
)

/**
 * Android does not let one app type into another app or read its answers, so "Other +"
 * apps are opened through the standard share action with the prompt as the shared text.
 */
object AppLauncher {

    fun queryShareTargets(context: Context, withIcons: Boolean): List<ShareTarget> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
        val found: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return found
            .filter { it.activityInfo.packageName != context.packageName }
            .map { info ->
                val appLabel = info.activityInfo.applicationInfo.loadLabel(pm).toString()
                val activityLabel = info.loadLabel(pm).toString()
                val label = if (activityLabel.isBlank() || activityLabel == appLabel) appLabel else "$appLabel – $activityLabel"
                val icon = if (withIcons) {
                    runCatching { info.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                } else {
                    null
                }
                ShareTarget(label, info.activityInfo.packageName, info.activityInfo.name, icon)
            }
            .distinctBy { it.packageName + "/" + it.activityName }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Opens [app] with [prompt] pre-filled. The prompt is also put on the clipboard because
     * some apps ignore shared text. Returns false if the app can no longer be opened.
     */
    fun open(context: Context, app: OtherApp, prompt: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Prompt", prompt))
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, prompt)
            .setClassName(app.packageName, app.activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
