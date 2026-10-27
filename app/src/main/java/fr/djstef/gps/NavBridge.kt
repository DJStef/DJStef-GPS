package fr.djstef.gps

import android.location.Location
import java.util.concurrent.CopyOnWriteArrayList

/** Pont entre l'activité du téléphone (moteur de navigation) et l'écran Android Auto. */
object NavBridge {
    var navigating = false
    var route: Route? = null
    var me: Location? = null
    var speed = 0
    var limit: Int? = null
    var over = false
    var nextKind = 1
    var nextText = ""
    var nextName = ""
    var nextDist = 0.0
    var remainDist = 0.0
    var remainSec = 0.0
    var destName = ""
    var radarText: String? = null
    var radarType: String? = null

    /** Définit la destination et lance directement la navigation (appelé depuis la voiture). */
    var go: ((Place) -> Unit)? = null
    var stop: (() -> Unit)? = null

    val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun changed() {
        for (l in listeners) {
            try {
                l()
            } catch (_: Exception) {
            }
        }
    }
}
