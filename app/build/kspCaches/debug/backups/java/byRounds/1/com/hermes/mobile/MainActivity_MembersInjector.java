package com.hermes.mobile;

import com.hermes.mobile.auth.AuthManager;
import com.hermes.mobile.data.repository.HermesRepository;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
public final class MainActivity_MembersInjector implements MembersInjector<MainActivity> {
  private final Provider<HermesRepository> repositoryProvider;

  private final Provider<AuthManager> authManagerProvider;

  public MainActivity_MembersInjector(Provider<HermesRepository> repositoryProvider,
      Provider<AuthManager> authManagerProvider) {
    this.repositoryProvider = repositoryProvider;
    this.authManagerProvider = authManagerProvider;
  }

  public static MembersInjector<MainActivity> create(Provider<HermesRepository> repositoryProvider,
      Provider<AuthManager> authManagerProvider) {
    return new MainActivity_MembersInjector(repositoryProvider, authManagerProvider);
  }

  @Override
  public void injectMembers(MainActivity instance) {
    injectRepository(instance, repositoryProvider.get());
    injectAuthManager(instance, authManagerProvider.get());
  }

  @InjectedFieldSignature("com.hermes.mobile.MainActivity.repository")
  public static void injectRepository(MainActivity instance, HermesRepository repository) {
    instance.repository = repository;
  }

  @InjectedFieldSignature("com.hermes.mobile.MainActivity.authManager")
  public static void injectAuthManager(MainActivity instance, AuthManager authManager) {
    instance.authManager = authManager;
  }
}
