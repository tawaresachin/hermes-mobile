package com.hermes.mobile;

import com.hermes.mobile.data.local.AppDatabase;
import com.hermes.mobile.data.local.MessageDao;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation",
    "nullness:initialization.field.uninitialized"
})
public final class AppModule_ProvideMessageDaoFactory implements Factory<MessageDao> {
  private final Provider<AppDatabase> databaseProvider;

  public AppModule_ProvideMessageDaoFactory(Provider<AppDatabase> databaseProvider) {
    this.databaseProvider = databaseProvider;
  }

  @Override
  public MessageDao get() {
    return provideMessageDao(databaseProvider.get());
  }

  public static AppModule_ProvideMessageDaoFactory create(Provider<AppDatabase> databaseProvider) {
    return new AppModule_ProvideMessageDaoFactory(databaseProvider);
  }

  public static MessageDao provideMessageDao(AppDatabase database) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideMessageDao(database));
  }
}
