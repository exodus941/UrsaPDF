package com.ursapdf

import android.os.Build
import android.os.ext.SdkExtensions

object Features {
    /**
     * The system PdfRenderer can search text and edit forms: Android 15, or Android
     * 11-14 that received SDK extension 13 through a Google system update. Elsewhere
     * the downloaded engine ([Libraries]) provides both.
     */
    val builtIn: Boolean = Build.VERSION.SDK_INT >= 35 ||
        (Build.VERSION.SDK_INT >= 30 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13)
}
