package com.stroexd.hsdecktracker.core.meta

import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.util.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** One number per card: its win rate (0..100) in Arena decks, from HSReplay. */
@Serializable
data class ArenaRatingSnapshot(
    val ratings: Map<Int, Double> = emptyMap(),
    val fetchedAt: Long = 0,
    val source: String = "",
)

/**
 * HSReplay's arena card stats endpoint is undocumented, so this walks the JSON defensively rather
 * than decoding a fixed schema: any wrapper shape is accepted, and a row missing an id or a known
 * metric key is skipped rather than failing the whole response.
 */
object ArenaRatingsParser {
    /** Scanned for a value, in order, so only this list needs fixing once the real field name is known. */
    private val METRIC_KEYS = listOf("deck_win_rate", "included_winrate", "win_rate", "winrate", "avg_rating", "score")
    private const val MAX_DEPTH = 4

    fun parse(json: String, db: CardDatabase): ArenaRatingSnapshot = runCatching {
        val root = AppJson.parseToJsonElement(json)
        val ratings = linkedMapOf<Int, Double>()
        for (row in rows(root, MAX_DEPTH)) {
            val dbfId = identify(row, db) ?: continue
            val metric = metric(row) ?: continue
            ratings.putIfAbsent(dbfId, metric)
        }
        ArenaRatingSnapshot(ratings = ratings)
    }.getOrDefault(ArenaRatingSnapshot())

    /** Every [JsonObject] reachable inside any [JsonArray] below [element], depth-capped. */
    private fun rows(element: JsonElement, depth: Int): List<JsonObject> {
        if (depth <= 0) return emptyList()
        return when (element) {
            is JsonArray -> element.flatMap { item ->
                when (item) {
                    is JsonObject -> listOf(item) + rows(item, depth - 1)
                    is JsonArray -> rows(item, depth - 1)
                    else -> emptyList()
                }
            }
            is JsonObject -> element.values.flatMap { rows(it, depth - 1) }
            else -> emptyList()
        }
    }

    private fun identify(row: JsonObject, db: CardDatabase): Int? =
        row.int("dbf_id") ?: row.int("dbfId") ?: row.string("card_id")?.let { db.byCardId(it)?.dbfId }

    private fun metric(row: JsonObject): Double? {
        val raw = METRIC_KEYS.firstNotNullOfOrNull { row.double(it) } ?: return null
        val scaled = if (raw in 0.0..1.0) raw * 100 else raw
        return scaled.takeIf { it in 0.0..100.0 }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
