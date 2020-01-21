// Copyright 2020 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package ai.vespa.example.album;

import ai.vespa.hosted.cd.Endpoint;
import ai.vespa.hosted.cd.TestRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.stream.Stream;

import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toUnmodifiableMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StagingCommons {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** Returns the container endpoint to do requests against. */
    static Endpoint container() {
        return TestRuntime.get().deploymentToTest().endpoint("default");
    }

    /** Returns the document path of the document with the given name. */
    static String documentPath(String documentName) {
        return "/document/v1/staging/music/docid/" + encode(documentName, UTF_8);
    }

    /** Reads and returns the contents of the JSON test resource with the given name. */
    static byte[] readDocumentResource(String documentName) {
        try {
            return StagingSetupTest.class.getResourceAsStream("/" + documentName + ".json").readAllBytes();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns static document ID paths and document bytes for the three static staging test documents. */
    static Map<String, byte[]> documentsByPath() {
        return Stream.of("A-Head-Full-of-Dreams",
                         "Hardwired...To-Self-Destruct",
                         "Love-Is-Here-To-Stay")
                     .collect(toUnmodifiableMap(StagingCommons::documentPath,
                                                StagingCommons::readDocumentResource));
    }

    /** Warm-up query matching all "music" documents — high timeout as the fresh container needs to warm up. */
    static Map<String, String> warmupQueryForAllDocuments() {
        return Map.of("yql", "SELECT * FROM SOURCES * WHERE sddocname CONTAINS \"music\";",
                      "timeout", "5s");
    }

    /** Sample query for all albums after 2015, preferably pop — should return albums by Diana Krall, then Metallica. */
    static Map<String, String> queryForNewPop() {
        return Map.of("yql", "SELECT * FROM SOURCES * WHERE year > 2015;",
                      "ranking", "rank_albums",
                      "ranking.features.query(user_profile)", "{{cat:pop}:0.8, {cat:rock}:0.2, {cat:jazz}:0.1}");
    }

    /** Verifies the static staging documents are searchable, ranked correctly, and render as expected. */
    static void verifyDocumentsAreSearchable() throws IOException {
        // Verify that the cluster has the fed documents, and that they are searchable.
        HttpResponse<String> warmUpResponse = container().send(container().request("/search/", warmupQueryForAllDocuments()));
        assertEquals(200, warmUpResponse.statusCode());
        assertEquals(3, mapper.readTree(warmUpResponse.body())
                              .get("root").get("fields").get("totalCount").asLong());

        // Verify that the cluster filters and ranks documents as expected, prior to upgrade.
        HttpResponse<String> queryResponse = container().send(container().request("/search/", queryForNewPop()));
        assertEquals(200, queryResponse.statusCode());
        JsonNode root = mapper.readTree(queryResponse.body()).get("root");
        assertEquals(2, root.get("fields").get("totalCount").asLong());

        JsonNode love = root.get("children").get(0).get("fields");
        assertEquals("Diana Krall", love.get("artist").asText());
        assertEquals("Love Is Here To Stay", love.get("album").asText());
        assertEquals(2018, love.get("year").asLong());

        JsonNode hardwired = root.get("children").get(1).get("fields");
        assertEquals("Metallica", hardwired.get("artist").asText());
        assertEquals("Hardwired...To Self-Destruct", hardwired.get("album").asText());
        assertEquals(2016, hardwired.get("year").asLong());
    }

}
