package com.hermes.mobile.ui.screens.sessions;

import android.content.Context;
import com.hermes.mobile.data.repository.HermesRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class SessionsViewModel_Factory implements Factory<SessionsViewModel> {
  private final Provider<HermesRepository> repositoryProvider;

  private final Provider<Context> appContextProvider;

  public SessionsViewModel_Factory(Provider<HermesRepository> repositoryProvider,
      Provider<Context> appContextProvider) {
    this.repositoryProvider = repositoryProvider;
    this.appContextProvider = appContextProvider;
  }

  @Override
  public SessionsViewModel get() {
    return newInstance(repositoryProvider.get(), appContextProvider.get());
  }

  public static SessionsViewModel_Factory create(Provider<HermesRepository> repositoryProvider,
      Provider<Context> appContextProvider) {
    return new SessionsViewModel_Factory(repositoryProvider, appContextProvider);
  }

  public static SessionsViewModel newInstance(HermesRepository repository, Context appContext) {
    return new SessionsViewModel(repository, appContext);
  }
}
