package aijob.catalog;

import lombok.Data;

/** Which ATS board a catalog posting was collected from, for source attribution. */
@Data
public class CatalogJobSourceRow {

    private Long catalogJobId;

    private String providerType;

    private String displayName;
}
