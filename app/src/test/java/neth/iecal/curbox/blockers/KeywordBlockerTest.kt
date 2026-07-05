package neth.iecal.curbox.blockers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordBlockerTest {

    @Test fun subdomainMatchesWhenKeywordHasDot() {
        assertTrue("youtube.com should match m.youtube.com",
            KeywordBlocker.matchesLiteral("youtube.com", "m.youtube.com"))
        assertTrue("youtube.com should match www.youtube.com",
            KeywordBlocker.matchesLiteral("youtube.com", "www.youtube.com"))
        assertTrue("youtube.com should match music.youtube.com",
            KeywordBlocker.matchesLiteral("youtube.com", "music.youtube.com"))
        assertTrue("youtube.com should match itself",
            KeywordBlocker.matchesLiteral("youtube.com", "youtube.com"))
    }

    @Test fun subdomainDoesNotMatchUnrelatedDomain() {
        assertFalse("youtube.com should NOT match example.com",
            KeywordBlocker.matchesLiteral("youtube.com", "example.com"))
        assertFalse("youtube.com should NOT match maliciousyoutube.com",
            KeywordBlocker.matchesLiteral("youtube.com", "maliciousyoutube.com"))
    }

    @Test fun subdomainMatchesWithPath() {
        assertTrue("youtube.com should match m.youtube.com/watch?v=123",
            KeywordBlocker.matchesLiteral("youtube.com", "m.youtube.com/watch?v=123"))
        assertTrue("youtube.com should match www.youtube.com/feed/trending",
            KeywordBlocker.matchesLiteral("youtube.com", "www.youtube.com/feed/trending"))
    }

    @Test fun exactMatchWorks() {
        assertTrue("keyword should match exact url",
            KeywordBlocker.matchesLiteral("test", "test"))
        assertTrue("keyword should match url with path",
            KeywordBlocker.matchesLiteral("example.com", "example.com/page"))
    }

    @Test fun keywordStartsWithSlashMatchesAnywhere() {
        assertTrue("/path should match anywhere in url",
            KeywordBlocker.matchesLiteral("/path", "example.com/path/to/page"))
    }

    @Test fun singleWordKeywordChecksDomainParts() {
        assertTrue("single word should match domain part",
            KeywordBlocker.matchesLiteral("youtube", "m.youtube.com"))
        assertFalse("single word should NOT match unrelated domain",
            KeywordBlocker.matchesLiteral("youtube", "example.com"))
    }
}
