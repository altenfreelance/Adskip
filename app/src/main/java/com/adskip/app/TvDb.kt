package com.adskip.app

import android.content.Context
import org.json.JSONObject

/** One remote-control signal: carrier frequency plus on/off timings in microseconds. */
class RawCode(val freq: Int, val timings: IntArray)

/**
 * The table of TV remote codes built from the open Flipper-IRDB database
 * (see tools/build_tv_codes.py). Each code set is the Mute, Play and
 * Fast-forward buttons of one remote. Indexes point into [codes]; -1 = none.
 */
class TvDb(private val codes: List<String>, val brands: List<Brand>) {

    class CodeSet(val id: String, val mute: Int, val play: Int, val ff: Int) {
        fun summary(): String =
            id + "  (" + (if (play >= 0) "play" else "no play") + ", " + (if (ff >= 0) "FF" else "no FF") + ")"
    }

    class Brand(val name: String, val sets: List<CodeSet>)

    fun pattern(index: Int): RawCode? {
        if (index < 0 || index >= codes.size) return null
        return try {
            val parts = codes[index].split(",")
            val freq = parts[0].toInt()
            val timings = IntArray(parts.size - 1) { parts[it + 1].toInt() }
            RawCode(freq, timings)
        } catch (e: Exception) {
            null
        }
    }

    /** Mute codes used by this brand, most widely shared first, so likely matches are tried early. */
    fun uniqueMutes(brand: Brand): List<Int> {
        val counts = HashMap<Int, Int>()
        for (s in brand.sets) counts[s.mute] = (counts[s.mute] ?: 0) + 1
        return counts.entries.sortedByDescending { it.value }.map { it.key }
    }

    companion object {
        private val POPULAR = listOf(
            "samsung", "lg", "sony", "insignia", "vizio", "tcl", "roku", "hisense",
            "panasonic", "philips", "sharp", "toshiba", "element", "westinghouse", "sanyo"
        )

        fun load(context: Context): TvDb? {
            return try {
                val text = context.assets.open("tvcodes.json").bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                val codeArr = root.getJSONArray("codes")
                val codes = ArrayList<String>(codeArr.length())
                for (i in 0 until codeArr.length()) codes.add(codeArr.getString(i))

                val brandArr = root.getJSONArray("brands")
                val brands = ArrayList<Brand>()
                for (i in 0 until brandArr.length()) {
                    val b = brandArr.getJSONObject(i)
                    val sArr = b.getJSONArray("s")
                    val sets = ArrayList<CodeSet>()
                    for (j in 0 until sArr.length()) {
                        val s = sArr.getJSONArray(j)
                        sets.add(CodeSet(s.getString(0), s.getInt(1), s.getInt(2), s.getInt(3)))
                    }
                    if (sets.isNotEmpty()) brands.add(Brand(b.getString("n"), sets))
                }
                val sorted = brands.sortedWith(
                    compareBy<Brand>(
                        { val p = POPULAR.indexOf(it.name.lowercase()); if (p < 0) 999 else p },
                        { it.name.lowercase() }
                    )
                )
                TvDb(codes, sorted)
            } catch (e: Exception) {
                null
            }
        }
    }
}
