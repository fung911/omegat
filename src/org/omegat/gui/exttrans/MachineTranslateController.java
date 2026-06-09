/**************************************************************************
 OmegaT - Computer Assisted Translation (CAT) tool
          with fuzzy matching, translation memory, keyword search,
          glossaries, and translation leveraging into updated projects.

 Copyright (C) 2009-2010 Alex Buloichik
               2011 Martin Fleurke
               2012 Jean-Christophe Helary
               2015 Aaron Madlon-Kay
               2018 Thomas Cordonnier
               2022-2025 Hiroshi Miura
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
 **************************************************************************/
package org.omegat.gui.exttrans;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import javax.swing.Timer;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;
import org.omegat.core.Core;
import org.omegat.core.data.CoreState;
import org.omegat.core.data.SourceTextEntry;
import org.omegat.gui.glossary.GlossaryEntry;
import org.omegat.util.OStrings;

/**
 * Controller for MachineTranslateTextArea following MVC. Responsible for
 * orchestrating MT searches and managing search threads.
 */
public class MachineTranslateController {
    private static final int ANIM_INTERVAL_MS = 30;
    private static final int REVEAL_MIN_STEP = 2;
    private static final int REVEAL_DIVISOR = 12;
    private static final int SPINNER_SLOWDOWN = 6;
    private static final char[] SPINNER = {'|', '/', '-', '\\'};

    private final MachineTranslateTextArea view;
    private final List<MachineTranslateFindThread> searchThreads = new ArrayList<>();

    /**
     * List displayed hold entries. An index shall be as same as ID attribute
     * value of HTML. Actual displayed entries are sorted, and the order is
     * different from the List.
     */
    private final List<MachineTranslationInfo> displayed = new CopyOnWriteArrayList<>();

    /** Engines whose request is currently in flight (shown as "translating"). */
    private final Set<String> pending = new LinkedHashSet<>();

    /** Engines that failed, mapped to their error message. */
    private final Map<String, String> errors = new LinkedHashMap<>();

    /** Engines that stream, mapped to the full text received so far. */
    private final Map<String, String> partial = new LinkedHashMap<>();

    /** How many characters of each engine's text are currently revealed. */
    private final Map<String, Integer> revealed = new LinkedHashMap<>();

    /** Completed engines whose typewriter reveal has not yet caught up. */
    private final Map<String, MachineTranslationInfo> finalizing = new LinkedHashMap<>();

    /** Drives the animated indicator and the typewriter reveal while requests run. */
    private final Timer pendingTimer;
    private int animationTick;

    public MachineTranslateController(MachineTranslateTextArea view) {
        this.view = view;
        selectedIndex = -1;
        pendingTimer = new Timer(ANIM_INTERVAL_MS, e -> {
            animationTick++;
            advanceReveal();
            promoteFinishedReveals();
            render();
        });
        setGlossaryMap();
    }

    /**
     * Configures the glossary map for machine translation connectors. This
     * method sets the glossary map supplier for all machine translation
     * services by utilizing the `getGlossaryMap` method as the supplier.
     * <p>
     * The glossary map provides the source-to-target text mappings that can be
     * used by machine translation services to improve translation output
     * quality or consistency.
     * <p>
     * This will override for testing purposes.
     */
    @VisibleForTesting
    void setGlossaryMap() {
        CoreState.getInstance().getMachineTranslatorsManager().setGlossaryMap(this::getGlossaryMap);
    }

    /**
     * Retrieves a glossary map containing source-to-target text mappings. This
     * map is constructed using the glossary entries found from the source text
     * currently being processed.
     *
     * @return a map where keys are source text strings, and values are their
     *         corresponding localized text strings, as per the glossary
     *         entries.
     */
    Map<String, String> getGlossaryMap() {
        if (view.getCurrentlyProcessedEntry() == null) {
            return Map.of();
        }
        return Core.getGlossaryManager().searchSourceMatches(view.getCurrentlyProcessedEntry()).stream()
                .collect(Collectors.toMap(GlossaryEntry::getSrcText, GlossaryEntry::getLocText));
    }

    /** Cycle getDisplayedTranslation **/
    private int selectedIndex;

    @Nullable
    MachineTranslationInfo getDisplayedResult() {
        if (displayed.isEmpty()) {
            return null;
        }
        selectedIndex = (selectedIndex + 1) % displayed.size();
        MachineTranslationInfo info = displayed.get(selectedIndex);
        view.highlightSelected(selectedIndex, info);
        return info;
    }

    void setFoundResult(MachineTranslationInfo data) {
        String name = data.translatorName;
        if (data.result != null && partial.containsKey(name)) {
            // The engine produced streamed text (or one fallback chunk): keep it
            // "pending" and let the timer finish the typewriter reveal before
            // promoting it to a final, selectable result. This guarantees a
            // visible character-by-character effect even when the whole response
            // arrived in a single burst.
            errors.remove(name);
            partial.put(name, data.result);
            finalizing.put(name, data);
            if (!pendingTimer.isRunning()) {
                startPendingAnimation();
            }
        } else {
            // Instant result (e.g. a cache hit) or an error / nothing found.
            pending.remove(name);
            partial.remove(name);
            revealed.remove(name);
            finalizing.remove(name);
            if (data.result != null) {
                errors.remove(name);
                displayed.add(data);
                displayed.sort(Comparator.comparing(info -> info.translatorName));
            } else if (data.errorMessage != null) {
                errors.put(name, data.errorMessage);
            }
            if (pending.isEmpty()) {
                stopPendingAnimation();
            }
        }
        render();
    }

