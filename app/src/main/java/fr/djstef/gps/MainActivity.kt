@file:Suppress("DEPRECATION")

package fr.djstef.gps

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        const val VERSION = "0.42"
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

        // Palette (même esprit que l'appli IPTV : fond bleu nuit, une couleur par section)
        const val BG = "#0B1220"
        const val BAR = "#101A30"
        const val CARD = "#16203A"
        const val NAVY = "#E6101828"
        const val ORANGE = "#F57C00"
        const val ORANGE_L = "#FF9F1C"
        const val RED = "#C62828"
        const val BLUE = "#1565C0"
        const val GREEN = "#2E7D32"
        const val SLATE = "#455A64"
        const val GREY = "#37474F"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: AppPrefs

    private lateinit var rootLayout: FrameLayout
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null

    private lateinit var topBox: LinearLayout
    private lateinit var bottomBox: LinearLayout
    private lateinit var searchRow: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var resultsBox: LinearLayout
    private var sideBox: View? = null
    private lateinit var sideMore: LinearLayout
    private lateinit var moreBtn: TextView

    /** Affiche/masque la liste de résultats et dégage le bas et le côté de l'écran pendant qu'elle est ouverte. */
    private fun setResults(show: Boolean) {
        resultsBox.visibility = if (show) View.VISIBLE else View.GONE
        val v = if (show) View.INVISIBLE else View.VISIBLE
        if (::bottomBox.isInitialized) bottomBox.visibility = v
        sideBox?.visibility = v
    }
    private lateinit var navBanner: LinearLayout
    private lateinit var navArrow: TextView
    private lateinit var navDist: TextView
    private lateinit var navStreet: TextView
    private lateinit var navRoad: TextView
    private lateinit var navToward: TextView
    private lateinit var roadView: TextView
    private lateinit var radarBox: LinearLayout
    private lateinit var radarIcon: ImageView
    private lateinit var radarText: TextView
    private lateinit var speedView: SpeedBubble
    private lateinit var limitView: SignView
    private lateinit var trafficBtn: TextView
    private lateinit var panel: LinearLayout
    private lateinit var etaView: TextView
    private lateinit var subView: TextView
    private lateinit var destView: TextView
    private lateinit var modeRow: LinearLayout
    private lateinit var startBtn: TextView
    private lateinit var cancelBtn: TextView
    private lateinit var muteBtn: TextView
    private lateinit var viewBtn: TextView
    private val modeBtns = HashMap<String, TextView>()

    private var topInset = 0
    private var bottomInset = 0
    private var bottomH = 0
    private var leftInset = 0
    private var rightInset = 0
    private var sideBtns: List<TextView> = emptyList()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var muted = false

    private var me: Location? = null
    private var lastBearing = 0f
    private var follow = true
    private var zoomBoost = 0.0
    private lateinit var carView: ImageView
    private var carTopBmp: android.graphics.Bitmap? = null
    private var carRearBmp: android.graphics.Bitmap? = null
    private lateinit var infoRow: LinearLayout
    private lateinit var navBand: LinearLayout
    private lateinit var navEta: TextView
    private lateinit var navSub: TextView
    private lateinit var navDestV: TextView
    private val vias = ArrayList<Place>()
    private var pendingVia = false
    private var alts: List<Route> = emptyList()
    private var altIdx = 0
    private lateinit var altRow: LinearLayout
    private lateinit var viaBtn: TextView
    private var lastBeep = 0L
    private val tone by lazy { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 90) }
    private var styleNight = false
    private var lastThemeCheck = 0L
    private var listenersAdded = false
    private var trafficEta: Pair<Double, Long>? = null
    private var trafficDelay = 0.0
    private var lastEtaFetch = 0L
    private var etaBusy = false
    private var zoomInBtn: View? = null
    private var zoomOutBtn: View? = null
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
    private var pendingFav: String? = null

    private var fixedRadars: List<Radar> = emptyList()
    private var baseRadars: List<Radar> = emptyList()
    private val lufopMap = HashMap<String, Radar>()
    private var lufopCenter: Pt? = null
    private var lufopBusy = false
    private var lufopLastError = ""
    private var lufopStats = ""
    private var lufopLastTry = 0L
    private lateinit var creditView: TextView
    private var localReports = ArrayList<Pair<Report, Long>>()
    private var remoteReports: List<Report> = emptyList()
    private val alerted = HashSet<String>()
    private var pollJob: Job? = null

    private var limitNow: Int? = null
    private var lastLimitPt: Pt? = null
    private var lastLimitTime = 0L
    private var limitBusy = false
    private var lastSpeedWarn = 0L

    private var menuView: View? = null
    private var autoStart = false
    private lateinit var trips: TripStore
    private var tripStart = 0L
    private var tripDist = 0.0
    private var tripMax = 0
    private var tripLast: Location? = null
    private val tripTrace = ArrayList<Pt>()
    private var menuPage: String? = null

    private val locMgr by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private val locListener = LocationListener { loc -> onLocation(loc) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    // ---------- Cycle de vie ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        trips = TripStore(this)
        CarSprites.assets = assets
        NavBridge.go = { pl ->
            runOnUiThread {
                autoStart = true
                goTo(pl)
            }
        }
        NavBridge.stop = { runOnUiThread { stopNavigation() } }
        MapLibre.getInstance(this)
        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRANCE
                ttsReady = true
            }
        }
        buildUi(savedInstanceState)
        goFullscreen()
        handleIncoming(intent)
        scope.launch {
            baseRadars = RadarRepository.load(this@MainActivity)
            fixedRadars = baseRadars + lufopMap.values
            refreshRadarLayer()
            if (baseRadars.isEmpty()) toast("Radars fixes indisponibles (pas de connexion ?)")
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
            menuView != null -> if (menuPage != null) showMenu(null) else closeMenu()
            resultsBox.visibility == View.VISIBLE -> setResults(false)
            navigating -> stopNavigation()
            route != null -> clearRoute()
            else -> super.onBackPressed()
        }
    }

    // ---------- Aides d'interface ----------

    private fun colorBg(color: String, radius: Int = 14): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(Color.parseColor(color))
        g.cornerRadius = dp(radius).toFloat()
        return g
    }

    private fun ovalBg(color: String): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(Color.parseColor(color))
        return g
    }

    private fun darker(color: Int, f: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = hsv[2] * f
        return Color.HSVToColor(hsv)
    }

    private fun gradBg(color: String, radius: Int): android.graphics.drawable.Drawable =
        Gloss.rect(Color.parseColor(color), dp(radius).toFloat(), dp(22))

    private fun glossBtn(color: String): android.graphics.drawable.Drawable =
        Gloss.rect(Color.parseColor(color), dp(14).toFloat(), dp(18))

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
        t.isSingleLine = true
        t.setPadding(dp(10), dp(13), dp(10), dp(13))
        t.background = glossBtn(color)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun circleBtn(text: String, size: Int, onClick: () -> Unit): TextView {
        val t = label(text, 20f, true)
        t.gravity = Gravity.CENTER
        t.background = Gloss.oval(Color.parseColor("#26324F"))
        t.elevation = dp(6).toFloat()
        t.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun startVoice() {
        try {
            val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Où allez-vous ?")
            startActivityForResult(i, 77)
        } catch (e: Exception) {
            toast("Reconnaissance vocale indisponible sur cet appareil")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK) {
            val t = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!t.isNullOrBlank()) {
                searchBox.setText(t)
                doSearch()
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchBox.windowToken, 0)
    }

    // ---------- Interface principale (portrait) ----------

    private fun buildUi(savedInstanceState: Bundle?) {
        val root = FrameLayout(this)
        rootLayout = root
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        // La voiture est une vue Android posée sur la carte : aucun clignotement lié aux mises à jour de la carte
        carView = ImageView(this)
        carView.scaleType = ImageView.ScaleType.FIT_XY
        carView.visibility = View.INVISIBLE
        root.addView(carView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        mapView.getMapAsync { m ->
            map = m
            m.cameraPosition = CameraPosition.Builder().target(LatLng(46.6, 2.5)).zoom(5.0).build()
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            styleNight = wantNight()
            m.setStyle(Style.Builder().fromUri(if (styleNight) STYLE_DARK else STYLE_URL)) { st -> onStyleReady(m, st) }
        }

        // --- Haut : recherche, résultats, consigne, alerte radar ---
        topBox = LinearLayout(this)
        topBox.orientation = LinearLayout.VERTICAL
        topBox.setPadding(dp(10), dp(10), dp(10), 0)

        searchRow = LinearLayout(this)
        searchRow.orientation = LinearLayout.HORIZONTAL
        searchRow.gravity = Gravity.CENTER_VERTICAL
        searchBox = EditText(this)
        searchBox.hint = "Où allez-vous ?"
        searchBox.setHintTextColor(Color.parseColor("#9AA3B5"))
        searchBox.setTextColor(Color.WHITE)
        searchBox.textSize = 16f
        searchBox.background = colorBg(NAVY, 26)
        searchBox.setPadding(dp(18), dp(12), dp(18), dp(12))
        searchBox.inputType = InputType.TYPE_CLASS_TEXT
        searchBox.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchBox.setSingleLine(true)
        searchBox.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        searchBox.setOnFocusChangeListener { _, has -> if (has && searchBox.text.isNullOrBlank()) fillSuggestions() }
        searchBox.setOnClickListener { if (searchBox.text.isNullOrBlank()) fillSuggestions() }
        searchRow.addView(searchBox, LinearLayout.LayoutParams(0, -2, 1f))
        val micBtn = circleBtn("🎤", 48) { startVoice() }
        (micBtn.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        searchRow.addView(micBtn)
        val menuBtn = circleBtn("☰", 48) { showMenu(null) }
        (menuBtn.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        searchRow.addView(menuBtn)
        topBox.addView(searchRow, LinearLayout.LayoutParams(-1, -2))

        val resScroll = object : ScrollView(this) {
            // La liste ne dépasse jamais 55 % de l'écran, pour rester lisible au-dessus de la carte
            override fun onMeasure(w: Int, h: Int) {
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels * 0.55).toInt(), View.MeasureSpec.AT_MOST))
            }
        }
        resultsBox = LinearLayout(this)
        resultsBox.orientation = LinearLayout.VERTICAL
        resultsBox.background = colorBg(NAVY, 16)
        resultsBox.visibility = View.GONE
        resScroll.addView(resultsBox)
        topBox.addView(resScroll, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(6) })

        // Consigne de guidage : flèche + distance + rue
        navBanner = LinearLayout(this)
        navBanner.orientation = LinearLayout.HORIZONTAL
        navBanner.gravity = Gravity.CENTER_VERTICAL
        navBanner.background = gradBg(GREEN, 18)
        navBanner.setPadding(dp(12), dp(8), dp(12), dp(8))
        navBanner.background.alpha = 222
        navBanner.visibility = View.GONE
        navArrow = label("↑", 36f, true)
        navArrow.gravity = Gravity.CENTER
        navBanner.addView(navArrow, LinearLayout.LayoutParams(dp(52), -2))
        val navCol = LinearLayout(this)
        navCol.orientation = LinearLayout.VERTICAL
        navDist = label("", 24f, true)
        navToward = label("", 18f, true)
        navToward.maxLines = 2
        navToward.ellipsize = android.text.TextUtils.TruncateAt.END
        navToward.visibility = View.GONE
        navStreet = label("", 14f, false, "#E8F5E9")
        navStreet.maxLines = 2
        navStreet.ellipsize = android.text.TextUtils.TruncateAt.END
        navRoad = TextView(this)
        navRoad.visibility = View.GONE
        val distRow = LinearLayout(this)
        distRow.orientation = LinearLayout.HORIZONTAL
        distRow.gravity = Gravity.CENTER_VERTICAL
        distRow.addView(navDist)
        distRow.addView(navRoad, LinearLayout.LayoutParams(-2, -2).also { it.leftMargin = dp(10) })
        navCol.addView(distRow)
        navCol.addView(navToward)
        navCol.addView(navStreet)
        navBanner.addView(navCol, LinearLayout.LayoutParams(0, -2, 1f).also { it.leftMargin = dp(8) })
        topBox.addView(navBanner, LinearLayout.LayoutParams(-1, -2))

        radarBox = LinearLayout(this)
        radarBox.orientation = LinearLayout.HORIZONTAL
        radarBox.gravity = Gravity.CENTER_VERTICAL
        radarBox.background = gradBg(RED, 18)
        radarBox.setPadding(dp(8), dp(5), dp(12), dp(5))
        radarBox.background.alpha = 222
        radarBox.visibility = View.GONE
        radarIcon = ImageView(this)
        radarBox.addView(radarIcon, LinearLayout.LayoutParams(dp(40), dp(40)))
        radarText = label("", 16f, true)
        radarBox.addView(radarText, LinearLayout.LayoutParams(0, -2, 1f).also { it.leftMargin = dp(10) })
        topBox.addView(radarBox, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(6) })

        root.addView(topBox, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // --- Droite : boutons ---
        val side = LinearLayout(this)
        side.orientation = LinearLayout.VERTICAL
        val recBtn = circleBtn("◎", 44) { recenter() }
        val repBtn = circleBtn("⚠", 44) { reportDialog() }
        muteBtn = circleBtn("🔊", 44) { toggleMute() }
        viewBtn = circleBtn("3D", 44) { toggleView() }
        trafficBtn = circleBtn("🚦", 44) { toggleTraffic() }
        val zin = circleBtn("＋", 44) { adjustZoom(0.5) }
        zin.setOnLongClickListener { prefs.setFloat("navZoom", 0f); toast("Zoom automatique selon la vitesse"); me?.let { moveCamera(it) }; true }
        val zout = circleBtn("－", 44) { adjustZoom(-0.5) }
        zin.visibility = View.GONE
        zout.visibility = View.GONE
        zoomInBtn = zin
        zoomOutBtn = zout
        // Un seul bouton visible (au-dessus du bandeau, à droite) ; les autres s'affichent quand on l'appuie
        sideMore = LinearLayout(this)
        sideMore.orientation = LinearLayout.VERTICAL
        sideMore.visibility = View.GONE
        moreBtn = circleBtn("☰", 44) {
            if (sideMore.visibility == View.VISIBLE) closeSide() else openSide()
        }
        val setBtn = circleBtn("⚙", 44) { closeSide(); showMenu(null) }
        sideBtns = listOf(recBtn, repBtn, trafficBtn, zin, zout, muteBtn, viewBtn, setBtn, moreBtn)
        for (b in sideBtns) {
            b.alpha = 0.82f
            (b.layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
            if (b !== moreBtn) {
                sideMore.addView(b)
                // chaque appui sur un bouton relance le décompte de 3 secondes
                b.setOnTouchListener { _, e -> if (e.action == android.view.MotionEvent.ACTION_DOWN) armSideClose(); false }
            }
        }
        side.addView(sideMore)
        side.addView(moreBtn)
        muteBtn.visibility = View.GONE
        viewBtn.visibility = View.GONE
        val sideLp = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM)
        sideLp.rightMargin = dp(10)
        sideLp.bottomMargin = dp(120)
        root.addView(side, sideLp)
        sideBox = side

        // --- Bas : vitesse, limite, panneau d'itinéraire ---
        bottomBox = LinearLayout(this)
        bottomBox.orientation = LinearLayout.VERTICAL

        infoRow = LinearLayout(this)
        infoRow.orientation = LinearLayout.VERTICAL
        infoRow.gravity = Gravity.BOTTOM or Gravity.START
        speedView = SpeedBubble(this)
        limitView = SignView(this)
        limitView.visibility = View.GONE
        limitView.alpha = 1f
        speedView.alpha = 1f
        roadView = TextView(this)
        roadView.visibility = View.GONE
        infoRow.addView(roadView, LinearLayout.LayoutParams(-2, -2).also { it.bottomMargin = dp(6); it.rightMargin = dp(8) })
        infoRow.addView(limitView, LinearLayout.LayoutParams(dp(84), dp(84)).also { it.bottomMargin = dp(6) })
        infoRow.addView(speedView, LinearLayout.LayoutParams(dp(84), dp(84)))
        bottomBox.addView(infoRow, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) })

        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = colorBg("#F2101828", 22)
        panel.setPadding(dp(16), dp(14), dp(16), dp(14))
        panel.visibility = View.GONE
        etaView = label("", 22f, true)
        subView = label("", 15f, false, "#C0C7D6")
        destView = label("", 14f, false, "#9AA3B5")
        destView.maxLines = 1
        panel.addView(etaView)
        panel.addView(subView)
        panel.addView(destView)
        altRow = LinearLayout(this)
        altRow.orientation = LinearLayout.HORIZONTAL
        altRow.visibility = View.GONE
        panel.addView(altRow, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })

        modeRow = LinearLayout(this)
        modeRow.orientation = LinearLayout.HORIZONTAL
        for ((key, name) in listOf("car" to "🚗 Voiture", "bike" to "🚲 Vélo", "foot" to "🚶 Marche")) {
            val b = button(name, GREY) { setMode(key) }
            b.textSize = 14f
            modeBtns[key] = b
            modeRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).also { it.rightMargin = dp(6) })
        }
        panel.addView(modeRow, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        startBtn = button("Démarrer", GREEN) { if (navigating) stopNavigation() else startNavigation() }
        cancelBtn = button("Annuler", RED) { clearRoute() }
        actions.addView(startBtn, LinearLayout.LayoutParams(0, -2, 2f).also { it.rightMargin = dp(6) })
        actions.addView(cancelBtn, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        val tools = LinearLayout(this)
        tools.orientation = LinearLayout.HORIZONTAL
        viaBtn = button("＋ Étape", GREY) { addViaStart() }
        viaBtn.textSize = 13f
        viaBtn.setOnLongClickListener { vias.clear(); toast("Étapes retirées"); computeRoute(navigating); true }
        val favBtn = button("⭐ Favori", GREY) { addFavoriteCurrent() }
        favBtn.textSize = 13f
        val shareBtn = button("↗ Partager", GREY) { shareTrip() }
        shareBtn.textSize = 13f
        tools.addView(viaBtn, LinearLayout.LayoutParams(0, -2, 1f).also { it.rightMargin = dp(6) })
        tools.addView(favBtn, LinearLayout.LayoutParams(0, -2, 1f).also { it.rightMargin = dp(6) })
        tools.addView(shareBtn, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(tools, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        bottomBox.addView(panel, LinearLayout.LayoutParams(-1, -2))
        creditView = label(Lufop.CREDIT + "\nhttps://lufop.net", 10f, false, "#C0C7D6")
        creditView.gravity = Gravity.END
        creditView.setShadowLayer(3f, 0f, 1f, Color.BLACK)
        android.text.util.Linkify.addLinks(creditView, android.text.util.Linkify.WEB_URLS)
        creditView.setLinkTextColor(Color.parseColor("#90CAF9"))
        creditView.visibility = View.GONE // crédits déplacés dans Réglages > À propos
        navBand = LinearLayout(this)
        navBand.orientation = LinearLayout.HORIZONTAL
        navBand.gravity = Gravity.CENTER_VERTICAL
        navBand.setBackgroundColor(Color.parseColor("#EE101828"))
        navBand.visibility = View.GONE
        navBand.setBackgroundColor(Color.TRANSPARENT)
        navBand.background = GradientDrawable().also {
            it.setColor(Color.parseColor("#EE101828"))
            it.cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
        }
        // Gauche : bouton ✕ pour arrêter ; centre : durée, distance · arrivée, destination
        val navStop = circleBtn("✕", 48) { stopNavigation() }
        navStop.background = GradientDrawable().also { it.shape = GradientDrawable.OVAL; it.setColor(Color.parseColor(RED)) }
        navStop.elevation = 0f
        navStop.alpha = 1f
        navStop.textSize = 22f
        navBand.addView(navStop)
        val navInfoCol = LinearLayout(this)
        navInfoCol.orientation = LinearLayout.VERTICAL
        navInfoCol.gravity = Gravity.CENTER_HORIZONTAL
        navEta = label("", 28f, true, "#81C784")
        navEta.gravity = Gravity.CENTER
        navSub = label("", 15f, false, "#E0E6F2")
        navSub.gravity = Gravity.CENTER
        navDestV = label("", 12f, false, "#9AA3B5")
        navDestV.gravity = Gravity.CENTER
        navDestV.maxLines = 1
        navDestV.ellipsize = android.text.TextUtils.TruncateAt.END
        navInfoCol.addView(navEta)
        navInfoCol.addView(navSub)
        navInfoCol.addView(navDestV)
        navBand.addView(navInfoCol, LinearLayout.LayoutParams(0, -2, 1f).also { it.leftMargin = dp(8); it.rightMargin = dp(8) })
        navBand.addView(View(this), LinearLayout.LayoutParams(dp(48), dp(48)))
        bottomBox.addView(navBand, LinearLayout.LayoutParams(-1, -2))
        bottomBox.addView(creditView, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(4) })

        val bLp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        bLp.leftMargin = dp(10)
        bLp.rightMargin = dp(10)
        bLp.bottomMargin = dp(10)
        root.addView(bottomBox, bLp)
        bottomBox.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            bottomH = bottom - top
            placeSide()
            applyMapMargins()
            applyNavPadding()
        }

        root.setOnApplyWindowInsetsListener { _, ins ->
            val dc = if (android.os.Build.VERSION.SDK_INT >= 28) ins.displayCutout else null
            topInset = maxOf(ins.systemWindowInsetTop, dc?.safeInsetTop ?: 0)
            bottomInset = maxOf(ins.systemWindowInsetBottom, dc?.safeInsetBottom ?: 0)
            leftInset = maxOf(ins.systemWindowInsetLeft, dc?.safeInsetLeft ?: 0)
            rightInset = maxOf(ins.systemWindowInsetRight, dc?.safeInsetRight ?: 0)
            applyLayout()
            ins
        }

        setContentView(root)
        updateModeButtons()
    }

    private val sideClose = Runnable { closeSide() }

    private fun armSideClose() {
        sideMore.removeCallbacks(sideClose)
        sideMore.postDelayed(sideClose, 3000)
    }

    private fun openSide() {
        sideMore.visibility = View.VISIBLE
        moreBtn.text = "✕"
        armSideClose()
    }

    private fun closeSide() {
        sideMore.removeCallbacks(sideClose)
        sideMore.visibility = View.GONE
        moreBtn.text = "☰"
    }

    /** Le bouton de menu se pose juste au-dessus du bandeau du bas, à droite. */
    private fun placeSide() {
        val sv = sideBox ?: return
        if (!::bottomBox.isInitialized || !::navBand.isInitialized) return
        val lp = sv.layoutParams as FrameLayout.LayoutParams
        val land = isLandscape()
        val base = when {
            navigating && !land -> navBand.height
            land -> 0
            else -> bottomBox.height
        }
        val want = base + bottomInset + dp(if (navigating && !land) 12 else 10)
        if (lp.bottomMargin != want || lp.rightMargin != dp(10) + rightInset) {
            lp.bottomMargin = want
            lp.rightMargin = dp(10) + rightInset
            sv.layoutParams = lp
        }
    }

    /** Pastille de numéro de route : A/E = autoroute (bleu), N = nationale (rouge), D = départementale (jaune). */
    private fun styleRoad(tv: TextView, refs: List<String>, sizeSp: Float) {
        val r = refs.firstOrNull()
        if (r == null) { tv.visibility = View.GONE; return }
        val (bg, fg) = when (r[0]) {
            'A', 'E' -> Pair("#1565C0", "#FFFFFF")
            'N' -> Pair("#C62828", "#FFFFFF")
            'D' -> Pair("#F9A825", "#1A1A1A")
            else -> Pair("#546E7A", "#FFFFFF")
        }
        tv.text = refs.take(2).joinToString(" · ")
        tv.textSize = sizeSp
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setTextColor(Color.parseColor(fg))
        tv.setPadding(dp(8), dp(2), dp(8), dp(2))
        tv.background = GradientDrawable().also {
            it.setColor(Color.parseColor(bg))
            it.cornerRadius = dp(6).toFloat()
            it.setStroke(dp(2), Color.WHITE)
        }
        tv.visibility = View.VISIBLE
    }

    private fun isLandscape() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /** Mise en page selon l'orientation : en paysage, le guidage reste dans une colonne à gauche. */
    private fun applyLayout() {
        if (!::topBox.isInitialized || !::bottomBox.isInitialized || !::navBand.isInitialized) return
        val land = isLandscape()
        val w = resources.displayMetrics.widthPixels
        val colW = if (land) (w * 0.46).toInt() else FrameLayout.LayoutParams.MATCH_PARENT
        (topBox.layoutParams as FrameLayout.LayoutParams).also { it.width = colW; topBox.layoutParams = it }
        topBox.setPadding(dp(10) + leftInset, topInset + dp(4), if (land) dp(10) else dp(10) + rightInset, 0)
        (bottomBox.layoutParams as FrameLayout.LayoutParams).also { it.width = colW; bottomBox.layoutParams = it }
        val sz = if (land) 70 else 84
        infoRow.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        (limitView.layoutParams as LinearLayout.LayoutParams).also {
            it.width = dp(sz); it.height = dp(sz)
            it.bottomMargin = if (land) 0 else dp(6)
            it.rightMargin = if (land) dp(8) else 0
        }
        (speedView.layoutParams as LinearLayout.LayoutParams).also { it.width = dp(sz); it.height = dp(sz) }
        limitView.requestLayout()
        speedView.requestLayout()
        navDestV.visibility = if (land) View.GONE else View.VISIBLE
        val bs = if (land) 40 else 44
        for (b in sideBtns) {
            (b.layoutParams as LinearLayout.LayoutParams).also { it.width = dp(bs); it.height = dp(bs); it.topMargin = dp(if (land) 5 else 8) }
            b.requestLayout()
        }
        trafficBtn.visibility = if (land && navigating) View.GONE else View.VISIBLE
        placeSide()
        applyBottomMargins()
    }

    /** En guidage, le bandeau d'infos occupe toute la largeur de la colonne en bas, fond quasi opaque. */
    private fun applyBottomMargins() {
        if (!::bottomBox.isInitialized || !::navBand.isInitialized) return
        val lp = bottomBox.layoutParams as FrameLayout.LayoutParams
        val land = isLandscape()
        infoRow.setPadding(if (navigating) leftInset + dp(10) else 0, 0, 0, 0)
        if (navigating) {
            lp.setMargins(0, 0, 0, 0)
            navBand.setPadding(dp(12) + leftInset, dp(8), dp(12), dp(6))
            creditView.setBackgroundColor(Color.parseColor("#EE101828"))
            creditView.setPadding(dp(12) + leftInset, 0, dp(12), bottomInset + dp(4))
            creditView.textSize = 9f
            (creditView.layoutParams as LinearLayout.LayoutParams).topMargin = 0
        } else {
            lp.setMargins(dp(10) + leftInset, 0, if (land) 0 else dp(10) + rightInset, bottomInset + dp(10))
            creditView.setBackgroundColor(Color.parseColor("#B0101828"))
            creditView.setPadding(dp(6), dp(2), dp(6), dp(2))
            creditView.textSize = 10f
            (creditView.layoutParams as LinearLayout.LayoutParams).topMargin = dp(4)
        }
        bottomBox.layoutParams = lp
        applyMapBottom()
    }

    private fun goFullscreen() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val a = window.attributes
                a.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = a
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        } catch (_: Exception) {
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        rootLayout.post {
            applyLayout()
            applyNavPadding()
            positionCar()
        }
    }

    /** Place la voiture vers le bas de la zone de carte visible. */
    private fun setMapPadding(m: MapLibreMap, l: Int, t: Int, r: Int, b: Int) {
        val cur = m.padding
        if (cur.size >= 4 && cur[0] == l && cur[1] == t && cur[2] == r && cur[3] == b) return
        m.setPadding(l, t, r, b)
    }

    private fun applyNavPadding() {
        val m = map ?: return
        if (!navigating || !::rootLayout.isInitialized) return
        val h = rootLayout.height
        val w = rootLayout.width
        if (h <= 0 || w <= 0) return
        if (isLandscape()) {
            val left = (w * 0.46).toInt()
            val top = (2 * 0.70 * h - h).toInt().coerceIn(0, (h * 0.8).toInt())
            setMapPadding(m, left, top, 0, bottomInset)
        } else {
            val bottom = bottomH + bottomInset
            val top = (2 * 0.74 * h - h + bottom).toInt().coerceIn(0, (h * 0.8).toInt())
            setMapPadding(m, 0, top, 0, bottom)
        }
        positionCar()
    }

    private fun applyMapBottom() {
        val lp = mapView.layoutParams as FrameLayout.LayoutParams
        val want = 0 // la carte passe sous le bandeau semi-transparent ; la voiture est remontée par le padding de la caméra
        if (lp.bottomMargin != want) {
            lp.bottomMargin = want
            mapView.layoutParams = lp
        }
    }

    private fun applyMapMargins() {
        val ui = map?.uiSettings ?: return
        applyMapBottom()
        val b = bottomH + bottomInset + dp(16)
        ui.logoGravity = Gravity.BOTTOM or Gravity.END
        ui.attributionGravity = Gravity.BOTTOM or Gravity.END
        ui.setLogoMargins(0, 0, dp(10), b)
        ui.setAttributionMargins(0, 0, dp(10), if (isLandscape()) bottomInset + dp(6) else b)
    }

    private fun updateModeButtons() {
        for ((k, b) in modeBtns) {
            b.background = glossBtn(if (k == mode) ORANGE_L else GREY)
            b.setTextColor(Color.parseColor(if (k == mode) "#0B1220" else "#FFFFFF"))
        }
    }

    /** Affiche ou masque les éléments selon l'état : sans route, aperçu, guidage. */
    private fun refreshPanel() {
        panel.visibility = if (route != null && !navigating) View.VISIBLE else View.GONE
        navBand.visibility = if (navigating) View.VISIBLE else View.GONE
        infoRow.visibility = View.VISIBLE
        navDestV.text = destName.substringBefore(",")
        applyBottomMargins()
        modeRow.visibility = if (navigating) View.GONE else View.VISIBLE
        cancelBtn.visibility = if (navigating) View.GONE else View.VISIBLE
        viaBtn.visibility = if (navigating) View.GONE else View.VISIBLE
        startBtn.text = if (navigating) "Arrêter" else "Démarrer"
        startBtn.background = glossBtn(if (navigating) RED else GREEN)
        trafficBtn.alpha = if (prefs.bool("traffic", false)) 1f else 0.55f
        searchRow.visibility = if (navigating) View.GONE else View.VISIBLE
        navBanner.visibility = if (navigating) View.VISIBLE else View.GONE
        muteBtn.visibility = if (navigating) View.VISIBLE else View.GONE
        zoomInBtn?.visibility = if (navigating) View.VISIBLE else View.GONE
        zoomOutBtn?.visibility = if (navigating) View.VISIBLE else View.GONE
        viewBtn.visibility = if (navigating) View.VISIBLE else View.GONE
        viewBtn.text = if (prefs.bool("view3d", true)) "3D" else "2D"
        muteBtn.text = if (muted) "🔇" else "🔊"
    }

    private fun toggleMute() {
        muted = !muted
        if (muted) tts?.stop()
        refreshPanel()
    }

    private fun toggleView() {
        prefs.setBool("view3d", !prefs.bool("view3d", true))
        refreshPanel()
        applyCarMode()
        me?.let { moveCamera(it) }
    }

    // ---------- Carte ----------

    /** Élargit les routes du fond de carte (surtout aux zooms de guidage) pour bien voir où on va. */
    private fun widenRoads(st: Style) {
        val re = Regex("^(road|tunnel|bridge)_(motorway_link|motorway|trunk_primary|secondary_tertiary|minor|street|link|service_track)(_casing)?$")
        for (l in st.layers) {
            if (l !is LineLayer) continue
            val m = re.find(l.id) ?: continue
            val cls = m.groupValues[2]
            val base = when (cls) {
                "motorway", "trunk_primary" -> floatArrayOf(2f, 5f, 12f, 26f, 48f)
                "secondary_tertiary" -> floatArrayOf(1.5f, 4f, 10f, 22f, 42f)
                "service_track" -> floatArrayOf(0.5f, 2f, 5f, 12f, 24f)
                else -> floatArrayOf(1f, 3f, 8f, 18f, 36f)
            }
            val add = if (m.groupValues[3].isNotEmpty()) floatArrayOf(1f, 1.5f, 2.5f, 4f, 6f) else floatArrayOf(0f, 0f, 0f, 0f, 0f)
            val z = floatArrayOf(10f, 13f, 15f, 17f, 19f)
            val stops = Array(5) { Expression.stop(z[it], base[it] + add[it]) }
            try {
                l.setProperties(PropertyFactory.lineWidth(Expression.interpolate(Expression.exponential(1.4f), Expression.zoom(), *stops)))
            } catch (_: Exception) {
            }
        }
    }

    private fun onStyleReady(m: MapLibreMap, st: Style) {
        style = st
        // Pas de fondu des symboles (la voiture clignotait à chaque mise à jour de position)
        st.transition = org.maplibre.android.style.layers.TransitionOptions(0, 0, false)
        widenRoads(st)
        for (id in listOf("radars", "reports", "route", "dest", "me")) {
            st.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList<Feature>())))
        }
        st.addLayer(
            LineLayer("route-casing", "route").withProperties(
                PropertyFactory.lineColor("#FFFFFF"),
                PropertyFactory.lineWidth(Expression.interpolate(Expression.exponential(1.6f), Expression.zoom(), Expression.stop(8, 6f), Expression.stop(12, 8f), Expression.stop(15, 12f), Expression.stop(17, 20f), Expression.stop(19, 34f))),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        st.addLayer(
            LineLayer("route-layer", "route").withProperties(
                PropertyFactory.lineColor("#1A4FB5"),
                PropertyFactory.lineWidth(Expression.interpolate(Expression.exponential(1.6f), Expression.zoom(), Expression.stop(8, 3f), Expression.stop(12, 5f), Expression.stop(15, 8f), Expression.stop(17, 13f), Expression.stop(19, 22f))),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        for (t in RadarArt.TYPES) st.addImage("ic-$t", RadarArt.bitmap(t, 128))
        st.addLayer(
            CircleLayer("radar-layer", "radars").withProperties(
                PropertyFactory.circleRadius(5f),
                PropertyFactory.circleColor("#E53935"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(1.5f)
            ).also { it.setMaxZoom(11f) }
        )
        st.addLayer(
            SymbolLayer("radar-sym", "radars").withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconSize(0.8f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            ).also { it.setMinZoom(11f) }
        )
        st.addLayer(
            SymbolLayer("report-layer", "reports").withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconSize(1.1f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
        st.addLayer(
            CircleLayer("dest-layer", "dest").withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor("#43A047"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f)
            )
        )
        st.addLayer(
            SymbolLayer("me-layer", "me").withProperties(
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
        applyCar()
        applyRadarVisibility()
        setupSatellite()
        setupTraffic()
        setupPoiLayers()

        if (!listenersAdded) {
            listenersAdded = true
            m.addOnMapLongClickListener { ll ->
                vias.clear()
                setDestination(Pt(ll.latitude, ll.longitude), "Point choisi sur la carte")
                true
            }
            m.addOnCameraMoveListener { positionCar() }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
            }
        }
        applyMapMargins()
        refreshRadarLayer()
        refreshReportLayer()
        redrawRoute()
        me?.let { updateMeLayer(it) }
        me?.let { moveCamera(it, true) }
    }

    private fun redrawRoute() {
        val st = style ?: return
        route?.let { r ->
            st.getSourceAs<GeoJsonSource>("route")?.setGeoJson(
                Feature.fromGeometry(LineString.fromLngLats(r.pts.map { Point.fromLngLat(it.lon, it.lat) }))
            )
        }
        dest?.let { st.getSourceAs<GeoJsonSource>("dest")?.setGeoJson(point(it)) }
    }

    // --- Fond satellite et thème jour/nuit ---

    private fun setupSatellite() {
        val st = style ?: return
        try {
            if (st.getSource("sat") == null) {
                val ts = TileSet("2.2.0", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}")
                ts.setMaxZoom(19f)
                st.addSource(RasterSource("sat", ts, 256))
                st.addLayerBelow(RasterLayer("sat-layer", "sat"), "route-casing")
            }
            applySatellite()
        } catch (e: Exception) {
        }
    }

    private fun applySatellite() {
        val vis = if (prefs.bool("satellite", false)) Property.VISIBLE else Property.NONE
        style?.getLayerAs<RasterLayer>("sat-layer")?.setProperties(PropertyFactory.visibility(vis))
    }

    private fun wantNight(): Boolean = when (prefs.str("theme", "auto")) {
        "night" -> true
        "day" -> false
        else -> Sun.isNight(me?.latitude ?: 46.6, me?.longitude ?: 2.5)
    }

    private fun checkTheme() {
        val m = map ?: return
        if (style == null) return
        val n = wantNight()
        if (n == styleNight) return
        styleNight = n
        m.setStyle(Style.Builder().fromUri(if (n) STYLE_DARK else STYLE_URL)) { st -> onStyleReady(m, st) }
    }

    // --- Voiture sur la carte ---

    private fun carMode3d() = navigating && prefs.bool("view3d", true) && !prefs.bool("northUp", false)

    private fun applyCar() {
        val st = style ?: return
        val model = prefs.int("car", 0)
        val col = Color.parseColor(prefs.str("carColor", CarSprites.COLORS[0]))
        val top = CarSprites.draw(model, col)
        val rear = CarSprites.drawRear(model, col)
        carTopBmp = top
        carRearBmp = rear
        st.removeImage("car-top")
        st.addImage("car-top", top)
        st.removeImage("car-rear")
        st.addImage("car-rear", rear)
        applyCarMode()
    }

    /** Taille d'icône qui grandit avec le zoom de la carte (référence : zoom 17,3). */
    private fun zoomScale(base: Float): Expression = Expression.interpolate(
        Expression.linear(), Expression.zoom(),
        Expression.stop(14, base * 0.35f), Expression.stop(16, base * 0.65f), Expression.stop(17.3, base),
        Expression.stop(18, base * 1.25f), Expression.stop(19, base * 1.7f), Expression.stop(20, base * 2.2f)
    )

    private fun adjustZoom(delta: Double) {
        val m = map ?: return
        val cur = prefs.float("navZoom", 16.5f).toDouble().takeIf { it > 0 } ?: (m.cameraPosition.zoom - zoomBoost)
        val z = ((Math.round((cur + delta) * 2) / 2.0)).coerceIn(14.0, 19.5)
        prefs.setFloat("navZoom", z.toFloat())
        toast(String.format(Locale.FRANCE, "Zoom %.1f", z))
        follow = true
        me?.let { moveCamera(it) }
    }

    /** Facteur de taille selon le zoom (mêmes paliers que l'ancienne icône : 1,0 à zoom 17,3). */
    private fun zoomFactor(z: Double): Double {
        val st = listOf(14.0 to 0.35, 16.0 to 0.65, 17.3 to 1.0, 18.0 to 1.25, 19.0 to 1.7, 20.0 to 2.2)
        if (z <= st.first().first) return st.first().second
        if (z >= st.last().first) return st.last().second
        for (i in 0 until st.size - 1) {
            val (z0, v0) = st[i]
            val (z1, v1) = st[i + 1]
            if (z in z0..z1) return v0 + (v1 - v0) * (z - z0) / (z1 - z0)
        }
        return 1.0
    }

    /** Place et oriente la voiture : au centre de l'écran en suivi, sinon à sa position projetée. */
    private fun positionCar() {
        if (!::carView.isInitialized) return
        val m = map ?: return
        val l = me
        val bmp = (if (carMode3d()) carRearBmp else carTopBmp)
        if (l == null || bmp == null || mapView.width == 0) {
            carView.visibility = View.INVISIBLE
            return
        }
        val cp = m.cameraPosition
        val size = prefs.float("carSize", 1f)
        val is3d = carMode3d()
        val scale = ((if (is3d) 0.5 else 0.4) * size * zoomFactor(cp.zoom)).toFloat()
        val w = bmp.width
        val h = bmp.height
        if (carView.layoutParams.width != w || carView.layoutParams.height != h) {
            val lp = carView.layoutParams
            lp.width = w
            lp.height = h
            carView.layoutParams = lp
        }
        val x: Float
        val y: Float
        if (follow) {
            val pad = m.padding
            x = mapView.width / 2f + (pad[0] - pad[2]) / 2f
            y = pad[1] + (mapView.height - pad[1] - pad[3]) / 2f
        } else {
            val pt = m.projection.toScreenLocation(LatLng(l.latitude, l.longitude))
            x = pt.x
            y = pt.y
        }
        val px = w / 2f
        val py = if (is3d) h.toFloat() else h / 2f
        carView.pivotX = px
        carView.pivotY = py
        carView.scaleX = scale
        carView.scaleY = scale
        carView.rotation = if (is3d) 0f else ((lastBearing - cp.bearing).toFloat())
        carView.translationX = x - px
        carView.translationY = y - py
        carView.visibility = View.VISIBLE
    }

    private fun applyCarMode() {
        val layer = style?.getLayerAs<SymbolLayer>("me-layer") ?: return
        val size = prefs.float("carSize", 1f)
        layer.setProperties(PropertyFactory.visibility(Property.NONE))
        carView.setImageBitmap(if (carMode3d()) carRearBmp else carTopBmp)
        positionCar()
        if (carMode3d()) {
            // Vue de derrière, « debout » face à la caméra inclinée
            layer.setProperties(
                PropertyFactory.iconImage("car-rear"),
                PropertyFactory.iconSize(zoomScale(0.5f * size)),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconRotate(0f)
            )
        } else {
            // Vue du dessus, posée sur la carte et orientée selon le cap
            layer.setProperties(
                PropertyFactory.iconImage("car-top"),
                PropertyFactory.iconSize(zoomScale(0.4f * size)),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconRotate(Expression.get("bearing"))
            )
        }
    }

    private fun applyRadarVisibility() {
        val vis = if (prefs.bool("showFixed", true)) Property.VISIBLE else Property.NONE
        style?.getLayerAs<CircleLayer>("radar-layer")?.setProperties(PropertyFactory.visibility(vis))
        style?.getLayerAs<SymbolLayer>("radar-sym")?.setProperties(PropertyFactory.visibility(vis))
    }

    private fun point(p: Pt) = Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat))

    private fun refreshRadarLayer() {
        val src = style?.getSourceAs<GeoJsonSource>("radars") ?: return
        src.setGeoJson(FeatureCollection.fromFeatures(fixedRadars.map {
            val f = point(it.pt)
            f.addStringProperty("icon", "ic-" + RadarArt.key(it.kind))
            f
        }))
    }

    private fun allReports(): List<Report> {
        val now = System.currentTimeMillis()
        localReports.removeAll { it.second < now }
        return localReports.map { it.first } + remoteReports
    }

    private fun refreshReportLayer() {
        val src = style?.getSourceAs<GeoJsonSource>("reports") ?: return
        src.setGeoJson(FeatureCollection.fromFeatures(allReports().map {
            val f = point(it.pt)
            f.addStringProperty("icon", "ic-" + RadarArt.key(it.type))
            f
        }))
    }

    private fun updateMeLayer(loc: Location) {
        positionCar()
        val src = style?.getSourceAs<GeoJsonSource>("me") ?: return
        val f = point(Pt(loc.latitude, loc.longitude))
        f.addNumberProperty("bearing", lastBearing.toDouble())
        src.setGeoJson(f)
    }

    // --- Suivi fluide : la caméra est déplacée à chaque image (≈60 fois/s) vers la position estimée ---
    private var smRunning = false
    private var snapIdx = 0
    private var smInit = false
    private var fixLat = 0.0
    private var fixLon = 0.0
    private var fixSpeed = 0.0
    private var fixBear = 0f
    private var fixAt = 0L
    private var dLat = 0.0
    private var dLon = 0.0
    private var dBear = 0.0
    private var dZoom = 17.0
    private var dTilt = 0.0
    private var tZoom = 17.0
    private var tTilt = 0.0
    private var tBear = 0.0
    private var lastFrameNs = 0L
    private val frameCb = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!smRunning) return
            smStep(frameTimeNanos)
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun smStart() {
        if (smRunning) return
        smRunning = true
        lastFrameNs = 0L
        android.view.Choreographer.getInstance().postFrameCallback(frameCb)
    }

    private fun smStop() {
        smRunning = false
        smInit = false
    }

    private fun smStep(ns: Long) {
        val m = map
        if (m == null || !navigating || !follow) { smStop(); return }
        val dt = if (lastFrameNs == 0L) 0.016 else ((ns - lastFrameNs) / 1e9).coerceIn(0.001, 0.1)
        lastFrameNs = ns
        // Position estimée : dernier point GPS + vitesse × temps écoulé, dans la direction du déplacement
        val age = ((android.os.SystemClock.elapsedRealtime() - fixAt) / 1000.0).coerceIn(0.0, 2.0)
        val d = fixSpeed * age
        val br = Math.toRadians(fixBear.toDouble())
        val tLat = fixLat + d * Math.cos(br) / 111320.0
        val tLon = fixLon + d * Math.sin(br) / (111320.0 * Math.cos(Math.toRadians(fixLat)).coerceAtLeast(0.2))
        val a = 1 - Math.exp(-dt / 0.2)
        dLat += (tLat - dLat) * a
        dLon += (tLon - dLon) * a
        var diff = ((tBear - dBear + 540.0) % 360.0) - 180.0
        dBear = (dBear + diff * (1 - Math.exp(-dt / 0.45)) + 360.0) % 360.0
        val a2 = 1 - Math.exp(-dt / 0.7)
        dZoom += (tZoom - dZoom) * a2
        dTilt += (tTilt - dTilt) * a2
        m.moveCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(LatLng(dLat, dLon)).zoom(dZoom).tilt(dTilt).bearing(dBear).build()
        ))
        positionCar()
    }

    private fun moveCamera(loc: Location, first: Boolean = false) {
        val m = map ?: return
        if (navigating && follow) {
            val kmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
            val manual = prefs.float("navZoom", 16.5f).toDouble()
            val auto = if (kmh < 30) 18.0 else if (kmh < 70) 17.3 else if (kmh < 100) 16.6 else 16.0
            tZoom = ((if (manual > 0) manual else auto) + zoomBoost).coerceIn(13.0, 20.5)
            tTilt = if (prefs.bool("view3d", true)) 60.0 else 0.0
            tBear = if (prefs.bool("northUp", false)) 0.0 else lastBearing.toDouble()
            fixLat = loc.latitude
            fixLon = loc.longitude
            fixSpeed = if (loc.hasSpeed() && loc.speed > 1.0f) loc.speed.toDouble() else 0.0
            fixBear = if (loc.hasBearing() && loc.speed > 1.5f) loc.bearing else lastBearing
            // Accrochage à l'itinéraire : la voiture et la caméra restent centrées sur le trait bleu
            route?.let { r ->
                val gp = Pt(loc.latitude, loc.longitude)
                var sn = Geo.snap(gp, r.pts, snapIdx - 30, snapIdx + 400)
                if (sn == null || sn.third > 40.0) sn = Geo.snap(gp, r.pts, 0, r.pts.size - 1)
                if (sn != null && sn.third <= 40.0) {
                    fixLat = sn.first.lat
                    fixLon = sn.first.lon
                    fixBear = sn.second.toFloat()
                    if (!prefs.bool("northUp", false)) tBear = sn.second
                    snapIdx = Geo.lastSnapIdx
                }
            }
            fixAt = android.os.SystemClock.elapsedRealtime()
            val cp = m.cameraPosition
            val far = smInit && Geo.dist(Pt(dLat, dLon), Pt(loc.latitude, loc.longitude)) > 250.0
            if (!smInit || first || far) {
                val c = cp.target
                dLat = c?.latitude ?: loc.latitude
                dLon = c?.longitude ?: loc.longitude
                dBear = cp.bearing
                dZoom = cp.zoom
                dTilt = cp.tilt
                smInit = true
            }
            smStart()
            return
        }
        smStop()
        val b = CameraPosition.Builder().target(LatLng(loc.latitude, loc.longitude))
        if (navigating) {
            val kmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
            val manual = prefs.float("navZoom", 16.5f).toDouble()
            val auto = if (kmh < 30) 18.0 else if (kmh < 70) 17.3 else if (kmh < 100) 16.6 else 16.0
            b.zoom(((if (manual > 0) manual else auto) + zoomBoost).coerceIn(13.0, 20.5))
            b.tilt(if (prefs.bool("view3d", true)) 60.0 else 0.0)
            b.bearing(if (prefs.bool("northUp", false)) 0.0 else lastBearing.toDouble())
        } else {
            b.zoom(if (first) 14.0 else max(m.cameraPosition.zoom, 14.0)).tilt(0.0).bearing(0.0)
        }
        m.animateCamera(CameraUpdateFactory.newCameraPosition(b.build()), 700)
        positionCar()
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
        val prev = me
        if (prev != null && loc.provider == LocationManager.NETWORK_PROVIDER &&
            prev.provider == LocationManager.GPS_PROVIDER && loc.time - prev.time < 10000
        ) return
        me = loc
        if (loc.hasBearing() && loc.speed > 1.5f) lastBearing = loc.bearing
        updateMeLayer(loc)
        if (follow) moveCamera(loc)
        val kmh = if (loc.hasSpeed()) (loc.speed * 3.6f).roundToInt() else 0
        updateLimit(loc)
        val lim = limitNow
        val over = lim != null && kmh > lim + 3
        speedView.set(kmh, over)
        NavBridge.me = loc
        NavBridge.speed = kmh
        NavBridge.limit = if (prefs.bool("showLimit", true)) lim else null
        NavBridge.over = over
        val now = System.currentTimeMillis()
        if (over && lim != null && prefs.bool("speedAlert", true) && now - lastSpeedWarn > 25000) {
            lastSpeedWarn = now
            speak("Attention, vitesse limitée à $lim")
        }
        if (over && prefs.bool("speedBeep", true) && !muted && now - lastBeep > 3000) {
            lastBeep = now
            try { tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 250) } catch (_: Exception) {}
        }
        if (now - lastThemeCheck > 60000) {
            lastThemeCheck = now
            checkTheme()
        }
        checkRadars(loc, kmh)
        maybeFetchLufop(loc)
        if (navigating) {
            recordTrip(loc, kmh)
            navUpdate(loc)
        }
        NavBridge.changed()
    }

    // ---------- Limitations de vitesse ----------

    private fun updateLimit(loc: Location) {
        if (!prefs.bool("showLimit", true) || limitBusy) return
        val now = System.currentTimeMillis()
        val p = Pt(loc.latitude, loc.longitude)
        val lp = lastLimitPt
        if (lp != null) {
            if (now - lastLimitTime < 4000) return
            if (loc.speed < 1f) return
            if (Geo.dist(lp, p) < 30.0 && now - lastLimitTime < 20000) return
        }
        limitBusy = true
        lastLimitPt = p
        lastLimitTime = now
        val heading = if (loc.hasBearing() && loc.speed > 1.5f) loc.bearing.toDouble() else null
        scope.launch {
            try {
                limitNow = SpeedLimits.at(p, heading)
                refreshLimitView()
            } catch (e: Exception) {
            } finally {
                limitBusy = false
            }
        }
    }

    private fun refreshLimitView() {
        val l = limitNow
        if (l != null && prefs.bool("showLimit", true)) {
            limitView.value = l
            limitView.visibility = View.VISIBLE
        } else {
            limitView.visibility = View.GONE
        }
    }

    // ---------- Recherche, favoris, récents ----------

    private fun addRow(text: String, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
        val t = label(text, 15f)
        t.setPadding(dp(14), dp(12), dp(14), dp(12))
        t.maxLines = 2
        t.setOnClickListener { onClick() }
        if (onLong != null) t.setOnLongClickListener { onLong(); true }
        resultsBox.addView(t)
    }

    private fun favRow(key: String, icon: String, name: String) {
        val f = prefs.fav(key)
        if (f != null) {
            addRow("$icon $name · ${f.name}", { goTo(f) }, { pendingFav = key; toast("Cherche la nouvelle adresse de $name") })
        } else {
            addRow("$icon $name · à définir", { pendingFav = key; toast("Tape l'adresse de $name puis choisis-la") })
        }
    }

    private fun fillSuggestions() {
        resultsBox.removeAllViews()
        favRow("home", "🏠", "Domicile")
        favRow("work", "💼", "Travail")
        for (f in prefs.favorites()) addRow("⭐ ${f.name}", { goTo(f) }, { prefs.removeFavorite(f); toast("Favori retiré"); fillSuggestions() })
        for (r in prefs.recents()) addRow("🕘 ${r.name}", { goTo(r) })
        for ((ico, ttl, phrase) in listOf(
            Triple("🍴", "Restaurants", "restaurant"), Triple("💊", "Pharmacies", "pharmacy"), Triple("🏨", "Hôtels", "hotel"),
            Triple("🏕", "Campings", "camp site"), Triple("⚡", "Bornes de recharge", "charging station"),
            Triple("🛒", "Supermarchés", "supermarket"), Triple("🥖", "Boulangeries", "bakery")
        )) addRow("$ico $ttl autour de moi", { nearby(ttl, phrase) })
        setResults(true)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** Lieu partagé depuis Google Maps (ou lien geo:) : on lit les coordonnées et on lance l'itinéraire. */
    private fun handleIncoming(i: android.content.Intent?) {
        i ?: return
        var text: String? = null
        if (i.action == android.content.Intent.ACTION_SEND) text = i.getStringExtra(android.content.Intent.EXTRA_TEXT)
        else if (i.action == android.content.Intent.ACTION_VIEW) text = i.dataString
        if (text.isNullOrBlank()) return
        i.action = null
        val t = text
        scope.launch {
            toast("Lieu reçu…")
            val pl = withContext(Dispatchers.IO) { placeFromShared(t) }
            if (pl == null) toast("Impossible de lire la position de ce lieu") else goTo(pl)
        }
    }

    private fun placeFromShared(t: String): Place? {
        fun coords(u: String): Pt? {
            val pats = listOf(
                Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)"),
                Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)"),
                Regex("[?&](?:q|ll|query|destination)=(-?\\d+\\.\\d+)(?:,|%2C)(-?\\d+\\.\\d+)"),
                Regex("^geo:(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
            )
            for (r in pats) r.find(u)?.let { return Pt(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
            return null
        }
        val url = Regex("https?://\\S+").find(t)?.value
        val firstLine = t.lines().firstOrNull { it.isNotBlank() && !it.startsWith("http") && !it.startsWith("geo:") }?.trim().orEmpty()
        val name0 = if (firstLine.isNotBlank()) firstLine else (Regex("[?&]q=([^&]+)").find(t)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "Lieu partagé")
        coords(url ?: t)?.let { return Place(name0, it) }
        if (url == null) return null
        // Lien court (maps.app.goo.gl…) : on suit la redirection pour retrouver les coordonnées
        return try {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            c.responseCode
            val fin = c.url.toString()
            val body = try { c.inputStream.bufferedReader().use { it.readText().take(400000) } } catch (_: Exception) { "" }
            c.disconnect()
            (coords(fin) ?: coords(body))?.let { Place(name0, it) }
        } catch (_: Exception) { null }
    }

    private fun goTo(p: Place) {
        if (pendingVia) {
            pendingVia = false
            searchBox.hint = "Où allez-vous ?"
            searchBox.setText(destName)
            setResults(false)
            hideKeyboard()
            vias.add(p)
            toast("Étape ajoutée : ${p.name.substringBefore(",")}")
            computeRoute(navigating)
            return
        }
        vias.clear()
        setResults(false)
        hideKeyboard()
        searchBox.setText(p.name)
        setDestination(p.pt, p.name)
    }

    private fun addViaStart() {
        if (dest == null) return
        pendingVia = true
        searchBox.hint = "Étape : où t'arrêter ?"
        searchBox.setText("")
        searchBox.requestFocus()
        fillSuggestions()
    }

    private fun addFavoriteCurrent() {
        val d = dest ?: return
        prefs.addFavorite(Place(destName.substringBefore(","), d))
        toast("Ajouté aux favoris")
    }

    private fun shareTrip() {
        val d = dest ?: return
        val sec = if (navigating) NavBridge.remainSec else (route?.dur ?: 0.0)
        val msg = buildString {
            append("Je vais à ${destName.substringBefore(",")}")
            if (sec > 0) append(", arrivée vers ${eta(sec)}")
            append(" — https://www.openstreetmap.org/?mlat=${d.lat}&mlon=${d.lon}#map=17/${d.lat}/${d.lon}")
        }
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, msg)
        startActivity(android.content.Intent.createChooser(i, "Partager mon trajet"))
    }

    private fun nearby(title: String, phrase: String) {
        val l = me
        if (l == null) {
            toast("Position GPS en attente...")
            return
        }
        toast("Recherche : $title…")
        scope.launch {
            try {
                val res = Nearby.category(Pt(l.latitude, l.longitude), phrase)
                resultsBox.removeAllViews()
                if (res.isEmpty()) addRow("Aucun résultat à proximité", {})
                for (pl in res) {
                    val d = Geo.dist(Pt(l.latitude, l.longitude), pl.pt)
                    val dTxt = if (d >= 1000) String.format(Locale.FRANCE, "%.1f km", d / 1000) else "${d.roundToInt()} m"
                    addRow("${pl.name} · $dTxt", { prefs.addRecent(Place(pl.name.substringBefore(","), pl.pt)); goTo(Place(pl.name.substringBefore(","), pl.pt)) })
                }
                setResults(true)
            } catch (e: Exception) {
                toast("Recherche impossible : ${e.message}")
            }
        }
    }

    private fun doSearch() {
        val q = searchBox.text.toString().trim()
        if (q.isEmpty()) return
        hideKeyboard()
        scope.launch {
            try {
                fun render(res: List<Place>, final: Boolean) {
                    resultsBox.removeAllViews()
                    if (res.isEmpty() && final) addRow("Aucun résultat", {})
                    for (p in res) {
                        addRow(p.name, {
                            val short = p.name.substringBefore(",")
                            val place = Place(short, p.pt)
                            val fav = pendingFav
                            if (fav != null) {
                                prefs.setFav(fav, place)
                                pendingFav = null
                                toast("Adresse enregistrée")
                            }
                            prefs.addRecent(place)
                            goTo(place)
                        })
                    }
                    if (final) addRow("🔎 Chercher « $q » dans Google Maps", {
                        try {
                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://www.google.com/maps/search/?api=1&query=" + android.net.Uri.encode(q))))
                            toast("Dans Google Maps : Partager > DJStef GPS")
                        } catch (_: Exception) { toast("Google Maps indisponible") }
                    })
                    if (!final) addRow("… recherche en cours", {})
                    else if (Geocoder.diag.isNotBlank()) addRow("ℹ v$VERSION · " + Geocoder.diag, {})
                    setResults(true)
                }
                Geocoder.googleKey = prefs.str("googleKey", "")
                Geocoder.tomtomKey = prefs.str("tomtomKey", "")
                render(emptyList(), false)
                val res = Geocoder.search(q, me?.let { Pt(it.latitude, it.longitude) }) { part ->
                    scope.launch { render(part, false) }
                }
                render(res, true)
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

    private fun routeOpts() = RouteOpts(
        tolls = !prefs.bool("avoidTolls", false),
        highways = !prefs.bool("avoidHighways", false),
        ferries = !prefs.bool("avoidFerries", false)
    )

    /** Radars fixes situés à moins de 30 m de l'itinéraire. */
    private suspend fun radarsOnRoute(pts: List<Pt>): List<Radar> = withContext(Dispatchers.Default) {
        if (pts.size < 2 || fixedRadars.isEmpty()) return@withContext emptyList<Radar>()
        val dense = ArrayList<Pt>()
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val n = max(1, (Geo.dist(a, b) / 40.0).toInt())
            for (k in 0 until n) {
                val t = k.toDouble() / n
                dense.add(Pt(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t))
            }
        }
        dense.add(pts.last())
        fun key(i: Long, j: Long): Long = (i shl 32) xor (j and 0xffffffffL)
        val grid = HashMap<Long, ArrayList<Pt>>()
        for (p in dense) {
            val k = key(Math.floor(p.lat / 0.001).toLong(), Math.floor(p.lon / 0.001).toLong())
            grid.getOrPut(k) { ArrayList() }.add(p)
        }
        val out = ArrayList<Radar>()
        for (r in fixedRadars) {
            val ci = Math.floor(r.pt.lat / 0.001).toLong()
            val cj = Math.floor(r.pt.lon / 0.001).toLong()
            var hit = false
            for (di in -1L..1L) {
                for (dj in -1L..1L) {
                    val l = grid[key(ci + di, cj + dj)] ?: continue
                    for (p in l) {
                        if (Geo.dist(p, r.pt) < 30.0) {
                            hit = true
                            break
                        }
                    }
                    if (hit) break
                }
                if (hit) break
            }
            if (hit) out.add(r)
        }
        out
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
                val from = Pt(l.latitude, l.longitude)
                val opts = routeOpts()
                val viaList = vias.map { Via(it.pt, true) }
                var list = Router.routes(mode, from, d, opts, emptyList(), viaList, if (mode == "car" && viaList.isEmpty() && !keepNav) 2 else 0)
                var r = list[0]
                var radars = radarsOnRoute(r.pts)
                var info = ""
                if (prefs.bool("avoidRadars", false) && mode == "car" && radars.isNotEmpty()) {
                    try {
                        val r2 = Router.route(mode, from, d, opts, radars.map { it.pt }, viaList)
                        val left = radarsOnRoute(r2.pts)
                        if (left.size < radars.size && r2.dur <= r.dur * 1.4 + 300) {
                            info = "évite ${radars.size - left.size} radar(s)"
                            r = r2
                            radars = left
                            list = listOf(r2)
                        } else {
                            info = "radars inévitables"
                        }
                    } catch (e: Exception) {
                        info = "détour impossible"
                    }
                }
                alts = list
                altIdx = 0
                showRoute(r, radars, info, keepNav, true)
                fetchTrafficEta(from, d, true)
            } catch (e: Exception) {
                autoStart = false
                toast("Itinéraire impossible : ${e.message}")
            } finally {
                rerouting = false
            }
        }
    }

    private fun showRoute(r: Route, radars: List<Radar>, info: String, keepNav: Boolean, fit: Boolean) {
        route = r
        if (navigating) NavBridge.route = r
        stepIdx = 0
        farSaid = -1
        nearSaid = -1
        offCount = 0
        trafficEta = null
        style?.getSourceAs<GeoJsonSource>("route")?.setGeoJson(
            Feature.fromGeometry(LineString.fromLngLats(r.pts.map { Point.fromLngLat(it.lon, it.lat) }))
        )
        val extra = buildString {
            if (radars.isNotEmpty()) append(" · ${radars.size} radar(s) sur le trajet")
            if (info.isNotEmpty()) append(" ($info)")
        }
        etaView.text = "Arrivée ${eta(r.dur)}"
        subView.text = summary(r.dur, r.dist) + extra
        destView.text = destName + (if (vias.isNotEmpty()) " · via " + vias.joinToString(", ") { it.name.substringBefore(",") } else "")
        refreshAlts()
        refreshPanel()
        if (fit && !keepNav) {
            follow = false
            val b = LatLngBounds.Builder()
            r.pts.forEach { b.include(LatLng(it.lat, it.lon)) }
            map?.easeCamera(CameraUpdateFactory.newLatLngBounds(b.build(), dp(50), dp(110), dp(50), dp(250)), 900)
        } else if (keepNav) {
            navDist.text = "Itinéraire recalculé"
        }
        if (!keepNav && autoStart) {
            autoStart = false
            startNavigation()
        }
    }

    private fun refreshAlts() {
        altRow.removeAllViews()
        if (alts.size < 2 || navigating) {
            altRow.visibility = View.GONE
            return
        }
        altRow.visibility = View.VISIBLE
        for ((i, r) in alts.withIndex()) {
            val b = button("${i + 1} · ${(r.dur / 60).roundToInt()} min · ${String.format(Locale.FRANCE, "%.0f", r.dist / 1000)} km", GREY) { selectAlt(i) }
            b.textSize = 12f
            b.background = glossBtn(if (i == altIdx) ORANGE_L else GREY)
            b.setTextColor(Color.parseColor(if (i == altIdx) "#0B1220" else "#FFFFFF"))
            altRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).also { it.rightMargin = dp(6) })
        }
    }

    private fun selectAlt(i: Int) {
        val r = alts.getOrNull(i) ?: return
        altIdx = i
        scope.launch {
            val radars = radarsOnRoute(r.pts)
            showRoute(r, radars, "", false, false)
        }
    }

    /** Durée avec trafic (TomTom) sur le trajet restant, si la clé TomTom est renseignée. */
    private fun fetchTrafficEta(from: Pt, to: Pt, preview: Boolean) {
        val key = prefs.str("tomtomKey", "")
        if (key.isBlank() || etaBusy) return
        etaBusy = true
        lastEtaFetch = System.currentTimeMillis()
        scope.launch {
            try {
                val res = TomTomEta.eta(key, from, to) ?: return@launch
                val sec = res.first
                val delay = res.second
                trafficEta = Pair(sec, System.currentTimeMillis())
                trafficDelay = delay
                if (preview && !navigating) {
                    etaView.text = "Arrivée ${eta(sec)}"
                    val r = route
                    if (r != null) subView.text = summary(sec, r.dist) + (if (delay >= 120) " · trafic +${(delay / 60).roundToInt()} min" else " · trafic fluide")
                }
            } catch (_: Exception) {
            } finally {
                etaBusy = false
            }
        }
    }

    private fun eta(sec: Double): String {
        val c = Calendar.getInstance()
        c.add(Calendar.SECOND, sec.roundToInt())
        return SimpleDateFormat("HH:mm", Locale.FRANCE).format(c.time)
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
        vias.clear()
        alts = emptyList()
        pendingVia = false
        style?.getSourceAs<GeoJsonSource>("route")?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        style?.getSourceAs<GeoJsonSource>("dest")?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        refreshPanel()
    }

    // ---------- Guidage ----------

    private fun speak(text: String) {
        if (ttsReady && !muted && prefs.bool("voice", true)) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    private fun arrowFor(k: Int): String = when (k) {
        15, 19, 21, 24, 3 -> "←"
        16 -> "↖"
        14 -> "↙"
        10, 18, 20, 23, 2 -> "→"
        9 -> "↗"
        11 -> "↘"
        12, 13 -> "↩"
        26, 27 -> "↻"
        4, 5, 6 -> "🏁"
        else -> "↑"
    }

    private fun startNavigation() {
        val r = route ?: return
        navigating = true
        NavBridge.navigating = true
        NavBridge.route = r
        NavBridge.destName = destName
        NavBridge.nextKind = r.steps.firstOrNull()?.kind ?: 1
        NavBridge.nextText = r.steps.firstOrNull()?.text ?: ""
        NavBridge.nextName = r.steps.firstOrNull()?.name ?: ""
        NavBridge.nextDist = 0.0
        NavBridge.remainDist = r.dist
        NavBridge.remainSec = r.dur
        tripStart = System.currentTimeMillis()
        tripDist = 0.0
        tripMax = 0
        tripLast = null
        tripTrace.clear()
        follow = true
        stepIdx = 0
        farSaid = -1
        nearSaid = -1
        if (prefs.bool("keepOn", true)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideKeyboard()
        setResults(false)
        applyNavPadding()
        val first = r.steps.firstOrNull()
        navArrow.text = arrowFor(first?.kind ?: 1)
        navDist.text = ""
        navStreet.text = first?.text ?: "Suivez l'itinéraire"
        speak(first?.text ?: "C'est parti")
        refreshPanel()
        applyCarMode()
        me?.let { moveCamera(it) }
    }

    private fun stopNavigation() {
        if (!navigating) return
        navigating = false
        zoomBoost = 0.0
        roadView.visibility = View.GONE
        NavBridge.navigating = false
        NavBridge.route = null
        NavBridge.changed()
        saveTrip()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        map?.setPadding(0, 0, 0, 0)
        refreshPanel()
        applyCarMode()
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

        while (stepIdx + 1 < r.steps.size - 1 && Geo.dist(p, r.steps[stepIdx + 1].at) < 25.0) {
            stepIdx++
            farSaid = -1
            nearSaid = -1
            if (r.steps[stepIdx].kind in 4..6 && vias.isNotEmpty()) vias.removeAt(0)
        }
        val next = r.steps.getOrNull(stepIdx + 1) ?: return
        val dNext = Geo.dist(p, next.at)

        if (stepIdx + 1 >= r.steps.size - 1 && dNext < 30.0) {
            speak("Vous êtes arrivé à destination")
            toast("Arrivé à destination")
            clearRoute()
            return
        }

        var remain = dNext
        for (j in stepIdx + 1 until r.steps.size) remain += r.steps[j].dist
        val remTime = if (r.dist > 0) r.dur * remain / r.dist else 0.0
        navArrow.text = arrowFor(next.kind)
        navDist.text = if (dNext >= 1000) String.format(Locale.FRANCE, "%.1f km", dNext / 1000) else "${(dNext / 10).roundToInt() * 10} m"
        val vm = Regex("\\s+vers\\s+(.+)$").find(next.text)
        val toward = if (next.sign.isNotBlank()) next.sign else vm?.groupValues?.get(1)?.replace("/", " · ")?.let { "vers $it" } ?: ""
        val instr = if (next.sign.isBlank() && vm != null) next.text.removeRange(vm.range) else next.text
        navToward.text = toward
        navToward.visibility = if (toward.isBlank()) View.GONE else View.VISIBLE
        navStreet.text = instr
        styleRoad(navRoad, next.refs, 18f)
        roadView.visibility = View.GONE
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastEtaFetch > 180000) dest?.let { fetchTrafficEta(p, it, false) }
        val te = trafficEta
        val shown = if (te != null && nowMs - te.second < 360000) te.first else remTime
        etaView.text = "Arrivée ${eta(shown)}"
        subView.text = summary(shown, remain) + (if (te != null && trafficDelay >= 120) " · trafic +${(trafficDelay / 60).roundToInt()} min" else "")
        val bandMin = (shown / 60).roundToInt()
        navEta.text = if (bandMin >= 60) "${bandMin / 60} h ${bandMin % 60} min" else "$bandMin min"
        val bandDist = if (remain >= 1000) String.format(Locale.FRANCE, "%.1f km", remain / 1000) else "${remain.roundToInt()} m"
        navSub.text = "$bandDist · ${eta(shown)}" + (if (te != null && trafficDelay >= 120) " · trafic +${(trafficDelay / 60).roundToInt()} min" else "")
        // Zoom d'approche : rond-point (dès 300 m) et virages (dès 150 m), puis retour au zoom choisi
        val roundabout = next.kind == 26 || next.kind == 27
        val turn = next.kind in setOf(15, 19, 21, 24, 3, 16, 14, 10, 18, 20, 23, 2, 9, 11, 12, 13)
        zoomBoost = if (roundabout && dNext < 300.0) 1.3 else if (turn && dNext < 150.0) 0.9 else 0.0
        NavBridge.nextKind = next.kind
        NavBridge.nextText = next.text
        NavBridge.nextName = next.name
        NavBridge.nextDist = dNext
        NavBridge.remainDist = remain
        NavBridge.remainSec = remTime

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

    // ---------- Lufop (radars mobiles, feux rouges…) ----------

    private fun lufopQuotaOk(): Boolean {
        val day = SimpleDateFormat("yyyyMMdd", Locale.FRANCE).format(Date())
        if (prefs.str("lufopDay", "") != day) {
            prefs.setStr("lufopDay", day)
            prefs.setInt("lufopCount", 0)
        }
        return prefs.int("lufopCount", 0) < 190
    }

    private fun maybeFetchLufop(loc: Location) {
        val key = prefs.str("lufopKey", "")
        if (key.isBlank() || lufopBusy || !prefs.bool("showFixed", true)) return
        val now = System.currentTimeMillis()
        if (now - lufopLastTry < 120000) return
        val p = Pt(loc.latitude, loc.longitude)
        val c = lufopCenter
        if (c != null && Geo.dist(c, p) < 8000.0) return
        if (!lufopQuotaOk()) return
        lufopBusy = true
        lufopLastTry = now
        scope.launch {
            try {
                val list = Lufop.around(key, p)
                prefs.setInt("lufopCount", prefs.int("lufopCount", 0) + 1)
                lufopCenter = p
                val counts = list.groupingBy { it.kind }.eachCount().entries.joinToString(", ") { "${it.key} ${it.value}" }
                lufopStats = "${SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date())} : ${list.size} reçus" +
                    (if (counts.isNotEmpty()) " ($counts)" else "") + " · requêtes aujourd'hui : ${prefs.int("lufopCount", 0)}/200"
                for (r in list) {
                    // Évite les doublons avec les radars fixes déjà connus (data.gouv.fr)
                    if (r.kind == "Radar fixe" && baseRadars.any { Math.abs(it.pt.lat - r.pt.lat) < 0.0006 && Math.abs(it.pt.lon - r.pt.lon) < 0.0009 }) continue
                    lufopMap[r.id] = r
                }
                lufopMap.entries.removeAll { Geo.dist(p, it.value.pt) > 45000.0 }
                fixedRadars = baseRadars + lufopMap.values
                refreshRadarLayer()
            } catch (e: Exception) {
                lufopLastError = e.message ?: "erreur"
            } finally {
                lufopBusy = false
            }
        }
    }

    // ---------- Radars ----------

    private fun checkRadars(loc: Location, kmh: Int) {
        val p = Pt(loc.latitude, loc.longitude)
        val moving = loc.hasSpeed() && loc.speed > 3f && loc.hasBearing()
        var best: Radar? = null
        var bestD = Double.MAX_VALUE
        val candidates = ArrayList<Radar>()
        if (prefs.bool("showFixed", true)) {
            for (r in fixedRadars) {
                if (Math.abs(r.pt.lat - p.lat) < 0.012 && Math.abs(r.pt.lon - p.lon) < 0.02) candidates.add(r)
            }
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
            val rt = route
            if (navigating && rt != null && rt.pts.size > 2) {
                // Guidage : seuls comptent les radars posés sur NOTRE itinéraire, devant nous (pas la route parallèle)
                val from = (snapIdx - 3).coerceIn(0, rt.pts.size - 2)
                val ahead = rt.pts.subList(from, minOf(rt.pts.size, from + 1500))
                val sn = Geo.snap(r.pt, ahead, 0, ahead.size - 1) ?: continue
                if (sn.third > 25.0) continue
                // Côté du radar par rapport à notre sens de marche : à gauche = chaussée d'en face (circulation à droite)
                val diff = ((Geo.bearing(sn.first, r.pt) - sn.second + 540.0) % 360.0) - 180.0
                if (diff < 0 && sn.third > 7.0) continue
            } else if (moving) {
                // Hors guidage : dans le sens de la marche et près de l'axe de la route suivie
                val ang = Geo.angleDiff(loc.bearing.toDouble(), Geo.bearing(p, r.pt))
                if (ang > 35.0) continue
                val lateral = d * Math.sin(Math.toRadians(ang))
                val left = ((Geo.bearing(p, r.pt) - loc.bearing + 540.0) % 360.0) - 180.0 < 0
                if (d > 60.0 && lateral > (if (left) 8.0 else 30.0)) continue
            }
            if (d < bestD) {
                bestD = d
                best = r
            }
        }
        val b = best
        if (b == null) {
            radarBox.visibility = View.GONE
            NavBridge.radarText = null
            return
        }
        val lim = if (b.limit != null) " · limité à ${b.limit}" else ""
        radarText.text = "${b.kind} dans ${bestD.roundToInt()} m$lim"
        NavBridge.radarText = radarText.text.toString()
        NavBridge.radarType = b.kind
        radarIcon.setImageBitmap(RadarArt.bitmap(RadarArt.key(b.kind), 128))
        radarBox.visibility = View.VISIBLE
        if (b.id !in alerted) {
            alerted.add(b.id)
            if (prefs.bool("radarAlert", true)) {
                val over = if (b.limit != null && kmh > b.limit + 2) ", vous dépassez la limite" else ""
                speak("${b.kind} dans ${distSay(bestD)}$over")
            }
        }
    }

    // ---------- Signalements ----------

    private fun serverUrl(): String = prefs.str("server", "")

    private fun reportDialog() {
        val l = me
        if (l == null) {
            toast("Position GPS en attente...")
            return
        }
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = colorBg("#F2101828", 22)
        box.setPadding(dp(12), dp(14), dp(12), dp(12))
        box.addView(label("Signaler ici", 20f, true))
        val types = ReportClient.TYPES
        var i = 0
        while (i < types.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (k in i until minOf(i + 3, types.size)) {
                val type = types[k]
                val cell = LinearLayout(this)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER
                cell.setPadding(dp(4), dp(8), dp(4), dp(8))
                cell.background = colorBg(CARD, 14)
                val iv = ImageView(this)
                iv.setImageBitmap(RadarArt.bitmap(RadarArt.key(type), 128))
                cell.addView(iv, LinearLayout.LayoutParams(dp(64), dp(64)))
                val t = label(type, 12f, true)
                t.gravity = Gravity.CENTER
                cell.addView(t)
                cell.setOnClickListener {
                    dlg.dismiss()
                    sendReport(type, l)
                }
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).also { it.setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            for (k in 0 until (i + 3 - types.size).coerceAtLeast(0)) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
            i += 3
        }
        val cancel = button("Annuler", GREY) { dlg.dismiss() }
        box.addView(cancel, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        dlg.setContentView(box)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
    }

    private fun sendReport(type: String, l: Location) {
        val at = Pt(l.latitude, l.longitude)
        val id = "loc${System.currentTimeMillis()}"
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

    private fun enlargeCar(model: Int, col: Int) {
        val dlg = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setBackgroundColor(Color.parseColor(BG))
        val rear = ImageView(this)
        rear.setImageBitmap(CarSprites.drawRear(model, col))
        rear.scaleType = ImageView.ScaleType.FIT_CENTER
        val top = ImageView(this)
        top.setImageBitmap(CarSprites.draw(model, col))
        top.scaleType = ImageView.ScaleType.FIT_CENTER
        box.addView(label(CarSprites.NAMES[model], 22f, true))
        box.addView(rear, LinearLayout.LayoutParams(-1, 0, 1f))
        box.addView(top, LinearLayout.LayoutParams(-1, 0, 1.3f))
        box.addView(label("Touche pour fermer", 12f, false, "#9AA3B5"))
        box.setOnClickListener { dlg.dismiss() }
        dlg.setContentView(box)
        dlg.show()
    }

    // ---------- Trajets, carburants, parkings ----------

    private fun recordTrip(loc: Location, kmh: Int) {
        if (kmh > tripMax) tripMax = kmh
        val last = tripLast
        val p = Pt(loc.latitude, loc.longitude)
        if (last == null) {
            tripLast = loc
            tripTrace.add(p)
            return
        }
        val d = Geo.dist(Pt(last.latitude, last.longitude), p)
        if (d >= 25) {
            tripDist += d
            tripLast = loc
            tripTrace.add(p)
        }
    }

    private fun saveTrip() {
        if (tripStart == 0L) return
        val trace = if (tripTrace.size > 400) {
            val step = tripTrace.size / 400.0
            (0 until 400).map { tripTrace[(it * step).toInt()] }
        } else tripTrace.toList()
        trips.add(Trip(tripStart, System.currentTimeMillis(), tripDist, tripMax, destName, dest, trace))
        tripStart = 0L
        tripTrace.clear()
    }

    private fun setupPoiLayers() {
        val st = style ?: return
        try {
            st.addImage("ic-fuel", PoiArt.bitmap("€", "#EF6C00"))
            st.addImage("ic-parking", PoiArt.bitmap("P", "#1E88E5"))
            st.addSource(GeoJsonSource("poi"))
            st.addLayerBelow(
                SymbolLayer("poi-layer", "poi").withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconSize(0.75f),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textSize(12f),
                    PropertyFactory.textColor("#FFFFFF"),
                    PropertyFactory.textHaloColor("#101828"),
                    PropertyFactory.textHaloWidth(2f),
                    PropertyFactory.textOffset(arrayOf(0f, 1.9f)),
                    PropertyFactory.textOptional(true)
                ),
                "dest-layer"
            )
            st.addSource(GeoJsonSource("trip"))
            st.addLayerBelow(
                LineLayer("trip-layer", "trip").withProperties(
                    PropertyFactory.lineColor("#8D6E63"),
                    PropertyFactory.lineWidth(6f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                ),
                "poi-layer"
            )
        } catch (e: Exception) {
            toast("Couches annexes indisponibles : ${e.message}")
        }
    }

    private fun showPois(items: List<Triple<Pt, String, String>>) {
        val feats = items.map { (p, icon, label) ->
            val f = point(p)
            f.addStringProperty("icon", icon)
            f.addStringProperty("label", label)
            f
        }
        style?.getSourceAs<GeoJsonSource>("poi")?.setGeoJson(FeatureCollection.fromFeatures(feats))
        if (items.isNotEmpty()) {
            val b = org.maplibre.android.geometry.LatLngBounds.Builder()
            for (i in items) b.include(org.maplibre.android.geometry.LatLng(i.first.lat, i.first.lon))
            me?.let { b.include(org.maplibre.android.geometry.LatLng(it.latitude, it.longitude)) }
            follow = false
            try {
                map?.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), dp(70)), 600)
            } catch (_: Exception) {
            }
        }
    }

    private fun km(m: Double) = if (m >= 1000) String.format(Locale.FRANCE, "%.1f km", m / 1000) else "${m.roundToInt()} m"

    private fun fuelPage(content: LinearLayout) {
        val c = card(content)
        sectionTitle(c, "CARBURANT", "#FFB74D")
        val cur = prefs.str("fuelType", "Gazole")
        val hs = HorizontalScrollView(this)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (name in Fuel.TYPES.keys) {
            val b = button(name, if (name == cur) "#EF6C00" else GREY) {
                prefs.setStr("fuelType", name)
                showMenu("fuel")
            }
            row.addView(b, LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = dp(8) })
        }
        hs.addView(row)
        c.addView(hs)

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val status = label("Recherche des stations…", 14f, false, "#9AA3B5")
        list.addView(status)
        content.addView(list)
        val l = me
        if (l == null) {
            status.text = "Position GPS en attente…"
            return
        }
        val key = Fuel.TYPES[cur] ?: "gazole"
        scope.launch {
            try {
                val all = Fuel.around(Pt(l.latitude, l.longitude), 15)
                val st = all.filter { it.prices.containsKey(key) }.sortedBy { it.prices[key] }.take(20)
                list.removeAllViews()
                if (st.isEmpty()) {
                    list.addView(label("Aucune station avec du $cur dans 15 km.", 14f, false, "#9AA3B5"))
                    return@launch
                }
                val cheapest = st.first().prices[key]
                list.addView(button("Voir sur la carte", "#EF6C00") {
                    showPois(st.map { Triple(it.pt, "ic-fuel", String.format(Locale.FRANCE, "%.3f", it.prices[key])) })
                    closeMenu()
                }, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) })
                for (s in st) {
                    val sc = card(list)
                    val price = s.prices[key] ?: 0.0
                    val top = LinearLayout(this@MainActivity)
                    top.orientation = LinearLayout.HORIZONTAL
                    top.gravity = Gravity.CENTER_VERTICAL
                    top.addView(label(String.format(Locale.FRANCE, "%.3f €", price), 22f, true, if (price == cheapest) "#66BB6A" else "#FFFFFF"), LinearLayout.LayoutParams(0, -2, 1f))
                    top.addView(label(km(s.dist), 14f, false, "#9AA3B5"))
                    sc.addView(top)
                    sc.addView(label("${s.address}, ${s.city}", 13f, false, "#C0C7D6"))
                    val others = Fuel.TYPES.filter { it.value != key && s.prices.containsKey(it.value) }
                        .map { "${it.key} ${String.format(Locale.FRANCE, "%.3f", s.prices[it.value])}" }
                    if (others.isNotEmpty()) sc.addView(label(others.joinToString(" · "), 11f, false, "#8A93A6"))
                    sc.setOnClickListener {
                        closeMenu()
                        goTo(Place("Station ${s.city}", s.pt))
                    }
                }
            } catch (e: Exception) {
                status.text = "Prix indisponibles : ${e.message}"
            }
        }
    }

    private fun parkingPage(content: LinearLayout) {
        // Autour de la destination si elle existe, sinon autour de toi
        val center = dest ?: me?.let { Pt(it.latitude, it.longitude) }
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val status = label("Recherche des parkings…", 14f, false, "#9AA3B5")
        list.addView(status)
        if (center == null) {
            status.text = "Position GPS en attente…"
            content.addView(list)
            return
        }
        content.addView(label(if (dest != null) "Autour de : $destName" else "Autour de ta position", 12f, false, "#9AA3B5"))
        content.addView(list)
        scope.launch {
            try {
                val ps = Parkings.around(center).take(25)
                list.removeAllViews()
                if (ps.isEmpty()) {
                    list.addView(label("Aucun parking trouvé à moins de 1,5 km.", 14f, false, "#9AA3B5"))
                    return@launch
                }
                list.addView(button("Voir sur la carte", "#1E88E5") {
                    showPois(ps.map { Triple(it.pt, "ic-parking", "") })
                    closeMenu()
                }, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) })
                for (p in ps) {
                    val pc = card(list)
                    pc.addView(label(p.name, 16f, true))
                    val info = listOfNotNull(p.fee, p.kind, p.capacity?.let { "$it places" }, km(p.dist))
                    pc.addView(label(info.joinToString(" · "), 13f, false, if (p.fee == "Gratuit") "#66BB6A" else "#C0C7D6"))
                    pc.setOnClickListener {
                        closeMenu()
                        goTo(Place(p.name, p.pt))
                    }
                }
                list.addView(label("Données OpenStreetMap : tarifs et places libres non disponibles en temps réel.", 11f, false, "#8A93A6"))
            } catch (e: Exception) {
                status.text = "Parkings indisponibles : ${e.message}"
            }
        }
    }

    private fun tripsPage(content: LinearLayout) {
        val all = trips.all()
        if (all.isEmpty()) {
            val c = card(content)
            c.addView(label("Aucun trajet pour l'instant. Chaque navigation lancée est enregistrée ici.", 14f, false, "#9AA3B5"))
            return
        }
        val total = all.sumOf { it.dist }
        val c0 = card(content)
        c0.addView(label("${all.size} trajets · ${km(total)}", 18f, true))
        val fmt = java.text.SimpleDateFormat("EEE d MMM · HH:mm", Locale.FRANCE)
        for (t in all) {
            val c = card(content)
            c.addView(label(if (t.destName.isBlank()) "Trajet" else t.destName, 16f, true))
            c.addView(label(fmt.format(java.util.Date(t.start)), 12f, false, "#9AA3B5"))
            val mins = ((t.end - t.start) / 60000.0).roundToInt()
            val avg = if (t.end > t.start) (t.dist / 1000.0) / ((t.end - t.start) / 3600000.0) else 0.0
            c.addView(label("${km(t.dist)} · $mins min · moy. ${avg.roundToInt()} km/h · max ${t.maxKmh} km/h", 13f, false, "#C0C7D6"))
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            if (t.trace.size > 1) row.addView(button("Tracé", "#8D6E63") {
                val coords = t.trace.map { Point.fromLngLat(it.lon, it.lat) }
                style?.getSourceAs<GeoJsonSource>("trip")?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(coords)))
                val b = org.maplibre.android.geometry.LatLngBounds.Builder()
                for (p in t.trace) b.include(org.maplibre.android.geometry.LatLng(p.lat, p.lon))
                follow = false
                closeMenu()
                try {
                    map?.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), dp(70)), 600)
                } catch (_: Exception) {
                }
            }, LinearLayout.LayoutParams(0, -2, 1f).also { it.rightMargin = dp(6) })
            t.dest?.let { d ->
                row.addView(button("Refaire", GREEN) {
                    closeMenu()
                    goTo(Place(t.destName.ifBlank { "Destination" }, d))
                }, LinearLayout.LayoutParams(0, -2, 1f).also { it.rightMargin = dp(6) })
            }
            row.addView(button("Suppr.", RED) {
                trips.remove(t.start)
                showMenu("trips")
            }, LinearLayout.LayoutParams(0, -2, 1f))
            c.addView(row, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        }
        content.addView(button("Tout effacer", GREY) {
            AlertDialog.Builder(this).setMessage("Effacer tout l'historique ?")
                .setPositiveButton("Effacer") { _, _ -> trips.clear(); showMenu("trips") }
                .setNegativeButton("Annuler", null).show()
        })
    }

    // ---------- Trafic temps réel (TomTom) ----------

    private fun setupTraffic() {
        val st = style ?: return
        val key = prefs.str("tomtomKey", "")
        if (key.isBlank()) return
        try {
            if (st.getSource("traffic") == null) {
                val ts = TileSet("2.2.0", "https://api.tomtom.com/traffic/map/4/tile/flow/relative0/{z}/{x}/{y}.png?key=$key")
                ts.setMaxZoom(22f)
                st.addSource(RasterSource("traffic", ts, 256))
                st.addLayerBelow(
                    RasterLayer("traffic-layer", "traffic").withProperties(PropertyFactory.rasterOpacity(0.85f)),
                    "route-casing"
                )
            }
            val vis = if (prefs.bool("traffic", false)) Property.VISIBLE else Property.NONE
            st.getLayerAs<RasterLayer>("traffic-layer")?.setProperties(PropertyFactory.visibility(vis))
        } catch (e: Exception) {
            toast("Trafic indisponible : ${e.message}")
        }
    }

    private fun toggleTraffic() {
        if (prefs.str("tomtomKey", "").isBlank()) {
            toast("Ajoute d'abord ta clé TomTom gratuite (☰ → Trafic)")
            showMenu("traffic")
            return
        }
        prefs.setBool("traffic", !prefs.bool("traffic", false))
        setupTraffic()
        refreshPanel()
    }

    private fun trafficPage(content: LinearLayout) {
        val c = card(content)
        sectionTitle(c, "TRAFIC EN TEMPS RÉEL", "#4DB6AC")
        switchRow(c, "Afficher le trafic", "Vert fluide, orange ralenti, rouge bouchon", "traffic", false) { setupTraffic(); refreshPanel() }
        val c2 = card(content)
        sectionTitle(c2, "CLÉ TOMTOM (GRATUITE)", "#4DB6AC")
        c2.addView(label("Crée un compte gratuit sur developer.tomtom.com (sans carte bancaire), puis copie ta clé API ici. Le palier gratuit couvre l'usage perso.", 12f, false, "#9AA3B5"))
        val et = EditText(this)
        et.setText(prefs.str("tomtomKey", ""))
        et.hint = "Clé API TomTom"
        et.setHintTextColor(Color.parseColor("#6B7385"))
        et.setTextColor(Color.WHITE)
        et.setSingleLine(true)
        c2.addView(et)
        c2.addView(button("Enregistrer", "#00897B") {
            val st = style
            if (st != null) {
                st.getLayer("traffic-layer")?.let { st.removeLayer(it) }
                st.getSource("traffic")?.let { st.removeSource(it) }
            }
            prefs.setStr("tomtomKey", et.text.toString().trim())
            setupTraffic()
            toast("Clé enregistrée")
        }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        val result = label("", 13f, true)
        c2.addView(button("Tester la clé", BLUE) {
            val k = et.text.toString().trim()
            if (k.isBlank()) {
                result.text = "Colle d'abord ta clé."
                result.setTextColor(Color.parseColor("#EF9A9A"))
                return@button
            }
            result.text = "Test en cours…"
            result.setTextColor(Color.WHITE)
            scope.launch {
                val msg = withContext(Dispatchers.IO) {
                    try {
                        Http.get("https://api.tomtom.com/traffic/map/4/tile/flow/relative0/10/530/375.png?key=$k")
                        "OK"
                    } catch (e: Exception) {
                        e.message ?: "erreur"
                    }
                }
                if (msg == "OK") {
                    result.text = "✔ Clé valide : le trafic est actif."
                    result.setTextColor(Color.parseColor("#81C784"))
                } else {
                    result.text = when {
                        msg.contains("403") || msg.contains("401") -> "✘ Clé refusée par TomTom ($msg). Vérifie qu'elle est copiée en entier."
                        else -> "✘ Test impossible : $msg"
                    }
                    result.setTextColor(Color.parseColor("#EF9A9A"))
                }
            }
        }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        c2.addView(result)
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

    // ---------- Menu (tuiles colorées, bandeau en haut, comme l'appli IPTV) ----------

    private fun closeMenu() {
        menuView?.let { rootLayout.removeView(it) }
        menuView = null
        menuPage = null
    }

    private fun pageColor(page: String?): String = when (page) {
        "dest" -> GREEN
        "vehicle" -> ORANGE
        "route" -> BLUE
        "radar" -> RED
        "traffic" -> "#00897B"
        "fuel" -> "#EF6C00"
        "parking" -> "#1E88E5"
        "trips" -> "#8D6E63"
        "guide" -> SLATE
        else -> ORANGE_L
    }

    private fun pageTitle(page: String?): String = when (page) {
        "dest" -> "Destinations"
        "vehicle" -> "Véhicule"
        "route" -> "Itinéraire"
        "radar" -> "Radars"
        "traffic" -> "Trafic"
        "fuel" -> "Carburants"
        "parking" -> "Parkings"
        "trips" -> "Mes trajets"
        "guide" -> "Guidage"
        "about" -> "À propos"
        else -> "DJSTEF GPS"
    }

    private fun showMenu(page: String?) {
        menuView?.let { rootLayout.removeView(it) }
        menuPage = page
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL
        v.setBackgroundColor(Color.parseColor(BG))
        v.isClickable = true

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Color.parseColor(BAR))
        bar.setPadding(dp(16), topInset + dp(14), dp(16), dp(14))
        val back = label(if (page == null) "✕" else "←", 26f, true)
        back.setPadding(dp(4), 0, dp(14), 0)
        back.setOnClickListener { if (page == null) closeMenu() else showMenu(null) }
        bar.addView(back)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(label(pageTitle(page), 22f, true, if (page == null) "#FFFFFF" else "#FFFFFF"))
        if (page == null) col.addView(label("GPS · v$VERSION", 12f, false, "#9AA3B5"))
        bar.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        v.addView(bar, LinearLayout.LayoutParams(-1, -2))
        val line = View(this)
        line.setBackgroundColor(Color.parseColor(pageColor(page)))
        v.addView(line, LinearLayout.LayoutParams(-1, dp(4)))

        val scroll = ScrollView(this)
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(14), dp(14), dp(14), dp(24) + bottomInset)
        scroll.addView(content)
        v.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        when (page) {
            null -> homeTiles(content)
            "dest" -> destPage(content)
            "vehicle" -> vehiclePage(content)
            "route" -> routePage(content)
            "radar" -> radarPage(content)
            "traffic" -> trafficPage(content)
            "fuel" -> fuelPage(content)
            "parking" -> parkingPage(content)
            "trips" -> tripsPage(content)
            "guide" -> guidePage(content)
            else -> aboutPage(content)
        }
        rootLayout.addView(v, FrameLayout.LayoutParams(-1, -1))
        menuView = v
    }

    private fun tile(parent: LinearLayout, page: String, icon: String, title: String, sub: String, color: String) {
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.background = gradBg(color, 20)
        t.setPadding(dp(16), dp(16), dp(16), dp(16))
        t.gravity = Gravity.BOTTOM
        t.addView(label(icon, 34f))
        t.addView(label(title, 18f, true))
        t.addView(label(sub, 12f, false, "#E3E8F2"))
        t.setOnClickListener { showMenu(page) }
        parent.addView(t, LinearLayout.LayoutParams(0, dp(140), 1f).also { it.setMargins(dp(5), dp(5), dp(5), dp(5)) })
    }

    private fun homeTiles(content: LinearLayout) {
        val items = listOf(
            arrayOf("dest", "📍", "Destinations", "Domicile, travail, récents", GREEN),
            arrayOf("vehicle", "🚗", "Véhicule", "Modèle 3D, couleur, taille", ORANGE),
            arrayOf("route", "🛣", "Itinéraire", "Péages, autoroutes, ferries", BLUE),
            arrayOf("radar", "📡", "Radars", "Alertes, éviter, signalements", RED),
            arrayOf("fuel", "⛽", "Carburants", "Stations et prix", "#EF6C00"),
            arrayOf("parking", "🅿", "Parkings", "Autour de toi", "#1E88E5"),
            arrayOf("trips", "🕘", "Mes trajets", "Historique", "#8D6E63"),
            arrayOf("traffic", "🚦", "Trafic", "Temps réel (TomTom)", "#00897B"),
            arrayOf("guide", "🔊", "Guidage", "Voix, vitesse, vue 3D", SLATE),
            arrayOf("about", "ℹ", "À propos", "Version et sources", GREY)
        )
        var i = 0
        while (i < items.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (k in i until minOf(i + 2, items.size)) {
                val it = items[k]
                tile(row, it[0], it[1], it[2], it[3], it[4])
            }
            content.addView(row, LinearLayout.LayoutParams(-1, -2))
            i += 2
        }
    }

    private fun card(content: LinearLayout): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = colorBg(CARD, 16)
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        content.addView(c, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) })
        return c
    }

    private fun sectionTitle(parent: LinearLayout, text: String, color: String) {
        val t = label(text, 13f, true, color)
        t.setPadding(0, 0, 0, dp(8))
        parent.addView(t)
    }

    private fun switchRow(parent: LinearLayout, title: String, sub: String, key: String, def: Boolean, onChange: (() -> Unit)? = null) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(label(title, 16f, true))
        if (sub.isNotEmpty()) col.addView(label(sub, 12f, false, "#9AA3B5"))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this)
        sw.isChecked = prefs.bool(key, def)
        sw.setOnCheckedChangeListener { _, on ->
            prefs.setBool(key, on)
            onChange?.invoke()
        }
        row.addView(sw)
        parent.addView(row)
    }

    private fun destPage(content: LinearLayout) {
        val cg = card(content)
        sectionTitle(cg, "RECHERCHE GOOGLE (FACULTATIF)", GREEN)
        cg.addView(label("Avec une clé Google Places API (New), la recherche utilise Google en premier : entreprises, commerces, adresses. Crée-la sur console.cloud.google.com (Places API (New) à activer ; un crédit gratuit mensuel est offert).", 12f, false, "#9AA3B5"))
        val gk = EditText(this)
        gk.setText(prefs.str("googleKey", ""))
        gk.hint = "Clé API Google Places"
        gk.setHintTextColor(Color.parseColor("#6B7385"))
        gk.setTextColor(Color.WHITE)
        gk.setSingleLine(true)
        cg.addView(gk)
        cg.addView(button("Enregistrer", GREEN) {
            prefs.setStr("googleKey", gk.text.toString().trim())
            Geocoder.googleKey = gk.text.toString().trim()
            toast("Clé enregistrée")
        }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        val c = card(content)
        sectionTitle(c, "FAVORIS", GREEN)
        for ((key, icon, name) in listOf(Triple("home", "🏠", "Domicile"), Triple("work", "💼", "Travail"))) {
            val f = prefs.fav(key)
            val t = label(if (f != null) "$icon $name · ${f.name}" else "$icon $name · à définir", 16f)
            t.setPadding(0, dp(10), 0, dp(10))
            t.setOnClickListener {
                if (f != null) {
                    closeMenu()
                    goTo(f)
                } else {
                    pendingFav = key
                    closeMenu()
                    searchBox.requestFocus()
                    toast("Tape l'adresse de $name puis choisis-la")
                }
            }
            t.setOnLongClickListener {
                pendingFav = key
                closeMenu()
                toast("Cherche la nouvelle adresse de $name")
                true
            }
            c.addView(t)
        }
        val c2 = card(content)
        sectionTitle(c2, "RÉCENTS", GREEN)
        val rec = prefs.recents()
        if (rec.isEmpty()) c2.addView(label("Aucune destination pour l'instant", 14f, false, "#9AA3B5"))
        for (r in rec) {
            val t = label("🕘 ${r.name}", 16f)
            t.setPadding(0, dp(10), 0, dp(10))
            t.setOnClickListener {
                closeMenu()
                goTo(r)
            }
            c2.addView(t)
        }
    }

    private fun vehiclePage(content: LinearLayout) {
        val model = prefs.int("car", 0)
        val colHex = prefs.str("carColor", CarSprites.COLORS[0])
        val col = Color.parseColor(colHex)

        val c = card(content)
        sectionTitle(c, "APERÇU", ORANGE_L)
        val prev = LinearLayout(this)
        prev.orientation = LinearLayout.HORIZONTAL
        prev.gravity = Gravity.CENTER
        val rear = ImageView(this)
        rear.setImageBitmap(CarSprites.drawRear(model, col))
        val top = ImageView(this)
        top.setImageBitmap(CarSprites.draw(model, col))
        prev.addView(rear, LinearLayout.LayoutParams(0, dp(110), 1f))
        prev.addView(top, LinearLayout.LayoutParams(0, dp(110), 1f))
        c.addView(prev)
        prev.setOnClickListener { enlargeCar(model, col) }
        c.addView(label("Touche l'aperçu pour l'agrandir · vue 3D de derrière en navigation, du dessus en 2D", 12f, false, "#9AA3B5"))

        val c2 = card(content)
        sectionTitle(c2, "MODÈLE", ORANGE_L)
        val hs = HorizontalScrollView(this)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (i in CarSprites.NAMES.indices) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            item.setPadding(dp(6), dp(6), dp(6), dp(6))
            val g = GradientDrawable()
            g.cornerRadius = dp(14).toFloat()
            g.setColor(Color.parseColor(if (i == model) "#2A3550" else "#101A30"))
            if (i == model) g.setStroke(dp(2), Color.parseColor(ORANGE_L))
            item.background = g
            val iv = ImageView(this)
            iv.setImageBitmap(CarSprites.drawRear(i, col))
            item.addView(iv, LinearLayout.LayoutParams(dp(90), dp(72)))
            item.addView(label(CarSprites.NAMES[i], 12f, true))
            item.setOnClickListener {
                prefs.setInt("car", i)
                applyCar()
                showMenu("vehicle")
            }
            row.addView(item, LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = dp(8) })
        }
        hs.addView(row)
        c2.addView(hs)

        val c3 = card(content)
        sectionTitle(c3, "COULEUR", ORANGE_L)
        val hs2 = HorizontalScrollView(this)
        val crow = LinearLayout(this)
        crow.orientation = LinearLayout.HORIZONTAL
        for (hex in CarSprites.COLORS) {
            val dot = View(this)
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(Color.parseColor(hex))
            g.setStroke(dp(if (hex == colHex) 4 else 1), Color.parseColor(if (hex == colHex) "#FFFFFF" else "#55FFFFFF"))
            dot.background = g
            dot.setOnClickListener {
                prefs.setStr("carColor", hex)
                applyCar()
                showMenu("vehicle")
            }
            crow.addView(dot, LinearLayout.LayoutParams(dp(44), dp(44)).also { it.rightMargin = dp(10) })
        }
        hs2.addView(crow)
        c3.addView(hs2)

        val c4 = card(content)
        sectionTitle(c4, "TAILLE SUR LA CARTE", ORANGE_L)
        val size = prefs.float("carSize", 1f)
        val sizeLbl = label(String.format(Locale.FRANCE, "×%.1f", size), 16f, true)
        c4.addView(sizeLbl)
        val sb = SeekBar(this)
        sb.max = 250
        sb.progress = ((size - 0.5f) * 100).roundToInt().coerceIn(0, 250)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = 0.5f + p / 100f
                sizeLbl.text = String.format(Locale.FRANCE, "×%.1f", v)
                if (fromUser) {
                    prefs.setFloat("carSize", v)
                    applyCarMode()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        c4.addView(sb)
    }

    private fun routePage(content: LinearLayout) {
        val c = card(content)
        sectionTitle(c, "ÉVITER (voiture)", "#64B5F6")
        switchRow(c, "Péages", "Privilégie les routes sans péage", "avoidTolls", false) { if (dest != null) computeRoute(navigating) }
        switchRow(c, "Autoroutes", "Évite les voies rapides", "avoidHighways", false) { if (dest != null) computeRoute(navigating) }
        switchRow(c, "Ferries", "Évite les bacs et traversées", "avoidFerries", false) { if (dest != null) computeRoute(navigating) }
        val c2 = card(content)
        c2.addView(label("Les options s'appliquent au prochain calcul d'itinéraire (ou tout de suite si un trajet est affiché).", 12f, false, "#9AA3B5"))
    }

    private fun radarPage(content: LinearLayout) {
        val c = card(content)
        sectionTitle(c, "ALERTES", "#EF5350")
        switchRow(c, "Alertes vocales", "Annonce les radars à l'approche", "radarAlert", true)
        switchRow(c, "Radars fixes sur la carte", "Données officielles data.gouv.fr", "showFixed", true) { applyRadarVisibility() }
        val c2 = card(content)
        sectionTitle(c2, "ITINÉRAIRE", "#EF5350")
        switchRow(c2, "Éviter les radars", "Cherche un détour (voiture) sans radar fixe, si le trajet reste raisonnable", "avoidRadars", false) { if (dest != null) computeRoute(navigating) }
        val cl = card(content)
        sectionTitle(cl, "LUFOP · RADARS MOBILES, FEUX ROUGES…", "#EF5350")
        cl.addView(label("Clé API gratuite (200 requêtes par jour). Ajoute les radars mobiles, feux rouges et vitesse moyenne de la base Lufop.", 12f, false, "#9AA3B5"))
        val lk = EditText(this)
        lk.setText(prefs.str("lufopKey", ""))
        lk.hint = "Clé API Lufop"
        lk.setHintTextColor(Color.parseColor("#6B7385"))
        lk.setTextColor(Color.WHITE)
        lk.setSingleLine(true)
        cl.addView(lk)
        val lres = label(
            if (lufopLastError.isNotEmpty()) "Dernière erreur : $lufopLastError" else lufopStats.ifEmpty { "Aucune donnée reçue pour l'instant (attends la position GPS)." },
            12f, true, if (lufopLastError.isNotEmpty()) "#EF9A9A" else "#C0C7D6"
        )
        cl.addView(button("Enregistrer et tester", RED) {
            val k = lk.text.toString().trim()
            prefs.setStr("lufopKey", k)
            lufopCenter = null
            lufopLastTry = 0L
            lufopLastError = ""
            if (k.isBlank()) {
                lufopMap.clear()
                fixedRadars = baseRadars
                refreshRadarLayer()
                lres.text = "Clé retirée."
                lres.setTextColor(Color.WHITE)
                return@button
            }
            lres.text = "Test en cours…"
            lres.setTextColor(Color.WHITE)
            scope.launch {
                try {
                    val meta = Lufop.meta(k)
                    lres.text = "✔ Clé acceptée par Lufop. " + meta.take(160)
                    lres.setTextColor(Color.parseColor("#81C784"))
                    me?.let { maybeFetchLufop(it) }
                } catch (e: Exception) {
                    lres.text = "✘ Clé refusée ou Lufop injoignable : ${e.message}"
                    lres.setTextColor(Color.parseColor("#EF9A9A"))
                }
            }
        }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        cl.addView(lres)
        val credit = label(Lufop.CREDIT + "\nhttps://lufop.net", 12f, false, "#C0C7D6")
        android.text.util.Linkify.addLinks(credit, android.text.util.Linkify.WEB_URLS)
        credit.setLinkTextColor(Color.parseColor("#90CAF9"))
        cl.addView(credit)

        val c3 = card(content)
        sectionTitle(c3, "SERVEUR DE SIGNALEMENTS", "#EF5350")
        c3.addView(label("Radars mobiles, contrôles, dangers signalés par les utilisateurs.", 12f, false, "#9AA3B5"))
        val et = EditText(this)
        et.setText(serverUrl())
        et.hint = "https://mon-serveur.workers.dev"
        et.setHintTextColor(Color.parseColor("#6B7385"))
        et.setTextColor(Color.WHITE)
        et.setSingleLine(true)
        c3.addView(et)
        val save = button("Enregistrer", RED) {
            prefs.setStr("server", et.text.toString().trim())
            startPolling()
            toast("Serveur enregistré")
        }
        c3.addView(save, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
    }

    private fun guidePage(content: LinearLayout) {
        val c = card(content)
        sectionTitle(c, "VOIX ET VITESSE", "#90A4AE")
        switchRow(c, "Guidage vocal", "Consignes en français", "voice", true)
        switchRow(c, "Limitations de vitesse", "Panneau de limite d'après OpenStreetMap", "showLimit", true) { refreshLimitView() }
        switchRow(c, "Alerte de dépassement", "Prévient si tu dépasses la limite de plus de 3 km/h", "speedAlert", true)
        switchRow(c, "Bip de dépassement", "Signal sonore répété tant que tu dépasses", "speedBeep", true)
        val c2 = card(content)
        sectionTitle(c2, "AFFICHAGE", "#90A4AE")
        switchRow(c2, "Vue 3D en navigation", "Carte inclinée, voiture vue de derrière", "view3d", true) { applyCarMode() }
        switchRow(c2, "Nord en haut", "Garde la carte fixe au lieu de tourner avec toi", "northUp", false) { applyCarMode() }
        switchRow(c2, "Écran toujours allumé", "Pendant le guidage", "keepOn", true)
        switchRow(c2, "Fond satellite", "Photos aériennes (Esri) à la place du plan", "satellite", false) { applySatellite() }
        val c3 = card(content)
        sectionTitle(c3, "THÈME DE LA CARTE", "#90A4AE")
        val cur = prefs.str("theme", "auto")
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for ((key, name) in listOf("auto" to "🌗 Auto", "day" to "☀ Jour", "night" to "🌙 Nuit")) {
            val b = button(name, GREY) { prefs.setStr("theme", key); checkTheme(); showMenu("guide") }
            b.textSize = 14f
            b.background = glossBtn(if (key == cur) ORANGE_L else GREY)
            b.setTextColor(Color.parseColor(if (key == cur) "#0B1220" else "#FFFFFF"))
            row.addView(b, LinearLayout.LayoutParams(0, -2, 1f).also { it.rightMargin = dp(6) })
        }
        c3.addView(row)
        c3.addView(label("Auto : bascule selon le lever et le coucher du soleil à ta position.", 12f, false, "#9AA3B5"))
    }

    private fun aboutPage(content: LinearLayout) {
        val c = card(content)
        c.addView(label("DJSTEF GPS · v$VERSION", 18f, true))
        c.addView(label("Carte : OpenStreetMap et OpenFreeMap\nItinéraires : Valhalla / OSRM (OpenStreetMap)\nRadars fixes : data.gouv.fr (Licence Ouverte 2.0)\nTrafic : TomTom\nCarburants : data.economie.gouv.fr (Licence Ouverte)\nRadars : Lufop.net et contributeurs OpenStreetMap (ODbL 1.0), lufop.net\nParkings : OpenStreetMap (ODbL)\nVoiture « Concept 3D » : Concept Car © Eric Chadwick / Khronos, CC BY 4.0, base Unity Fan (CC0)\nLimitations de vitesse : OpenStreetMap (Overpass)\nAdresses : Nominatim\nSatellite : Esri World Imagery\nHeure d'arrivée avec trafic : TomTom\nRecherche : Photon (Komoot), Nominatim, annuaire des entreprises et Base Adresse Nationale (data.gouv.fr), TomTom (clé du trafic), Google Places (si clé)", 13f, false, "#C0C7D6"))
        val cb = card(content)
        sectionTitle(cb, "SAUVEGARDE DES RÉGLAGES", "#90A4AE")
        cb.addView(label("Copie tes réglages (clés API, voiture, favoris…) dans le presse-papiers, ou restaure-les après une réinstallation.", 12f, false, "#9AA3B5"))
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val bx = button("Copier mes réglages", BLUE) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("DJStef GPS", prefs.exportJson()))
            toast("Réglages copiés dans le presse-papiers")
        }
        val bi = button("Restaurer depuis le presse-papiers", GREY) {
            val t = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            val n = prefs.importJson(t)
            toast(if (n > 0) "$n réglages restaurés" else "Rien à restaurer dans le presse-papiers")
            if (n > 0) { applyCar(); applySatellite(); checkTheme() }
        }
        cb.addView(bx, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        cb.addView(bi, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        val c2 = card(content)
        c2.addView(label("Les limitations de vitesse et les radars peuvent être incomplets ou périmés : respecte toujours la signalisation sur la route.", 13f, false, "#9AA3B5"))
    }
}
