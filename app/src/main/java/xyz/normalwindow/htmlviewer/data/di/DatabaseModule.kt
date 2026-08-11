package xyz.normalwindow.htmlviewer.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import xyz.normalwindow.htmlviewer.data.db.AppDatabase
import xyz.normalwindow.htmlviewer.data.db.FileMetaDao
import xyz.normalwindow.htmlviewer.data.db.MIGRATION_1_2
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "htmlviewer.db")
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides
    fun provideFileMetaDao(db: AppDatabase): FileMetaDao = db.fileMetaDao()
}
