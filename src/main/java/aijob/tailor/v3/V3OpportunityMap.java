package aijob.tailor.v3;

import java.util.List;

/**
 * V3-3A Opportunity Map. Does not write jobs, resumes, proposals, or files.
 */
public record V3OpportunityMap(
        List<V3OpportunityEntry> entries,
        List<V3EnhancedQuestion> questions,
        List<String> skippedQuestionIds,
        int writerCalls,
        int openaiCalls,
        boolean wroteJobOrResume
) {
    public V3OpportunityMap {
        entries = entries == null ? List.of() : List.copyOf(entries);
        questions = questions == null ? List.of() : List.copyOf(questions);
        skippedQuestionIds = skippedQuestionIds == null ? List.of() : List.copyOf(skippedQuestionIds);
    }

    public static V3OpportunityMap empty() {
        return new V3OpportunityMap(List.of(), List.of(), List.of(), 0, 0, false);
    }
}
