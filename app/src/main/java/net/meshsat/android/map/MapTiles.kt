package net.meshsat.android.map

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.meshsat.android.ui.theme.MeshSatBg
import net.meshsat.android.ui.theme.MeshSatSurface
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.MapTileRequestState
import org.osmdroid.tileprovider.modules.IArchiveFile
import org.osmdroid.tileprovider.modules.MBTilesFileArchive
import org.osmdroid.tileprovider.modules.MapTileApproximater
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** A detailed offline map the user added in Setup > Maps, checked to be one the map can draw. */
data class DetailedMap(val file: File, val name: String)

/**
 * Where the map's tiles come from (MESHSAT-1249, B5). Both the Map tab and Zones use this, so they
 * behave the same off-grid:
 *
 * 1. the user's detailed MBTiles file, when one is chosen in Setup > Maps;
 * 2. OpenStreetMap tiles saved from earlier online use, then downloaded ones when there is internet;
 * 3. the bundled world overview (assets/world.mbtiles, Natural Earth, zoom 0 to 3) when a download
 *    fails, so the map is never blank without a network. Deeper zooms are scaled up from it.
 *
 * The app has no ACCESS_NETWORK_STATE permission, so "offline" is not asked of the system: it is what
 * the tile downloader reports. Three failed downloads in a row mark the map offline, one success
 * clears it. When every tile on screen is already saved nothing is downloaded, and the map is not
 * called offline because nothing is missing.
 */
object MapTiles {
    private const val TAG = "MapTiles"
    private const val FAILURES_BEFORE_OFFLINE = 3

    private val consecutiveFailures = AtomicInteger(0)
    private val _offline = MutableStateFlow(false)

    /** True while online tiles cannot be downloaded and the offline tiles stand in. */
    val offline: StateFlow<Boolean> = _offline

    /**
     * A dark treatment for light map tiles: colours keep their hue (water stays blue, parks green),
     * with saturation cut to 40 % and brightness to half, and a small lift so the already dark
     * world overview keeps its coastlines. The old filter inverted every colour.
     */
    val DIM_TILES: ColorMatrixColorFilter = ColorMatrixColorFilter(
        ColorMatrix().apply {
            setSaturation(0.4f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        0.5f, 0f, 0f, 0f, 12f,
                        0f, 0.5f, 0f, 0f, 12f,
                        0f, 0f, 0.5f, 0f, 12f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        },
    )

    @Volatile
    private var configured = false

    /** osmdroid's own settings; must run before any map or tile provider is made. */
    fun configure(context: Context) {
        if (configured) return
        Configuration.getInstance().apply {
            userAgentValue = "MeshSat-Android"
            osmdroidBasePath = File(context.filesDir, "osmdroid")
            osmdroidTileCache = File(context.filesDir, "osmdroid/tiles")
            osmdroidBasePath.mkdirs()
            osmdroidTileCache.mkdirs()
        }
        configured = true
    }

    /** The bundled world overview, copied out of the APK once (about 250 KB). */
    fun worldFile(context: Context): File? {
        MBTilesManager.ensureBundledMapSync(context)
        return MBTilesManager.getMBTilesFile(context, MBTilesManager.BUNDLED_WORLD_MAP)
    }

    /**
     * The chosen detailed map, or null when the file is missing, unreadable or holds vector tiles
     * (osmdroid draws raster tiles only). Opens the file, so call it off the main thread.
     */
    fun detailedMap(context: Context, filename: String): DetailedMap? {
        if (filename.isBlank() || filename == MBTilesManager.BUNDLED_WORLD_MAP) return null
        val file = MBTilesManager.getMBTilesFile(context, filename) ?: return null
        return try {
            MBTilesReader.open(file).use { reader ->
                if (reader.isVector) null else DetailedMap(file, reader.name.ifBlank { file.nameWithoutExtension })
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot use offline map $filename: ${e.message}")
            null
        }
    }

    /** A map view on the MeshSat tiles, dark, with the stock zoom buttons hidden (the screens draw 48 dp ones). */
    fun newMapView(context: Context, detailed: File? = null): MapView {
        configure(context)
        val provider = MeshSatTileProvider(context.applicationContext, worldFile(context), detailed)
        return MapView(context, provider).apply {
            setMultiTouchControls(true)
            setBackgroundColor(MeshSatBg.toArgb())
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setMinZoomLevel(2.0)
            clipToOutline = true
            styleTiles(this)
        }
    }

    /** Switches the map to [detailed] (or back to none). Does nothing when it already shows that file. */
    fun useDetailed(context: Context, mapView: MapView, detailed: File?) {
        val current = mapView.tileProvider as? MeshSatTileProvider
        if (current != null && current.detailedPath == detailed?.path) return
        configure(context)
        // setTileProvider detaches the old provider and makes a new tiles overlay, so restyle it.
        mapView.setTileProvider(MeshSatTileProvider(context.applicationContext, worldFile(context), detailed))
        styleTiles(mapView)
    }

    private fun styleTiles(mapView: MapView) {
        val tiles = mapView.overlayManager.tilesOverlay
        tiles.setColorFilter(DIM_TILES)
        tiles.setLoadingBackgroundColor(MeshSatBg.toArgb())
        tiles.setLoadingLineColor(MeshSatSurface.toArgb())
    }

    internal fun reportDownload(ok: Boolean) {
        if (ok) {
            consecutiveFailures.set(0)
            if (_offline.value) _offline.value = false
        } else if (consecutiveFailures.incrementAndGet() >= FAILURES_BEFORE_OFFLINE && !_offline.value) {
            _offline.value = true
        }
    }

    internal fun openArchive(file: File): IArchiveFile? = try {
        MBTilesFileArchive.getDatabaseFileArchive(file)
    } catch (e: Exception) {
        Log.w(TAG, "Cannot open ${file.name}: ${e.message}")
        null
    }
}

/**
 * osmdroid's standard provider for OpenStreetMap (saved tiles, scaled stand-ins, downloads) with two
 * archives added: the user's detailed map in front of everything, and the world overview after the
 * downloader, so it answers only when a download fails. Both also feed the scaler, which draws a
 * deeper zoom from their tiles when nothing better exists.
 */
class MeshSatTileProvider(
    context: Context,
    world: File?,
    detailed: File?,
) : MapTileProviderBasic(context, TileSourceFactory.MAPNIK) {

    /** The detailed map this provider was made for, so a settings change can tell whether to rebuild. */
    val detailedPath: String? = detailed?.path

    init {
        val receiver = SimpleRegisterReceiver(context)
        val scaler = mTileProviderList.firstOrNull { it is MapTileApproximater } as? MapTileApproximater
        detailed?.let { MapTiles.openArchive(it) }?.let { archive ->
            val provider = MapTileFileArchiveProvider(receiver, tileSource, arrayOf(archive))
            mTileProviderList.add(0, provider)
            scaler?.addProvider(provider)
        }
        world?.let { MapTiles.openArchive(it) }?.let { archive ->
            val provider = MapTileFileArchiveProvider(receiver, tileSource, arrayOf(archive))
            mTileProviderList.add(provider)
            scaler?.addProvider(provider)
        }
    }

    override fun mapTileRequestCompleted(aState: MapTileRequestState, aDrawable: Drawable?) {
        if (aState.currentProvider is MapTileDownloader) MapTiles.reportDownload(ok = true)
        super.mapTileRequestCompleted(aState, aDrawable)
    }

    override fun mapTileRequestFailed(aState: MapTileRequestState) {
        if (aState.currentProvider is MapTileDownloader) MapTiles.reportDownload(ok = false)
        super.mapTileRequestFailed(aState)
    }
}
