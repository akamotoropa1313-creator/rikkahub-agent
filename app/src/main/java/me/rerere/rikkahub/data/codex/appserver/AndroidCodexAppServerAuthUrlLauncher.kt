package me.rerere.rikkahub.data.codex.appserver

import android.content.Context
import android.content.Intent
import android.net.Uri

class AndroidCodexAppServerAuthUrlLauncher(private val context: Context) : CodexAppServerAuthUrlLauncher {
    override fun launch(authUrl: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
