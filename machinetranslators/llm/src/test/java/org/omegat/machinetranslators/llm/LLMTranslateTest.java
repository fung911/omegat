/*******************************************************************************
  OmegaT - Computer Assisted Translation (CAT) tool
           with fuzzy matching, translation memory, keyword search,
           glossaries, and translation leveraging into updated projects.

  Copyright (C) 2026 Team Wheel Reinventor
                Home page: https://www.omegat.org/
                Support center: https://omegat.org/support

  This file is part of OmegaT.

  OmegaT is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  OmegaT is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 ******************************************************************************/

package org.omegat.machinetranslators.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.Test;

import org.omegat.core.TestCoreWireMock;
import org.omegat.core.machinetranslators.MachineTranslateError;
import org.omegat.util.Language;
import org.omegat.util.Preferences;

public class LLMTranslateTest extends TestCoreWireMock {

    @Test
    public void createJsonRequest() throws Exception {
        LLMTranslate llmTranslate = new LLMTranslate();
        String json = llmTranslate.createJsonRequest("gpt-4o-mini", "Translate this.");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(json);
        assertEquals("gpt-4o-mini", node.get("model").asText());
        assertEquals(0, node.get("temperature").asInt());
        assertEquals("user", node.get("messages").get(0).get("role").asText());
        assertEquals("Translate this.", node.get("messages").get(0).get("content").asText());
    }

    @Test
    public void getJsonResults() throws Exception {
        LLMTranslate llmTranslate = new LLMTranslate();
        String json = "{\"choices\": ["
                + "  {\"index\": 0, \"message\": {\"role\": \"assistant\","
                + "   \"content\": \"translated text goes here.\"}}"
                + "]}";
        String translation = llmTranslate.getJsonResults(json);
        assertEquals("translated text goes here.", translation);
    }

    @Test
    public void renderPrompt() {
        LLMTranslate llmTranslate = new LLMTranslate();
        Language sLang = new Language("EN");
        Language tLang = new Language("FR");
        String rendered = llmTranslate.renderPrompt("{source_lang} -> {target_lang}: {text}", sLang, tLang,
                "Hello");
        assertEquals(sLang.getDisplayName() + " -> " + tLang.getDisplayName() + ": Hello", rendered);
    }

    @Test
    public void testResponse() throws Exception {
        Preferences.setPreference(LLMTranslate.ALLOW_LLM_TRANSLATE, true);

        int port = wireMockRule.port();
        String url = String.format("http://localhost:%d/v1/chat/completions", port);
        System.setProperty(LLMTranslate.PROPERTY_API_URL, url);
        System.setProperty(LLMTranslate.PROPERTY_API_KEY, "test-key");
        System.setProperty(LLMTranslate.PROPERTY_MODEL, "gpt-4o-mini");

        WireMock.stubFor(WireMock.post(WireMock.anyUrl())
                .withHeader("Authorization", WireMock.equalTo("Bearer test-key"))
                .withRequestBody(WireMock.matchingJsonPath("$.messages[0].content"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"choices\": [{\"message\": "
                                + "{\"role\": \"assistant\", \"content\": \"Bonjour\"}}]}")));

        LLMTranslate llmTranslate = new LLMTranslate();
        String result = llmTranslate.translate(new Language("EN"), new Language("FR"), "Hello");
        assertEquals("Bonjour", result);
    }

