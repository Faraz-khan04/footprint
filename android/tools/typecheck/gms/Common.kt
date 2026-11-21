// Stand-ins for the parts of Google Play services the app uses (play-services-fitness 21.2.0).
// Signatures mirror the documented Recording API on mobile; type-check only.
package com.google.android.gms.common

import android.content.Context

class ConnectionResult { companion object { const val SUCCESS = 0 } }
class GoogleApiAvailabilityLight {
    fun isGooglePlayServicesAvailable(context: Context, minApkVersion: Int): Int = TODO()
    companion object { @JvmStatic fun getInstance(): GoogleApiAvailabilityLight = TODO() }
}
