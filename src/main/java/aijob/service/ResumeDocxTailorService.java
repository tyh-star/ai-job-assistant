package aijob.service;

import aijob.dto.TailorChange;

import java.nio.file.Path;
import java.util.List;

public interface ResumeDocxTailorService {

    Path createTailoredDocx(
            Path originalDocx,
            List<TailorChange> changes,
            Long userId,
            Long jobId
    );
}
