package com.amurcanov.tgwsproxy

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast

/** Uses Android URI handlers so site, Play Store and third-party clients all work. */
object TelegramConnector {
    fun localHost(host: String): String = when (host.trim()) {
        "0.0.0.0", "" -> "127.0.0.1"
        "::", "[::]" -> "::1"
        else -> host.trim()
    }

    fun proxyUri(connection: ActiveProxyConnection): Uri = Uri.Builder()
        .scheme("tg").authority("proxy")
        .appendQueryParameter("server", localHost(connection.host))
        .appendQueryParameter("port", connection.port.toString())
        .appendQueryParameter("secret", connection.secret)
        .build()

    fun open(context: Context, connection: ActiveProxyConnection, chooseAgain: Boolean = false) {
        val uri = proxyUri(connection)
        val base = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val clients = pm.queryIntentActivities(base, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.exported && it.activityInfo.enabled }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        if (clients.isEmpty()) {
            Toast.makeText(context, "Telegram не найден. Установите клиент или скопируйте реквизиты прокси.", Toast.LENGTH_LONG).show()
            return
        }
        val preferences = context.getSharedPreferences("telegram_client", Context.MODE_PRIVATE)
        val saved = preferences.getString("package", null)
        fun launch(packageName: String) {
            try {
                context.startActivity(Intent(base).setPackage(packageName))
                preferences.edit().putString("package", packageName).apply()
            } catch (_: ActivityNotFoundException) {
                preferences.edit().remove("package").apply()
                Toast.makeText(context, "Клиент недоступен. Выберите другой Telegram.", Toast.LENGTH_LONG).show()
            } catch (_: SecurityException) {
                Toast.makeText(context, "Не удалось открыть Telegram.", Toast.LENGTH_LONG).show()
            }
        }
        val remembered = clients.firstOrNull { it.activityInfo.packageName == saved }
        when {
            clients.size == 1 -> launch(clients.first().activityInfo.packageName)
            remembered != null && !chooseAgain -> launch(remembered.activityInfo.packageName)
            else -> AlertDialog.Builder(context)
                .setTitle("Выберите Telegram")
                .setItems(clients.map { "${it.loadLabel(pm)} (${it.activityInfo.packageName})" }.toTypedArray()) { _, index ->
                    launch(clients[index].activityInfo.packageName)
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }
}
