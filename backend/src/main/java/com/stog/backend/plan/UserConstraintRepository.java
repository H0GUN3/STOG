package com.stog.backend.plan;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserConstraintRepository {
    private final JdbcClient jdbc;

    public UserConstraintRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean userExists(long userId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM users WHERE id = :userId)")
            .param("userId", userId)
            .query(Boolean.class)
            .single();
    }

    public List<UserConstraintResponses.Value> findByUser(long userId) {
        return jdbc.sql("""
                SELECT constraint_type, constraint_code
                FROM user_constraints
                WHERE user_id = :userId
                ORDER BY constraint_type, constraint_code
                """)
            .param("userId", userId)
            .query((row, rowNumber) -> new UserConstraintResponses.Value(
                row.getString("constraint_type"),
                row.getString("constraint_code")
            ))
            .list();
    }

    public void replace(long userId, List<UserConstraintRequests.Item> constraints) {
        jdbc.sql("DELETE FROM user_constraints WHERE user_id = :userId")
            .param("userId", userId)
            .update();
        for (UserConstraintRequests.Item constraint : constraints) {
            jdbc.sql("""
                    INSERT INTO user_constraints (user_id, constraint_type, constraint_code)
                    VALUES (:userId, :constraintType, :constraintCode)
                    """)
                .param("userId", userId)
                .param("constraintType", constraint.constraint_type())
                .param("constraintCode", constraint.constraint_code())
                .update();
        }
    }
}
