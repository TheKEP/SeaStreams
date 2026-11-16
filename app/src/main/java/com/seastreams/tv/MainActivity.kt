package com.seastreams.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.seastreams.tv.core.webserver.LocalPortalServer
import com.seastreams.tv.ui.navigation.SeaStreamsNavGraph
import com.seastreams.tv.ui.theme.SeaStreamsTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Single-activity Compose for TV host. The LAN Web Portal runs while the app is in the foreground. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var portalServer: LocalPortalServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SeaStreamsTheme {
                SeaStreamsNavGraph()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        portalServer.requestStart()
    }

    override fun onStop() {
        portalServer.requestStop()
        super.onStop()
    }
}
