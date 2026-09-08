package aijob.service.impl;

import aijob.dto.JobSearchHistoryItemResponse;
import aijob.dto.RecordJobSearchHistoryRequest;
import aijob.entity.JobSearchHistory;
import aijob.entity.User;
import aijob.exception.JobSearchHistoryNotFoundException;
import aijob.exception.UserNotFoundException;
import aijob.mapper.JobSearchHistoryMapper;
import aijob.mapper.UserMapper;
import aijob.service.JobSearchHistoryCriteria;
import aijob.service.JobSearchHistoryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JobSearchHistoryServiceImpl implements JobSearchHistoryService {

    static final int MAX_PER_USER = 20;

    private final UserMapper userMapper;
    private final JobSearchHistoryMapper historyMapper;

    public JobSearchHistoryServiceImpl(UserMapper userMapper, JobSearchHistoryMapper historyMapper) {
        this.userMapper = userMapper;
        this.historyMapper = historyMapper;
    }

    @Override
    public void record(String email, RecordJobSearchHistoryRequest request) {
        JobSearchHistoryCriteria.Normalized normalized = JobSearchHistoryCriteria.normalize(request);
        if (normalized.isEmpty()) {
            return;
        }
        User user = requireUser(email);
        JobSearchHistory row = new JobSearchHistory();
        row.setUserId(user.getId());
        row.setKeywords(normalized.keywords());
        row.setCity(normalized.city());
        row.setCityCountry(normalized.cityCountry());
        row.setCountry(normalized.country());
        row.setWorkMode(normalized.workMode());
        row.setEmploymentType(normalized.employmentType());
        row.setPostedWithin(normalized.postedWithin());
        row.setSortBy(normalized.sortBy());
        row.setCriteriaHash(normalized.criteriaHash());
        historyMapper.upsert(row);
        trimToMax(user.getId());
    }

    @Override
    public List<JobSearchHistoryItemResponse> list(String email) {
        User user = requireUser(email);
        return historyMapper.findByUserIdNewestFirst(user.getId()).stream()
                .limit(MAX_PER_USER)
                .map(JobSearchHistoryServiceImpl::toResponse)
                .toList();
    }

    @Override
    public void deleteOne(String email, Long id) {
        User user = requireUser(email);
        int deleted = historyMapper.deleteByIdAndUserId(id, user.getId());
        if (deleted == 0) {
            throw new JobSearchHistoryNotFoundException();
        }
    }

    @Override
    public void deleteAll(String email) {
        User user = requireUser(email);
        historyMapper.deleteAllByUserId(user.getId());
    }

    private void trimToMax(Long userId) {
        List<Long> ids = historyMapper.findIdsByUserIdNewestFirst(userId);
        if (ids.size() <= MAX_PER_USER) {
            return;
        }
        historyMapper.deleteByIdsAndUserId(ids.subList(MAX_PER_USER, ids.size()), userId);
    }

    private User requireUser(String email) {
        User user = userMapper.findByEmail(email);
        if (user == null) {
            throw new UserNotFoundException("User not found");
        }
        return user;
    }

    private static JobSearchHistoryItemResponse toResponse(JobSearchHistory row) {
        return new JobSearchHistoryItemResponse(
                row.getId(),
                row.getKeywords(),
                row.getCity(),
                row.getCityCountry(),
                row.getCountry(),
                row.getWorkMode(),
                row.getEmploymentType(),
                row.getPostedWithin(),
                row.getSortBy(),
                row.getSearchCount(),
                row.getLastSearchedAt());
    }
}
