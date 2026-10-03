package com.motointercom.di

import com.motointercom.data.audio.AudioMixer
import com.motointercom.data.audio.AudioPlayer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI Module providing singleton instances of audio processing utilities.
 * Note: [com.motointercom.service.IntercomService] manages its own scoped instances
 * directly tied to the foreground service lifecycle to guarantee clean state resets
 * across sessions without lingering in process-wide singletons.
 */
@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    @Singleton
    fun provideAudioMixer(): AudioMixer = AudioMixer()

    @Provides
    @Singleton
    fun provideAudioPlayer(): AudioPlayer = AudioPlayer()
}
