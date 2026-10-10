package aijob.rewrite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Strict JSON schemas for every structured AI step (all fields required, no extras). */
final class RewriteSchemas {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RewriteSchemas() {
    }

    static ObjectNode string() {
        return MAPPER.createObjectNode().put("type", "string");
    }

    static ObjectNode stringArray() {
        ObjectNode node = MAPPER.createObjectNode().put("type", "array");
        node.set("items", string());
        return node;
    }

    static ObjectNode enumOf(String... values) {
        ObjectNode node = string();
        ArrayNode list = node.putArray("enum");
        for (String value : values) {
            list.add(value);
        }
        return node;
    }

    static ObjectNode object(Object... namesAndTypes) {
        ObjectNode node = MAPPER.createObjectNode().put("type", "object");
        node.put("additionalProperties", false);
        ObjectNode properties = node.putObject("properties");
        ArrayNode required = node.putArray("required");
        for (int i = 0; i < namesAndTypes.length; i += 2) {
            String name = (String) namesAndTypes[i];
            properties.set(name, (ObjectNode) namesAndTypes[i + 1]);
            required.add(name);
        }
        return node;
    }

    static ObjectNode arrayOf(ObjectNode items) {
        ObjectNode node = MAPPER.createObjectNode().put("type", "array");
        node.set("items", items);
        return node;
    }

    /** Free: {changes:[{nodeId, originalText, revisedText, reason}]} */
    static ObjectNode freeChanges() {
        return object("changes", arrayOf(object(
                "nodeId", string(), "originalText", string(), "revisedText", string(), "reason", string())));
    }

    /** Pro step 1: structured job requirements. */
    static ObjectNode jobRequirements() {
        return object(
                "requiredSkills", stringArray(),
                "preferredSkills", stringArray(),
                "responsibilities", stringArray(),
                "keywords", stringArray(),
                "experienceLevel", string());
    }

    /** Pro step 2: evidence status per requirement, and the questions worth asking. */
    static ObjectNode assessment() {
        return object(
                "assessments", arrayOf(object(
                        "requirementId", string(),
                        "status", enumOf("VERIFIED", "INFERRED", "UNSUPPORTED"),
                        "evidenceNodeIds", stringArray(),
                        "note", string())),
                "questions", arrayOf(object(
                        "requirementId", string(), "question", string(), "reason", string())));
    }

    /** Pro step 4: suggestions to review (no file yet). */
    static ObjectNode suggestions() {
        return object("suggestions", arrayOf(object(
                "nodeId", string(), "originalText", string(), "revisedText", string(),
                "reason", string(), "requirementIds", stringArray())));
    }

    /** Pro step 6: final rewrite of the accepted suggestions only. */
    static ObjectNode finalTexts() {
        return object("finalTexts", arrayOf(object("nodeId", string(), "finalText", string())));
    }

    static List<String> texts(com.fasterxml.jackson.databind.JsonNode array) {
        java.util.List<String> values = new java.util.ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(item -> {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    values.add(text);
                }
            });
        }
        return values;
    }
}
