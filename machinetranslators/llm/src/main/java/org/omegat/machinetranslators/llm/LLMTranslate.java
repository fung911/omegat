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

import java.awt.Window;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.text.MessageFormat;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeMap;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.omegat.core.Core;
import org.omegat.core.machinetranslators.BaseCachedTranslate;
import org.omegat.core.machinetranslators.MachineTranslateError;
import org.omegat.gui.exttrans.MTConfigDialog;
import org.omegat.util.HttpConnectionUtils;
import org.omegat.util.Language;
import org.omegat.util.Log;
import org.omegat.util.Preferences;

/**
 * Generic machine translation connector for any OpenAI-compatible chat
 * completion endpoint (OpenAI, Deepseek, a local Ollama or LM Studio server,
 * etc.).
 * <p>
 * The user configures a bearer API key, the endpoint URL, the model name and a
 * free-text prompt template. The template understands three placeholders:
 * <code>{source_lang}</code>, <code>{target_lang}</code> and
 * <code>{text}</code>.
 *
 * @author Team Wheel Reinventor
 * @see <a href="https://platform.openai.com/docs/api-reference/chat">Chat
 *      Completions API</a>
 */
@NullMarked
@SuppressWarnings("unused")
public class LLMTranslate extends BaseCachedTranslate {

    public static final String ALLOW_LLM_TRANSLATE = "allow_llm_translate";

    protected static final String PROPERTY_API_KEY = "llm.api.key";
    protected static final String PROPERTY_API_URL = "llm.api.url";
    protected static final String PROPERTY_MODEL = "llm.api.model";
    protected static final String PROPERTY_PROMPT_TEMPLATE = "llm.prompt.template";

    protected static final String DEFAULT_API_URL = "https://api.openai.com/v1/chat/completions";
    protected static final String DEFAULT_MODEL = "gpt-4o-mini";
    protected static final String DEFAULT_PROMPT_TEMPLATE =
            "You are a professional translator. Translate the following text\n"
            + "from {source_lang} to {target_lang}. Return ONLY the translated\n"
            + "text, no explanation.\n\n{text}";

    private static final String BUNDLE_BASENAME = "org.omegat.machinetranslators.llm.Bundle";
    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle(BUNDLE_BASENAME);

    /** Shared, thread-safe JSON mapper. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Maximum length of an API error body echoed back to the user. */
    private static final int MAX_ERROR_DETAIL = 300;

    /** Connect/read timeout for the API call, so a dead endpoint fails fast. */
    private static final int REQUEST_TIMEOUT_MS = 60_000;

    /**
     * Register the connector into OmegaT.
     */
    public static void loadPlugins() {
        Core.registerMachineTranslationClass(LLMTranslate.class);
    }

    public static void unloadPlugins() {
    }

    @Override
    protected String getPreferenceName() {
        return ALLOW_LLM_TRANSLATE;
    }

    @Override
    public String getName() {
        return BUNDLE.getString("MT_ENGINE_LLM");
    }

    @Override
    protected @Nullable String translate(Language sLang, Language tLang, String text) throws Exception {
        String prompt = renderPrompt(getPromptTemplate(), sLang, tLang, text);
        String json = createJsonRequest(getModel(), prompt, false);
        String response = post(getApiUrl(), json, buildHeaders(false));
        return cleanSpacesAroundTags(getJsonResults(response), text);
    }

    @Override
    protected @Nullable String translate(Language sLang, Language tLang, String text,
            Consumer<String> partialConsumer) throws Exception {
        String prompt = renderPrompt(getPromptTemplate(), sLang, tLang, text);
        String json = createJsonRequest(getModel(), prompt, true);
        String full = postStreaming(getApiUrl(), json, buildHeaders(true), partialConsumer);
        return cleanSpacesAroundTags(full, text);
    }

