package com.android_package_installer

import android.annotation.TargetApi
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.util.Log
import io.flutter.plugin.common.PluginRegistry

internal class OnNewIntentListener(private var activity: Activity?) : PluginRegistry.NewIntentListener {
    private val TAG = "OnNewIntentListener"

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onNewIntent(intent: Intent): Boolean {
        Log.d(TAG, "onNewIntent chiamato")
        Log.d(TAG, "Intent action: ${intent.action}")
        Log.d(TAG, "packageInstalledAction: $packageInstalledAction")
        Log.d(TAG, "Intent extras: ${intent.extras}")

        if (intent.action == packageInstalledAction && intent.extras != null) {
            Log.d(TAG, "Condizione intent.action soddisfatta")
            val extras: Bundle = intent.extras ?: return false
            Log.d(TAG, "Extras ottenuti: $extras")

            val status = extras.getInt(PackageInstaller.EXTRA_STATUS)
            Log.d(TAG, "Status ricevuto: $status")

            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    Log.d(TAG, "Status: PENDING_USER_ACTION")
                    var confirmIntent = (extras.get(Intent.EXTRA_INTENT) as Intent)
                    confirmIntent =
                        confirmIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    Log.d(TAG, "Avvio confirm intent")
                    activity?.startActivity(confirmIntent)
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    Log.d(TAG, "Status: SUCCESS - Installazione completata")

                    // Prendi il package name dall'intent originale o dall'extra
                    val packageNameFromIntent = intent.getStringExtra("PACKAGE_NAME")
                    val packageNameFromExtras = extras.getString(PackageInstaller.EXTRA_PACKAGE_NAME)

                    Log.d(TAG, "Package name da intent: $packageNameFromIntent")
                    Log.d(TAG, "Package name da extras: $packageNameFromExtras")

                    val packageName = packageNameFromIntent ?: packageNameFromExtras
                    Log.d(TAG, "Package name finale: $packageName")

                    packageName?.let {
                        Log.d(TAG, "Tentativo di avvio app con package: $it")
                        launchInstalledApp(it)
                    } ?: Log.w(TAG, "Package name è null - impossibile avviare l'app")

                    Log.d(TAG, "Chiamata MethodCallHandler.resultSuccess")
                    MethodCallHandler.resultSuccess(status)
                }

                else -> {
                    Log.d(TAG, "Status: $status (altro)")
                    MethodCallHandler.resultSuccess(status)
                }
            }
        } else {
            Log.d(TAG, "Condizione intent.action NON soddisfatta")
            Log.d(TAG, "Action match: ${intent.action == packageInstalledAction}")
            Log.d(TAG, "Extras non null: ${intent.extras != null}")
        }
        return true
    }

    private fun launchInstalledApp(packageName: String) {
        Log.d(TAG, "launchInstalledApp chiamato per: $packageName")

        // Usa più delay progressivi per dare tempo al sistema
        retryLaunchApp(packageName, 0, arrayOf(500, 1500, 3000, 5000))
    }

    private fun retryLaunchApp(packageName: String, attemptIndex: Int, delays: Array<Long>) {
        if (attemptIndex >= delays.size) {
            Log.w(TAG, "Tutti i tentativi falliti per $packageName")
            openAppSettings(packageName)
            return
        }

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "Tentativo ${attemptIndex + 1} per: $packageName")

            if (tryLaunchWithBroadcast(packageName)) {
                Log.d(TAG, "App lanciata con successo al tentativo ${attemptIndex + 1}")
                return@postDelayed
            }

            // Se fallisce, riprova con il prossimo delay
            retryLaunchApp(packageName, attemptIndex + 1, delays)
        }, delays[attemptIndex])
    }

    private fun tryLaunchWithBroadcast(packageName: String): Boolean {
        try {
            // Forza il refresh del PackageManager
            val pm = activity?.packageManager

            // Metodo 1: Forza refresh della cache
            pm?.getInstalledPackages(0)

            // Metodo 2: Invia broadcast per forzare l'aggiornamento
            val broadcastIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            activity?.sendBroadcast(broadcastIntent)

            // Metodo 3: Prova con getLaunchIntentForPackage
            val launchIntent = pm?.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                Log.d(TAG, "Launch intent ottenuto: $launchIntent")
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                activity?.startActivity(launchIntent)
                return true
            }

            // Metodo 4: Query manuale con refresh
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val allApps = pm?.queryIntentActivities(mainIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            Log.d(TAG, "Query apps: ${allApps?.size}")

            val targetApp = allApps?.find { it.activityInfo.packageName == packageName }
            if (targetApp != null) {
                Log.d(TAG, "App trovata nella query: ${targetApp.activityInfo.name}")
                val intent = Intent().apply {
                    setClassName(packageName, targetApp.activityInfo.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                activity?.startActivity(intent)
                return true
            }

            // Metodo 5: Usa ComponentName direttamente
            return tryLaunchWithComponentName(packageName)

        } catch (e: Exception) {
            Log.e(TAG, "Errore tryLaunchWithBroadcast: ${e.message}")
            return false
        }
    }

    private fun tryLaunchWithComponentName(packageName: String): Boolean {
        return try {
            // Per le app Flutter, prova con MainActivity
            val componentName = android.content.ComponentName(packageName, "$packageName.MainActivity")
            val intent = Intent().apply {
                component = componentName
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            Log.d(TAG, "Tentativo con ComponentName: $componentName")
            activity?.startActivity(intent)
            Log.d(TAG, "App lanciata con ComponentName")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Errore ComponentName: ${e.message}")

            // Ultimo tentativo: usa il package manager per trovare la main activity
            tryFindMainActivity(packageName)
        }
    }

    private fun tryFindMainActivity(packageName: String): Boolean {
        return try {
            val pm = activity?.packageManager
            val packageInfo = pm?.getPackageInfo(packageName, android.content.pm.PackageManager.GET_ACTIVITIES)

            packageInfo?.activities?.forEach { activityInfo ->
                Log.d(TAG, "Activity trovata: ${activityInfo.name}")

                // Prova ogni activity per vedere se è quella principale
                try {
                    val intent = Intent().apply {
                        setClassName(packageName, activityInfo.name)
                        action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    activity?.startActivity(intent)
                    Log.d(TAG, "App lanciata con activity: ${activityInfo.name}")
                    return true

                } catch (e: Exception) {
                    Log.d(TAG, "Activity ${activityInfo.name} non è launcher")
                }
            }

            false

        } catch (e: Exception) {
            Log.e(TAG, "Errore tryFindMainActivity: ${e.message}")
            false
        }
    }

    private fun openAppSettings(packageName: String) {
        try {
            Log.d(TAG, "Apertura impostazioni app per: $packageName")
            val settingsIntent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity?.startActivity(settingsIntent)
            Log.d(TAG, "Impostazioni app aperte")
        } catch (e: Exception) {
            Log.e(TAG, "Errore apertura impostazioni: ${e.message}")
        }
    }
}