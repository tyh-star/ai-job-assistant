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

    /** Same write-back and verification, into an output path the caller chose. */
    Path createTailoredDocx(
            Path originalDocx,
            List<TailorChange> changes,
            Path outputPath
    );
}
