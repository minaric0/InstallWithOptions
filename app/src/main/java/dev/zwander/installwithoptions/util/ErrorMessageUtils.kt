package dev.zwander.installwithoptions.util

import android.content.Context
import dev.zwander.installwithoptions.R

fun Context.getSpecificErrorMessage(legacyStatus: Int): String? {
    val res = when (legacyStatus) {
        // INSTALL_FAILED_ALREADY_EXISTS
        -1 -> R.string.install_failed_already_exists
        // Android 17 PackageManager legacy install status codes.
        -2 -> R.string.a175n_error_invalid_apk
        -7 -> R.string.a175n_error_signature
        -12 -> R.string.a175n_error_min_sdk
        -14 -> R.string.a175n_error_max_sdk
        -15 -> R.string.a175n_error_test_apk
        -25 -> R.string.a175n_error_downgrade
        -28 -> R.string.a175n_error_missing_split
        -29 -> R.string.a175n_error_target_sdk
        -111 -> R.string.a175n_error_user_restricted
        -113 -> R.string.install_failed_no_matching_abis
        else -> null
    }

    return res?.let { resources.getString(it) }
}
