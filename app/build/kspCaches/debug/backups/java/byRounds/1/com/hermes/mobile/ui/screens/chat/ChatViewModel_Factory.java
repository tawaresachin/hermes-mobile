package com.hermes.mobile.ui.screens.chat;

import androidx.lifecycle.SavedStateHandle;
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
public final class ChatViewModel_Factory implements Factory<ChatViewModel> {
  private final Provider<HermesRepository> repositoryProvider;

  private final Provider<SavedStateHandle> savedStateHandleProvider;

  public ChatViewModel_Factory(Provider<HermesRepository> repositoryProvider,
      Provider<SavedStateHandle> savedStateHandleProvider) {
    this.repositoryProvider = repositoryProvider;
    this.savedStateHandleProvider = savedStateHandleProvider;
  }

  @Override
  public ChatViewModel get() {
    return newInstance(repositoryProvider.get(), savedStateHandleProvider.get());
  }

  public static ChatViewModel_Factory create(Provider<HermesRepository> repositoryProvider,
      Provider<SavedStateHandle> savedStateHandleProvider) {
    return new ChatViewModel_Factory(repositoryProvider, savedStateHandleProvider);
  }

  public static ChatViewModel newInstance(HermesRepository repository,
      SavedStateHandle savedStateHandle) {
    return new ChatViewModel(repository, savedStateHandle);
  }
}
