package app.shaqati.core

import app.shaqati.core.model.Audience
import app.shaqati.core.model.Feature
import app.shaqati.core.model.Furnishing
import app.shaqati.core.model.RentPeriod
import app.shaqati.core.text.ArabicText
import app.shaqati.core.text.TextSignals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextSignalsTest {
    @Test
    fun `normalizes Arabic letters, diacritics and digits`() {
        assertEquals("مجدده بالكامل 3 غرف", ArabicText.normalize("مجدّدة   بالكامل ٣ غرف"))
        assertEquals("اول ساكن", ArabicText.normalize("أوّل ساكن"))
        assertEquals("45000", ArabicText.asciiDigits("٤٥٠٠٠"))
    }

    @Test
    fun `renovated ad scores positive with readable reasons`() {
        val f = TextSignals.analyze(
            "شقة للإيجار مجددة بالكامل",
            "شقة أول ساكن، إضاءة مخفية وسبوت لايت، أرضيات بورسلان، جبس بورد، دهان جديد",
        )
        assertTrue(f.renovationScore >= 12, "score ${f.renovationScore}")
        assertTrue(f.firstTenant)
        val labels = f.positive.map { it.phrase }
        assertTrue("hidden / LED lighting" in labels)
        assertTrue("porcelain floor" in labels)
        assertTrue("gypsum ceiling" in labels)
        assertTrue(f.negative.isEmpty())
    }

    @Test
    fun `old or needs-renovation ad scores negative`() {
        val f = TextSignals.analyze("شقة قديمة تحتاج ترميم", "البلاط موزاييك")
        assertTrue(f.renovationScore < 0, "score ${f.renovationScore}")
        assertTrue(f.negative.any { it.phrase == "needs renovation" })
    }

    @Test
    fun `furnishing is classified with negations first`() {
        assertEquals(Furnishing.UNFURNISHED, TextSignals.analyze("شقة غير مفروشة").furnishing)
        assertEquals(Furnishing.SEMI_FURNISHED, TextSignals.analyze("شقة شبه مفروشة").furnishing)
        assertEquals(Furnishing.FURNISHED, TextSignals.analyze("شقة مفروشة للإيجار").furnishing)
        assertEquals(Furnishing.SEMI_FURNISHED, TextSignals.analyze("المطبخ راكب والمكيفات راكبة").furnishing)
        assertEquals(Furnishing.UNKNOWN, TextSignals.analyze("شقة للإيجار").furnishing)
    }

    @Test
    fun `audience detection`() {
        assertEquals(Audience.FAMILIES, TextSignals.analyze("للعوائل فقط").audience)
        assertEquals(Audience.SINGLES, TextSignals.analyze("للعزاب فقط").audience)
        assertEquals(Audience.ANY, TextSignals.analyze("عوائل وعزاب").audience)
    }

    @Test
    fun `room and bathroom counts from text`() {
        val f = TextSignals.analyze("شقة 3 غرف نوم وصالة ومجلس و دورتين مياه ومطبخ")
        assertEquals(3, f.bedrooms)
        assertEquals(1, f.livingRooms)
        assertEquals(2, f.bathrooms)
        assertTrue(f.hasMajlis)
        assertTrue(f.kitchenMentioned)

        val g = TextSignals.analyze("غرفتين وصالة وحمامين")
        assertEquals(2, g.bedrooms)
        assertEquals(2, g.bathrooms)

        val h = TextSignals.analyze("ثلاث غرف وصالتين و ٣ دورات مياه")
        assertEquals(3, h.bedrooms)
        assertEquals(2, h.livingRooms)
        assertEquals(3, h.bathrooms)
    }

    @Test
    fun `maid room is not counted as a bedroom`() {
        val f = TextSignals.analyze("1 غرفة خادمة و 2 غرف نوم")
        assertEquals(2, f.bedrooms)
        assertTrue(Feature.MAID_ROOM in f.features)
    }

    @Test
    fun `english ads`() {
        val f = TextSignals.analyze("Newly renovated 2 bedroom apartment, 2 bathrooms, unfurnished, spotlights, families only")
        assertEquals(2, f.bedrooms)
        assertEquals(2, f.bathrooms)
        assertEquals(Furnishing.UNFURNISHED, f.furnishing)
        assertEquals(Audience.FAMILIES, f.audience)
        assertTrue(f.renovationScore > 0)
    }

    @Test
    fun `rent period`() {
        assertEquals(RentPeriod.YEARLY, TextSignals.analyze("الإيجار السنوي 45 ألف").period)
        assertEquals(RentPeriod.MONTHLY, TextSignals.analyze("الإيجار الشهري 3500").period)
        assertEquals(RentPeriod.YEARLY, TextSignals.analyze("الدفع على دفعتين").period)
        assertEquals(RentPeriod.UNKNOWN, TextSignals.analyze("شهري أو سنوي").period)
    }

    @Test
    fun `kitchen states`() {
        assertEquals(true, TextSignals.analyze("المطبخ راكب").kitchenFitted)
        assertEquals(false, TextSignals.analyze("بدون مطبخ").kitchenFitted)
        assertFalse(TextSignals.analyze("بدون مطبخ").noKitchen)
        assertTrue(TextSignals.analyze("لا يوجد مطبخ").noKitchen)
        assertNull(TextSignals.analyze("شقة").kitchenFitted)
    }

    @Test
    fun `presenting the old is not a negative`() {
        val f = TextSignals.analyze("تقديم الطلبات عبر الواتساب")
        assertTrue(f.negative.isEmpty())
    }
}
