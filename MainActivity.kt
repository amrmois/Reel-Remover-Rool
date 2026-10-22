package com.amr.reelblocker

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "Reel Blocker"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        }

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
        }

        val info = TextView(this).apply {
            textSize = 15f
            text = "Blocks the Reels tab in Instagram and stops endless reel swiping.\n\n" +
                "A reel someone sends you in a message still opens. You just can't swipe " +
                "to the next one; swiping takes you back to the chat.\n\n" +
                "Turn it on under Accessibility > Installed apps > Reel Blocker.\n\n" +
                "If Android says the setting is restricted: Settings > Apps > Reel Blocker > " +
                "three-dot menu > Allow restricted settings, then try again."
        }

        val button = Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        val appInfo = Button(this).apply {
            text = "Open app info (restricted settings)"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:$packageName"))
                )
            }
        }

        root.addView(title)
        root.addView(status)
        root.addView(info)
        root.addView(button)
        root.addView(appInfo)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (isServiceEnabled()) "Status: ON" else "Status: OFF"
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = ComponentName(this, ReelBlockerService::class.java)
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }
}
