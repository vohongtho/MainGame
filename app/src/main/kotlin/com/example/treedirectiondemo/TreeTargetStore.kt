package com.example.treedirectiondemo

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Persistent target identity used by production navigation.
 *
 * The app can receive a tree from another screen/app using either Intent extras:
 *   TREE_ID, TREE_LAT, TREE_LNG, TREE_TERRAIN_OFFSET_M
 * or a deep link:
 *   treenavigator://navigate?treeId=T123&lat=1.3521&lng=103.8198&offset=0
 */
class TreeTargetStore(context: Context) {
    data class Target(
        val treeId: String,
        val latitude: Double,
        val longitude: Double,
        val altitudeAboveTerrainM: Double = 0.0,
        val source: Source = Source.PRODUCTION
    ) {
        enum class Source { PRODUCTION, GENERATED_TEST }

        fun isValid(): Boolean =
            treeId.isNotBlank() &&
                latitude in -89.9..89.9 &&
                longitude in -180.0..180.0 &&
                altitudeAboveTerrainM in -20.0..100.0
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): Target? {
        if (!prefs.getBoolean(KEY_HAS_TARGET, false)) return null
        val target = Target(
            treeId = prefs.getString(KEY_ID, "") ?: "",
            latitude = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAT, 0L)),
            longitude = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LNG, 0L)),
            altitudeAboveTerrainM = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_OFFSET, 0L)),
            source = runCatching {
                Target.Source.valueOf(prefs.getString(KEY_SOURCE, Target.Source.PRODUCTION.name)!!)
            }.getOrDefault(Target.Source.PRODUCTION)
        )
        return target.takeIf(Target::isValid)
    }

    fun save(target: Target) {
        require(target.isValid()) { "Invalid tree target" }
        prefs.edit()
            .putBoolean(KEY_HAS_TARGET, true)
            .putString(KEY_ID, target.treeId)
            .putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(target.latitude))
            .putLong(KEY_LNG, java.lang.Double.doubleToRawLongBits(target.longitude))
            .putLong(KEY_OFFSET, java.lang.Double.doubleToRawLongBits(target.altitudeAboveTerrainM))
            .putString(KEY_SOURCE, target.source.name)
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_HAS_TARGET).remove(KEY_ID).remove(KEY_LAT).remove(KEY_LNG)
            .remove(KEY_OFFSET).remove(KEY_SOURCE).apply()
    }

    fun fromIntent(intent: Intent?): Target? {
        if (intent == null) return null

        val fromExtras = if (
            intent.hasExtra(EXTRA_LAT) && intent.hasExtra(EXTRA_LNG)
        ) {
            Target(
                treeId = intent.getStringExtra(EXTRA_ID)?.ifBlank { null } ?: "TREE",
                latitude = intent.getDoubleExtra(EXTRA_LAT, Double.NaN),
                longitude = intent.getDoubleExtra(EXTRA_LNG, Double.NaN),
                altitudeAboveTerrainM = intent.getDoubleExtra(EXTRA_TERRAIN_OFFSET_M, 0.0),
                source = Target.Source.PRODUCTION
            )
        } else null

        if (fromExtras?.isValid() == true) return fromExtras

        return fromUri(intent.data)
    }

    private fun fromUri(uri: Uri?): Target? {
        if (uri == null || uri.scheme != "treenavigator" || uri.host != "navigate") return null
        val lat = uri.getQueryParameter("lat")?.toDoubleOrNull() ?: return null
        val lng = uri.getQueryParameter("lng")?.toDoubleOrNull() ?: return null
        val offset = uri.getQueryParameter("offset")?.toDoubleOrNull() ?: 0.0
        val id = uri.getQueryParameter("treeId")?.ifBlank { null } ?: "TREE"
        return Target(id, lat, lng, offset, Target.Source.PRODUCTION).takeIf(Target::isValid)
    }

    companion object {
        const val EXTRA_ID = "TREE_ID"
        const val EXTRA_LAT = "TREE_LAT"
        const val EXTRA_LNG = "TREE_LNG"
        const val EXTRA_TERRAIN_OFFSET_M = "TREE_TERRAIN_OFFSET_M"

        private const val PREFS = "tree_target_store"
        private const val KEY_HAS_TARGET = "has_target"
        private const val KEY_ID = "tree_id"
        private const val KEY_LAT = "tree_lat"
        private const val KEY_LNG = "tree_lng"
        private const val KEY_OFFSET = "terrain_offset"
        private const val KEY_SOURCE = "source"
    }
}
