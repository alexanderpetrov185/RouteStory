package com.alexander.routestory.di

import android.content.Context
import androidx.room.Room
import com.alexander.routestory.data.RouteStoryDatabase
import com.alexander.routestory.data.PlannedRouteRepositoryImpl
import com.alexander.routestory.data.PlannedRouteDao
import com.alexander.routestory.data.MIGRATION_2_3
import com.alexander.routestory.data.PlannedRouteRepository
import com.alexander.routestory.data.TrackDao
import com.alexander.routestory.data.MIGRATION_1_2
import com.alexander.routestory.data.TrackPhotoDao
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
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RouteStoryDatabase =
        Room.databaseBuilder(context, RouteStoryDatabase::class.java, "routestory.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun provideTrackDao(db: RouteStoryDatabase): TrackDao = db.trackDao()

    @Provides
    fun provideTrackPhotoDao(db: RouteStoryDatabase): TrackPhotoDao = db.trackPhotoDao()

    @Provides
    fun providePlannedRouteDao(db: RouteStoryDatabase): PlannedRouteDao = db.plannedRouteDao()

    @Provides
    @Singleton
    fun providePlannedRouteRepository(impl: PlannedRouteRepositoryImpl): PlannedRouteRepository = impl

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideFusedLocationClient(@ApplicationContext context: Context): FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @Provides
    @Singleton
    fun provideTrackRepository(impl: TrackRepositoryImpl): TrackRepository = impl
}