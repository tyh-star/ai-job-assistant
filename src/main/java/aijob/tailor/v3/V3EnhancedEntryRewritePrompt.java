package aijob.tailor.v3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ENHANCED prompt. Independent of frozen V3-2.4 BASIC {@link V3EntryRewritePrompt}.
 */
final class V3EnhancedEntryRewritePrompt {

    private V3EnhancedEntryRewritePrompt() {
    }

    static String build(V3EntryRewriteRequest request) {
        StringBuilder text = new StringBuilder();
        text.append("productMode: ENHANCED\n");
        text.append("promptVersion: ").append(V3GenerationTelemetry.ENHANCED_PROMPT_VERSION).append('\n');
        text.append("canonicalEntryKey: ").append(request.canonicalEntryKey()).append('\n');
        text.append('\n');
        text.append("ORIGINAL ENTRY\n");
        text.append("sectionRole: ").append(request.sectionRole()).append('\n');
        text.append("title: ").append(request.entryTitle()).append('\n');
        text.append("date: ").append(request.entryDate()).append('\n');
        text.append("Do not change the title or dates.\n");
        text.append('\n');
        text.append("RESUME FACTS FROM THIS ENTRY ONLY\n");
        text.append("Use only these facts. Each line is a sourceNodeId plus the natural-language fact.\n");
        for (V3WritableNode node : request.writableNodes()) {
            text.append('[').append(node.nodeId()).append("] ").append(node.originalText()).append('\n');
        }
        text.append("bulletCountLimit: ").append(request.bulletCountLimit()).append('\n');
        text.append("totalCharacterBudget: ").append(request.totalCharacterBudget()).append('\n');
        text.append('\n');
        text.append("USER CONFIRMED FACTS FOR THIS ENTRY\n");
        text.append("You may use these. They are confirmed for this canonicalEntryKey only.\n");
        List<V3UserEvidence> confirmed = request.userConfirmedFacts();
        if (confirmed.isEmpty()) {
            text.append("(none)\n");
        } else {
            for (V3UserEvidence item : confirmed) {
                text.append("- ").append(item.combinedText()).append('\n');
            }
        }
        text.append('\n');
        text.append("USER REJECTED FACTS FOR THIS ENTRY\n");
        text.append("Do not write these facts into the resume.\n");
        List<V3UserEvidence> rejected = request.rejectedFacts();
        if (rejected.isEmpty()) {
            text.append("(none)\n");
        } else {
            for (V3UserEvidence item : rejected) {
                text.append("- ").append(item.combinedText()).append('\n');
            }
        }
        text.append('\n');
        text.append("ALLOWED EVIDENCE BOUNDARY\n");
        text.append("- Resume original facts from this entry.\n");
        text.append("- USER CONFIRMED facts listed above for this entry.\n");
        text.append("- Do not use confirmed facts from any other entry.\n");
        text.append("- Do not treat GENERAL answers as project facts.\n");
        text.append("- Do not invent numbers, actions, skills, or results.\n");
        text.append('\n');
        text.append("RELEVANT JD REQUIREMENTS\n");
        text.append("rankedRequirementIds: ").append(String.join(", ", request.rankedRequirementIds())).append('\n');
        for (V3RankedRequirement requirement : request.rankedRequirements()) {
            text.append(requirement.id()).append(" [").append(requirement.coverage()).append("] ")
                    .append(requirement.text()).append('\n');
        }
        text.append('\n');
        text.append("FORBIDDEN FACTS\n");
        text.append("Do not copy skills, tools, or results that appear only in other resume entries.\n");
        Set<String> forbidden = V3EntryRewritePrompt.forbiddenFacts(request);
        if (forbidden.isEmpty()) {
            text.append("(none listed)\n");
        } else {
            for (String fact : forbidden) {
                text.append("- ").append(fact).append('\n');
            }
        }
        if (request.repair()) {
            text.append('\n');
            text.append("REPAIR both variants. Body-level validator error: ")
                    .append(request.repairError()).append('\n');
            text.append("If unused confirmed facts are listed, incorporate their distinctive facts naturally.\n");
            text.append("Keep original material facts. Do not invent NestJS, metrics, SmartPC facts, or fact keys.\n");
        }
        text.append('\n');
        text.append("JOB DESCRIPTION:\n").append(request.jobDescription()).append('\n');
        text.append('\n');
        text.append("OUTPUT FORMAT\n");
        text.append("Return CLARITY_FIRST and JD_TARGETED. Each variant: decision, rewrittenNodes.\n");
        text.append("Each node: nodeId, newText, alignedRequirementIds, improvementReasons,\n");
        text.append("optional citedSourceNodeIds chosen only from this entry's node ids.\n");
        text.append("If there is no clear improvement, both variants may use decision=NO_CHANGE.\n");
        text.append("Do not rewrite just to look more professional or clearer.\n");
        text.append(V3LanguageGuide.TEXT).append('\n');
        return text.toString();
    }

    static List<V3UserEvidence> confirmedFor(V3EntryRewriteRequest request) {
        return request == null ? List.of() : new ArrayList<>(request.userConfirmedFacts());
    }

    static LinkedHashSet<String> confirmedSurfaces(V3EntryRewriteRequest request) {
        LinkedHashSet<String> surfaces = new LinkedHashSet<>();
        for (V3UserEvidence item : confirmedFor(request)) {
            if (!item.combinedText().isBlank()) {
                surfaces.add(item.combinedText());
            }
        }
        return surfaces;
    }
}
