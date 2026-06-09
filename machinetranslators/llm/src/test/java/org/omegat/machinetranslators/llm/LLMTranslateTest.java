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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.Test;

import org.omegat.core.TestCoreWireMock;
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
}
