import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TranscriptFetchTest {

    @Test
    @DisplayName("Format-3 caption XML joins <s> cues in order")
    void parsesFormat3Cues() {
        String xml = "<timedtext><body><p><s>Hello</s><s>world</s></p></body></timedtext>";

        String result = TranscriptFetch.parseCaptions(xml);

        assertEquals("Hello world", result);
    }

    @Test
    @DisplayName("srv1 caption XML joins <text> cues when no <s> cues exist")
    void parsesSrv1TextCues() {
        String xml = "<transcript><text start=\"0\">First line</text>"
            + "<text start=\"1\">Second line</text></transcript>";

        String result = TranscriptFetch.parseCaptions(xml);

        assertEquals("First line Second line", result);
    }

    @Test
    @DisplayName("Caption cleanup decodes supported entities and removes nested tags")
    void decodesEntitiesAndRemovesTags() {
        String xml = "<transcript><text>Tom &amp; Jerry &lt;3 "
            + "&quot;tests&quot; &#39;today&#39; <font color=\"red\">now</font></text></transcript>";

        String result = TranscriptFetch.parseCaptions(xml);

        assertEquals("Tom & Jerry <3 \"tests\" 'today' now", result);
    }

    @Test
    @DisplayName("Caption cleanup collapses whitespace and skips empty cues")
    void normalizesWhitespaceAndSkipsEmptyCues() {
        String xml = "<transcript><text>  many\n   spaces  </text>"
            + "<text>   </text><text>remain</text></transcript>";

        String result = TranscriptFetch.parseCaptions(xml);

        assertEquals("many spaces remain", result);
    }

    @Test
    @DisplayName("Missing caption elements produce an empty transcript")
    void returnsEmptyStringWhenNoCuesExist() {
        assertEquals("", TranscriptFetch.parseCaptions("<transcript/>"));
    }

    @Test
    @DisplayName("Retry wrapper returns immediately after a successful call")
    void withBackoffReturnsSuccessfulValue() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        String result = TranscriptFetch.withBackoff(() -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("HTTP 404 is not retried because only 429 and 5xx are retryable")
    void withBackoffDoesNotRetryHttp404() {
        AtomicInteger calls = new AtomicInteger();

        TranscriptFetch.CurlHttpException thrown = assertThrows(
            TranscriptFetch.CurlHttpException.class,
            () -> TranscriptFetch.withBackoff(() -> {
                calls.incrementAndGet();
                throw new TranscriptFetch.CurlHttpException(404);
            })
        );

        assertEquals(404, thrown.status);
        assertEquals(1, calls.get());
    }
}
