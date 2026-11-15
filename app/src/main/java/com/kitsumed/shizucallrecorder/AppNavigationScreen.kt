/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.onboarding.OnboardingStatus
import com.kitsumed.shizucallrecorder.ui.screens.DisclaimerScreen
import com.kitsumed.shizucallrecorder.ui.screens.PermissionsScreen
import com.kitsumed.shizucallrecorder.ui.screens.SettingsScreen
import com.kitsumed.shizucallrecorder.ui.screens.RecordingArchiveScreen
import rikka.shizuku.Shizuku
import com.kitsumed.shizucallrecorder.ui.theme.ShizuCallRecorderTheme
import com.kitsumed.shizucallrecorder.ui.viewmodels.AppNavigationViewModel
import com.kitsumed.shizucallrecorder.ui.viewmodels.SettingsViewModel

/**
 * Top-level router composable called from [MainActivity].
 *
 * Decides which of three destinations to display - the one-time disclaimer, the permissions
 * checklist, or the main settings, wraps every destination in the app theme compose.
 *
 * ## State flow
 * - [AppNavigationViewModel] is the "Brain" for routing: it owns [AppNavigationViewModel.onboardingStatus]
 *   and decides which screen is active.
 * - [SettingsViewModel] is the "Brain" for settings: it owns [SettingsViewModel.updateTrigger]
 *   and all user-preference persistence.
 * - Both expose `StateFlow`s observed via [collectAsState] - the "bridge" that watches a data
 *   stream and triggers a refresh (recompose) whenever a value changes.
 * - activity lifecycle is the object that tells this composable whether the current screen
 *   is visible, in the background, or being destroyed. We attach a observer
 *   via [DisposableEffect] to refresh state whenever the user returns to the app (e.g. after
 *   granting a permission in the system Settings app).
 */
@Composable
fun AppNavigationScreen() {
    val activityContext = LocalContext.current

    // AppNavigationViewModel - the "Brain" for routing: owns onboarding state.
    val appNavViewModel: AppNavigationViewModel = viewModel()

    // SettingsViewModel - the "Brain" for settings: owns theme + preference state.
    val settingsViewModel: SettingsViewModel = viewModel()

    /**
     * [collectAsState] bridges the [AppNavigationViewModel.onboardingStatus] `StateFlow` to Compose.
     * Every time the flow emits a new [OnboardingStatus.Status] value, Compose triggers a
     * refresh (recompose) so navigation picks the correct destination.
     */
    val onboardingStatus by appNavViewModel.onboardingStatus.collectAsState()

    /**
     * [collectAsState] bridges the [SettingsViewModel.updateTrigger] `StateFlow` to Compose.
     * Reading allow us to trigger a refresh (recompose) whenever the user changes a setting that requires a
     * major UI update (e.g. theme change) that can only be updated here in the AppNavigationScreen.
     */
    val settingsViewModelUpdateTrigger by settingsViewModel.updateTrigger.collectAsState() // reading .value here is required so it trigger a recomposition as soon as it changes.

    // AppPreferences is used to read preferences directly.
    val preferences = settingsViewModel.preferences

    // Listen for refresh in the SettingsViewModel, as certain settings changes may change some checks in the onboarding status.
    LaunchedEffect(settingsViewModelUpdateTrigger) {
        val newStatus = OnboardingStatus.getStatus(activityContext, preferences)
        if (newStatus != onboardingStatus) {
            appNavViewModel.refresh()
        }
    }

    var setupRequested by rememberSaveable { mutableStateOf(false) }
    val archiveConfigured = preferences.getRecordingFolderUri() != null
    val screenState = when {
        !onboardingStatus.disclaimerAccepted -> AppScreen.Disclaimer
        !onboardingStatus.isComplete() && (!archiveConfigured || setupRequested) -> AppScreen.Permissions
        else -> AppScreen.Recorder
    }

    LaunchedEffect(onboardingStatus) {
        if (onboardingStatus.isComplete()) setupRequested = false
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { appNavViewModel.refresh() }
    DisposableEffect(appNavViewModel) {
        val onStart = Shizuku.OnBinderReceivedListener { appNavViewModel.refresh() }
        val onStop = Shizuku.OnBinderDeadListener { appNavViewModel.refresh() }
        Shizuku.addBinderReceivedListener(onStart)
        Shizuku.addBinderDeadListener(onStop)
        onDispose {
            Shizuku.removeBinderReceivedListener(onStart)
            Shizuku.removeBinderDeadListener(onStop)
        }
    }

    // Derive the active theme from AppPreferences so a theme change triggers a refresh (recompose)
    // and is applied immediately.
    val darkTheme = when ( preferences.getThemeMode()) {
        AppPreferences.ThemeMode.LIGHT -> false
        AppPreferences.ThemeMode.DARK   -> true
        AppPreferences.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val dynamicColor = preferences.isDynamicColorEnabled()

    // -------- Show the right screen
    ShizuCallRecorderTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
        AnimatedContent(
            targetState = screenState,
            transitionSpec = {
                val movingForward = targetState.ordinal > initialState.ordinal
                // Slide in from the right when moving forward (1), slide out to the left when moving forward (-1).
                val slideDirection = if (movingForward) 1 else -1

                val enterSpec = slideInHorizontally(tween(350)) { width -> width * slideDirection } + fadeIn(tween(350))
                val exitSpec = slideOutHorizontally(tween(300)) { width -> -width * slideDirection } + fadeOut(tween(300))

                enterSpec togetherWith exitSpec
            },
            label = "AppScreenNavigationTransition"
        ) { targetScreenState ->
            when (targetScreenState) {
                AppScreen.Disclaimer -> DisclaimerScreen(
                    onContinue = {
                        preferences.setDisclaimerAccepted(true)
                        appNavViewModel.refresh()
                    }
                )

                AppScreen.Permissions -> {
                    BackHandler(enabled = setupRequested && archiveConfigured) { setupRequested = false }
                    PermissionsScreen(
                        status = onboardingStatus,
                        onPermissionGranted = { appNavViewModel.refresh() }
                    )
                }

                AppScreen.Recorder -> RecorderHome(
                    status = onboardingStatus,
                    settingsViewModel = settingsViewModel,
                    settingsRevision = settingsViewModelUpdateTrigger,
                    onSetup = { setupRequested = true },
                )
            }
        }
    }
}

private enum class AppScreen { Disclaimer, Permissions, Recorder }

@Composable
private fun RecorderHome(
    status: OnboardingStatus.Status,
    settingsViewModel: SettingsViewModel,
    settingsRevision: Int,
    onSetup: () -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSettings) { showSettings = false }
    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0)) {
                NavigationBarItem(
                    selected = !showSettings,
                    onClick = { showSettings = false },
                    icon = { Icon(Icons.Default.History, null) },
                    label = { Text(stringResource(R.string.archive_tab)) },
                )
                NavigationBarItem(
                    selected = showSettings,
                    onClick = { showSettings = true },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text(stringResource(R.string.archive_settings)) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (showSettings) {
                SettingsScreen(viewModel = settingsViewModel)
            } else {
                RecordingArchiveScreen(status, settingsViewModel.preferences, settingsRevision, onSetup)
            }
        }
    }
}
