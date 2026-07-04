package dev.warsha.remoteble.agent

import android.os.Build

actual fun platformName(): String = "android/${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"
