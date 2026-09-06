package com.stog.backend.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "text")
    private String nickname;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preference_scores", columnDefinition = "jsonb")
    private Map<String, Double> preferenceScores;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "travel_style_scores", columnDefinition = "jsonb")
    private Map<String, Double> travelStyleScores;

    @Column(name = "honey_balance", nullable = false)
    private long honeyBalance;

    @Column(name = "profile_image_key")
    private String profileImageKey;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected User() {
    }

    public User(String nickname) {
        this.nickname = nickname;
    }

    public Long getId() {
        return id;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public Map<String, Double> getPreferenceScores() {
        return preferenceScores;
    }

    public Map<String, Double> getTravelStyleScores() {
        return travelStyleScores;
    }

    public void setPreferenceScores(Map<String, Double> preferenceScores) {
        this.preferenceScores = preferenceScores;
    }

    public void setTravelStyleScores(Map<String, Double> travelStyleScores) {
        this.travelStyleScores = travelStyleScores;
    }

    public long getHoneyBalance() {
        return honeyBalance;
    }

    public String getProfileImageKey() {
        return profileImageKey;
    }

    public void setProfileImageKey(String profileImageKey) {
        this.profileImageKey = profileImageKey;
    }
}
