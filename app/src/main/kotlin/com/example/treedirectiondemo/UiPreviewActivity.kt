package com.example.treedirectiondemo

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout

/** Temporary deterministic renderer used by CI screenshots. It has no production navigation logic. */
class UiPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(52, 79, 51)) }
        val ui = TreeNavigatorUiView(this)
        root.addView(ui, FrameLayout.LayoutParams(-1,-1))
        setContentView(root)
        ui.screen = runCatching {
            TreeNavigatorUiView.Screen.valueOf(intent.getStringExtra("screen") ?: "HOME")
        }.getOrDefault(TreeNavigatorUiView.Screen.HOME)
        ui.model = TreeNavigatorUiView.Model(
            gpsQuality="GOOD", gpsAccuracy=3.0f,
            headingQuality="GOOD", headingAccuracyDeg=2.1,
            targetDistanceM=24.0, directionDeltaDeg=18.0,
            targetScreenX=0.69f, targetScreenY=0.43f,
            targetInFront=true, targetReady=true, arTracking=true,
            selectedDistanceM=35, elevationOffsetM=0,
            showDistance=true, showGuidance=true,
            headingSmoothing="Balanced", gpsSmoothing="High", declinationEnabled=true,
            gameYaw=123.4, magneticHeading=128.7, trueHeading=126.2, filteredHeading=126.1,
            turnSpeed=8.3, gpsBearing=144.6, filteredBearing=143.8,
            phoneLat=10.776123, phoneLng=106.701234,
            treeLat=10.776345, treeLng=106.701567,
            arStatus="WORLD LOCKED"
        )
    }
}
