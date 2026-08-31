package aijob.service.impl;

import aijob.dto.AnswerStatus;
import aijob.dto.EntryOption;
import aijob.dto.EvidenceReasonCode;
import aijob.dto.RequirementCategory;
import aijob.dto.ResumeEvidenceItemResponse;
import aijob.dto.SaveResumeEvidenceRequest;
import aijob.entity.Resume;
import aijob.entity.ResumeEvidenceItem;
import aijob.entity.User;
import aijob.exception.EvidenceConflictException;
import aijob.exception.InvalidEvidenceRequestException;
import aijob.exception.ResumeNotFoundException;
import aijob.exception.UserNotFoundException;
import aijob.mapper.ResumeEvidenceMapper;
import aijob.mapper.ResumeMapper;
import aijob.mapper.UserMapper;
import aijob.service.ResumeEvidenceService;
import aijob.util.ClaimLedger;
import aijob.util.EvidenceSemanticValidator;
import aijob.util.EvidenceTopics;
import aijob.util.ResumeAnalysisInput;
import aijob.util.ResumeAnalysisInputFactory;
import aijob.util.ResumeEntryCatalog;
import aijob.util.ResumeFamily;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ResumeEvidenceServiceImpl implements ResumeEvidenceService {

    private static final int TOPIC_MAX = 100;
    private static final int TEXT_MAX = 1000;
    private static final int METRIC_MAX = 255;

    private final UserMapper userMapper;
    private final ResumeMapper resumeMapper;
    private final ResumeEvidenceMapper resumeEvidenceMapper;
    private final ResumeAnalysisInputFactory analysisInputFactory;
    private final ObjectMapper objectMapper;

    public ResumeEvidenceServiceImpl(
            UserMapper userMapper,
            ResumeMapper resumeMapper,
            ResumeEvidenceMapper resumeEvidenceMapper,
            ResumeAnalysisInputFactory analysisInputFactory,
            ObjectMapper objectMapper) {
        this.userMapper = userMapper;
        this.resumeMapper = resumeMapper;
        this.resumeEvidenceMapper = resumeEvidenceMapper;
        this.analysisInputFactory = analysisInputFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ResumeEvidenceItemResponse> list(String email, Long resumeId) {
        User user = requireUser(email);
        Resume selected = ResumeFamily.requireOwned(resumeMapper, resumeId, user.getId());
        ResumeAnalysisInput input = analysisInputFactory.resolve(selected, user.getId());
        Resume root = input.uploadRootResume();
        List<ResumeEvidenceItem> items = resumeEvidenceMapper.findByResumeAndUser(root.getId(), user.getId());
        List<ResumeEvidenceItemResponse> responses = new ArrayList<>();
        if (items != null) {
            for (ResumeEvidenceItem item : items) {
                responses.add(toResponse(item));
            }
        }
        return responses;
    }

    @Override
    @Transactional
    public SaveResult save(String email, Long resumeId, SaveResumeEvidenceRequest request) {
        User user = requireUser(email);
        Resume selected = ResumeFamily.requireOwned(resumeMapper, resumeId, user.getId());
        ResumeAnalysisInput input = analysisInputFactory.resolve(selected, user.getId());
        Resume root = input.uploadRootResume();
        if (request == null || !Boolean.TRUE.equals(request.getConfirmed())) {
            throw new InvalidEvidenceRequestException("confirmed must be true");
        }

        String topic = trimToNull(request.getTopic());
        if (topic == null || topic.length() > TOPIC_MAX) {
            throw new InvalidEvidenceRequestException("topic is required and must be at most 100 characters");
        }
        String topicKey = EvidenceTopics.topicKey(topic);
        String answerStatus = request.getAnswerStatus() == null ? "" : request.getAnswerStatus().strip();
        if (!AnswerStatus.isValid(answerStatus)) {
            throw new InvalidEvidenceRequestException("answerStatus is invalid");
        }
        String category = normalizeCategory(request.getCategory());
        String context = bound(request.getContext(), TEXT_MAX, "context");
        String action = bound(request.getAction(), TEXT_MAX, "action");
        String result = bound(request.getResult(), TEXT_MAX, "result");
        String metric = bound(request.getMetric(), METRIC_MAX, "metric");

        List<EntryOption> catalog = ResumeEntryCatalog.options(input.matchingNodes(), input.structuredResume());
        String entryKey = request.getEntryKey() == null || request.getEntryKey().isBlank()
                ? ResumeEntryCatalog.GENERAL
                : request.getEntryKey().strip();
        EntryOption selectedEntry = findEntry(catalog, entryKey);
        if (selectedEntry == null) {
            throw new InvalidEvidenceRequestException(
                    EvidenceReasonCode.EVIDENCE_ENTRY_MISMATCH, "entryKey is not a valid catalog option");
        }
        List<String> relatedNodeIds = normalizeNodeIds(request.getRelatedNodeIds());
        EvidenceSemanticValidator.Result validation = EvidenceSemanticValidator.validate(
                new EvidenceSemanticValidator.Input(
                        topic,
                        category,
                        topic,
                        answerStatus,
                        context,
                        action,
                        result,
                        metric,
                        entryKey,
                        relatedNodeIds,
                        selectedEntry,
                        ClaimLedger.from(input.matchingNodes()),
                        input.evidenceSourcesById()
                )
        );
        if (!validation.valid()) {
            throw new InvalidEvidenceRequestException(validation.reasonCode(), null);
        }
        reconcileTopicStatus(user.getId(), root.getId(), topicKey, answerStatus);

        ResumeEvidenceItem item = new ResumeEvidenceItem();
        item.setUserId(user.getId());
        item.setResumeId(root.getId());
        item.setTopicKey(topicKey);
        item.setTopicLabel(topic);
        item.setCategory(category);
        item.setAnswerStatus(answerStatus);
        item.setEntryKey(entryKey);
        item.setEntryLabel(selectedEntry.getEntryLabel());
        item.setRelatedNodeIdsJson(writeJson(relatedNodeIds));
        item.setContextText(context);
        item.setActionText(action);
        item.setResultText(result);
        item.setMetricText(metric);

        ResumeEvidenceItem existing = resumeEvidenceMapper.findByTopicAndEntry(
                user.getId(), root.getId(), topicKey, entryKey);
        if (existing == null) {
            resumeEvidenceMapper.insert(item);
            ResumeEvidenceItem stored = resumeEvidenceMapper.findByTopicAndEntry(
                    user.getId(), root.getId(), topicKey, entryKey);
            return new SaveResult(toResponse(stored == null ? item : stored), true);
        }
        item.setId(existing.getId());
        resumeEvidenceMapper.updateByTopicAndEntry(item);
        ResumeEvidenceItem stored = resumeEvidenceMapper.findByTopicAndEntry(
                user.getId(), root.getId(), topicKey, entryKey);
        if (stored == null) {
            stored = item;
        }
        return new SaveResult(toResponse(stored), false);
    }

    @Override
    public void delete(String email, Long resumeId, Long evidenceId) {
        User user = requireUser(email);
        Resume selected = ResumeFamily.requireOwned(resumeMapper, resumeId, user.getId());
        ResumeAnalysisInput input = analysisInputFactory.resolve(selected, user.getId());
        Resume root = input.uploadRootResume();
        ResumeEvidenceItem item = resumeEvidenceMapper.findByIdAndUser(evidenceId, user.getId());
        if (item == null || !root.getId().equals(item.getResumeId())) {
            throw new ResumeNotFoundException("Resume not found");
        }
        resumeEvidenceMapper.deleteByIdAndUser(evidenceId, user.getId());
    }

    private void reconcileTopicStatus(Long userId, Long resumeId, String topicKey, String answerStatus) {
        List<ResumeEvidenceItem> existing = resumeEvidenceMapper.findByResumeAndUser(resumeId, userId);
        if (existing == null) {
            existing = List.of();
        }
        boolean hasUsed = false;
        for (ResumeEvidenceItem item : existing) {
            if (item != null
                    && topicKey.equals(item.getTopicKey())
                    && AnswerStatus.CONFIRMED_USED.equals(item.getAnswerStatus())
                    && EvidenceSemanticValidator.validateStored(item, null).valid()) {
                hasUsed = true;
                break;
            }
        }
        if (AnswerStatus.CONFIRMED_NOT_USED.equals(answerStatus) && hasUsed) {
            throw new EvidenceConflictException(
                    "You already saved evidence of using this skill. Delete the existing evidence before marking it as not used."
            );
        }
        if (AnswerStatus.CONFIRMED_USED.equals(answerStatus)) {
            resumeEvidenceMapper.deleteNotUsedByTopic(userId, resumeId, topicKey);
        }
    }

    private EntryOption findEntry(List<EntryOption> catalog, String entryKey) {
        for (EntryOption option : catalog) {
            if (option != null && entryKey.equals(option.getEntryKey())) {
                return option;
            }
        }
        return null;
    }

    private List<String> normalizeNodeIds(List<String> nodeIds) {
        if (nodeIds == null) {
            return List.of();
        }
        Map<String, String> unique = new LinkedHashMap<>();
        for (String nodeId : nodeIds) {
            if (nodeId == null || nodeId.isBlank()) {
                continue;
            }
            unique.putIfAbsent(nodeId.strip(), nodeId.strip());
        }
        return new ArrayList<>(unique.values());
    }

    private ResumeEvidenceItemResponse toResponse(ResumeEvidenceItem item) {
        ResumeEvidenceItemResponse response = new ResumeEvidenceItemResponse();
        response.setId(item.getId());
        response.setResumeId(item.getResumeId());
        response.setTopic(item.getTopicLabel());
        response.setTopicKey(item.getTopicKey());
        response.setCategory(item.getCategory());
        response.setAnswerStatus(item.getAnswerStatus());
        response.setEntryKey(item.getEntryKey());
        response.setEntryLabel(item.getEntryLabel());
        response.setRelatedNodeIds(readJson(item.getRelatedNodeIdsJson()));
        response.setContext(item.getContextText());
        response.setAction(item.getActionText());
        response.setResult(item.getResultText());
        response.setMetric(item.getMetricText());
        response.setProvenance(AnswerStatus.CONFIRMED_USED.equals(item.getAnswerStatus())
                ? "USER_CONFIRMED"
                : "USER_REJECTED");
        EvidenceSemanticValidator.Result validation = EvidenceSemanticValidator.validateStored(item, null);
        response.setValidationStatus(validation.validationStatus());
        response.setValidationReasonCode(validation.reasonCode());
        response.setCreatedAt(item.getCreatedAt());
        response.setUpdatedAt(item.getUpdatedAt());
        return response;
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private List<String> readJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return values == null ? List.of() : values;
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return RequirementCategory.HARD_SKILL;
        }
        String value = category.strip();
        if (value.length() > 32) {
            throw new InvalidEvidenceRequestException("category is too long");
        }
        return value;
    }

    private String bound(String value, int max, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new InvalidEvidenceRequestException(field + " is too long");
        }
        return trimmed;
    }

    private User requireUser(String email) {
        User user = userMapper.findByEmail(email);
        if (user == null) {
            throw new UserNotFoundException("User not found");
        }
        return user;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