    private Map<String, String> buildHeaders(boolean streaming) {
        String apiKey = getCredential(PROPERTY_API_KEY);
        Map<String, String> headers = new TreeMap<>();
        headers.put("Accept", streaming ? "text/event-stream" : "application/json");
        // Local providers such as Ollama or LM Studio need no key; only send the
        // Authorization header when one is configured.
        if (!apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        return headers;
    }

    /**
     * POST the request body and translate any low-level network failure into a
     * {@link MachineTranslateError} that carries a human-readable message. This
     * is what lets OmegaT report timeouts, wrong URLs and rejected keys to the
     * user (in the status bar, prefixed with the engine name) instead of
     * failing silently.
     */
    protected String post(String url, String json, Map<String, String> headers) throws MachineTranslateError {
        try {
            return HttpConnectionUtils.postJSON(url, json, headers, REQUEST_TIMEOUT_MS);
        } catch (HttpConnectionUtils.ResponseError e) {
            // The server answered with a non-200 status (bad key, unknown model,
            // wrong path...). Echo the API's own error body, which is the most
            // useful diagnostic.
            Log.log(e);
            String detail = (e.body != null && !e.body.isEmpty()) ? e.body.trim() : e.message;
            if (detail.length() > MAX_ERROR_DETAIL) {
                detail = detail.substring(0, MAX_ERROR_DETAIL) + "…";
            }
            throw new MachineTranslateError(
                    MessageFormat.format(BUNDLE.getString("MT_ENGINE_LLM_HTTP_ERROR"), e.code, detail));
        } catch (SocketTimeoutException e) {
            Log.log(e);
            throw new MachineTranslateError(BUNDLE.getString("MT_ENGINE_LLM_TIMEOUT"));
        } catch (IOException e) {
            // Unknown host, connection refused, DNS failure, malformed URL, etc.
            Log.log(e);
            String reason = e.getMessage() != null ? e.getMessage() : e.toString();
            throw new MachineTranslateError(
                    MessageFormat.format(BUNDLE.getString("MT_ENGINE_LLM_CONNECTION_ERROR"), url, reason));
        }
    }

    /**
     * Stream the response as OpenAI server-sent events, pushing each token to
     * {@code partialConsumer} as it arrives and returning the assembled text.
     * Network failures are mapped to {@link MachineTranslateError} exactly like
     * {@link #post(String, String, Map)}.
     */
    protected String postStreaming(String url, String json, Map<String, String> headers,
            Consumer<String> partialConsumer) throws Exception {
        StringBuilder assembled = new StringBuilder();
        StringBuilder rawBody = new StringBuilder();
        try {
            HttpConnectionUtils.postJSONStreaming(url, json, headers, REQUEST_TIMEOUT_MS, line -> {
                rawBody.append(line).append('\n');
                String delta = parseSseDelta(line);
                if (delta != null && !delta.isEmpty()) {
                    assembled.append(delta);
                    partialConsumer.accept(delta);
                }
            });
        } catch (HttpConnectionUtils.ResponseError e) {
            Log.log(e);
            String detail = (e.body != null && !e.body.isEmpty()) ? e.body.trim() : e.message;
            if (detail.length() > MAX_ERROR_DETAIL) {
                detail = detail.substring(0, MAX_ERROR_DETAIL) + "…";
            }
            throw new MachineTranslateError(
                    MessageFormat.format(BUNDLE.getString("MT_ENGINE_LLM_HTTP_ERROR"), e.code, detail));
        } catch (SocketTimeoutException e) {
            Log.log(e);
            throw new MachineTranslateError(BUNDLE.getString("MT_ENGINE_LLM_TIMEOUT"));
        } catch (IOException e) {
            Log.log(e);
            String reason = e.getMessage() != null ? e.getMessage() : e.toString();
            throw new MachineTranslateError(
                    MessageFormat.format(BUNDLE.getString("MT_ENGINE_LLM_CONNECTION_ERROR"), url, reason));
        }
        if (assembled.length() == 0) {
            // The server ignored stream:true and returned a single JSON body.
            // Parse it as a normal completion so we never return an empty result.
            String full = getJsonResults(rawBody.toString());
            partialConsumer.accept(full);
            return full;
        }
        return assembled.toString();
    }

    /**
     * Extract the incremental <code>choices[0].delta.content</code> from a
     * single SSE line, or return null for blank, keep-alive or
     * <code>[DONE]</code> lines.
     */
    private @Nullable String parseSseDelta(String line) {
        if (!line.startsWith("data:")) {
            return null;
        }
        String payload = line.substring("data:".length()).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(payload);
            JsonNode choices = node.get("choices");
            if (choices != null && choices.has(0)) {
                JsonNode delta = choices.get(0).get("delta");
                JsonNode content = delta == null ? null : delta.get("content");
                // Only real string content counts. Role-only or keep-alive
                // chunks carry a JSON null here, whose asText() would otherwise
                // yield the literal string "null".
                if (content != null && content.isTextual()) {
                    return content.asText();
                }
            }
        } catch (IOException e) {
            // Ignore an unparseable keep-alive/comment line and keep reading.
            Log.log(e);
        }
        return null;
    }

    /**
     * Substitute the <code>{source_lang}</code>, <code>{target_lang}</code> and
     * <code>{text}</code> placeholders in the prompt template.
     */
    protected String renderPrompt(String template, Language sLang, Language tLang, String text) {
        return template.replace("{source_lang}", sLang.getDisplayName())
                .replace("{target_lang}", tLang.getDisplayName())
                .replace("{text}", text);
    }

    /**
     * Build a non-streaming OpenAI chat-completion request body.
     */
    protected String createJsonRequest(String model, String prompt) throws JsonProcessingException {
        return createJsonRequest(model, prompt, false);
    }

