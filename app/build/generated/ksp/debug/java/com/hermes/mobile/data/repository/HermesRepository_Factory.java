package com.hermes.mobile.data.repository;

import android.content.Context;
import com.hermes.mobile.data.local.MessageDao;
import com.hermes.mobile.data.local.SessionDao;
import com.hermes.mobile.network.HermesApiService;
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
public final class HermesRepository_Factory implements Factory<HermesRepository> {
  private final Provider<HermesApiService> apiServiceProvider;

  private final Provider<SessionDao> sessionDaoProvider;

  private final Provider<MessageDao> messageDaoProvider;

  private final Provider<Context> contextProvider;

  public HermesRepository_Factory(Provider<HermesApiService> apiServiceProvider,
      Provider<SessionDao> sessionDaoProvider, Provider<MessageDao> messageDaoProvider,
      Provider<Context> contextProvider) {
    this.apiServiceProvider = apiServiceProvider;
    this.sessionDaoProvider = sessionDaoProvider;
    this.messageDaoProvider = messageDaoProvider;
    this.contextProvider = contextProvider;
  }

  @Override
  public HermesRepository get() {
    return newInstance(apiServiceProvider.get(), sessionDaoProvider.get(), messageDaoProvider.get(), contextProvider.get());
  }

  public static HermesRepository_Factory create(Provider<HermesApiService> apiServiceProvider,
      Provider<SessionDao> sessionDaoProvider, Provider<MessageDao> messageDaoProvider,
      Provider<Context> contextProvider) {
    return new HermesRepository_Factory(apiServiceProvider, sessionDaoProvider, messageDaoProvider, contextProvider);
  }

  public static HermesRepository newInstance(HermesApiService apiService, SessionDao sessionDao,
      MessageDao messageDao, Context context) {
    return new HermesRepository(apiService, sessionDao, messageDao, context);
  }
}
