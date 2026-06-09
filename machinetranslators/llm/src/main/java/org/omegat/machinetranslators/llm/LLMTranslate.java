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
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeMap;

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
        String apiKey = getCredential(PROPERTY_API_KEY);
        String prompt = renderPrompt(getPromptTemplate(), sLang, tLang, text);
        String json = createJsonRequest(getModel(), prompt);

        Map<String, String> headers = new TreeMap<>();
        headers.put("Accept", "application/json");
        // Local providers such as Ollama or LM Studio need no key; only send the
        // Authorization header when one is configured.
        if (!apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }

        String response = HttpConnectionUtils.postJSON(getApiUrl(), json, headers);
        String translation = getJsonResults(response);
        return cleanSpacesAroundTags(translation, text);
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
     * Build the OpenAI chat-completion request body.
     */
    protected String createJsonRequest(String model, String prompt) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);
        root.put("temperature", 0);
        return mapper.writeValueAsString(root);
    }

    /**
     * Extract <code>choices[0].message.content</code> from the response.
     *
     * @param json
     *            the raw response body.
     * @return the translated text.
     */
    protected String getJsonResults(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode;
        try {
            rootNode = mapper.readTree(json);
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
