package fr.djstef.gps.car

import android.app.Presentation
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.TravelEstimate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import fr.djstef.gps.AppPrefs
import fr.djstef.gps.CarSprites
import fr.djstef.gps.NavBridge
import fr.djstef.gps.RadarArt
import fr.djstef.gps.Route
import fr.djstef.gps.SignView
import fr.djstef.gps.SpeedBubble
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.TimeZone
import androidx.car.app.navigation.model.Step as CarStep

/**
 * Écran principal Android Auto : la carte MapLibre est rendue dans une fenêtre virtuelle
 * dont la surface est celle de la voiture, avec compteur, panneau de limite et alerte radar par-dessus.
 */
class NavScreen(ctx: CarContext) : Screen(ctx) {
    private val prefs = AppPrefs(ctx)

    init {
        CarSprites.assets = ctx.assets
    }
    private var vd: VirtualDisplay? = null
    private var pres: Presentation? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var overlay: FrameLayout? = null
    private var speedView: SpeedBubble? = null
    private var limitView: SignView? = null
    private var radarBox: LinearLayout? = null
    private var radarIcon: ImageView? = null
    private var radarText: TextView? = null
    private var lastRoute: Route? = null
    private var navStarted = false
    private var lastInvalidate = 0L
    private var lastBearing = 0f
    private var w = 0
    private var h = 0
    private var navMgr: NavigationManager? = null
    private val listener: () -> Unit = { onBridge() }

