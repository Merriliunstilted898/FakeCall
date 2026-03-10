package com.upnp.fakeCall.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.upnp.fakeCall.FakeCallViewModel
import com.upnp.fakeCall.ui.screens.DashboardScreen
import com.upnp.fakeCall.ui.screens.SettingsScreen

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_SETTINGS = "settings"

private val RequiredPermissions = arrayOf(
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_PHONE_NUMBERS,
    Manifest.permission.RECORD_AUDIO
)

@Composable
fun FakeCallApp(viewModel: FakeCallViewModel = viewModel()) {
    val navController = rememberNavController()
    val slideSpec = tween<IntOffset>(
        durationMillis = 380,
        easing = FastOutSlowInEasing
    )
    val fadeSpec = tween<Float>(
        durationMillis = 180,
        easing = FastOutSlowInEasing
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onPermissionStateChanged(hasAllPermissions(navController.context))
    }

    LaunchedEffect(Unit) {
        val granted = hasAllPermissions(navController.context)
        viewModel.onPermissionStateChanged(granted)
        if (!granted) {
            permissionLauncher.launch(RequiredPermissions)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        NavHost(
            navController = navController,
            startDestination = ROUTE_DASHBOARD,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = slideSpec
                ) + fadeIn(animationSpec = fadeSpec)
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = slideSpec
                ) + fadeOut(animationSpec = fadeSpec)
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = slideSpec
                ) + fadeIn(animationSpec = fadeSpec)
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = slideSpec
                ) + fadeOut(animationSpec = fadeSpec)
            }
        ) {
            composable(route = ROUTE_DASHBOARD) {
                DashboardScreen(
                    viewModel = viewModel,
                    onOpenSettings = { navController.navigate(ROUTE_SETTINGS) }
                )
            }

            composable(route = ROUTE_SETTINGS) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onRequestPermissions = { permissionLauncher.launch(RequiredPermissions) }
                )
            }
        }
    }
}

private fun hasAllPermissions(context: Context): Boolean {
    return RequiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
