package com.fxsound.audio.opra

import java.util.Locale

/**
 * E1: ranked catalogue search.
 *
 * Lowercase keys are computed once per catalogue load, so typing does not re-lowercase
 * 6,000 product names on every keystroke. Ranking order:
 *
 *  0 exact product name · 1 product-name prefix · 2 product-word prefix
 *  3 product-name contains · 4 exact vendor · 5 vendor prefix · 6 vendor contains
 *  7 multi-token match spread across name and vendor
 *
 * Every query token must match the product name or the vendor, so "sony xm4" narrows.
 * Pure and Android-free so it can be unit-tested against the real fixture.
 */
internal object OpraSearch {

    class Item(
        val entry: CatalogueEntry,
        val nameLower: String,
        val vendorLower: String
    )

    fun items(entries: Collection<CatalogueEntry>): List<Item> =
        entries.filter { it.summaries.isNotEmpty() }.map {
            Item(
                entry = it,
                nameLower = it.product.name.lowercase(Locale.ROOT),
                vendorLower = it.product.vendorName.lowercase(Locale.ROOT)
            )
        }

    fun rank(items: List<Item>, rawQuery: String, limit: Int = 100): List<CatalogueEntry> {
        val query = rawQuery.trim().lowercase(Locale.ROOT)
        if (query.isEmpty()) {
            return items.sortedBy { it.nameLower }.take(limit).map { it.entry }
        }
        val tokens = query.split(' ').filter { it.isNotEmpty() }

        fun score(item: Item): Int {
            if (!tokens.all { item.nameLower.contains(it) || item.vendorLower.contains(it) }) {
                return Int.MAX_VALUE
            }
            return when {
                item.nameLower == query -> 0
                item.nameLower.startsWith(query) -> 1
                item.nameLower.split(' ', '-', '(', ')', '/')
                    .any { it.isNotEmpty() && it.startsWith(query) } -> 2
                item.nameLower.contains(query) -> 3
                item.vendorLower == query -> 4
                item.vendorLower.startsWith(query) -> 5
                item.vendorLower.contains(query) -> 6
                else -> 7
            }
        }

        return items
            .map { it to score(it) }
            .filter { it.second != Int.MAX_VALUE }
            .sortedWith(compareBy({ it.second }, { it.first.nameLower }, { it.first.vendorLower }))
            .take(limit)
            .map { it.first.entry }
    }
}
