package io.github.arnavdugad.usagenotch

import android.app.Activity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Scans the pairing QR code shown in UsageNotch for Windows (Settings → Phone). Uses Google Play services'
 * scanner: its own camera screen, no camera permission for this app, and the image stays on the phone.
 */
object PairScanner {
    sealed interface Result { data class Code(val text: String) : Result; data object Cancelled : Result; data class Unavailable(val reason: String) : Result }
    fun scan(activity: Activity, done: (Result) -> Unit) {
        try {
            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
            GmsBarcodeScanning.getClient(activity, options).startScan()
                .addOnSuccessListener { code -> done(code.rawValue?.let { Result.Code(it) } ?: Result.Unavailable("That code couldn't be read. Try again, a little closer.")) }
                .addOnCanceledListener { done(Result.Cancelled) }
                .addOnFailureListener { done(Result.Unavailable("The scanner isn't available on this phone. Use Paste pairing code instead.")) }
        } catch (_: Throwable) { done(Result.Unavailable("The scanner isn't available on this phone. Use Paste pairing code instead.")) }
    }
}
