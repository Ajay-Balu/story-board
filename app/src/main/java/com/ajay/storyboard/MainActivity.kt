package com.ajay.storyboard

import android.os.Bundle
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ajay.storyboard.ui.StoryBoardNav
import com.ajay.storyboard.ui.theme.StoryBoardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as StoryBoardApp
        app.runner.attach(this)
        applyNightMode(app.settings.themeMode)
        handleShared(intent)
        setContent {
            val mode = app.settings.themeMode
            androidx.compose.runtime.LaunchedEffect(mode) { applyNightMode(mode) }
            StoryBoardTheme(mode, app.settings.wallpaperColors) {
                StoryBoardNav(app)
            }
        }
    }

    /**
     * Sets the app's own night mode (Android 12+) so the window theme — and with it the chat pages —
     * follow the choice in Settings. Older Android versions follow the system setting.
     */
    private fun applyNightMode(mode: com.ajay.storyboard.data.ThemeMode) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        val ui = getSystemService(android.app.UiModeManager::class.java) ?: return
        val wanted = when (mode) {
            com.ajay.storyboard.data.ThemeMode.SYSTEM -> android.app.UiModeManager.MODE_NIGHT_AUTO
            com.ajay.storyboard.data.ThemeMode.LIGHT -> android.app.UiModeManager.MODE_NIGHT_NO
            com.ajay.storyboard.data.ThemeMode.DARK -> android.app.UiModeManager.MODE_NIGHT_YES
        }
        runCatching { ui.setApplicationNightMode(wanted) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
    }

    /** A .storyboard file opened or shared from another app is imported as a new book. */
    private fun handleShared(intent: android.content.Intent?) {
        val i = intent ?: return
        val uri: android.net.Uri = when (i.action) {
            android.content.Intent.ACTION_VIEW -> i.data
            android.content.Intent.ACTION_SEND -> @Suppress("DEPRECATION") (i.getParcelableExtra(android.content.Intent.EXTRA_STREAM) as? android.net.Uri)
            else -> null
        } ?: return
        val app = application as StoryBoardApp
        app.appScope.launch {
            val result = runCatching {
                contentResolver.openInputStream(uri)!!.use { com.ajay.storyboard.backup.StoryShare.import(this@MainActivity, app.repo, it) }
            }
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    result.fold({ "Story imported into your library" }, { "Could not import: ${it.message}" }),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
        setIntent(android.content.Intent())
    }

    override fun onDestroy() {
        (application as StoryBoardApp).runner.detach()
        super.onDestroy()
    }
}
