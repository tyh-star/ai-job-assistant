package aijob.tailor.v3;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * V3-2.3 prompt: natural-language facts and node ids only. No internal fact keys.
 */
final class V3EntryRewritePrompt {

    private V3EntryRewritePrompt() {
    }

    static String build(V3EntryRewriteRequest request) {
        StringBuilder text = new StringBuilder();
        text.append("productMode: ").append(request.productMode()).append('\n');
        text.append("canonicalEntryKey: ").append(request.canonicalEntryKey()).append('\n');
        text.append('\n');
        text.append("ORIGINAL ENTRY\n");
        text.append("sectionRole: ").append(request.sectionRole()).append('\n');
        text.append("title: ").append(request.entryTitle()).append('\n');
        text.append("date: ").append(request.entryDate()).append('\n');
        text.append("Do not change the title or dates.\n");
        text.append('\n');
        text.append("ALLOWED FACTS FROM THIS ENTRY\n");
        text.append("Use only these facts. Each line is a sourceNodeId plus the natural-language fact.\n");
        text.append("Do not emit internal fact keys.\n");
        for (V3WritableNode node : request.writableNodes()) {
            text.append('[').append(node.nodeId()).append("] ").append(node.originalText()).append('\n');
        }
        text.append("bulletCountLimit: ").append(request.bulletCountLimit()).append('\n');
        text.append("totalCharacterBudget: ").append(request.totalCharacterBudget()).append('\n');
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
        Set<String> forbidden = forbiddenFacts(request);
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
            text.append("Fix the entry text. Do not invent fact keys.\n");
        }
        text.append('\n');
        text.append("JOB DESCRIPTION:\n").append(request.jobDescription()).append('\n');
        text.append('\n');
        text.append("OUTPUT FORMAT\n");
        text.append("Return CLARITY_FIRST and JD_TARGETED. Each variant: decision, rewrittenNodes.\n");
        text.append("Each node: nodeId, newText, alignedRequirementIds, improvementReasons,\n");
        text.append("optional citedSourceNodeIds chosen only from this entry's node ids.\n");
        text.append("citedSourceNodeIds are explanations only. The server decides fact coverage.\n");
        text.append(V3LanguageGuide.TEXT).append('\n');
        return text.toString();
    }

    static Set<String> forbiddenFacts(V3EntryRewriteRequest request) {
        LinkedHashSet<String> forbidden = new LinkedHashSet<>();
        V3ResumeFactGraphs resume = request.resumeFactGraphs();
        if (resume == null) {
            return forbidden;
        }
        for (V3EntryFactGraph other : resume.entries()) {
            if (other.canonicalEntryKey().equals(request.canonicalEntryKey())) {
                continue;
            }
            String label = other.originalHeading().isBlank()
                    ? other.canonicalEntryKey()
                    : other.originalHeading();
            forbidden.add("any skill, tool, or result that appears only in " + label);
        }
        return forbidden;
    }
}
