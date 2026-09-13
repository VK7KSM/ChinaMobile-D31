package dev.octoshrimpy.quik.util

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

object ConversationVisibility {
    @JvmStatic fun isInteractiveUnlocked(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive &&
            !(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
}
