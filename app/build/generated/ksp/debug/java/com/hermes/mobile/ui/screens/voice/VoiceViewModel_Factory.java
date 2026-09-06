package com.hermes.mobile.ui.screens.voice;

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
public final class VoiceViewModel_Factory implements Factory<VoiceViewModel> {
  private final Provider<HermesRepository> repositoryProvider;

  private final Provider<Context> contextProvider;

  public VoiceViewModel_Factory(Provider<HermesRepository> repositoryProvider,
      Provider<Context> contextProvider) {
    this.repositoryProvider = repositoryProvider;
    this.contextProvider = contextProvider;
  }

  @Override
  public VoiceViewModel get() {
    return newInstance(repositoryProvider.get(), contextProvider.get());
  }

  public static VoiceViewModel_Factory create(Provider<HermesRepository> repositoryProvider,
      Provider<Context> contextProvider) {
    return new VoiceViewModel_Factory(repositoryProvider, contextProvider);
  }

  public static VoiceViewModel newInstance(HermesRepository repository, Context context) {
    return new VoiceViewModel(repository, context);
  }
}
