package net.meshsat.android

import net.meshsat.android.satellite.PassPredictor
import net.meshsat.android.satellite.TleFetcher
import net.meshsat.android.satellite.TleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Passes are predicted with no network, from the Iridium elements shipped in the app
 * (MESHSAT-1240), and a download only replaces them when it is newer.
 */
class OfflinePassPredictionTest {

    private val bundled = TleParser.parseMulti(File("src/main/assets/${TleFetcher.BUNDLED_ASSET}").readText())

    @Test
    fun `the shipped snapshot holds the Iridium NEXT constellation`() {
        assertTrue("got ${bundled.size}", bundled.size >= 66)
        assertTrue(bundled.all { it.name.startsWith("IRIDIUM") })
    }

    @Test
    fun `the TLE API fallback keeps Iridium NEXT and drops the first generation and debris`() {
        assertTrue(TleFetcher.isIridiumNext("IRIDIUM 106"))
        assertTrue(TleFetcher.isIridiumNext("IRIDIUM 180 "))
        assertTrue(!TleFetcher.isIridiumNext("IRIDIUM 7"))
        assertTrue(!TleFetcher.isIridiumNext("IRIDIUM 33 DEB"))
        assertTrue(!TleFetcher.isIridiumNext("IRIDIUM 97"))
        // The filter reproduces Celestrak's iridium-NEXT group exactly.
        assertTrue(bundled.all { TleFetcher.isIridiumNext(it.name) })
    }

    @Test
    fun `the newer set wins, and the snapshot fills in when nothing was downloaded`() {
        val older = bundled.map { it.copy(epochJd = it.epochJd - 30) }
        assertEquals(TleFetcher.Source.Bundled, TleFetcher.choose(emptyList(), bundled).source)
        assertEquals(TleFetcher.Source.Bundled, TleFetcher.choose(older, bundled).source)
        assertEquals(TleFetcher.Source.Downloaded, TleFetcher.choose(bundled, older).source)
        assertEquals(TleFetcher.Source.None, TleFetcher.choose(emptyList(), emptyList()).source)
    }

    @Test
    fun `passes over Leiden are predicted from the snapshot alone`() {
        val start = TleFetcher.newestEpochUnix(bundled)
        val all = PassPredictor.predictAllPasses(bundled, 52.16, 4.51, 0.0, start, start + 6 * 3600L, 5.0)
        val high = PassPredictor.predictAllPasses(bundled, 52.16, 4.51, 0.0, start, start + 6 * 3600L, 60.0)
        assertTrue("got ${all.size} passes in 6 h", all.size > 20)
        assertTrue("got ${high.size} passes above 60 degrees", high.isNotEmpty() && high.size < all.size)
        assertTrue(all.all { it.losUnix > it.aosUnix })
    }
}
