package aijob.tailor.v3;

import java.util.ArrayList;
import java.util.List;

public final class V3RewriteTestSupport {

    private V3RewriteTestSupport() {
    }

    public static V3ResumeLanguageReviewer acceptBestAi() {
        return (jobDescription, entries) -> {
            List<V3ResumeLanguageReviewer.Choice> choices = new ArrayList<>();
            for (V3ResumeLanguageReviewer.EntryComparison entry : entries) {
                V3SelectionChoice choice = V3SelectionChoice.ORIGINAL;
                if (entry.jdTargeted() != null) {
                    choice = V3SelectionChoice.JD_TARGETED;
                } else if (entry.clarityFirst() != null) {
                    choice = V3SelectionChoice.CLARITY_FIRST;
                }
                choices.add(new V3ResumeLanguageReviewer.Choice(entry.canonicalEntryKey(), choice, "ok"));
            }
            return choices;
        };
    }

    static V3EntryRewriteVariant variant(
            V3VariantType type,
            V3EntryRewriteRequest request,
            String... bullets) {
        List<V3RewrittenNode> nodes = new ArrayList<>();
        List<V3WritableNode> writable = request.writableNodes();
        for (int i = 0; i < writable.size(); i++) {
            String text = i < bullets.length ? bullets[i] : writable.get(i).originalText();
            nodes.add(new V3RewrittenNode(
                    writable.get(i).nodeId(), text, List.of(), List.of(), "COORDINATED", "test"));
        }
        return new V3EntryRewriteVariant(
                type,
                V3RewriteDecision.REWRITE,
                nodes,
                "test",
                List.of(),
                List.of(),
                List.of(),
                V3SelfCheck.empty());
    }

    public static V3EntryRewriteResult rewriteBoth(V3EntryRewriteRequest request, String... bullets) {
        return V3EntryRewriteResult.both(
                request.canonicalEntryKey(),
                variant(V3VariantType.CLARITY_FIRST, request, bullets),
                variant(V3VariantType.JD_TARGETED, request, bullets));
    }

    static V3EntryRewriteResult rewriteSplit(
            V3EntryRewriteRequest request,
            List<String> clarity,
            List<String> targeted) {
        return V3EntryRewriteResult.both(
                request.canonicalEntryKey(),
                variant(V3VariantType.CLARITY_FIRST, request, clarity.toArray(String[]::new)),
                variant(V3VariantType.JD_TARGETED, request, targeted.toArray(String[]::new)));
    }
}
