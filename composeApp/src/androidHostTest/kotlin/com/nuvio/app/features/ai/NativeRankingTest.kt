package com.nuvio.app.features.ai

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.NativeDiscoverCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeRankingTest {
    private fun candidate(id: Int, name: String, genres: List<Int>, rating: Double = 7.0) = NativeDiscoverCandidate(
        preview = MetaPreview(id = "tmdb:$id", type = "movie", name = name),
        genreIds = genres,
        voteAverage = rating,
        voteCount = 500,
    )

    @Test
    fun splitFollowsTheSeriesShare() {
        assertEquals(20 to 0, NativeRanking.split(20, 0))
        assertEquals(15 to 5, NativeRanking.split(20, 25))
        assertEquals(10 to 10, NativeRanking.split(20, 50))
        assertEquals(0 to 20, NativeRanking.split(20, 100))
    }

    @Test
    fun interleaveSpreadsBothListsEvenly() {
        val mixed = NativeRanking.interleave(listOf("m1", "m2", "m3", "m4"), listOf("s1", "s2"))
        assertEquals(6, mixed.size)
        assertEquals(setOf("s1", "s2"), mixed.filter { it.startsWith("s") }.toSet())
        assertTrue(mixed.indexOf("s1") < 3, "first series should appear early: $mixed")
    }

    @Test
    fun likedGenresOutrankEqualQualityTitles() {
        val ranked = NativeRanking.rank(
            candidates = listOf(candidate(1, "Plain Drama", listOf(18)), candidate(2, "Space Epic", listOf(878))),
            weights = emptyMap(),
            liked = setOf(878),
            disliked = emptySet(),
            excludedNames = emptySet(),
        )
        assertEquals("Space Epic", ranked.first().preview.name)
    }

    @Test
    fun dislikedGenresAndWatchedTitlesAreDropped() {
        val ranked = NativeRanking.rank(
            candidates = listOf(
                candidate(1, "Slasher", listOf(27)),
                candidate(2, "Already Seen", listOf(35)),
                candidate(3, "Fresh Comedy", listOf(35)),
            ),
            weights = emptyMap(),
            liked = emptySet(),
            disliked = setOf(27),
            excludedNames = setOf(normalizeAiTitle("Already Seen")),
        )
        assertEquals(listOf("Fresh Comedy"), ranked.map { it.preview.name })
    }

    @Test
    fun tvGenresFoldOntoMovieGenres() {
        assertEquals(28, NativeRanking.canonicalGenre(10759))
        assertEquals(878, NativeRanking.canonicalGenre(10765))
        assertEquals(10759, NativeRanking.tvGenre(12))
    }

    @Test
    fun roundRobinBlendsDecades() {
        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), NativeRanking.roundRobin(listOf(listOf("a1", "a2", "a3"), listOf("b1", "b2"))))
    }

    @Test
    fun isoDateFormatsEpochMilliseconds() {
        assertEquals("1970-01-01", isoDate(0L))
        assertEquals("2026-10-22", isoDate(1_792_627_200_000L))
    }

    // ---- Engagement-weighted taste ----

    @Test
    fun engagementWeightRewardsFinishedTitlesAndBingedSeries() {
        assertEquals(1.0, NativeRanking.engagementWeight(1.0, 0, isSeries = false), 1e-9)
        assertEquals(0.35 + 0.65 * 0.2, NativeRanking.engagementWeight(0.2, 0, isSeries = false), 1e-9)
        // A single finished episode is just a normal watch; six or more cap the depth bonus.
        assertEquals(1.0, NativeRanking.engagementWeight(1.0, 1, isSeries = true), 1e-9)
        assertEquals(1.5, NativeRanking.engagementWeight(1.0, 12, isSeries = true), 1e-9)
        // Episode counts never inflate a movie.
        assertEquals(1.0, NativeRanking.engagementWeight(1.0, 12, isSeries = false), 1e-9)
        assertTrue(NativeRanking.engagementWeight(0.3, 0, false) < NativeRanking.engagementWeight(0.9, 0, false))
    }

    // ---- Because you watched ----

    @Test
    fun coOccurrenceBoostsFavourTitlesRecommendedBySeveralSeeds() {
        val shared = candidate(1, "Shared", listOf(18))
        val onlyOne = candidate(2, "Only One", listOf(18))
        val boosts = NativeRanking.coOccurrenceBoosts(
            listOf(1.0 to listOf(shared, onlyOne), 0.8 to listOf(shared)),
        )
        assertTrue(boosts.getValue("tmdb:1") > boosts.getValue("tmdb:2"))
        assertTrue(boosts.getValue("tmdb:1") > 1.0, "a title from two seeds should earn more than one seed's weight")
    }

    @Test
    fun rankUsesBoostsAndDropsHiddenIds() {
        val ranked = NativeRanking.rank(
            candidates = listOf(
                candidate(1, "Plain", listOf(18), rating = 8.0),
                candidate(2, "Boosted", listOf(18), rating = 7.0),
                candidate(3, "Hidden", listOf(18), rating = 9.0),
            ),
            weights = emptyMap(),
            liked = emptySet(),
            disliked = emptySet(),
            excludedNames = emptySet(),
            excludedIds = setOf("tmdb:3"),
            boosts = mapOf("tmdb:2" to 1.5),
        )
        assertEquals(listOf("Boosted", "Plain"), ranked.map { it.preview.name })
    }

    // ---- Diversity ----

    @Test
    fun diversifyPullsADifferentGenreAheadOfNearCopies() {
        val ranked = listOf(
            candidate(1, "Heist", listOf(28, 53), rating = 8.0),
            candidate(2, "Chase", listOf(28, 53), rating = 7.9),
            candidate(3, "Brawl", listOf(28, 53), rating = 7.85),
            candidate(4, "Lullaby", listOf(18), rating = 7.8),
            candidate(5, "Siege", listOf(28, 53), rating = 7.0),
        )
        val result = NativeRanking.diversify(
            ranked = ranked,
            scoreOf = { NativeRanking.score(it, emptyMap(), emptySet()) },
            count = 5,
        )
        assertEquals("Heist", result[0].preview.name)
        assertEquals("Lullaby", result[1].preview.name, "the drama should break up the run of action thrillers: ${result.map { it.preview.name }}")
        assertEquals(ranked.map { it.preview.id }.toSet(), result.map { it.preview.id }.toSet())
    }

    @Test
    fun diversifyKeepsTheTailInOriginalOrder() {
        val ranked = (1..6).map { candidate(it, "Title $it", listOf(18), rating = 9.0 - it * 0.1) }
        val result = NativeRanking.diversify(ranked, { NativeRanking.score(it, emptyMap(), emptySet()) }, count = 2)
        // Identical genres leave nothing to diversify, so the order must not change.
        assertEquals(ranked.map { it.preview.id }, result.map { it.preview.id })
    }

    @Test
    fun sameFranchiseMatchesSequelsButNotShortOrUnrelatedTitles() {
        assertTrue(NativeRanking.sameFranchise("Avatar", "Avatar: The Way of Water"))
        assertTrue(NativeRanking.sameFranchise("The Dark Knight", "The Dark Knight Rises"))
        assertFalse(NativeRanking.sameFranchise("Heat", "Heated Rivalry"))
        assertFalse(NativeRanking.sameFranchise("Taken", "Takeshi's Castle"))
    }

    // ---- Cross-row de-duplication ----

    @Test
    fun dedupeKeepsEarlierRowsIntactAndBackfillsLaterOnes() {
        val rows = listOf(
            listOf("a", "b", "c"),
            listOf("b", "c", "d", "e", "f", "g"),
        )
        val result = NativeRanking.dedupeAcrossRows(rows, { it }, limit = 3, minimum = 2)
        assertEquals(listOf("a", "b", "c"), result[0])
        assertEquals(listOf("d", "e", "f"), result[1])
    }

    @Test
    fun dedupeFallsBackToOriginalPicksWhenARowWouldBeEmptied() {
        val rows = listOf(listOf("a", "b", "c"), listOf("a", "b", "c"))
        val result = NativeRanking.dedupeAcrossRows(rows, { it }, limit = 3, minimum = 2)
        assertEquals(listOf("a", "b", "c"), result[1])
    }

    // ---- Not interested ----

    @Test
    fun hiddenTitlesLowerTheirGenresWithAFloor() {
        val once = NativeRanking.applyNotInterested(mapOf(27 to 0.6), listOf(listOf(27, 53)))
        assertEquals(0.35, once.getValue(27), 1e-9)
        assertEquals(-0.25, once.getValue(53), 1e-9)

        val many = NativeRanking.applyNotInterested(emptyMap(), List(10) { listOf(27) })
        assertEquals(-1.0, many.getValue(27), 1e-9)

        // TV genre ids fold onto their movie equivalents.
        val tv = NativeRanking.applyNotInterested(mapOf(28 to 0.5), listOf(listOf(10759)))
        assertEquals(0.25, tv.getValue(28), 1e-9)
    }

    @Test
    fun notInterestedEntriesSurviveStorageRoundTrip() {
        val entries = listOf(
            NotInterestedEntry("tmdb:603", "The Matrix", listOf(28, 878)),
            NotInterestedEntry("tt0111161", "Se7en!", emptyList()),
        )
        val decoded = NotInterestedEntry.decode(NotInterestedEntry.encode(entries))
        assertEquals(listOf("tmdb:603", "tt0111161"), decoded.map { it.id })
        assertEquals(normalizeAiTitle("The Matrix"), decoded[0].name)
        assertEquals(listOf(28, 878), decoded[0].genreIds)
        assertEquals(emptyList(), decoded[1].genreIds)
        assertEquals(emptyList(), NotInterestedEntry.decode(null))
        assertEquals(emptyList(), NotInterestedEntry.decode(""))
        assertEquals(listOf("tmdb:1"), NotInterestedEntry.decode("garbage;tmdb:1|abc|18").map { it.id })
    }
}
