package com.alexander.routestory.di

import android.content.Context
import androidx.room.Room
import com.alexander.routestory.data.RouteStoryDatabase
import com.alexander.routestory.data.TrackDao
import com.alexander.routestory.data.TrackRepositoryImpl
import com.alexander.routestory.domain.TrackRepository
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RouteStoryDatabase =
        Room.databaseBuilder(context, RouteStoryDatabase::class.java, "routestory.db").build()

    @Provides
    fun provideTrackDao(db: RouteStoryDatabase): TrackDao = db.trackDao()

    @Provides
    @Singleton
    fun provideFusedLocationClient(@ApplicationContext context: Context): FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @Provides
    @Singleton
    fun provideTrackRepository(impl: TrackRepositoryImpl): TrackRepository = impl
}