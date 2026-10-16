package app.shaqati.core.text

import app.shaqati.core.model.Audience
import app.shaqati.core.model.Feature
import app.shaqati.core.model.Furnishing
import app.shaqati.core.model.RentPeriod
import kotlinx.serialization.Serializable

/** A matched phrase and the weight it contributes to the renovation signal. */
@Serializable
data class SignalHit(val phrase: String, val weight: Int)

/** What the ad text says, independent of the structured fields a site publishes. */
@Serializable
data class TextFacts(
    val renovationScore: Int = 0,
    val positive: List<SignalHit> = emptyList(),
    val negative: List<SignalHit> = emptyList(),
    val furnishing: Furnishing = Furnishing.UNKNOWN,
    val audience: Audience = Audience.UNKNOWN,
    val bedrooms: Int? = null,
    val livingRooms: Int? = null,
    val bathrooms: Int? = null,
    val hasMajlis: Boolean = false,
    val kitchenMentioned: Boolean = false,
    val kitchenFitted: Boolean? = null,
    val noKitchen: Boolean = false,
    val period: RentPeriod = RentPeriod.UNKNOWN,
    val features: Set<String> = emptySet(),
    val firstTenant: Boolean = false,
)

object TextSignals {
    private class Rule(val pattern: Regex, val weight: Int, val label: String)

    private fun rule(p: String, w: Int, label: String = p) = Rule(Regex(p), w, label)

    // Patterns run on ArabicText.normalize(...) output (ة→ه, أ→ا, ى→ي, lowercase Latin).
    private val positiveRules = listOf(
        rule("اول ساكن|ما سكنت|لم تسكن|ما سكنها احد|لم يسكنها|جديده لم|first tenant|never (been )?(lived|occupied)", 4, "first tenant"),
        rule("(ترميم|تجديد) (كامل|شامل|جذري)|مجدده بالكامل|مجدد بالكامل|مرممه بالكامل|fully renovated|completely renovated", 4, "fully renovated"),
        rule("brand new|جديده كليا|جديد كليا", 3, "brand new"),
        rule("مجدده|مجدد(?!ه)|مرممه|مرمم(?!ه)|(?<!(تحتاج|يحتاج|بحاجه|تحتاج الى|يحتاج الى|بحاجه الى) )(ترميم|تجديد)|(?<!needs? )renovated|refurbished", 3, "renovated"),
        rule("مودرن|عصري|عصريه|modern|contemporary", 2, "modern style"),
        rule("تشطيب (حديث|فاخر|ممتاز|راقي|سوبر لوكس)|تشطيبات (حديثه|فاخره|راقيه)|سوبر لوكس|luxury finish|high[- ]end finish", 2, "high-end finish"),
        rule("حديثه البناء|حديث البناء|بناء حديث|عماره جديده|عمارة جديدة|مبنى جديد|new building|newly built", 2, "new building"),
        rule("اضاءه مخفيه|انارة مخفيه|اناره مخفيه|اضاءه ليد|اناره ليد|ليد مخفي|hidden light|indirect light|led strip", 2, "hidden / LED lighting"),
        rule("سبوت ?لايت|سبوتات|سبوت|spot ?lights?|downlights?", 2, "spotlights"),
        rule("جبس ?بورد|جبسن ?بورد|اسقف جبس|سقف جبس|جبس|gypsum|false ceiling", 2, "gypsum ceiling"),
        rule("بورسلان|بورسلين|porcelain", 2, "porcelain floor"),
        rule("باركيه|باركيت|spc|فينيل|vinyl|parquet|laminate", 1, "parquet / SPC floor"),
        rule("رخام|marble", 1, "marble"),
        rule("دهان جديد|دهانات جديده|بويه جديده|مدهونه جديد|fresh(ly)? paint|newly painted", 2, "fresh paint"),
        rule("ابواب جديده|ابواب حديثه|new doors", 1, "new doors"),
        rule("مطبخ جديد|مطبخ حديث|مطبخ مودرن|new kitchen", 1, "new kitchen"),
        rule("(دورات مياه|حمامات|دوره مياه) (جديده|حديثه|مودرن)|new bathroom", 1, "new bathrooms"),
        rule("سمارت|منزل ذكي|انظمه ذكيه|نظام ذكي|smart home", 1, "smart home"),
        rule("سيكوريت|زجاج سكوريت|tempered glass", 1, "modern glass"),
        rule("نظيفه جدا|نظيف جدا|very clean|immaculate", 1, "very clean"),
    )

    private val negativeRules = listOf(
        rule("تحتاج (الى )?(ترميم|صيانه|تجديد)|يحتاج (الى )?(ترميم|صيانه|تجديد)|بحاجه (الى )?(ترميم|صيانه|تجديد)|needs? (renovation|repair|maintenance)", -4, "needs renovation"),
        rule("على (وضعها|حالها|وضعه|حاله)|as is", -2, "as-is condition"),
        rule("قديمه|قديم(?!ه)|old building|older building", -2, "described as old"),
        rule("موزاييك|موزايك|بلاط قديم|terrazzo", -3, "old tiles"),
    )

