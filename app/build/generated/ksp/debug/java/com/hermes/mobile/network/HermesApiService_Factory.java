package com.hermes.mobile.network;

import android.content.Context;
import com.hermes.mobile.auth.AuthManager;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
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
public final class HermesApiService_Factory implements Factory<HermesApiService> {
  private final Provider<Context> contextProvider;

  private final Provider<AuthManager> authManagerProvider;

  private final Provider<AuthInterceptor> authInterceptorProvider;

  public HermesApiService_Factory(Provider<Context> contextProvider,
      Provider<AuthManager> authManagerProvider,
      Provider<AuthInterceptor> authInterceptorProvider) {
    this.contextProvider = contextProvider;
    this.authManagerProvider = authManagerProvider;
    this.authInterceptorProvider = authInterceptorProvider;
  }

  @Override
  public HermesApiService get() {
    return newInstance(contextProvider.get(), authManagerProvider.get(), authInterceptorProvider.get());
  }

  public static HermesApiService_Factory create(Provider<Context> contextProvider,
      Provider<AuthManager> authManagerProvider,
      Provider<AuthInterceptor> authInterceptorProvider) {
    return new HermesApiService_Factory(contextProvider, authManagerProvider, authInterceptorProvider);
  }

  public static HermesApiService newInstance(Context context, AuthManager authManager,
      AuthInterceptor authInterceptor) {
    return new HermesApiService(context, authManager, authInterceptor);
  }
}
