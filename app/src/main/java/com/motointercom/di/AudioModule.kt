package com.motointercom.di

import com.motointercom.data.audio.AudioMixer
import com.motointercom.data.audio.AudioPlayer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
