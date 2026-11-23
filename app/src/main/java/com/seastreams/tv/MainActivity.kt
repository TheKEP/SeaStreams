package com.seastreams.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.seastreams.tv.core.webserver.LocalPortalServer
import com.seastreams.tv.ui.navigation.BackKeyDecision
import com.seastreams.tv.ui.navigation.BackLongPressGate
import com.seastreams.tv.ui.navigation.BackLongPressRouter
import com.seastreams.tv.ui.navigation.SeaStreamsNavGraph
import com.seastreams.tv.ui.navigation.activityViewModel
import com.seastreams.tv.ui.theme.AppearanceViewModel
import com.seastreams.tv.ui.theme.SeaStreamsTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Single-activity Compose for TV host. The LAN Web Portal runs while the app is in the foreground. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var portalServer: LocalPortalServer

    private val backLongPress = BackLongPressRouter()
    private val backGate = BackLongPressGate()
    private val handler = Handler(Looper.getMainLooper())
    private var deferredBackDown: KeyEvent? = null
    private val backLongPressTimeout = Runnable {
        if (backGate.onTimeout(backLongPress.enabled)) fireBackLongPress()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appearance: AppearanceViewModel = activityViewModel()
            val background by appearance.background.collectAsState()
            SeaStreamsTheme(background = Color(background)) {
                SeaStreamsNavGraph(backLongPress)
            }
        }
    }

    /**
     * Long-press `Back` → Search (PRD §1). Runs before Compose sees the key: while the shortcut is
     * enabled a fresh `Back` `KeyDown` is held back, then either replayed with its `KeyUp` (short
     * press: every existing `Back` action runs unchanged) or dropped once the long press fires, with
     * the trailing repeats and `KeyUp` consumed. With the shortcut off, `Back` passes straight through.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val decision = backGate.onKey(
            isBack = event.keyCode == KeyEvent.KEYCODE_BACK,
            isKeyDown = event.action == KeyEvent.ACTION_DOWN,
            repeatCount = event.repeatCount,
            isLongPress = event.isLongPress,
            isCanceled = event.isCanceled,
            enabled = backLongPress.enabled,
            onFire = ::fireBackLongPress,
        )
        return when (decision) {
            BackKeyDecision.PASS -> super.dispatchKeyEvent(event)
            BackKeyDecision.DEFER -> {
                // Input events are recycled after dispatch, so keep a copy to replay.
                deferredBackDown = KeyEvent(event)
                handler.removeCallbacks(backLongPressTimeout)
                handler.postDelayed(backLongPressTimeout, ViewConfiguration.getLongPressTimeout().toLong())
                true
            }
            BackKeyDecision.CONSUME -> {
                if (!backGate.isPending) clearDeferredBack()
                true
            }
            BackKeyDecision.REPLAY -> {
                val down = deferredBackDown
                clearDeferredBack()
                if (down != null) super.dispatchKeyEvent(down)
                super.dispatchKeyEvent(event)
            }
        }
    }

    private fun fireBackLongPress() {
        clearDeferredBack()
        backLongPress.onLongPress?.invoke()
    }

    private fun clearDeferredBack() {
        handler.removeCallbacks(backLongPressTimeout)
        deferredBackDown = null
    }

    private fun resetBackLongPress() {
        backGate.reset()
        clearDeferredBack()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) resetBackLongPress()
    }

    override fun onStart() {
        super.onStart()
        portalServer.requestStart()
    }

    override fun onPause() {
        resetBackLongPress()
        super.onPause()
    }

    override fun onStop() {
        // An external player streaming Usenet from the loopback proxy holds a lease (NntpProxyService): the server must
        // outlive this stop, so only the UI's claim is dropped and the lease's release stops it later.
        if (portalServer.leased) portalServer.releaseUiClaim() else portalServer.requestStop()
        super.onStop()
    }
}
