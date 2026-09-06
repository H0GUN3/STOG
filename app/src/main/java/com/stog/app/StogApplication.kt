package com.stog.app

import android.app.Application
import com.kakao.sdk.common.KakaoSdk
import com.stog.app.feature.record.RecordingActivationCoordinator

class StogApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val nativeAppKey = BuildConfig.KAKAO_NATIVE_APP_KEY
        KakaoSdk.init(this, nativeAppKey)
        RecordingActivationCoordinator(this).recover()
    }
}
