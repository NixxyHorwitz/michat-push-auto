package com.michat.automator.util

import android.graphics.Bitmap
import android.graphics.Rect
import java.util.Locale

object GenderAnalyzer {

    enum class Gender {
        FEMALE,
        MALE,
        UNKNOWN
    }

    // Kumpulan Nama Khas Perempuan Indonesia
    private val FEMALE_NAME_DICT = hashSetOf(
        "dinda", "saskia", "windy", "bella", "rein", "ciya", "lila", "amel", "amelia",
        "siti", "nur", "dewi", "putri", "ayu", "anisa", "annisa", "aulia", "cantika",
        "chintya", "clara", "cindy", "dara", "dian", "dina", "elisa", "fanny", "fiona",
        "fitri", "gina", "grace", "hana", "icha", "indah", "intan", "jessica", "kartika",
        "lisa", "maya", "mega", "mira", "monica", "mutia", "nadia", "nabila", "natasya",
        "nia", "nita", "novi", "novita", "olivia", "rahma", "rani", "rara", "ratna",
        "renata", "ria", "rina", "safitri", "sari", "selly", "selvi", "sheila", "sherly",
        "silvia", "siska", "suci", "tania", "tiara", "tika", "vanessa", "vina", "wulan",
        "zahra", "lia", "mia", "via", "tia", "fifi", "caca", "kiki", "dedek", "neng",
        "cici", "sis", "mbak", "mami", "bunda", "mama", "camel", "hijab", "rere", "icha",
        "mimi", "chika", "angel", "angie", "syifa", "carmel", "tita", "manda", "mutiara",
        "salwa", "nayla", "tasya", "intania", "adinda", "widi", "widya", "nadya", "restu",
        "fauziah", "khairun", "azizah", "karina", "novia", "jesika", "yola", "yolanda",
        "febby", "melly", "michele", "michelle", "natasha", "kezia", "valen", "valentina"
    )

    // Kumpulan Nama Khas Laki-Laki Indonesia
    private val MALE_NAME_DICT = hashSetOf(
        "putra", "syafutra", "daniel", "maykel", "michael", "al", "budi", "agus", "dimas",
        "fajar", "reza", "rizky", "bayu", "eko", "hendra", "indra", "arif", "arief", "joko",
        "bambang", "kevin", "rendy", "adit", "aditya", "yoga", "wahyu", "satria", "ilham",
        "teguh", "danang", "bima", "fauzi", "alvin", "koko", "mas", "bang", "om", "pak",
        "bro", "hendri", "hafiz", "doni", "donny", "ferry", "anton", "iwan", "wawan",
        "yanto", "antonio", "david", "alex", "alexander", "rio", "gilang", "dika", "andika"
    )

    // Kata Kunci Bio/Signature Khas Profil Perempuan
    private val FEMALE_BIO_KEYWORDS = hashSetOf(
        "bobo", "hotel", "sayang", "cantik", "ready", "manja", "cewe", "cewek", "muasin",
        "open", "openn", "vcs", "bo", "gadis", "panggilan", "bece", "manis", "stay", "avail",
        "available", "room", "kost", "exclude", "include", "real", "trusted"
    )

    /**
     * Menganalisis Gender dari Warna Ikon Gender di Samping Nama
     * Icon MiChat:
     * - Pink / Merah muda (♀): Red > 200, Red - Green > 50, Red - Blue > 50
     * - Biru muda (♂): Blue > 200, Blue - Red > 50
     */
    fun analyzeGenderFromIconColor(bitmap: Bitmap, bounds: Rect): Gender {
        var pinkCount = 0
        var blueCount = 0

        val startX = bounds.left.coerceAtLeast(0)
        val endX = bounds.right.coerceAtMost(bitmap.width)
        val startY = bounds.top.coerceAtLeast(0)
        val endY = bounds.bottom.coerceAtMost(bitmap.height)

        if (startX >= endX || startY >= endY) return Gender.UNKNOWN

        for (x in startX until endX) {
            for (y in startY until endY) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Pink (Female)
                if (r > 190 && (r - g) > 40 && (r - b) > 40) {
                    pinkCount++
                }
                // Blue (Male)
                else if (b > 190 && (b - r) > 40) {
                    blueCount++
                }
            }
        }

        return when {
            pinkCount > 15 -> Gender.FEMALE
            blueCount > 15 -> Gender.MALE
            else -> Gender.UNKNOWN
        }
    }

    /**
     * Menalar Gender dari Nama Akun & Status/Bio (NLP & Heuristik)
     */
    fun analyzeGenderFromNameAndBio(rawName: String, rawBio: String?): Gender {
        val nameWords = extractNormalizedWords(rawName)
        val bioWords = if (rawBio != null) extractNormalizedWords(rawBio) else emptySet()

        var femaleScore = 0
        var maleScore = 0

        // 1. Cek kecocokan kata pada nama
        for (w in nameWords) {
            if (FEMALE_NAME_DICT.contains(w)) femaleScore += 3
            if (MALE_NAME_DICT.contains(w)) maleScore += 3

            // Heuristik sufiks nama feminin Indonesia (akhiran -a, -i, -ya, -ita, -ina)
            if (w.endsWith("ya") || w.endsWith("ita") || w.endsWith("ina") || w.endsWith("tya")) {
                femaleScore += 1
            }
        }

        // 2. Cek kecocokan kata pada bio / signature
        for (w in bioWords) {
            if (FEMALE_BIO_KEYWORDS.contains(w)) femaleScore += 2
            if (w == "pria" || w == "cowok" || w == "cowo" || w == "single boy") maleScore += 2
        }

        return when {
            femaleScore > maleScore -> Gender.FEMALE
            maleScore > femaleScore -> Gender.MALE
            else -> Gender.UNKNOWN
        }
    }

    /**
     * Menormalisasi teks:
     * - Huruf kecil
     * - Buang angka, simbol, emoji, tanda baca
     * - Reduksi huruf berulang (contoh: "dindaaa" -> "dinda", "windyy" -> "windy")
     */
    private fun extractNormalizedWords(text: String): Set<String> {
        val result = mutableSetOf<String>()
        val clean = text.replace(Regex("[^a-zA-Z\\s]"), " ")
            .lowercase(Locale.ROOT)

        val tokens = clean.split(Regex("\\s+")).filter { it.length >= 2 }
        for (t in tokens) {
            result.add(t)

            // Reduksi karakter berulang 3+ menjadi 1 (contoh: "dindaaa" -> "dinda")
            val reduced3 = t.replace(Regex("(.)\\1{2,}"), "$1")
            result.add(reduced3)

            // Reduksi karakter berulang 2+ menjadi 1 (contoh: "windyy" -> "windy")
            val reduced2 = t.replace(Regex("(.)\\1+"), "$1")
            result.add(reduced2)
        }
        return result
    }
}
