package aijob.mapper;

import aijob.entity.User;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface UserMapper {

    @Select("SELECT * FROM users WHERE id = #{id}")
    User findById(Long id);

    @Select("SELECT * FROM users WHERE email = #{email}")
    User findByEmail(String email);

    @Select("SELECT * FROM users")
    List<User> findAll();

    @Insert("""
            INSERT INTO users (email, password, name, role)
            VALUES (#{email}, #{password}, #{name}, #{role})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    @Update("""
            UPDATE users
            SET name = #{name},
                email = #{email},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int update(User user);

    /** Name only -- see UpdateUserNameRequest's javadoc for why this is separate from email. */
    @Update("""
            UPDATE users
            SET name = #{name},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int updateName(@Param("id") Long id, @Param("name") String name);

    /** Email only -- see UpdateUserEmailRequest's javadoc for why this is its own path. */
    @Update("""
            UPDATE users
            SET email = #{email},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int updateEmail(@Param("id") Long id, @Param("email") String email);

    /**
     * The password hash and the auth-version bump happen in the SAME statement --
     * a password change that took effect but somehow left the old auth_version in
     * place would silently un-revoke every previously-issued token (see
     * JwtAuthenticationFilter's javadoc), so these two writes can never be split
     * across two separate UPDATEs.
     */
    @Update("""
            UPDATE users
            SET password = #{password},
                auth_version = auth_version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int changePasswordAndBumpAuthVersion(@Param("id") Long id, @Param("password") String password);

    /**
     * Read on every authenticated request by JwtAuthenticationFilter -- deliberately
     * a single-column SELECT rather than reusing {@link #findByEmail}, so the password
     * hash never passes through a filter that runs on every request.
     */
    @Select("SELECT auth_version FROM users WHERE email = #{email}")
    Integer findAuthVersionByEmail(String email);

    @Delete("DELETE FROM users WHERE id = #{id}")
    int deleteById(Long id);
}
