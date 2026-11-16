package com.seastreams.tv.ui.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Resolves a `@HiltViewModel` from the host activity's store. The activity's default factory is
 * Hilt's, so this needs no extra navigation/hilt-compose artifacts.
 */
@Composable
inline fun <reified VM : ViewModel> activityViewModel(key: String = VM::class.java.name): VM {
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity) { "Must be hosted in a ComponentActivity" }
    return remember(activity, key) { ViewModelProvider(activity)[key, VM::class.java] }
}
