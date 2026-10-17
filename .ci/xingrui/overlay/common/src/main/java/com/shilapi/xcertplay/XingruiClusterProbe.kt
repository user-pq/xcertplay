package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display

/** Runtime-only Xingrui cluster probe using Android's public display API. */
internal object XingruiClusterProbe {
    private const val TAG = "XingruiCluster"

    fun find(context: Context, supplied: List<Display>? = null): Display? {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return null
        val displays = supplied ?: manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toList()
        return displays
            .map { it to candidate(it) }
            .onEach { (display, c) ->
                Log.i(TAG, "candidate id=${display.displayId} name=${display.name} ${c.width}x${c.height} " +
                    "flags=${display.flags} valid=${display.isValid} score=${XingruiDisplayScorer.score(c)}")
            }
            .filter { (_, c) -> XingruiDisplayScorer.isLikelyCluster(c) }
            .maxByOrNull { (_, c) -> XingruiDisplayScorer.score(c) }
            ?.first
    }

    fun isCandidate(display: Display): Boolean = XingruiDisplayScorer.isLikelyCluster(candidate(display))

    fun report(context: Context): String {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return "displayManager=missing"
        val all = manager.displays.joinToString(" | ") { display ->
            val c = candidate(display)
            "${display.displayId}:${display.name} ${c.width}x${c.height} flags=${display.flags} " +
                "valid=${display.isValid} score=${XingruiDisplayScorer.score(c)} likely=${XingruiDisplayScorer.isLikelyCluster(c)}"
        }
        val selected = find(context)?.let { "${it.displayId}:${it.name}" } ?: "none"
        return "allDisplays=$all\nselectedXingruiCluster=$selected"
    }

    private fun candidate(display: Display): XingruiDisplayScorer.Candidate {
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        return XingruiDisplayScorer.Candidate(
            id = display.displayId,
            name = display.name.orEmpty(),
            width = size.x,
            height = size.y,
            presentation = (display.flags and Display.FLAG_PRESENTATION) != 0,
            valid = display.isValid,
            isDefault = display.displayId == Display.DEFAULT_DISPLAY,
        )
    }
}
