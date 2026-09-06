package com.stog.app.feature.space

import org.junit.Assert.assertEquals
import org.junit.Test

class PlacePhotoUrlTest {
    @Test
    fun encodesGooglePhotoNameAsBackendQueryParameter() {
        assertEquals(
            "http://10.0.2.2:8080/places/photo?name=places%2FChIJ123%2Fphotos%2Fphoto_1",
            placePhotoUrl(
                baseUrl = "http://10.0.2.2:8080/",
                photoName = "places/ChIJ123/photos/photo_1",
            ),
        )
    }

    @Test
    fun upgradesExternalCleartextImageUrlsButKeepsLocalBackendHttp() {
        assertEquals(
            "https://tong.visitkorea.or.kr/cms/resource/event.png",
            imageRequestUrl("http://tong.visitkorea.or.kr/cms/resource/event.png"),
        )
        assertEquals(
            "http://127.0.0.1:8080/places/photo?name=photo",
            imageRequestUrl("http://127.0.0.1:8080/places/photo?name=photo"),
        )
    }
}
