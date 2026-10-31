package io.github.arnavdugad.usagenotch

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * One vocabulary of touch feedback, so each moment feels different:
 * - [tick]: a light tick while you scrub a chart;
 * - [confirm]: a crisp confirmation when a refresh finishes;
 * - [warning]: a firmer pattern when a limit crosses your warning (or, stronger, your urgent) level while you watch;
 * - [renewed]: a celebratory double tap when a limit renews.
 *
 * Feedback goes through the view, so Android's own "touch feedback" setting is always honoured, and Reduce motion
 * silences all of it.
 */
class Haptics(private val view: View?, private val reduced: Boolean) {
    private fun play(constant: Int) { if (!reduced) view?.performHapticFeedback(constant) }
    private fun later(ms: Long, constant: Int) { if (!reduced) view?.postDelayed({ view.performHapticFeedback(constant) }, ms) }

    fun tick() = play(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = play(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    fun warning(urgent: Boolean) {
        play(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
        if (urgent) later(140, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    }
    fun renewed() {
        play(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        later(110, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    companion object {
        /** Which level a used fraction is at: 0 below warning, 1 at warning, 2 at urgent. */
        fun level(used: Double, warn: Int, critical: Int) = when { used * 100 >= critical -> 2; used * 100 >= warn -> 1; else -> 0 }
    }
}

val LocalHaptics = staticCompositionLocalOf { Haptics(null, true) }

@Composable fun rememberHaptics(reduced: Boolean): Haptics { val view = LocalView.current; return remember(view, reduced) { Haptics(view, reduced) } }
