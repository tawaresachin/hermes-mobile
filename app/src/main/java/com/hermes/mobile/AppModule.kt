package com.hermes.mobile

import android.content.Context
import com.hermes.mobile.data.local.AppDatabase
import com.hermes.mobile.data.local.MessageDao
import com.hermes.mobile.data.local.SessionDao
import com.hermes.mobile.network.HermesApiService
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
    fun provideHermesApiService(): HermesApiService = HermesApiService()

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.create(context)
    }

    @Provides
    @Singleton
    fun provideSessionDao(database: AppDatabase): SessionDao = database.sessionDao()

    @Provides
    @Singleton
    fun provideMessageDao(database: AppDatabase): MessageDao = database.messageDao()
}
