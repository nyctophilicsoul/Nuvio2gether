package com.nuvio.app

import androidx.compose.runtime.Composable
import com.nuvio.app.features.localfiles.LocalFilesScreen
import com.nuvio.app.features.localfiles.LocalVideoFile
import com.nuvio.app.navigation.LocalFilesRoute
import com.nuvio.app.navigation.NuvioNavigator

@Composable
internal fun LocalFilesDestination(
    route: LocalFilesRoute,
    navController: NuvioNavigator,
    onPlay: (LocalVideoFile) -> Unit,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    LocalFilesScreen(
        onBack = onBack,
        preferredMatchKey = route.preferredMatchKey,
        onPlay = onPlay,
    )
}