    private val featureRules = listOf(
        Regex("مصعد|اسانسير|elevator|lift") to Feature.ELEVATOR,
        Regex("موقف|مواقف|parking|garage|كراج") to Feature.PARKING,
        Regex("مكيفات|مكيف|تكييف|split ac|air condition") to Feature.AC,
        Regex("غرفه خادمه|غرفة خادمة|غرفه شغاله|maid'?s? room") to Feature.MAID_ROOM,
        Regex("غرفه سائق|غرفة سائق|driver'?s? room") to Feature.DRIVER_ROOM,
        Regex("مدخل (خاص|مستقل)|private entrance|separate entrance") to Feature.PRIVATE_ENTRANCE,
        Regex("عداد (كهرباء )?(مستقل|خاص|منفصل)|عدادات مستقله|separate (electric(ity)? )?meter") to Feature.SEPARATE_METER,
        Regex("سطح خاص|سطح مستقل|private roof") to Feature.ROOF,
        Regex("حوش|فناء|حديقه خاصه|yard|garden") to Feature.YARD,
        Regex("مسبح|pool") to Feature.POOL,
        Regex("مستودع|غرفه تخزين|storage") to Feature.STORAGE,
        Regex("بلكونه|بلكون|شرفه|balcony|terrace") to Feature.BALCONY,
        Regex("سمارت|منزل ذكي|smart home") to Feature.SMART_HOME,
    )

    private const val NUM = "(\\d{1,2}|واحد|واحده|اثنين|اثنان|ثلاث|ثلاثه|اربع|اربعه|خمس|خمسه|ست|سته)"

    private val bedroomPatterns = listOf(
        Regex("$NUM\\s*(غرف|غرفه|غرفات)(\\s*نوم)?"),
        Regex("(غرف|غرفه)\\s*(النوم\\s*)?(عدد\\s*)?:?\\s*(\\d{1,2})"),
        Regex("(\\d{1,2})\\s*(bed(room)?s?|br|bhk)\\b"),
    )
    private val twoBedrooms = Regex("غرفتين|غرفتان|غرفتي نوم|two bed(room)?s?")
    private val oneBedroom = Regex("غرفه (نوم )?(و|\\+) ?صال|غرفه وحده|غرفه واحده|one bed(room)?|1 ?bhk")
    private val livingPatterns = listOf(
        Regex("$NUM\\s*(صالات|صاله)"),
        Regex("(\\d{1,2})\\s*(living rooms?|halls?|lounges?)\\b"),
    )
    private val twoLiving = Regex("صالتين|صالتان")
    private val anyLiving = Regex("صاله|صالة|living room|lounge|\\bhall\\b")
    private val bathroomPatterns = listOf(
        Regex("$NUM\\s*(دورات|دوره|دورة|حمامات|حمام)(\\s*مياه)?"),
        Regex("(دورات المياه|الحمامات)\\s*:?\\s*(\\d{1,2})"),
        Regex("(\\d{1,2})\\s*(bath(room)?s?|wc|toilets?)\\b"),
    )
    private val twoBathrooms = Regex("دورتين|دورتان|دورتي مياه|حمامين|حمامان|two bath(room)?s?")
    private val majlis = Regex("مجلس|majlis|guest room|diwan|ديوانيه")
    private val kitchenWord = Regex("مطبخ|kitchen")
    private val kitchenFitted = Regex("مطبخ (راكب|مركب|جاهز|مجهز|كامل|مع الاجهزه)|مع المطبخ|المطبخ راكب|fitted kitchen|equipped kitchen|kitchen installed|built[- ]in kitchen")
    private val kitchenEmpty = Regex("بدون مطبخ|مطبخ (فاضي|فارغ|بدون)|unfitted kitchen|no kitchen cabinets")
    private val noKitchen = Regex("لا يوجد مطبخ|بدون مكان مطبخ|no kitchen\\b")

    private val furnished = Regex("(?<!شبه |غير |بدون |نصف )(مفروشه|مفروش|مؤثثه|مؤثث)|fully furnished|(?<!semi[- ]|un)furnished")
    private val semiFurnished = Regex("شبه (مفروشه|مفروش|مؤثثه|مؤثث)|نصف (مفروشه|مؤثثه)|semi[- ]furnished|مطبخ (راكب|مركب)|مكيفات (راكبه|مركبه)|مع المكيفات")
    private val unfurnished = Regex("غير (مفروشه|مفروش|مؤثثه|مؤثث)|بدون (فرش|اثاث|عفش)|unfurnished|not furnished|فاضيه|فارغه")

    private val families = Regex("عوائل|عائلات|للعوائل|عائلي|عائليه|families|family only|for famil")
    private val singles = Regex("عزاب|للعزاب|عزوبيه|شباب|bachelors?|singles?")
    private val familiesOnly = Regex("(عوائل|للعوائل|عائلات) فقط|families only|family only|لا يوجد عزاب|ممنوع العزاب|no bachelors")
    private val singlesOnly = Regex("(عزاب|للعزاب|شباب) فقط|bachelors? only|singles only")

