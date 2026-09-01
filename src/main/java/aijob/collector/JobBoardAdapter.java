package aijob.collector;

import aijob.entity.JobSource;
import aijob.enums.AtsProviderType;

import java.util.List;

/**
 * Reads one public ATS board.
 *
 * <p>Implementations must only ever issue plain unauthenticated GETs to their own
 * provider's official host. They must not log in, solve challenges, rotate proxies,
 * or follow redirects to arbitrary destinations.
 */
public interface JobBoardAdapter {

    AtsProviderType providerType();

    /**
     * @throws JobCollectionException when the board could not be read; the caller
     *         records a failed run and expires nothing.
     */
    List<NormalizedJob> fetch(JobSource source);
}