    @Test
    public void noApiKeyOmitsAuthorizationHeader() throws Exception {
        Preferences.setPreference(LLMTranslate.ALLOW_LLM_TRANSLATE, true);
        // A local provider (Ollama, LM Studio) needs no key.
        System.clearProperty(LLMTranslate.PROPERTY_API_KEY);

        int port = wireMockRule.port();
        String url = String.format("http://localhost:%d/v1/chat/completions", port);
        System.setProperty(LLMTranslate.PROPERTY_API_URL, url);
        System.setProperty(LLMTranslate.PROPERTY_MODEL, "local-model");

        WireMock.stubFor(WireMock.post(WireMock.anyUrl())
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"choices\": [{\"message\": "
                                + "{\"role\": \"assistant\", \"content\": \"Hallo\"}}]}")));

        LLMTranslate llmTranslate = new LLMTranslate();
        String result = llmTranslate.translate(new Language("EN"), new Language("DE"), "Hello");
        assertEquals("Hallo", result);

        WireMock.verify(WireMock.postRequestedFor(WireMock.anyUrl()).withoutHeader("Authorization"));
    }

    @Test
    public void streamingAssemblesDeltasAndReportsTokens() throws Exception {
        Preferences.setPreference(LLMTranslate.ALLOW_LLM_TRANSLATE, true);

        int port = wireMockRule.port();
        String url = String.format("http://localhost:%d/v1/chat/completions", port);
        System.setProperty(LLMTranslate.PROPERTY_API_URL, url);
        System.setProperty(LLMTranslate.PROPERTY_API_KEY, "test-key");
        System.setProperty(LLMTranslate.PROPERTY_MODEL, "gpt-4o-mini");

        // The request must opt into streaming, and the response is an SSE stream.
        WireMock.stubFor(WireMock.post(WireMock.anyUrl())
                .withRequestBody(WireMock.matchingJsonPath("$.stream"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: {\"choices\": [{\"delta\": {\"role\": \"assistant\"}}]}\n\n"
                                + "data: {\"choices\": [{\"delta\": {\"content\": null}}]}\n\n"
                                + "data: {\"choices\": [{\"delta\": {\"content\": \"Bon\"}}]}\n\n"
                                + "data: {\"choices\": [{\"delta\": {\"content\": \"jour\"}}]}\n\n"
                                + "data: [DONE]\n\n")));

        List<String> tokens = new ArrayList<>();
        LLMTranslate llmTranslate = new LLMTranslate();
        String result = llmTranslate.translate(new Language("EN"), new Language("FR"), "Hello", tokens::add);

        // The role-only and content:null chunks must be skipped (no literal "null").
        assertEquals("Bonjour", result);
        assertEquals(Arrays.asList("Bon", "jour"), tokens);
    }

    @Test
    public void getTranslationStreamsThroughCacheLayer() throws Exception {
        // Exercises the full path the MT pane uses:
        // getTranslation(..., consumer) -> BaseCachedTranslate -> translate(..., consumer).
        Preferences.setPreference(LLMTranslate.ALLOW_LLM_TRANSLATE, true);

        int port = wireMockRule.port();
        String url = String.format("http://localhost:%d/v1/chat/completions", port);
        System.setProperty(LLMTranslate.PROPERTY_API_URL, url);
        System.setProperty(LLMTranslate.PROPERTY_API_KEY, "test-key");
        System.setProperty(LLMTranslate.PROPERTY_MODEL, "gpt-4o-mini");

        WireMock.stubFor(WireMock.post(WireMock.anyUrl())
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: {\"choices\": [{\"delta\": {\"content\": \"Bon\"}}]}\n\n"
                                + "data: {\"choices\": [{\"delta\": {\"content\": \"jour\"}}]}\n\n"
                                + "data: [DONE]\n\n")));

        List<String> tokens = new ArrayList<>();
        LLMTranslate llmTranslate = new LLMTranslate();
        String result = llmTranslate.getTranslation(new Language("EN"), new Language("FR"), "Hello",
                tokens::add);

        assertEquals("Bonjour", result);
        assertEquals(Arrays.asList("Bon", "jour"), tokens);
    }

    @Test
    public void nonStreamingServerFallsBackToFullParse() throws Exception {
        // A server that ignores stream:true and returns a single JSON body must
        // still produce the translation rather than an empty string.
        Preferences.setPreference(LLMTranslate.ALLOW_LLM_TRANSLATE, true);

        int port = wireMockRule.port();
        String url = String.format("http://localhost:%d/v1/chat/completions", port);
        System.setProperty(LLMTranslate.PROPERTY_API_URL, url);
        System.setProperty(LLMTranslate.PROPERTY_API_KEY, "test-key");
        System.setProperty(LLMTranslate.PROPERTY_MODEL, "gpt-4o-mini");

        WireMock.stubFor(WireMock.post(WireMock.anyUrl())
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"choices\": [{\"message\": "
                                + "{\"role\": \"assistant\", \"content\": \"Bonjour\"}}]}")));

        LLMTranslate llmTranslate = new LLMTranslate();
        String result = llmTranslate.translate(new Language("EN"), new Language("FR"), "Hello", d -> { });
        assertEquals("Bonjour", result);
    }

    @Test
    public void reasoningModelThinkingChunksProduceNoNull() throws Exception {
        // Reproduces the reported symptom: a reasoning model streams ~100
        // "thinking" chunks where content is JSON null (the thinking goes into
        // reasoning_content) before the real answer. None of these must leak a
        // literal "null" into the translation.
        Preferences.setPreference(LLMTranslate.ALLOW_LLM_TRANSLATE, true);

        int port = wireMockRule.port();
        String url = String.format("http://localhost:%d/v1/chat/completions", port);
        System.setProperty(LLMTranslate.PROPERTY_API_URL, url);
        System.setProperty(LLMTranslate.PROPERTY_API_KEY, "test-key");
        System.setProperty(LLMTranslate.PROPERTY_MODEL, "reasoner");

        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            body.append("data: {\"choices\": [{\"delta\": "
                    + "{\"content\": null, \"reasoning_content\": \"thinking\"}}]}\n\n");
        }
        body.append("data: {\"choices\": [{\"delta\": "
                + "{\"content\": \"Built a PostgreSQL database.\"}}]}\n\n");
        body.append("data: [DONE]\n\n");

        WireMock.stubFor(WireMock.post(WireMock.anyUrl())
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(body.toString())));

        List<String> tokens = new ArrayList<>();
        LLMTranslate llmTranslate = new LLMTranslate();
        String result = llmTranslate.translate(new Language("EN"), new Language("ZH"), "x", tokens::add);

        assertEquals("Built a PostgreSQL database.", result);
        assertFalse("translation must not contain a literal 'null'", result.contains("null"));
        assertEquals(1, tokens.size());
    }

    @Test
    public void httpErrorSurfacesAsMachineTranslateError() throws Exception {
        Preferences.setPreference(LLMTranslate.ALLOW_LLM_TRANSLATE, true);

        int port = wireMockRule.port();
        String url = String.format("http://localhost:%d/v1/chat/completions", port);
        System.setProperty(LLMTranslate.PROPERTY_API_URL, url);
        System.setProperty(LLMTranslate.PROPERTY_API_KEY, "bad-key");
        System.setProperty(LLMTranslate.PROPERTY_MODEL, "gpt-4o-mini");

        WireMock.stubFor(WireMock.post(WireMock.anyUrl())
                .willReturn(WireMock.aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": {\"message\": \"Incorrect API key provided\"}}")));

        LLMTranslate llmTranslate = new LLMTranslate();
        try {
            llmTranslate.translate(new Language("EN"), new Language("FR"), "Hello");
            fail("Expected a MachineTranslateError to be thrown on HTTP 401");
        } catch (MachineTranslateError e) {
            // The message must name the status and the API's own reason so the
            // user can tell a rejected key from a network problem.
            String message = e.getMessage();
            assertNotNull(message);
            assertTrue(message, message.contains("401"));
            assertTrue(message, message.contains("Incorrect API key provided"));
        }
    }
}
