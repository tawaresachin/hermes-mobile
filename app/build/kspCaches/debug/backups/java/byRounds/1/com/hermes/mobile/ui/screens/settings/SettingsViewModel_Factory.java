package com.hermes.mobile.ui.screens.settings;

import com.hermes.mobile.auth.AuthManager;
import com.hermes.mobile.data.repository.HermesRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
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
public final class SettingsViewModel_Factory implements Factory<SettingsViewModel> {
  private final Provider<HermesRepository> repositoryProvider;

  private final Provider<AuthManager> authManagerProvider;

  public SettingsViewModel_Factory(Provider<HermesRepository> repositoryProvider,
      Provider<AuthManager> authManagerProvider) {
    this.repositoryProvider = repositoryProvider;
    this.authManagerProvider = authManagerProvider;
  }

  @Override
  public SettingsViewModel get() {
    return newInstance(repositoryProvider.get(), authManagerProvider.get());
  }

  public static SettingsViewModel_Factory create(Provider<HermesRepository> repositoryProvider,
      Provider<AuthManager> authManagerProvider) {
    return new SettingsViewModel_Factory(repositoryProvider, authManagerProvider);
  }

  public static SettingsViewModel newInstance(HermesRepository repository,
      AuthManager authManager) {
    return new SettingsViewModel(repository, authManager);
  }
}
