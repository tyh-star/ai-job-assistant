package aijob.tailor.v3;

import aijob.util.EvidenceTopics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * User-facing whyBetter text. Names confirmed facts that actually entered the
 * final output. Never leaks topic keys or unused Evidence.
 */
final class V3EnhancedWhyBetter {

    record Copy(String en, String zh) {
        Copy {
            en = en == null ? "" : en;
            zh = zh == null ? "" : zh;
        }

        static Copy empty() {
            return new Copy("", "");
        }
    }

    private V3EnhancedWhyBetter() {
    }

    static Copy describe(List<V3UserEvidence> usedInFinal, boolean evidenceEnhanced) {
        if (!evidenceEnhanced || usedInFinal == null || usedInFinal.isEmpty()) {
            return new Copy(
                    "Clarifies the existing project facts without adding unconfirmed details.",
                    "在不增加未确认信息的前提下，把现有项目事实写得更清楚。");
        }
        LinkedHashSet<String> labelsEn = new LinkedHashSet<>();
        LinkedHashSet<String> labelsZh = new LinkedHashSet<>();
        for (V3UserEvidence item : usedInFinal) {
            Label label = labelOf(item);
            if (!label.en.isBlank()) {
                labelsEn.add(label.en);
                labelsZh.add(label.zh);
            }
        }
        if (labelsEn.isEmpty()) {
            return new Copy(
                    "Incorporates your confirmed project details to better match the role’s key requirements.",
                    "结合了你确认的项目经历，使内容与目标职位的关键要求更加匹配。");
        }
        if (labelsEn.contains("Nx") && labelsEn.contains("API contract") && labelsEn.contains("Kafka")
                && labelsEn.size() == 3) {
            return new Copy(
                    "Incorporates your confirmed Nx, API contract, and Kafka experience to better match the role’s key requirements.",
                    "结合了你确认的 Nx、API 契约和 Kafka 经历，使项目与目标职位的关键要求更加匹配。");
        }
        return new Copy(
                "Incorporates your confirmed " + joinEn(new ArrayList<>(labelsEn))
                        + " experience to better match the role’s key requirements.",
                "结合了你确认的 " + String.join("、", labelsZh) + " 经历，使项目与目标职位的关键要求更加匹配。");
    }

    private static Label labelOf(V3UserEvidence item) {
        String topic = item == null ? "" : item.topicKey();
        if (EvidenceTopics.ENHANCED_NX_MONOREPO.equals(topic)) {
            return new Label("Nx", "Nx");
        }
        if (EvidenceTopics.ENHANCED_API_CONTRACT.equals(topic)) {
            return new Label("API contract", "API 契约");
        }
        if (EvidenceTopics.ENHANCED_EVENT_DRIVEN.equals(topic)) {
            return new Label("Kafka", "Kafka");
        }
        if (EvidenceTopics.ENHANCED_MAINTAIN.equals(topic)) {
            return new Label("later-maintenance", "后续维护");
        }
        if (EvidenceTopics.ENHANCED_DATA_STORE.equals(topic)) {
            if (item.displayText().toLowerCase(Locale.ROOT).contains("mysql")) {
                return new Label("MySQL", "MySQL");
            }
            return new Label("data store", "数据存储");
        }
        if (EvidenceTopics.ENHANCED_TESTING.equals(topic)) {
            return new Label("testing", "测试");
        }
        if (EvidenceTopics.ENHANCED_BUILD_TOOL.equals(topic)) {
            return new Label("build tool", "构建工具");
        }
        String surface = item == null ? "" : item.displayText().strip();
        if (surface.toLowerCase(Locale.ROOT).contains("mysql")) {
            return new Label("MySQL", "MySQL");
        }
        if (surface.length() > 48) {
            surface = surface.substring(0, 48).strip();
        }
        if (looksLikeTopicKey(surface)) {
            return new Label("", "");
        }
        return new Label(surface, surface);
    }

    private static boolean looksLikeTopicKey(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.equals("maintain") || lower.equals("testing") || lower.equals("build-tool")
                || lower.equals("data-store") || lower.equals("metric")
                || lower.equals("event-driven") || lower.equals("nx-monorepo")
                || lower.equals("api-contract") || lower.contains("event-driven.")
                || lower.endsWith("metric.");
    }

    private static String joinEn(List<String> labels) {
        if (labels.size() == 1) {
            return labels.get(0);
        }
        if (labels.size() == 2) {
            return labels.get(0) + " and " + labels.get(1);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0 && i == labels.size() - 1) {
                out.append(", and ");
            } else if (i > 0) {
                out.append(", ");
            }
            out.append(labels.get(i));
        }
        return out.toString();
    }

    private record Label(String en, String zh) {
    }
}