    /**
     * Accumulate a streamed chunk for an engine. The pane is not refreshed here;
     * the timer reveals the accumulated text at a steady pace so the typewriter
     * effect stays visible regardless of how fast the chunks actually arrive.
     */
    void appendPartial(String engineName, String delta) {
        if (pending.contains(engineName)) {
            partial.merge(engineName, delta, String::concat);
        }
    }

    /** Advance the revealed-character count of each streaming engine by one step. */
    private void advanceReveal() {
        for (String name : pending) {
            String full = partial.get(name);
            if (full == null) {
                continue;
            }
            int shown = revealed.getOrDefault(name, 0);
            if (shown < full.length()) {
                int gap = full.length() - shown;
                int step = Math.max(REVEAL_MIN_STEP, gap / REVEAL_DIVISOR);
                revealed.put(name, Math.min(full.length(), shown + step));
            }
        }
    }

    /** Promote completed engines to final results once their reveal has caught up. */
    private void promoteFinishedReveals() {
        Iterator<Map.Entry<String, MachineTranslationInfo>> it = finalizing.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, MachineTranslationInfo> entry = it.next();
            String name = entry.getKey();
            String full = partial.get(name);
            int shown = revealed.getOrDefault(name, 0);
            if (full == null || shown >= full.length()) {
                displayed.add(entry.getValue());
                displayed.sort(Comparator.comparing(info -> info.translatorName));
                pending.remove(name);
                partial.remove(name);
                revealed.remove(name);
                it.remove();
            }
        }
        if (pending.isEmpty()) {
            stopPendingAnimation();
        }
    }

    /**
     * Rebuild the pane: successful results first (these are selectable for
     * insertion), then errors, then an animated "translating" row for every
     * engine whose request is still in flight.
     */
    private void render() {
        StringBuilder sb = new StringBuilder("<html>");
        for (int i = 0; i < displayed.size(); i++) {
            MachineTranslationInfo info = displayed.get(i);
            sb.append("<div id=\"").append(i).append("\">");
            sb.append(info.result);
            sb.append("<div class=\"engine\">&lt;").append(escapeHtml(info.translatorName))
                    .append("&gt;</div></div>");
        }
        for (Map.Entry<String, String> error : errors.entrySet()) {
            sb.append("<div class=\"mterror\">").append(escapeHtml(error.getValue()));
            sb.append("<div class=\"engine\">&lt;").append(escapeHtml(error.getKey()))
                    .append("&gt;</div></div>");
        }
        if (!pending.isEmpty()) {
            char spinner = SPINNER[(animationTick / SPINNER_SLOWDOWN) % SPINNER.length];
            String label = OStrings.getString("MT_PENDING");
            for (String name : pending) {
                sb.append("<div class=\"mtpending\">");
                String full = partial.get(name);
                if (full != null && !full.isEmpty()) {
                    // Streaming under way: reveal the text so far with a cursor.
                    int shown = Math.min(revealed.getOrDefault(name, 0), full.length());
                    sb.append(escapeHtml(full.substring(0, shown))).append(spinner);
                } else {
                    // Request sent, nothing back yet.
                    sb.append(spinner).append(' ').append(label);
                }
                sb.append("<div class=\"engine\">&lt;").append(escapeHtml(name))
                        .append("&gt;</div></div>");
            }
        }
        sb.append("</html>");
        view.setText(sb.toString());
    }

    private void startPendingAnimation() {
        animationTick = 0;
        if (!pendingTimer.isRunning()) {
            pendingTimer.start();
        }
    }

    private void stopPendingAnimation() {
        pendingTimer.stop();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    void clearFoundResult() {
        displayed.clear();
        errors.clear();
        pending.clear();
        partial.clear();
        revealed.clear();
        finalizing.clear();
        selectedIndex = -1;
        stopPendingAnimation();
    }

    /**
     * Start MT search for a new entry.
     */
    public void startSearchThread(SourceTextEntry newEntry) {
        startSearchThread(newEntry, false);
    }

    /**
     * Start MT search for a new entry with an option to force network fetch.
     */
    public void startSearchThread(SourceTextEntry newEntry, boolean force) {
        // clear view and stop any running threads first
        view.clear();
        stopSearchThreads();
        synchronized (searchThreads) {
            for (IMachineTranslation mt : getMachineTranslators()) {
                if (mt.isEnabled()) {
                    pending.add(mt.getName());
                    MachineTranslateFindThread mtSearchThread = new MachineTranslateFindThread(view, mt,
                            newEntry, force);
                    searchThreads.add(mtSearchThread);
                    mtSearchThread.start();
                }
            }
        }
        if (!pending.isEmpty()) {
            startPendingAnimation();
            render();
        }
    }

    /**
     * Force reload for the current entry.
     */
    public void forceLoad() {
        SourceTextEntry current = view.getCurrentlyProcessedEntry();
        if (current != null) {
            // check if any thread is running
            synchronized (searchThreads) {
                for (MachineTranslateFindThread thread : searchThreads) {
                    if (thread.isAlive()) {
                        return;
                    }
                }
            }
            startSearchThread(current, true);
        }
    }

    /**
     * Stop all running search threads.
     */
    public void stopSearchThreads() {
        synchronized (searchThreads) {
            for (MachineTranslateFindThread thread : searchThreads) {
                if (thread.isAlive()) {
                    thread.interrupt();
                }
            }
            searchThreads.clear();
        }
    }

    /**
     * Get all machine translation providers.
     */
    private List<IMachineTranslation> getMachineTranslators() {
        return CoreState.getInstance().getMachineTranslatorsManager().getMachineTranslators();
    }
}
