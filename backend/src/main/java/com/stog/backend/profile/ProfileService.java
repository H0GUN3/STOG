package com.stog.backend.profile;

import com.stog.backend.auth.User;
import com.stog.backend.auth.UserRepository;
import com.stog.backend.storage.GcsReadUrlSigner;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {
    private static final Set<String> PREFERENCE_KEYS = Set.of(
        "nature",
        "culture",
        "food",
        "shopping",
        "experience",
        "relaxation"
    );
    private static final Set<String> TRAVEL_STYLE_KEYS = Set.of(
        "localness",
        "crowd_tolerance",
        "pace",
        "spontaneity",
        "activity_intensity",
        "novelty_seeking",
        "travel_effort_tolerance"
    );

    private final UserRepository users;
    private final ProfileSurveyRepository surveyProfiles;
    private final SurveyProfileScorer scorer;
    private final ProfileSummaryRepository summaries;
    private final ObjectProvider<GcsReadUrlSigner> signedReads;

    public ProfileService(
        UserRepository users,
        ProfileSurveyRepository surveyProfiles,
        SurveyProfileScorer scorer,
        ProfileSummaryRepository summaries,
        ObjectProvider<GcsReadUrlSigner> signedReads
    ) {
        this.users = users;
        this.surveyProfiles = surveyProfiles;
        this.scorer = scorer;
        this.summaries = summaries;
        this.signedReads = signedReads;
    }

    @Transactional(readOnly = true)
    public ProfileResponses.Profile get(long userId) {
        User user = user(userId);
        return response(userId, user);
    }

    @Transactional(readOnly = true)
    public ProfileResponses.Summary summary(long userId) {
        User user = user(userId);
        ProfileSummaryRepository.Metrics metrics = summaries.metrics(userId);
        return new ProfileResponses.Summary(
            userId,
            user.getNickname(),
            profileImageUrl(user.getProfileImageKey()),
            metrics.tripCount(),
            metrics.visitedCellCount(),
            metrics.photoCount(),
            user.getHoneyBalance()
        );
    }

    @Transactional
    public ProfileResponses.Profile update(
        long userId,
        ProfileRequests.Update request
    ) {
        if (
            request.nickname() == null &&
            request.preference_scores() == null &&
            request.travel_style_scores() == null
        ) {
            throw badRequest("At least one profile layer is required");
        }
        User user = user(userId);
        if (request.nickname() != null) {
            String nickname = request.nickname().trim();
            if (nickname.isBlank()) {
                throw badRequest("nickname must not be blank");
            }
            user.setNickname(nickname);
        }
        if (request.preference_scores() != null) {
            user.setPreferenceScores(normalize(
                request.preference_scores(),
                PREFERENCE_KEYS,
                "preference_scores"
            ));
        }
        if (request.travel_style_scores() != null) {
            Map<String, Double> normalized = normalize(
                request.travel_style_scores(),
                TRAVEL_STYLE_KEYS,
                "travel_style_scores"
            );
            user.setTravelStyleScores(normalized);
        }
        Map<String, Double> preference = request.preference_scores() == null
            ? null
            : completeLayer(
                surveyProfiles.findPreferences(userId).orElse(user.getPreferenceScores()),
                user.getPreferenceScores(),
                request.preference_scores(),
                PREFERENCE_KEYS
            );
        Map<String, Double> travelStyle = request.travel_style_scores() == null
            ? null
            : completeLayer(
                surveyProfiles.findTravelStyles(userId).orElse(user.getTravelStyleScores()),
                user.getTravelStyleScores(),
                request.travel_style_scores(),
                TRAVEL_STYLE_KEYS
            );
        if (preference != null) {
            user.setPreferenceScores(preference);
        }
        if (travelStyle != null) {
            user.setTravelStyleScores(travelStyle);
        }
        surveyProfiles.replaceScores(userId, fullOrNull(preference, PREFERENCE_KEYS),
            fullOrNull(travelStyle, TRAVEL_STYLE_KEYS));
        return response(userId, user);
    }

    @Transactional
    public ProfileResponses.Survey submitSurvey(
        long userId,
        ProfileRequests.Survey request
    ) {
        if (!SurveyProfileScorer.VERSION.equals(request.survey_version())) {
            throw badRequest("survey_version must be v1");
        }
        SurveyProfileScorer.Result result;
        try {
            result = scorer.score(request.survey_type(), request.answers());
        } catch (IllegalArgumentException error) {
            throw badRequest(error.getMessage());
        }
        User user = user(userId);
        surveyProfiles.lockUser(userId);
        if (!result.canonical() && surveyProfiles.hasPrecisionSurvey(userId)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "precision survey has already created the canonical profile"
            );
        }
        surveyProfiles.saveSurvey(
            userId,
            request.survey_version(),
            request.survey_type(),
            request.answers(),
            result
        );
        user.setPreferenceScores(result.preference());
        user.setTravelStyleScores(result.travelStyle());
        return new ProfileResponses.Survey(
            request.survey_version(),
            request.survey_type(),
            result.canonical(),
            result.preference(),
            result.travelStyle()
        );
    }

    private User user(long userId) {
        return users.findById(userId).orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "User was not found"
        ));
    }

    private URI profileImageUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        GcsReadUrlSigner signer = signedReads.getIfAvailable();
        if (signer == null) {
            return null;
        }
        try {
            return signer.issueReadUrl(objectKey);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private ProfileResponses.Profile response(long userId, User user) {
        return new ProfileResponses.Profile(
            surveyProfiles.findPreferences(userId).orElse(
                user.getPreferenceScores() == null ? Map.of() : user.getPreferenceScores()
            ),
            surveyProfiles.findTravelStyles(userId).orElse(
                user.getTravelStyleScores() == null ? Map.of() : user.getTravelStyleScores()
            )
        );
    }

    private static Map<String, Double> completeLayer(
        Map<String, Double> current,
        Map<String, Double> userValues,
        Map<String, Double> requested,
        Set<String> allowedKeys
    ) {
        Map<String, Double> merged = new LinkedHashMap<>();
        if (current != null) {
            merged.putAll(current);
        } else if (userValues != null) {
            merged.putAll(userValues);
        }
        merged.putAll(requested);
        return merged.keySet().containsAll(allowedKeys) && merged.size() == allowedKeys.size()
            ? Map.copyOf(merged)
            : Map.copyOf(requested);
    }

    private static Map<String, Double> fullOrNull(
        Map<String, Double> values,
        Set<String> allowedKeys
    ) {
        return values != null && values.keySet().containsAll(allowedKeys)
            && values.size() == allowedKeys.size() ? values : null;
    }

    private static Map<String, Double> normalize(
        Map<String, Double> values,
        Set<String> allowedKeys,
        String field
    ) {
        Map<String, Double> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            if (!allowedKeys.contains(entry.getKey())) {
                throw badRequest(field + " contains an unsupported key");
            }
            Double value = entry.getValue();
            if (value == null || !Double.isFinite(value) || value < 0.0 || value > 1.0) {
                throw badRequest(field + " values must be between 0 and 1");
            }
            normalized.put(entry.getKey(), value);
        }
        return Map.copyOf(normalized);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
