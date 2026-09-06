package com.hermes.mobile;

import com.hermes.mobile.data.local.AppDatabase;
import com.hermes.mobile.data.local.SessionDao;
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
public final class AppModule_ProvideSessionDaoFactory implements Factory<SessionDao> {
  private final Provider<AppDatabase> databaseProvider;

  public AppModule_ProvideSessionDaoFactory(Provider<AppDatabase> databaseProvider) {
    this.databaseProvider = databaseProvider;
  }

  @Override
  public SessionDao get() {
    return provideSessionDao(databaseProvider.get());
  }

  public static AppModule_ProvideSessionDaoFactory create(Provider<AppDatabase> databaseProvider) {
    return new AppModule_ProvideSessionDaoFactory(databaseProvider);
  }

  public static SessionDao provideSessionDao(AppDatabase database) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideSessionDao(database));
  }
}
