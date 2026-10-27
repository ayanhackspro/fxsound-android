package com.fxsound.audio.opra

import com.fxsound.audio.data.MinimalJsonParser

/**
 * Parser for the OPRA `database_v1.jsonl` dump.
 *
 * Parsing is JSONL — one JSON object per line:
 *   {"type":"vendor",  "id":"kiiboom", "data":{"name":"KiiBOOM"}}
 *   {"type":"product", "id":"kiiboom::evoke", "data":{"name":"Evoke", ...,"vendor_id":"kiiboom","subtype":"in_ear","line_art_...":...}}
 *   {"type":"eq",      "id":"kiiboom:evoke::autoeq_hi_end_portable", "data":{"author":..., "details":..., "type":"parametric_eq","parameters":{"gain_db":-4,"bands":[...]}}, "product_id":"kiiboom::evoke"}
 *
 * Uses the app's hand-rolled JSON parser because `org.json` is stubbed on the JVM
 * unit-test classpath and the parser must be test-verified. Parsing never fabricates
 * data: malformed lines are skipped and counted, not invented.
 */
object OpraJson {

    class IndexResult(
        val catalogue: OpraCatalogue,
        val summariesByProduct: Map<String, List<OpraEqSummary>>,
        val profileOffsets: Map<String, Long>,
        val skippedLines: Int
    )

    /** Walks a dump file. [lineConsumer] receives (byte offset, JSON line) — kept streaming. */
    fun index(
        lines: Sequence<Pair<Long, String>>
    ): IndexResult {
        val vendors = mutableMapOf<String, String>()
        val products = mutableListOf<OpraProduct>()
        val summaries = mutableMapOf<String, MutableList<OpraEqSummary>>()
        val offsets = mutableMapOf<String, Long>()
        var skipped = 0

        for ((offset, line) in lines) {
            try {
                val fields = parseObject(line)
                when (fields["type"]) {
                    "vendor" -> {
                        val id = fields["id"]?.toString()
                        val name = (fields["data"] as? Map<*, *>)?.get("name")?.toString()
                        if (id != null && name != null) {
                            vendors[id] = name
                        } else {
                            skipped++
                        }
                    }
                    "product" -> {
                        val id = fields["id"]?.toString()
                        val data = fields["data"] as? Map<*, *>
                        if (id != null && data != null) {
                            products.add(
                                OpraProduct(
                                    id = id,
                                    name = data["name"]?.toString() ?: id,
                                    vendorId = data["vendor_id"]?.toString() ?: "",
                                    vendorName = "",
                                    subtype = data["subtype"]?.toString()
                                )
                            )
                        } else {
                            skipped++
                        }
                    }
                    "eq" -> {
                        val id = fields["id"]?.toString()
                        val data = fields["data"] as? Map<*, *>
                        // `product_id` is published inside `data` in database_v1.jsonl; the
                        // top-level fallback keeps hand-written fixtures working.
                        val productId = (data?.get("product_id") ?: fields["product_id"])?.toString()
                        val params = data?.get("parameters") as? Map<*, *>
                        val rawBands = params?.get("bands") as? List<*>
                        val author = data?.get("author")?.toString()

                        if (id != null && productId != null && params != null && rawBands != null && author != null) {
                            var shelfCount = 0
                            var unsupportedCount = 0
                            for (raw in rawBands) {
                                val type = OpraBandType.fromWire(
                                    (raw as? Map<*, *>)?.get("type")?.toString() ?: ""
                                )
                                if (type == OpraBandType.LOW_SHELF || type == OpraBandType.HIGH_SHELF) shelfCount++
                                else if (type == null) unsupportedCount++
                            }
                            summaries.getOrPut(productId) { mutableListOf() }.add(
                                OpraEqSummary(
                                    id = id,
                                    productId = productId,
                                    author = author,
                                    details = data["details"]?.toString(),
                                    link = data["link"]?.toString(),
                                    preampDb = (params["gain_db"] as Number).toFloat(),
                                    bandCount = rawBands.size,
                                    shelfBandCount = shelfCount,
                                    unsupportedBandCount = unsupportedCount
                                )
                            )
                            offsets[id] = offset
                        } else {
                            skipped++
                        }
                    }
                    else -> skipped++
                }
            } catch (e: Exception) {
                // JsonSyntax, casts and malformed value access — skip the line, never fabricate.
                skipped++
            }
        }

        val entries = products.associate { p ->
            p.id to CatalogueEntry(
                product = p.copy(vendorName = vendors[p.vendorId] ?: p.vendorId),
                summaries = summaries[p.id] ?: emptyList()
            )
        }
        return IndexResult(
            catalogue = OpraCatalogue(vendors, entries),
            summariesByProduct = summaries,
            profileOffsets = offsets,
            skippedLines = skipped
        )
    }

