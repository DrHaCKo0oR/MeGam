package app.shaqati.core

import app.shaqati.core.util.MapLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapLinksTest {
    @Test
    fun `reads coordinates from the common Google Maps link shapes`() {
        val place = MapLinks.parse("https://www.google.com/maps/place/Building/@24.7701,46.6801,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d24.765512!4d46.680234")!!
        assertEquals(24.765512, place.lat, 1e-9)
        assertEquals(46.680234, place.lng, 1e-9)
        assertEquals(24.7712, MapLinks.parse("https://maps.google.com/?q=24.7712,46.6823")!!.lat, 1e-9)
        assertEquals(46.69, MapLinks.parse("https://www.google.com/maps/search/24.7801,+46.6900?entry=tts")!!.lng, 1e-9)
        assertEquals(24.75, MapLinks.parse("24.750, 46.700")!!.lat, 1e-9)
        assertNull(MapLinks.parse("https://maps.app.goo.gl/AbCdEf"))
        assertTrue(MapLinks.isShortLink("look https://maps.app.goo.gl/AbCdEf?g_st=ic"))
        assertEquals("https://maps.app.goo.gl/AbCdEf?g_st=ic", MapLinks.firstUrl("look https://maps.app.goo.gl/AbCdEf?g_st=ic"))
    }
}