    private val surfaceCb = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            attach(surfaceContainer)
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            release()
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            overlay?.setPadding(
                visibleArea.left, visibleArea.top,
                (w - visibleArea.right).coerceAtLeast(0), (h - visibleArea.bottom).coerceAtLeast(0)
            )
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCb)
                try {
                    val nm = carContext.getCarService(NavigationManager::class.java)
                    nm.setNavigationManagerCallback(object : NavigationManagerCallback {
                        override fun onStopNavigation() {
                            NavBridge.stop?.invoke()
                        }

                        override fun onAutoDriveEnabled() {}
                    })
                    navMgr = nm
                } catch (_: Exception) {
                }
                NavBridge.listeners.add(listener)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                NavBridge.listeners.remove(listener)
                release()
                if (navStarted) {
                    try {
                        navMgr?.navigationEnded()
                    } catch (_: Exception) {
                    }
                }
            }
        })
    }

    // ---------- Surface / carte ----------

    private fun attach(c: SurfaceContainer) {
        release()
        val s = c.surface ?: return
        w = c.width
        h = c.height
        val dm = carContext.getSystemService(DisplayManager::class.java)
        val v = dm.createVirtualDisplay(
            "djstef-map", w, h, c.dpi, s,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        )
        vd = v
        val p = Presentation(carContext, v.display)
        pres = p
        val ctx = p.context
        val d = ctx.resources.displayMetrics.density
        fun dp(x: Int) = (x * d).toInt()

        MapLibre.getInstance(ctx)
        val root = FrameLayout(ctx)
        val mv = MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx).textureMode(true))
        root.addView(mv, FrameLayout.LayoutParams(-1, -1))
        val ov = FrameLayout(ctx)
        root.addView(ov, FrameLayout.LayoutParams(-1, -1))
        overlay = ov

        val sv = SpeedBubble(ctx)
        ov.addView(sv, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.BOTTOM or Gravity.START).also { it.setMargins(dp(10), 0, 0, dp(10)) })
        speedView = sv
        val lv = SignView(ctx)
        lv.visibility = View.GONE
        ov.addView(lv, FrameLayout.LayoutParams(dp(88), dp(88), Gravity.BOTTOM or Gravity.START).also { it.setMargins(dp(112), 0, 0, dp(12)) })
        limitView = lv

        val rb = LinearLayout(ctx)
        rb.orientation = LinearLayout.HORIZONTAL
        rb.gravity = Gravity.CENTER_VERTICAL
        rb.setBackgroundColor(0xE6C62828.toInt())
        rb.setPadding(dp(10), dp(6), dp(16), dp(6))
        val ri = ImageView(ctx)
        rb.addView(ri, LinearLayout.LayoutParams(dp(56), dp(56)))
        val rt = TextView(ctx)
        rt.setTextColor(Color.WHITE)
        rt.textSize = 22f
        rt.setTypeface(rt.typeface, Typeface.BOLD)
        rb.addView(rt, LinearLayout.LayoutParams(-2, -2).also { it.leftMargin = dp(10) })
        rb.visibility = View.GONE
        ov.addView(rb, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.topMargin = dp(10) })
        radarBox = rb
        radarIcon = ri
        radarText = rt

        p.setContentView(root)
        p.show()
        mapView = mv
        mv.onCreate(null)
        mv.onStart()
        mv.onResume()
        mv.getMapAsync { m ->
            map = m
            m.uiSettings.setAllGesturesEnabled(false)
            m.setStyle(MAP_STYLE) { st ->
                style = st
                setupLayers(st)
                lastRoute = null
                onBridge()
            }
        }
    }

    private fun setupLayers(st: Style) {
        val model = prefs.int("car", 0)
        val col = Color.parseColor(prefs.str("carColor", CarSprites.COLORS[0]))
        st.addImage("car-top", CarSprites.draw(model, col))
        st.addSource(GeoJsonSource("route"))
        st.addLayer(
            LineLayer("route-case", "route").withProperties(
                PropertyFactory.lineColor("#0B1220"), PropertyFactory.lineWidth(13f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        st.addLayer(
            LineLayer("route-layer", "route").withProperties(
                PropertyFactory.lineColor("#FF9F1C"), PropertyFactory.lineWidth(8f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        val key = prefs.str("tomtomKey", "")
        if (key.isNotBlank() && prefs.bool("traffic", false)) {
            try {
                val ts = TileSet("2.2.0", "https://api.tomtom.com/traffic/map/4/tile/flow/relative0/{z}/{x}/{y}.png?key=$key")
                st.addSource(RasterSource("traffic", ts, 256))
                st.addLayerBelow(RasterLayer("traffic-layer", "traffic").withProperties(PropertyFactory.rasterOpacity(0.85f)), "route-case")
            } catch (_: Exception) {
            }
        }
        st.addSource(GeoJsonSource("me"))
        st.addLayer(
            SymbolLayer("me-layer", "me").withProperties(
                PropertyFactory.iconImage("car-top"),
                PropertyFactory.iconSize(0.4f * prefs.float("carSize", 1f)),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconRotate(Expression.get("bearing"))
            )
        )
    }

    private fun release() {
        try {
            mapView?.onPause()
            mapView?.onStop()
            mapView?.onDestroy()
        } catch (_: Exception) {
        }
        try {
            pres?.dismiss()
        } catch (_: Exception) {
        }
        try {
            vd?.release()
        } catch (_: Exception) {
        }
        mapView = null
        map = null
        style = null
        pres = null
        vd = null
        overlay = null
    }

    // ---------- Mise à jour depuis le téléphone ----------

    private fun onBridge() {
        if (NavBridge.navigating != navStarted) {
            navStarted = NavBridge.navigating
            try {
                if (navStarted) navMgr?.navigationStarted() else navMgr?.navigationEnded()
            } catch (_: Exception) {
            }
            invalidate()
        }
        speedView?.set(NavBridge.speed, NavBridge.over)
        val lim = NavBridge.limit
        if (lim != null) {
            limitView?.value = lim
            limitView?.visibility = View.VISIBLE
        } else {
            limitView?.visibility = View.GONE
        }
        val rt = NavBridge.radarText
        if (rt != null) {
            radarText?.text = rt
            radarIcon?.setImageBitmap(RadarArt.bitmap(RadarArt.key(NavBridge.radarType ?: rt), 128))
            radarBox?.visibility = View.VISIBLE
        } else {
            radarBox?.visibility = View.GONE
        }

        val m = map
        val st = style
        val loc = NavBridge.me
        if (m != null && st != null) {
            val r = NavBridge.route
            if (r !== lastRoute) {
                lastRoute = r
                val src = st.getSourceAs<GeoJsonSource>("route")
                if (r == null) src?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
                else src?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(r.pts.map { Point.fromLngLat(it.lon, it.lat) })))
            }
            if (loc != null) {
                if (loc.hasBearing() && loc.speed > 1.5f) lastBearing = loc.bearing
                val f = Feature.fromGeometry(Point.fromLngLat(loc.longitude, loc.latitude))
                f.addNumberProperty("bearing", lastBearing)
                st.getSourceAs<GeoJsonSource>("me")?.setGeoJson(f)
                val kmh = NavBridge.speed
                val zoom = when {
                    kmh > 100 -> 15.0
                    kmh > 70 -> 15.8
                    kmh > 40 -> 16.6
                    else -> 17.3
                }
                m.setPadding(0, (h * 0.35).toInt(), 0, 0)
                m.easeCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(loc.latitude, loc.longitude))
                            .zoom(zoom)
                            .tilt(if (NavBridge.navigating) 50.0 else 0.0)
                            .bearing(if (NavBridge.navigating) lastBearing.toDouble() else 0.0)
                            .build()
                    ),
                    900
                )
            }
        }
        val now = System.currentTimeMillis()
        if (navStarted && now - lastInvalidate > 1500) {
            lastInvalidate = now
            invalidate()
        }
    }

    // ---------- Gabarit affiché par Android Auto ----------

    private fun maneuverType(k: Int): Int = when (k) {
        1, 2, 3 -> Maneuver.TYPE_DEPART
        4 -> Maneuver.TYPE_DESTINATION
        5 -> Maneuver.TYPE_DESTINATION_RIGHT
        6 -> Maneuver.TYPE_DESTINATION_LEFT
        9 -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        10 -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        11 -> Maneuver.TYPE_TURN_SHARP_RIGHT
        12 -> Maneuver.TYPE_U_TURN_RIGHT
        13 -> Maneuver.TYPE_U_TURN_LEFT
        14 -> Maneuver.TYPE_TURN_SHARP_LEFT
        15 -> Maneuver.TYPE_TURN_NORMAL_LEFT
        16 -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        18 -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
        19 -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
        20 -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
        21 -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
        23 -> Maneuver.TYPE_KEEP_RIGHT
        24 -> Maneuver.TYPE_KEEP_LEFT
        25 -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
        26 -> Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
        27 -> Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
        28 -> Maneuver.TYPE_FERRY_BOAT
        else -> Maneuver.TYPE_STRAIGHT
    }

    private fun dist(m: Double): Distance =
        if (m >= 1000) Distance.create(m / 1000.0, Distance.UNIT_KILOMETERS_P1)
        else Distance.create(((m / 10).toInt() * 10).toDouble().coerceAtLeast(10.0), Distance.UNIT_METERS)

    override fun onGetTemplate(): Template {
        val b = NavigationTemplate.Builder()
        val strip = ActionStrip.Builder()
        if (NavBridge.navigating) {
            strip.addAction(
                Action.Builder().setTitle("Arrêter").setOnClickListener {
                    NavBridge.stop?.invoke()
                    invalidate()
                }.build()
            )
            val sb = CarStep.Builder(NavBridge.nextText.ifBlank { "Suivez l'itinéraire" })
                .setManeuver(Maneuver.Builder(maneuverType(NavBridge.nextKind)).build())
            if (NavBridge.nextName.isNotBlank()) sb.setRoad(NavBridge.nextName)
            val step = sb.build()
            b.setNavigationInfo(RoutingInfo.Builder().setCurrentStep(step, dist(NavBridge.nextDist)).build())
            val sec = NavBridge.remainSec.toLong().coerceAtLeast(0)
            b.setDestinationTravelEstimate(
                TravelEstimate.Builder(
                    dist(NavBridge.remainDist.coerceAtLeast(10.0)),
                    DateTimeWithZone.create(System.currentTimeMillis() + sec * 1000, TimeZone.getDefault())
                ).setRemainingTimeSeconds(sec).build()
            )
        } else {
            strip.addAction(
                Action.Builder().setTitle("Destinations").setOnClickListener {
                    screenManager.push(DestScreen(carContext))
                }.build()
            )
        }
        b.setActionStrip(strip.build())
        return b.build()
    }

    companion object {
        const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
    }
}
