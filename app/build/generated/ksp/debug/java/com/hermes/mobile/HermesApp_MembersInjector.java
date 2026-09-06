package com.hermes.mobile;

import com.hermes.mobile.network.AuthInterceptor;
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
public final class HermesApp_MembersInjector implements MembersInjector<HermesApp> {
  private final Provider<AuthInterceptor> authInterceptorProvider;

  public HermesApp_MembersInjector(Provider<AuthInterceptor> authInterceptorProvider) {
    this.authInterceptorProvider = authInterceptorProvider;
  }

  public static MembersInjector<HermesApp> create(
      Provider<AuthInterceptor> authInterceptorProvider) {
    return new HermesApp_MembersInjector(authInterceptorProvider);
  }

  @Override
  public void injectMembers(HermesApp instance) {
    injectAuthInterceptor(instance, authInterceptorProvider.get());
  }

  @InjectedFieldSignature("com.hermes.mobile.HermesApp.authInterceptor")
  public static void injectAuthInterceptor(HermesApp instance, AuthInterceptor authInterceptor) {
    instance.authInterceptor = authInterceptor;
  }
}
