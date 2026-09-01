package aijob.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Detects whether the running database supports the MySQL FULLTEXT index that
 * {@code job_catalog} declares. H2 (tests) does not, so keyword search falls back
 * to LIKE there without forcing production onto a full table scan.
 */
@Component
public class SqlDialect {

    private static final Logger log = LoggerFactory.getLogger(SqlDialect.class);

    private final boolean fullTextSupported;

    @Autowired
    public SqlDialect(DataSource dataSource) {
        this.fullTextSupported = detectFullText(dataSource);
    }

    SqlDialect(boolean fullTextSupported) {
        this.fullTextSupported = fullTextSupported;
    }

    public boolean supportsFullText() {
        return fullTextSupported;
    }

    private static boolean detectFullText(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            boolean mysql = product != null && product.toLowerCase(Locale.ROOT).contains("mysql");
            log.info("Job catalog search dialect: product={} fullText={}", product, mysql);
            return mysql;
        } catch (SQLException ex) {
            log.warn("Could not detect SQL dialect; falling back to LIKE keyword search");
            return false;
        }
    }
}
