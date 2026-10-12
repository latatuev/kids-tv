package io.lotus.executor

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Lightweight trusted-operation dispatcher. No arbitrary command input:
 * only a literal 'ping' from the strict parser is accepted.
 * HTTPS links are untrusted input; this read-only action is intentionally safe.
 */
class LinkDispatchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receive(intent)
        finishWithoutAnimation()
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        receive(newIntent)
        finishWithoutAnimation()
    }

    private fun receive(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_VIEW) return
        val task = LinkParser.parse(incoming.dataString) ?: return
        ResultStore.executePing(this, task)
    }

    private fun finishWithoutAnimation() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
