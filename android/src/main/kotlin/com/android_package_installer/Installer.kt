package com.android_package_installer

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.FileInputStream
import java.io.IOException

internal class Installer(private val context: Context, private var activity: Activity?) {
    private var sessionId: Int = 0
    private lateinit var session: PackageInstaller.Session
    private lateinit var packageManager: PackageManager
    private lateinit var packageInstaller: PackageInstaller

    fun setActivity(activity: Activity?) {
        this.activity = activity
    }

    // Aggiungi questa variabile a livello di classe
    private var currentPackageName: String? = null

    fun installPackage(apkPath: String) {
        try {
            // Estrai il package name prima dell'installazione
            currentPackageName = extractPackageNameFromApk(apkPath)

            session = createSession(activity!!)
            loadAPKFile(apkPath, session)

            val intent = Intent(context, activity!!.javaClass)
            intent.action = packageInstalledAction
            // Passa il package name attraverso l'intent
            currentPackageName?.let {
                intent.putExtra("PACKAGE_NAME", it)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val statusReceiver = pendingIntent.intentSender
            session.commit(statusReceiver)
            session.close()
        } catch (e: IOException) {
            throw RuntimeException("IO exception", e)
        } catch (e: Exception) {
            session.abandon()
            throw e
        }
    }

    private fun extractPackageNameFromApk(apkPath: String): String? {
        return try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_ACTIVITIES)
            packageInfo?.packageName
        } catch (e: Exception) {
            android.util.Log.e("PackageInstaller", "Errore estrazione package name: ${e.message}")
            null
        }
    }

    private fun createSession(activity: Activity): PackageInstaller.Session {
        try {
            packageManager = activity.packageManager
            packageInstaller = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                params.setInstallReason(PackageManager.INSTALL_REASON_USER)
            }

            sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)
        } catch (e: Exception) {
            throw e
        }
        return session
    }

    @Throws(IOException::class)
    private fun loadAPKFile(apkPath: String, session: PackageInstaller.Session) {
        session.openWrite("package", 0, -1).use { packageInSession ->
            FileInputStream(apkPath).use { `is` ->
                val buffer = ByteArray(16384)
                var n: Int
                var o = 1
                while (`is`.read(buffer).also { n = it } >= 0) {
                    packageInSession.write(buffer, 0, n)
                    o++
                }
            }
        }
    }
}

