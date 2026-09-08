package com.mtbanalyzer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permission that lets the gallery see videos this install doesn't own —
 * recordings from a previous install, or clips other apps put in Movies/. Without it
 * MediaStore only returns files the current install created, which is why an
 * uninstall/reinstall used to look like it had lost every recording.
 */
object MediaPermissions {

    fun readVideoPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasReadVideoPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, readVideoPermission()) == PackageManager.PERMISSION_GRANTED
}