    /**
     * Build the OpenAI chat-completion request body, optionally enabling the
     * streaming response mode.
     */
    protected String createJsonRequest(String model, String prompt, boolean stream)
            throws JsonProcessingException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);
        root.put("temperature", 0);
        if (stream) {
            root.put("stream", true);
        }
        return MAPPER.writeValueAsString(root);
    }

    /**
     * Extract <code>choices[0].message.content</code> from the response.
     *
     * @param json
     *            the raw response body.
     * @return the translated text.
     */
    protected String getJsonResults(String json) throws Exception {
        JsonNode rootNode;
        try {
            rootNode = MAPPER.readTree(json);
        } catch (Exception e) {
            Log.logErrorRB(e, "MT_JSON_ERROR");
            throw new MachineTranslateError(BUNDLE.getString("MT_JSON_ERROR"));
        }
        JsonNode choices = rootNode.get("choices");
        if (choices != null && choices.has(0)) {
            JsonNode message = choices.get(0).get("message");
            if (message != null && message.get("content") != null) {
                return message.get("content").asText();
            }
        }
        Log.logErrorRB("MT_JSON_ERROR");
        throw new MachineTranslateError(BUNDLE.getString("MT_JSON_ERROR"));
    }

    private String getApiUrl() {
        String url = System.getProperty(PROPERTY_API_URL, Preferences.getPreference(PROPERTY_API_URL));
        if (url == null || url.isEmpty()) {
            url = DEFAULT_API_URL;
        }
        return url;
    }

    private String getModel() {
        String model = System.getProperty(PROPERTY_MODEL, Preferences.getPreference(PROPERTY_MODEL));
        if (model == null || model.isEmpty()) {
            model = DEFAULT_MODEL;
        }
        return model;
    }

    private String getPromptTemplate() {
        String template = System.getProperty(PROPERTY_PROMPT_TEMPLATE,
                Preferences.getPreference(PROPERTY_PROMPT_TEMPLATE));
        if (template == null || template.isEmpty()) {
            template = DEFAULT_PROMPT_TEMPLATE;
        }
        return template;
    }

    /**
     * Engine is configurable.
     *
     * @return true
     */
    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public void showConfigurationUI(Window parent) {
        JPanel llmPanel = new JPanel();
        llmPanel.setLayout(new java.awt.GridBagLayout());
        llmPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 15, 0));
        llmPanel.setAlignmentX(0.0F);

        java.awt.GridBagConstraints gridBagConstraints;

        // Model name
        JLabel modelLabel = new JLabel(BUNDLE.getString("MT_ENGINE_LLM_MODEL_LABEL"));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 10, 5);
        llmPanel.add(modelLabel, gridBagConstraints);

        JTextField modelField = new JTextField(getModel());
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.gridwidth = java.awt.GridBagConstraints.REMAINDER;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.ipadx = 50;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 10, 0);
        modelLabel.setLabelFor(modelField);
        llmPanel.add(modelField, gridBagConstraints);

        // Prompt template
        JLabel promptLabel = new JLabel(BUNDLE.getString("MT_ENGINE_LLM_PROMPT_LABEL"));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.gridwidth = java.awt.GridBagConstraints.REMAINDER;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 5, 0);
        llmPanel.add(promptLabel, gridBagConstraints);

        JTextArea promptArea = new JTextArea(getPromptTemplate(), 6, 40);
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        JScrollPane promptScroll = new JScrollPane(promptArea);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.gridwidth = java.awt.GridBagConstraints.REMAINDER;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 5, 0);
        promptLabel.setLabelFor(promptArea);
        llmPanel.add(promptScroll, gridBagConstraints);

        // Reset the prompt template to the built-in default
        JButton resetButton = new JButton(BUNDLE.getString("MT_ENGINE_LLM_RESET_PROMPT"));
        resetButton.addActionListener(e -> promptArea.setText(DEFAULT_PROMPT_TEMPLATE));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.gridwidth = java.awt.GridBagConstraints.REMAINDER;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        llmPanel.add(resetButton, gridBagConstraints);

        MTConfigDialog dialog = new MTConfigDialog(parent, getName()) {
            @Override
            protected void onConfirm() {
                boolean temporary = panel.temporaryCheckBox.isSelected();

                String apiKey = panel.valueField1.getText().trim();
                setCredential(PROPERTY_API_KEY, apiKey, temporary);

                String apiUrl = panel.valueField2.getText().trim();
                System.setProperty(PROPERTY_API_URL, apiUrl);
                Preferences.setPreference(PROPERTY_API_URL, apiUrl);

                String model = modelField.getText().trim();
                System.setProperty(PROPERTY_MODEL, model);
                Preferences.setPreference(PROPERTY_MODEL, model);

                String prompt = promptArea.getText();
                System.setProperty(PROPERTY_PROMPT_TEMPLATE, prompt);
                Preferences.setPreference(PROPERTY_PROMPT_TEMPLATE, prompt);

                // The cache key does not include the model or prompt, so any
                // configuration change invalidates the previously cached results.
                clearCache();
            }
        };

        dialog.panel.valueLabel1.setText(BUNDLE.getString("MT_ENGINE_LLM_API_KEY_LABEL"));
        dialog.panel.valueField1.setText(getCredential(PROPERTY_API_KEY));

        dialog.panel.valueLabel2.setText(BUNDLE.getString("MT_ENGINE_LLM_API_URL_LABEL"));
        dialog.panel.valueField2.setText(getApiUrl());

        dialog.panel.temporaryCheckBox.setSelected(isCredentialStoredTemporarily(PROPERTY_API_KEY));

        dialog.panel.itemsPanel.add(llmPanel);

        dialog.show();
    }
}
