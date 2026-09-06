package com.stog.backend.plan;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserConstraintService {
    private static final String MOBILITY_STYLE = "mobility_style";
    private static final Set<String> MOBILITY_CODES = Set.of(
        "한 지역 도보",
        "대중교통 광역",
        "자차 원거리",
        "한 장소 체류"
    );

    private final UserConstraintRepository constraints;

    public UserConstraintService(UserConstraintRepository constraints) {
        this.constraints = constraints;
    }

    @Transactional(readOnly = true)
    public UserConstraintResponses.Values get(long actorId) {
        requireKnownUser(actorId);
        return new UserConstraintResponses.Values(constraints.findByUser(actorId));
    }

    @Transactional
    public UserConstraintResponses.Values replace(
        long actorId,
        UserConstraintRequests.Replace request
    ) {
        requireKnownUser(actorId);
        validate(request.constraints());
        constraints.replace(actorId, request.constraints());
        return new UserConstraintResponses.Values(constraints.findByUser(actorId));
    }

    private void requireKnownUser(long actorId) {
        if (!constraints.userExists(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User access denied");
        }
    }

    private void validate(List<UserConstraintRequests.Item> values) {
        Set<String> unique = new HashSet<>();
        for (UserConstraintRequests.Item value : values) {
            if (!MOBILITY_STYLE.equals(value.constraint_type())
                || !MOBILITY_CODES.contains(value.constraint_code())) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "User constraint is unsupported"
                );
            }
            if (!unique.add(value.constraint_type() + "\u0000" + value.constraint_code())) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "User constraints must be unique"
                );
            }
        }
    }
}
