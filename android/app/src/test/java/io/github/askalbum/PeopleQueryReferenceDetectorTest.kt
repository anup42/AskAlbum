package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleQueryReferenceDetectorTest {
    @Test
    fun detectsKnownIdentitiesWithoutGuessingArbitraryNames() {
        assertEquals(listOf(PersonClause("me")), PeopleQueryReferenceDetector.detect("Show photos with me"))
        assertEquals(listOf(PersonClause("wife")), PeopleQueryReferenceDetector.detect("my wife's birthday photos"))
        assertEquals(listOf(PersonClause("\u092d\u0948\u092f\u093e")), PeopleQueryReferenceDetector.detect("\u092d\u0948\u092f\u093e ke saath photos"))
        assertTrue(PeopleQueryReferenceDetector.detect("Show Pooja photos").isEmpty())
        assertTrue(PeopleQueryReferenceDetector.detect("Show family photos").isEmpty())
    }

    @Test
    fun preservesNegativeIdentityPolarity() {
        assertEquals(
            listOf(PersonClause("wife", mustBePresent = false)),
            PeopleQueryReferenceDetector.detect("Show photos without my wife"),
        )
        assertEquals(
            listOf(PersonClause("\u092d\u093e\u0908", mustBePresent = false)),
            PeopleQueryReferenceDetector.detect("\u092d\u093e\u0908 ke bina photos"),
        )
    }
}
