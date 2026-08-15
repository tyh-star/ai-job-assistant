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

    @Update("""
            UPDATE users
            SET password = #{password},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int updatePassword(User user);

    @Delete("DELETE FROM users WHERE id = #{id}")
    int deleteById(Long id);
}
