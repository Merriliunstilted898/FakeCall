package com.upnp.fakeCall.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

    NavHost(
        navController = navController,
        startDestination = ROUTE_DASHBOARD,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(320)
            )
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(320)
            )
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(320)
            )
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(320)
            )
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

private fun hasAllPermissions(context: Context): Boolean {
    return RequiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
