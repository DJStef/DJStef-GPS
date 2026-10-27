package fr.djstef.gps

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        const val VERSION = "0.1"
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val NAVY = "#E6101828"
        const val ORANGE = "#FF9F1C"
        const val RED = "#E53935"
        const val GREEN = "#43A047"
        const val GREY = "#3A4256"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null

    private lateinit var searchBox: EditText
    private lateinit var resultsBox: LinearLayout
    private lateinit var navBanner: TextView
    private lateinit var radarBanner: TextView
    private lateinit var speedView: TextView
    private lateinit var panel: LinearLayout
    private lateinit var panelInfo: TextView
    private lateinit var startBtn: TextView
    private val modeBtns = HashMap<String, TextView>()

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var me: Location? = null
    private var follow = true
    private var navigating = false
    private var mode = "car"
    private var dest: Pt? = null
    private var destName = ""
    private var route: Route? = null
    private var stepIdx = 0
    private var farSaid = -1
    private var nearSaid = -1
    private var offCount = 0
    private var rerouting = false

    private var fixedRadars: List<Radar> = emptyList()
    private var localReports = ArrayList<Pair<Report, Long>>() // signalement + expiration
    private var remoteReports: List<Report> = emptyList()
    private val alerted = HashSet<String>()
    private var pollJob: Job? = null

    private val prefs by lazy { getSharedPreferences("djstef_gps", Context.MODE_PRIVATE) }
    private val locMgr by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }

    private val locListener = LocationListener { loc -> onLocation(loc) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    // ---------- Cycle de vie ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRANCE
                ttsReady = true
            }
        }
        buildUi(savedInstanceState)
        scope.launch {
            fixedRadars = RadarRepository.load(this@MainActivity)
            refreshRadarLayer()
            if (fixedRadars.isEmpty()) toast("Radars fixes indisponibles (pas de connexion ?)")
        }
        ensureLocation()
        startPolling()
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume(); startLocation() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { locMgr.removeUpdates(locListener) } catch (e: Exception) { }
        tts?.shutdown()
        mapView.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            resultsBox.visibility == View.VISIBLE -> resultsBox.visibility = View.GONE
            navigating -> stopNavigation()
            route != null -> clearRoute()
            else -> super.onBackPressed()
        }
    }

    // ---------- Interface ----------

    private fun bg(color: String, radius: Int = 14): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(Color.parseColor(color))
        g.cornerRadius = dp(radius).toFloat()
        return g
    }

    private fun label(text: String, size: Float = 15f, bold: Boolean = false, color: String = "#FFFFFF"): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(Color.parseColor(color))
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    private fun button(text: String, color: String, onClick: () -> Unit): TextView {
        val t = label(text, 15f, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(14), dp(12), dp(14), dp(12))
        t.background = bg(color)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun roundBtn(text: String, onClick: () -> Unit): TextView {
        val t = label(text, 20f, true)
        t.gravity = Gravity.CENTER
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(Color.parseColor(NAVY))
        t.background = g
        t.layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).also { it.topMargin = dp(10) }
        t.setOnClickListener { onClick() }
        return t
    }

    private fun buildUi(savedInstanceState: Bundle?) {
        val root = FrameLayout(this)
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        mapView.getMapAsync { m ->
            map = m
            m.cameraPosition = CameraPosition.Builder().target(LatLng(46.6, 2.5)).zoom(5.0).build()
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { st -> onStyleReady(m, st) }
        }

        // Haut : recherche + résultats + bandeaux
        val top = LinearLayout(this)
        top.orientation = LinearLayout.VERTICAL
        top.setPadding(dp(10), dp(10), dp(10), 0)

        searchBox = EditText(this)
        searchBox.hint = "Rechercher une adresse ou un lieu"
        searchBox.setHintTextColor(Color.parseColor("#9AA3B5"))
        searchBox.setTextColor(Color.WHITE)
        searchBox.textSize = 16f
        searchBox.background = bg(NAVY, 24)
        searchBox.setPadding(dp(18), dp(12), dp(18), dp(12))
        searchBox.inputType = InputType.TYPE_CLASS_TEXT
        searchBox.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchBox.setSingleLine(true)
        searchBox.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        top.addView(searchBox, LinearLayout.LayoutParams(-1, -2))

        val resScroll = ScrollView(this)
        resultsBox = LinearLayout(this)
        resultsBox.orientation = LinearLayout.VERTICAL
        resultsBox.background = bg(NAVY, 14)
        resultsBox.visibility = View.GONE
        resScroll.addView(resultsBox)
        top.addView(resScroll, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(6) })

        navBanner = label("", 18f, true)
        navBanner.background = bg("#E60F5132", 14)
        navBanner.setPadding(dp(14), dp(12), dp(14), dp(12))
        navBanner.visibility = View.GONE
        top.addView(navBanner, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })

        radarBanner = label("", 18f, true)
        radarBanner.background = bg("#F2B71C1C", 14)
        radarBanner.setPadding(dp(14), dp(12), dp(14), dp(12))
        radarBanner.visibility = View.GONE
        top.addView(radarBanner, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })

        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Droite : boutons
        val side = LinearLayout(this)
        side.orientation = LinearLayout.VERTICAL
        side.addView(roundBtn("◎") { recenter() })
        side.addView(roundBtn("⚠") { reportDialog() })
        val sideLp = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL)
        sideLp.rightMargin = dp(10)
        root.addView(side, sideLp)

        // Bas gauche : vitesse + version
        speedView = label("0\nkm/h", 18f, true)
        speedView.gravity = Gravity.CENTER
        speedView.background = bg(NAVY, 40)
        val spLp = FrameLayout.LayoutParams(dp(78), dp(78), Gravity.BOTTOM or Gravity.START)
        spLp.leftMargin = dp(10)
        spLp.bottomMargin = dp(16)
        root.addView(speedView, spLp)

        val ver = label("v$VERSION", 11f, false, "#C0C7D6")
        ver.setOnLongClickListener { serverDialog(); true }
        val vLp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
        vLp.rightMargin = dp(10)
        vLp.bottomMargin = dp(2)
        ver.setShadowLayer(3f, 0f, 0f, Color.BLACK)
        root.addView(ver, vLp)

        // Bas : panneau itinéraire
        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = bg(NAVY, 18)
        panel.setPadding(dp(14), dp(12), dp(14), dp(12))
        panel.visibility = View.GONE
        panelInfo = label("", 17f, true)
        panel.addView(panelInfo)

        val modes = LinearLayout(this)
        modes.orientation = LinearLayout.HORIZONTAL
        for ((key, name) in listOf("car" to "Voiture", "bike" to "Vélo", "foot" to "Marche")) {
            val b = button(name, GREY) { setMode(key) }
            modeBtns[key] = b
            modes.addView(b, LinearLayout.LayoutParams(0, -2, 1f).also { it.rightMargin = dp(6) })
        }
        panel.addView(modes, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        startBtn = button("Démarrer", GREEN) { if (navigating) stopNavigation() else startNavigation() }
        actions.addView(startBtn, LinearLayout.LayoutParams(0, -2, 2f).also { it.rightMargin = dp(6) })
        actions.addView(button("Annuler", RED.replace("#", "#")) { clearRoute() }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })

        val pLp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        pLp.leftMargin = dp(100)
        pLp.rightMargin = dp(72)
        pLp.bottomMargin = dp(16)
        root.addView(panel, pLp)

        setContentView(root)
        updateModeButtons()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun updateModeButtons() {
        for ((k, b) in modeBtns) b.background = bg(if (k == mode) ORANGE else GREY)
        for ((k, b) in modeBtns) b.setTextColor(Color.parseColor(if (k == mode) "#0B1220" else "#FFFFFF"))
    }

    // ---------- Carte ----------

    private fun onStyleReady(m: MapLibreMap, st: Style) {
        style = st
        st.addSource(GeoJsonSource("radars", FeatureCollection.fromFeatures(emptyList<Feature>())))
        st.addSource(GeoJsonSource("reports", FeatureCollection.fromFeatures(emptyList<Feature>())))
        st.addSource(GeoJsonSource("route", FeatureCollection.fromFeatures(emptyList<Feature>())))
        st.addSource(GeoJsonSource("dest", FeatureCollection.fromFeatures(emptyList<Feature>())))
        st.addSource(GeoJsonSource("me", FeatureCollection.fromFeatures(emptyList<Feature>())))

        st.addLayer(
            LineLayer("route-layer", "route").withProperties(
                PropertyFactory.lineColor(ORANGE),
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        st.addLayer(
            CircleLayer("radar-layer", "radars").withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor(RED),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(1.5f)
            )
        )
        st.addLayer(
            CircleLayer("report-layer", "reports").withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor("#FFD600"),
                PropertyFactory.circleStrokeColor("#000000"),
                PropertyFactory.circleStrokeWidth(2f)
            )
        )
        st.addLayer(
            CircleLayer("dest-layer", "dest").withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor(GREEN),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f)
            )
        )
        st.addLayer(
            CircleLayer("me-layer", "me").withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor("#2196F3"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f)
            )
        )

        m.addOnMapLongClickListener { ll ->
            setDestination(Pt(ll.latitude, ll.longitude), "Point choisi sur la carte")
            true
        }
        m.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
        }
        refreshRadarLayer()
        refreshReportLayer()
        me?.let { updateMeLayer(it) }
        me?.let { moveCamera(it, true) }
    }

    private fun point(p: Pt) = Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat))

    private fun refreshRadarLayer() {
        val src = style?.getSourceAs<GeoJsonSource>("radars") ?: return
        src.setGeoJson(FeatureCollection.fromFeatures(fixedRadars.map { point(it.pt) }))
    }

    private fun allReports(): List<Report> {
        val now = System.currentTimeMillis()
        localReports.removeAll { it.second < now }
        return localReports.map { it.first } + remoteReports
    }

    private fun refreshReportLayer() {
        val src = style?.getSourceAs<GeoJsonSource>("reports") ?: return
        src.setGeoJson(FeatureCollection.fromFeatures(allReports().map { point(it.pt) }))
    }

    private fun updateMeLayer(loc: Location) {
        val src = style?.getSourceAs<GeoJsonSource>("me") ?: return
        src.setGeoJson(point(Pt(loc.latitude, loc.longitude)))
    }

    private fun moveCamera(loc: Location, first: Boolean = false) {
        val m = map ?: return
        val b = CameraPosition.Builder().target(LatLng(loc.latitude, loc.longitude))
        if (navigating) {
            b.zoom(17.0).tilt(45.0)
            if (loc.hasBearing() && loc.speed > 2f) b.bearing(loc.bearing.toDouble())
        } else {
            b.zoom(if (first) 14.0 else max(m.cameraPosition.zoom, 14.0)).tilt(0.0).bearing(0.0)
        }
        m.animateCamera(CameraUpdateFactory.newCameraPosition(b.build()), 700)
    }

    private fun recenter() {
        follow = true
        val l = me
        if (l == null) toast("Position GPS en attente...") else moveCamera(l)
    }

    // ---------- Position ----------

    private fun ensureLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startLocation()
    }

    private fun startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            locMgr.removeUpdates(locListener)
            if (locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER))
                locMgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
            if (locMgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                locMgr.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, locListener, Looper.getMainLooper())
            val last = locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locMgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null && me == null) onLocation(last)
        } catch (e: SecurityException) {
        }
    }

    private fun onLocation(loc: Location) {
        // On ignore une position réseau moins précise quand le GPS est actif.
        val prev = me
        if (prev != null && loc.provider == LocationManager.NETWORK_PROVIDER &&
            prev.provider == LocationManager.GPS_PROVIDER && loc.time - prev.time < 10000
        ) return
        me = loc
        updateMeLayer(loc)
        if (follow) moveCamera(loc)
        val kmh = if (loc.hasSpeed()) (loc.speed * 3.6f).roundToInt() else 0
        speedView.text = "$kmh\nkm/h"
        checkRadars(loc, kmh)
        if (navigating) navUpdate(loc)
    }

    // ---------- Recherche ----------

    private fun doSearch() {
        val q = searchBox.text.toString().trim()
        if (q.isEmpty()) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchBox.windowToken, 0)
        scope.launch {
            try {
                val res = Geocoder.search(q)
                resultsBox.removeAllViews()
                if (res.isEmpty()) {
                    val t = label("Aucun résultat", 15f)
                    t.setPadding(dp(14), dp(12), dp(14), dp(12))
                    resultsBox.addView(t)
                }
                for (p in res) {
                    val t = label(p.name, 15f)
                    t.setPadding(dp(14), dp(12), dp(14), dp(12))
                    t.maxLines = 2
                    t.setOnClickListener {
                        resultsBox.visibility = View.GONE
                        searchBox.setText(p.name.substringBefore(","))
                        setDestination(p.pt, p.name.substringBefore(","))
                    }
                    resultsBox.addView(t)
                }
                resultsBox.visibility = View.VISIBLE
            } catch (e: Exception) {
                toast("Recherche impossible : ${e.message}")
            }
        }
    }

    // ---------- Itinéraire ----------

    private fun setDestination(p: Pt, name: String) {
        dest = p
        destName = name
        style?.getSourceAs<GeoJsonSource>("dest")?.setGeoJson(point(p))
        computeRoute(false)
    }

    private fun setMode(m: String) {
        mode = m
        updateModeButtons()
        if (dest != null) computeRoute(navigating)
    }

    private fun computeRoute(keepNav: Boolean) {
        val d = dest ?: return
        val l = me
        if (l == null) {
            toast("Position GPS en attente...")
            return
        }
        scope.launch {
            try {
                val r = Router.route(mode, Pt(l.latitude, l.longitude), d)
                route = r
                stepIdx = 0
                farSaid = -1
                nearSaid = -1
                offCount = 0
                style?.getSourceAs<GeoJsonSource>("route")?.setGeoJson(
                    Feature.fromGeometry(LineString.fromLngLats(r.pts.map { Point.fromLngLat(it.lon, it.lat) }))
                )
                panel.visibility = View.VISIBLE
                panelInfo.text = summary(r.dur, r.dist) + (if (destName.isNotBlank()) "\n$destName" else "")
                if (!keepNav) {
                    follow = false
                    val b = LatLngBounds.Builder()
                    r.pts.forEach { b.include(LatLng(it.lat, it.lon)) }
                    map?.easeCamera(CameraUpdateFactory.newLatLngBounds(b.build(), dp(60), dp(140), dp(90), dp(240)), 900)
                }
                if (keepNav) {
                    navBanner.visibility = View.VISIBLE
                    navBanner.text = "Itinéraire recalculé"
                }
            } catch (e: Exception) {
                toast("Itinéraire impossible : ${e.message}")
            } finally {
                rerouting = false
            }
        }
    }

    private fun summary(sec: Double, meters: Double): String {
        val min = (sec / 60).roundToInt()
        val t = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
        val d = if (meters >= 1000) String.format(Locale.FRANCE, "%.1f km", meters / 1000) else "${meters.roundToInt()} m"
        return "$t · $d"
    }

    private fun clearRoute() {
        stopNavigation()
        route = null
        dest = null
        panel.visibility = View.GONE
        style?.getSourceAs<GeoJsonSource>("route")?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        style?.getSourceAs<GeoJsonSource>("dest")?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
    }

    // ---------- Guidage ----------

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    private fun startNavigation() {
        val r = route ?: return
        navigating = true
        follow = true
        stepIdx = 0
        farSaid = -1
        nearSaid = -1
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startBtn.text = "Arrêter"
        navBanner.visibility = View.VISIBLE
        navBanner.text = r.steps.firstOrNull()?.text ?: "Suivez l'itinéraire"
        speak(r.steps.firstOrNull()?.text ?: "C'est parti")
        me?.let { moveCamera(it) }
    }

    private fun stopNavigation() {
        if (!navigating) return
        navigating = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startBtn.text = "Démarrer"
        navBanner.visibility = View.GONE
        map?.let { m ->
            val cp = m.cameraPosition
            m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(cp).tilt(0.0).bearing(0.0).build()), 500)
        }
    }

    private fun distSay(m: Double): String {
        val r = (m / 10).roundToInt() * 10
        return if (r >= 1000) String.format(Locale.FRANCE, "%.1f kilomètre", r / 1000.0) else "$r mètres"
    }

    private fun navUpdate(loc: Location) {
        val r = route ?: return
        val p = Pt(loc.latitude, loc.longitude)

        // Sortie d'itinéraire : recalcul après 3 positions consécutives trop éloignées
        if (Geo.distToRoute(p, r.pts) > 80.0) {
            offCount++
            if (offCount >= 3 && !rerouting) {
                rerouting = true
                offCount = 0
                speak("Recalcul de l'itinéraire")
                computeRoute(true)
            }
            return
        } else {
            offCount = 0
        }

        // Avancement sur les manœuvres
        while (stepIdx + 1 < r.steps.size - 1 && Geo.dist(p, r.steps[stepIdx + 1].at) < 25.0) {
            stepIdx++
            farSaid = -1
            nearSaid = -1
        }
        val next = r.steps.getOrNull(stepIdx + 1) ?: return
        val dNext = Geo.dist(p, next.at)

        // Arrivée
        if (stepIdx + 1 >= r.steps.size - 1 && dNext < 30.0) {
            speak("Vous êtes arrivé à destination")
            toast("Arrivé à destination")
            clearRoute()
            return
        }

        var remain = dNext
        for (j in stepIdx + 1 until r.steps.size) remain += r.steps[j].dist
        val remTime = if (r.dist > 0) r.dur * remain / r.dist else 0.0
        navBanner.text = "${if (dNext >= 1000) String.format(Locale.FRANCE, "%.1f km", dNext / 1000) else "${dNext.roundToInt()} m"} · ${next.text}"
        panelInfo.text = summary(remTime, remain)

        val far = if (loc.speed > 16f) 600.0 else if (loc.speed > 8f) 350.0 else 150.0
        if (dNext < 60.0 && nearSaid != stepIdx) {
            nearSaid = stepIdx
            farSaid = stepIdx
            speak(next.text)
        } else if (dNext < far && farSaid != stepIdx) {
            farSaid = stepIdx
            speak("Dans ${distSay(dNext)}, ${next.text}")
        }
    }

    // ---------- Radars ----------

    private fun checkRadars(loc: Location, kmh: Int) {
        val p = Pt(loc.latitude, loc.longitude)
        val moving = loc.hasSpeed() && loc.speed > 3f && loc.hasBearing()
        var best: Radar? = null
        var bestD = Double.MAX_VALUE
        val candidates = ArrayList<Radar>(fixedRadars.size / 20 + 8)
        for (r in fixedRadars) {
            if (Math.abs(r.pt.lat - p.lat) < 0.012 && Math.abs(r.pt.lon - p.lon) < 0.02) candidates.add(r)
        }
        for (rep in allReports()) {
            if (Math.abs(rep.pt.lat - p.lat) < 0.012 && Math.abs(rep.pt.lon - p.lon) < 0.02)
                candidates.add(Radar("rep-${rep.id}", rep.pt, rep.type, null, true))
        }
        for (r in candidates) {
            val d = Geo.dist(p, r.pt)
            if (d > 800.0) {
                alerted.remove(r.id)
                continue
            }
            if (moving && Geo.angleDiff(loc.bearing.toDouble(), Geo.bearing(p, r.pt)) > 50.0) continue
            if (d < bestD) {
                bestD = d
                best = r
            }
        }
        val b = best
        if (b == null) {
            radarBanner.visibility = View.GONE
            return
        }
        val lim = if (b.limit != null) " · limité à ${b.limit}" else ""
        radarBanner.text = "${b.kind} dans ${bestD.roundToInt()} m$lim"
        radarBanner.visibility = View.VISIBLE
        if (b.id !in alerted) {
            alerted.add(b.id)
            val over = if (b.limit != null && kmh > b.limit + 2) ", vous dépassez la limite" else ""
            speak("${b.kind} dans ${distSay(bestD)}$over")
        }
    }

    // ---------- Signalements ----------

    private fun serverUrl(): String = prefs.getString("server", "") ?: ""

    private fun reportDialog() {
        val l = me
        if (l == null) {
            toast("Position GPS en attente...")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Signaler ici")
            .setItems(ReportClient.TYPES.toTypedArray()) { _, i ->
                val type = ReportClient.TYPES[i]
                val at = Pt(l.latitude, l.longitude)
                val id = "loc${System.currentTimeMillis()}"
                // Visible tout de suite sur la carte, expire au bout de 4 h
                localReports.add(Report(id, type, at, System.currentTimeMillis(), 1) to System.currentTimeMillis() + 4 * 3600 * 1000L)
                refreshReportLayer()
                val s = serverUrl()
                if (s.isBlank()) {
                    toast("Signalement enregistré sur ce téléphone (serveur non configuré)")
                } else {
                    scope.launch {
                        try {
                            ReportClient.send(s, type, at)
                            toast("Signalement envoyé, merci !")
                        } catch (e: Exception) {
                            toast("Envoi impossible : ${e.message}")
                        }
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun serverDialog() {
        val et = EditText(this)
        et.setText(serverUrl())
        et.hint = "https://mon-serveur.workers.dev"
        AlertDialog.Builder(this)
            .setTitle("Serveur de signalements")
            .setView(et)
            .setPositiveButton("Enregistrer") { _, _ ->
                prefs.edit().putString("server", et.text.toString().trim()).apply()
                startPolling()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                val s = serverUrl()
                val l = me
                if (s.isNotBlank() && l != null) {
                    try {
                        remoteReports = ReportClient.fetch(s, Pt(l.latitude, l.longitude))
                        refreshReportLayer()
                    } catch (e: Exception) {
                    }
                }
                delay(30000)
            }
        }
    }
}
