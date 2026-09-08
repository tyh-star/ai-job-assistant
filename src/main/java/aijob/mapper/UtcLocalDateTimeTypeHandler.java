package aijob.mapper;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * {@code jobs.applied_at} is a MySQL {@code TIMESTAMP} (a real UTC instant). Treating the
 * mapped {@link LocalDateTime} as UTC wall time is only correct if JDBC writes and reads
 * that instant, not the JVM's default zone. {@link Timestamp#toInstant()} is the JDBC
 * instant; converting through {@link ZoneOffset#UTC} keeps the service-layer contract.
 * Not registered globally -- only {@code applied_at} mappings opt in.
 */
public class UtcLocalDateTimeTypeHandler extends BaseTypeHandler<LocalDateTime> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDateTime parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setTimestamp(i, Timestamp.from(parameter.toInstant(ZoneOffset.UTC)));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toUtc(rs.getTimestamp(columnName));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toUtc(rs.getTimestamp(columnIndex));
    }

    @Override
    public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toUtc(cs.getTimestamp(columnIndex));
    }

    private static LocalDateTime toUtc(Timestamp timestamp) {
        return timestamp == null ? null : LocalDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC);
    }
}
