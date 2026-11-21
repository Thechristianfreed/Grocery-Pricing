package com.freed.grocery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Parses a real snapshot captured from the daily job, so the app and the job can't drift apart. */
class DataTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val snap = Parser.snapshot(res("prices.json"))
    private val all get() = snap.stores.map { it.id }.toSet()

    @Test fun parsesStoresStaplesAndDeals() {
        assertEquals(12, snap.stores.size)
        assertEquals(41, snap.staples.size)
        assertTrue(snap.deals.size > 500)
        assertTrue(snap.stores.first { it.id == "safeway" }.hasAds)
        assertTrue(!snap.stores.first { it.id == "winco" }.hasAds)
    }

    @Test fun cheapestIsLowestComparableUnitPrice() {
        val butter = snap.staples.first { it.id == "butter" }
        val ranked = butter.ranked(all)
        assertTrue(ranked.size >= 2)
        assertEquals(ranked.minOf { it.second.unitPrice!! }, butter.cheapest(all)!!.second.unitPrice!!, 0.0)
        assertEquals(ranked.map { it.second.unitPrice }, ranked.map { it.second.unitPrice }.sortedBy { it })
    }

    @Test fun hidingAStoreRemovesItFromTheRanking() {
        val butter = snap.staples.first { it.id == "butter" }
        val winner = butter.cheapest(all)!!.first
        val next = butter.cheapest(all - winner)
        assertNotNull(next)
        assertTrue(next!!.first != winner)
    }

    @Test fun offersWithoutASizeAreNeverRankedCheapest() {
        snap.staples.forEach { s -> s.ranked(all).forEach { assertNotNull(it.second.unitPrice) } }
    }

    @Test fun stapleWithNoAdsHasNoCheapest() {
        val none = snap.staples.first { it.offers.isEmpty() }
        assertNull(none.cheapest(all))
    }

    @Test fun parsesHistory() {
        val hist = Parser.history(res("history.json"))
        val butter = hist.getValue("butter")
        assertTrue(butter.isNotEmpty())
        assertEquals(butter.map { it.from }, butter.map { it.from }.sorted())
    }

    @Test fun formatting() {
        assertEquals("$2.99/lb", Fmt.unitPrice(2.99, "lb"))
        assertEquals("12.5¢/oz", Fmt.unitPrice(0.125, "oz"))
        assertEquals("89¢/lb", Fmt.unitPrice(0.89, "lb"))
        assertEquals("$1.25 ea", Fmt.unitPrice(1.25, "each"))
        assertEquals("$4.97/lb", Fmt.shelf(4.97, "lb"))
        assertEquals("Nov 6", Fmt.day("2026-11-06"))
        assertEquals("garbage", Fmt.day("garbage"))
    }

    @Test fun ageCountsWholeDays() {
        val now = Instant.parse("2026-11-24T12:00:00Z")
        assertEquals(3L, ageInDays("2026-11-21T10:00:00Z", now))
        assertNull(ageInDays("not a date", now))
    }
}
