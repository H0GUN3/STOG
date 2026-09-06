package com.stog.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class TourApiFestivalProviderTest {
    @Test
    void parsesFestivalItemsAndRejectsRowsWithoutNearbyCoordinates() {
        List<TourApiFestival> festivals = TourApiFestivalProvider.parse("""
            {
              "response": {
                "header": {"resultCode": "0000"},
                "body": {
                  "items": {
                    "item": [
                      {
                        "contentid": "1001",
                        "title": "전주 비빔밥 축제",
                        "eventplace": "전주월드컵경기장",
                        "addr1": "전북 전주시",
                        "addr2": "덕진구",
                        "mapx": "127.126",
                        "mapy": "35.846",
                        "eventstartdate": "20261010",
                        "eventenddate": "20261012",
                        "firstimage": "https://example.test/festival.jpg",
                        "modifiedtime": "20260901123000"
                      },
                      {
                        "contentid": "1002",
                        "title": "좌표 없는 행사",
                        "eventstartdate": "20261010",
                        "eventenddate": "20261012"
                      }
                    ]
                  }
                }
              }
            }
            """);

        assertThat(festivals).hasSize(1);
        assertThat(festivals.get(0).externalId()).isEqualTo("1001");
        assertThat(festivals.get(0).title()).isEqualTo("전주 비빔밥 축제");
        assertThat(festivals.get(0).formattedAddress()).isEqualTo("전북 전주시 덕진구");
        assertThat(festivals.get(0).startsOn().toString()).isEqualTo("2026-10-10");
        assertThat(festivals.get(0).imageUri())
            .isEqualTo("https://example.test/festival.jpg");
    }

    @Test
    void providerErrorCodeDoesNotBecomeAnEmptyEventList() {
        assertThatThrownBy(() -> TourApiFestivalProvider.parse("""
            {
              "response": {
                "header": {"resultCode": "03", "resultMsg": "NO_DATA"}
              }
            }
            """))
            .isInstanceOf(TourApiEventProviderException.class)
            .hasMessage("provider_error_03");
    }

    @Test
    void encodesServiceKeyWithoutDoubleEncodingOrRawPlusCharacters() {
        assertThat(TourApiFestivalProvider.normalizeServiceKey("abc%2Bdef%3D"))
            .isEqualTo("abc%2Bdef%3D");
        assertThat(TourApiFestivalProvider.normalizeServiceKey("abc+def"))
            .isEqualTo("abc%2Bdef");
    }
}