    /** Reads the full profile (with bands) from the given raw JSONL line. */
    fun parseProfile(line: String): OpraEqProfile? {
        val fields = try {
            parseObject(line)
        } catch (e: MinimalJsonParser.JsonSyntax) {
            return null
        }
        if (fields["type"] != "eq") return null
        val id = fields["id"]?.toString() ?: return null
        val data = fields["data"] as? Map<*, *> ?: return null
        val productId = (data["product_id"] ?: fields["product_id"])?.toString() ?: return null
        val params = data["parameters"] as? Map<*, *> ?: return null
        val rawBands = params["bands"] as? List<*> ?: return null
        val author = data["author"]?.toString() ?: return null

        val bands = rawBands.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val type = OpraBandType.fromWire(map["type"]?.toString() ?: "") ?: return@mapNotNull null
            OpraBand(
                type = type,
                frequency = (map["frequency"] as Number).toFloat(),
                gainDb = (map["gain_db"] as? Number)?.toFloat() ?: 0.0f,
                q = (map["q"] as? Number)?.toFloat(),
                slopeDbPerOctave = (map["slope"] as? Number)?.toFloat()
            )
        }
        if (bands.isEmpty()) return null

        return OpraEqProfile(
            id = id,
            productId = productId,
            author = author,
            details = data["details"]?.toString(),
            link = data["link"]?.toString(),
            preampDb = (params["gain_db"] as? Number)?.toFloat() ?: 0.0f,
            bands = bands
        )
    }

    /** Serializes the user's selected profile (plus global adjustment) for DataStore persistence. */
    fun encodeForPersistence(profile: OpraEqProfile, adjustDb: Float = 0f): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"id\":\"").append(escape(profile.id)).append("\",")
        sb.append("\"productId\":\"").append(escape(profile.productId)).append("\",")
        sb.append("\"author\":\"").append(escape(profile.author)).append("\",")
        sb.append("\"details\":\"").append(escape(profile.details ?: "")).append("\",")
        sb.append("\"link\":\"").append(escape(profile.link ?: "")).append("\",")
        sb.append("\"preampDb\":").append(profile.preampDb).append(",")
        sb.append("\"adjustDb\":").append(adjustDb).append(",")
        sb.append("\"bands\":[")
        profile.bands.forEachIndexed { i, b ->
            if (i > 0) sb.append(",")
            sb.append("{\"type\":\"").append(b.type.wireName).append("\",")
            sb.append("\"frequency\":").append(b.frequency).append(",")
            sb.append("\"gain_db\":").append(b.gainDb).append(",")
            if (b.q != null) sb.append("\"q\":").append(b.q).append(",")
            if (b.slopeDbPerOctave != null) sb.append("\"slope\":").append(b.slopeDbPerOctave).append(",")
            sb.setLength(sb.length - 1) // strip trailing comma
            sb.append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /** A selected profile plus the user's global adjustment, restored across restarts. */
    class PersistedSelection(val profile: OpraEqProfile, val adjustDb: Float)

    fun decodeFromPersistence(json: String): PersistedSelection? {
        return try {
            val f = parseObject(json)
            val rawBands = f["bands"] as? List<*> ?: return null
            val bands = rawBands.mapNotNull { raw ->
                val m = raw as? Map<*, *> ?: return@mapNotNull null
                val type = OpraBandType.fromWire(m["type"]?.toString() ?: "") ?: return@mapNotNull null
                OpraBand(
                    type = type,
                    frequency = (m["frequency"] as Number).toFloat(),
                    gainDb = (m["gain_db"] as? Number)?.toFloat() ?: 0.0f,
                    q = (m["q"] as? Number)?.toFloat(),
                    slopeDbPerOctave = (m["slope"] as? Number)?.toFloat()
                )
            }
            val profile = OpraEqProfile(
                id = f["id"]?.toString() ?: return null,
                productId = f["productId"]?.toString() ?: return null,
                author = f["author"]?.toString() ?: return null,
                details = f["details"]?.toString(),
                link = f["link"]?.toString(),
                preampDb = (f["preampDb"] as? Number)?.toFloat() ?: 0.0f,
                bands = bands
            )
            PersistedSelection(profile, (f["adjustDb"] as? Number)?.toFloat() ?: 0f)
        } catch (e: Exception) {
            null
        }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    // ---- low-level JSON walking on top of MinimalJsonParser --------------------

    fun parseObject(json: String): Map<String, Any?> {
        val p = MinimalJsonParser(json)
        p.skipWhitespace()
        if (!p.consume('{')) throw MinimalJsonParser.JsonSyntax()
        val map = mutableMapOf<String, Any?>()
        p.skipWhitespace()
        if (p.consume('}')) return map
        while (true) {
            p.skipWhitespace()
            val key = p.parseString()
            p.skipWhitespace()
            if (!p.consume(':')) throw MinimalJsonParser.JsonSyntax()
            map[key] = parseValue(p)
            p.skipWhitespace()
            if (p.consume(',')) continue
            if (p.consume('}')) return map
            throw MinimalJsonParser.JsonSyntax()
        }
    }

    fun parseArray(json: String): List<Any?> {
        val p = MinimalJsonParser(json)
        p.skipWhitespace()
        if (!p.consume('[')) throw MinimalJsonParser.JsonSyntax()
        val list = mutableListOf<Any?>()
        p.skipWhitespace()
        if (p.consume(']')) return list
        while (true) {
            list.add(parseValue(p))
            p.skipWhitespace()
            if (p.consume(',')) continue
            if (p.consume(']')) return list
            throw MinimalJsonParser.JsonSyntax()
        }
    }

    private fun parseValue(p: MinimalJsonParser): Any? {
        p.skipWhitespace()
        return when {
            p.peekIs('"') -> p.parseString()
            p.peekIs('[') -> {
                p.consume('[')
                val list = mutableListOf<Any?>()
                p.skipWhitespace()
                if (p.consume(']')) return list
                while (true) {
                    list.add(parseValue(p))
                    p.skipWhitespace()
                    if (p.consume(',')) continue
                    if (p.consume(']')) return list
                    throw MinimalJsonParser.JsonSyntax()
                }
            }
            p.peekIs('{') -> {
                p.consume('{')
                val map = mutableMapOf<String, Any?>()
                p.skipWhitespace()
                if (p.consume('}')) return map
                while (true) {
                    p.skipWhitespace()
                    val key = p.parseString()
                    p.skipWhitespace()
                    if (!p.consume(':')) throw MinimalJsonParser.JsonSyntax()
                    map[key] = parseValue(p)
                    p.skipWhitespace()
                    if (p.consume(',')) continue
                    if (p.consume('}')) return map
                    throw MinimalJsonParser.JsonSyntax()
                }
            }
            else -> p.parseNumber()
        }
    }
}
