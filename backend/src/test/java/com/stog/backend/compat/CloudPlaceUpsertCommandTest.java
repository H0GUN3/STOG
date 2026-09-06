package com.stog.backend.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stog.backend.place.PlaceCandidate;
import java.util.List;
import org.junit.jupiter.api.Test;

public class CloudPlaceUpsertCommandTest {
    private static final PlaceCandidate GOOGLE_PLACE = new PlaceCandidate(
        "google",
        "ChIJgoogle",
        "전주 카페",
        "전주시 완산구",
        35.815,
        127.15,
        List.of("cafe"),
        List.of(),
        null,
        "https://example.test",
        null,
        List.of()
    );

    @Test
    void commandKeepsGoogleIdentityAndWriteClassification() {
        CloudPlaceUpsertCommand command = new CloudPlaceUpsertCommand(
            GOOGLE_PLACE,
            "BUSINESS",
            "GOOGLE",
            "OUTDOOR",
            12000
        );

        assertThat(command.candidate()).isEqualTo(GOOGLE_PLACE);
        assertThat(command.itemType()).isEqualTo("BUSINESS");
        assertThat(command.source()).isEqualTo("GOOGLE");
        assertThat(command.environmentType()).isEqualTo("OUTDOOR");
    }

    @Test
    void commandRejectsNonGoogleCandidates() {
        PlaceCandidate kakao = new PlaceCandidate(
            "kakao",
            "kakao-1",
            "장소",
            null,
            null,
            null,
            List.of(),
            List.of(),
            null,
            null,
            null,
            List.of()
        );

        assertThatThrownBy(() -> new CloudPlaceUpsertCommand(
            kakao,
            "BUSINESS",
            "GOOGLE",
            "UNKNOWN",
            null
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("google");
    }

    @Test
    void commandRejectsInvalidCloudValues() {
        assertThatThrownBy(() -> new CloudPlaceUpsertCommand(
            GOOGLE_PLACE,
            "EVENT",
            "GOOGLE",
            "OUTDOOR",
            null
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("itemType");

        assertThatThrownBy(() -> new CloudPlaceUpsertCommand(
            GOOGLE_PLACE,
            "BUSINESS",
            "GOOGLE",
            "UNKNOWN",
            -1
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("estimatedCost");

        assertThatThrownBy(() -> new CloudPlaceUpsertCommand(
            GOOGLE_PLACE,
            null,
            "GOOGLE",
            "UNKNOWN",
            null
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("itemType");

        assertThatThrownBy(() -> new CloudPlaceUpsertCommand(
            GOOGLE_PLACE,
            "BUSINESS",
            "GOOGLE",
            null,
            null
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("environmentType");
    }
}