    private val yearly = Regex("سنوي|سنويا|بالسنه|في السنه|للسنه|/ ?سنه|yearly|per year|annual|/year|a year|دفعتين|دفعات|دفعه واحده")
    private val monthly = Regex("شهري|شهريا|بالشهر|في الشهر|للشهر|/ ?شهر|monthly|per month|/month|a month")
    private val daily = Regex("يومي|يوميا|بالليله|لليله|per night|daily")

    fun analyze(vararg texts: String?): TextFacts {
        val t = texts.filterNotNull().joinToString(" \n ") { ArabicText.normalize(it) }
        if (t.isBlank()) return TextFacts()

        val pos = positiveRules.mapNotNull { r -> r.pattern.find(t)?.let { SignalHit(r.label, r.weight) } }
        val neg = negativeRules.mapNotNull { r ->
            r.pattern.find(t)?.let { m ->
                // "قديم" inside "تقديم" or "تم تجديد القديم" style phrases is not a condition.
                if (r.label == "described as old" && isFalseOld(t, m.range.first)) null else SignalHit(r.label, r.weight)
            }
        }
        val score = pos.sumOf { it.weight } + neg.sumOf { it.weight }

        val furn = when {
            unfurnished.containsMatchIn(t) -> Furnishing.UNFURNISHED
            semiFurnished.containsMatchIn(t) && !furnishedStrong(t) -> Furnishing.SEMI_FURNISHED
            furnished.containsMatchIn(t) -> Furnishing.FURNISHED
            else -> Furnishing.UNKNOWN
        }
        val aud = when {
            familiesOnly.containsMatchIn(t) -> Audience.FAMILIES
            singlesOnly.containsMatchIn(t) -> Audience.SINGLES
            families.containsMatchIn(t) && singles.containsMatchIn(t) -> Audience.ANY
            families.containsMatchIn(t) -> Audience.FAMILIES
            singles.containsMatchIn(t) -> Audience.SINGLES
            else -> Audience.UNKNOWN
        }

        val beds = maxCount(t, bedroomPatterns, excludeAround = Regex("خادمه|سائق|غسيل|تخزين|maid|driver|laundry|storage"))
            ?: when {
                twoBedrooms.containsMatchIn(t) -> 2
                oneBedroom.containsMatchIn(t) -> 1
                else -> null
            }
        val living = maxCount(t, livingPatterns) ?: when {
            twoLiving.containsMatchIn(t) -> 2
            anyLiving.containsMatchIn(t) -> 1
            else -> null
        }
        val baths = maxCount(t, bathroomPatterns) ?: if (twoBathrooms.containsMatchIn(t)) 2 else null

        val fitted = when {
            kitchenFitted.containsMatchIn(t) -> true
            kitchenEmpty.containsMatchIn(t) -> false
            else -> null
        }
        val period = when {
            daily.containsMatchIn(t) && !yearly.containsMatchIn(t) && !monthly.containsMatchIn(t) -> RentPeriod.DAILY
            yearly.containsMatchIn(t) && !monthly.containsMatchIn(t) -> RentPeriod.YEARLY
            monthly.containsMatchIn(t) && !yearly.containsMatchIn(t) -> RentPeriod.MONTHLY
            else -> RentPeriod.UNKNOWN
        }
        val features = featureRules.filter { it.first.containsMatchIn(t) }.map { it.second }.toMutableSet()
        if (fitted == true) features += Feature.KITCHEN_FITTED

        return TextFacts(
            renovationScore = score,
            positive = pos,
            negative = neg,
            furnishing = furn,
            audience = aud,
            bedrooms = beds,
            livingRooms = living,
            bathrooms = baths,
            hasMajlis = majlis.containsMatchIn(t),
            kitchenMentioned = kitchenWord.containsMatchIn(t),
            kitchenFitted = fitted,
            noKitchen = noKitchen.containsMatchIn(t),
            period = period,
            features = features,
            firstTenant = pos.any { it.phrase == "first tenant" },
        )
    }

    private fun furnishedStrong(t: String) = Regex("(?<!شبه |غير |بدون |نصف )مفروشه بالكامل|fully furnished|مفروشه فرش كامل").containsMatchIn(t)

    private fun isFalseOld(t: String, at: Int): Boolean {
        val start = (at - 6).coerceAtLeast(0)
        val window = t.substring(start, (at + 10).coerceAtMost(t.length))
        return window.contains("تقديم") || window.contains("تم تجديد") || window.contains("بدل القديم")
    }

    private fun maxCount(t: String, patterns: List<Regex>, excludeAround: Regex? = null): Int? {
        var best: Int? = null
        for (p in patterns) {
            for (m in p.findAll(t)) {
                if (excludeAround != null) {
                    val after = t.substring(m.range.last + 1, (m.range.last + 14).coerceAtMost(t.length))
                    if (excludeAround.containsMatchIn(after)) continue
                }
                val n = m.groupValues.drop(1).firstNotNullOfOrNull { g -> ArabicText.numberWord(g.trim()) } ?: continue
                if (n in 1..12) best = maxOf(best ?: 0, n)
            }
        }
        return best
    }
}
