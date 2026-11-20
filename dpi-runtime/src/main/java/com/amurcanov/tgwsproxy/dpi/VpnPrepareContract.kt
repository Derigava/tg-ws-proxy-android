package com.amurcanov.tgwsproxy.dpi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.result.contract.ActivityResultContract

/**
 * Launches the system VPN-consent dialog (`VpnService.prepare`) and reports
 * whether the user granted it. Task 4's UI registers this contract; the caller
 * must first check that the slot is not prepared (a [AndroidVpnSlotSampler]
 * sample reports `prepared = false`) before launching, because [createIntent]
 * fails when prepare() already returns null (nothing to prompt).
 */
class VpnPrepareContract : ActivityResultContract<Unit, Boolean>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        VpnService.prepare(context)
            ?: error("VPN is already prepared; no consent dialog is needed")

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == Activity.RESULT_OK
}
