package com.nuvio.app.features.ai

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.NativeDiscoverCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
