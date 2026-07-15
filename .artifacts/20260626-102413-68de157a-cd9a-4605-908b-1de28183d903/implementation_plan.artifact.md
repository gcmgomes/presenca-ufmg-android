# Unit Tests for Providers

This plan outlines the refactoring and testing of the newly introduced provider classes to ensure architectural stability and 100% test coverage.

## User Review Required

> [!NOTE]
> `MainActivity` will be updated to use the concrete `AndroidDialogProvider` instead of an anonymous object, which improves consistency across the project.

## Proposed Changes

### [Provider Refactoring]

Refactor providers to follow the interface/implementation pattern consistently and allow for better dependency injection in tests.

#### [PreviewProvider.kt](file:///Users/guilherme/git/presensor/app/src/main/java/com/example/presensor/tools/providers/PreviewProvider.kt)

- Convert `PreviewProvider` to an interface.
- Create `AndroidPreviewProvider` as the concrete implementation.

#### [DialogProvider.kt](file:///Users/guilherme/git/presensor/app/src/main/java/com/example/presensor/tools/providers/DialogProvider.kt)

- Update `AndroidDialogProvider` to accept `PreviewProvider` in its constructor.
- Implement `showSessionImportPreview` and `showStudentImportPreview` by delegating to `PreviewProvider`.

#### [MainActivity.kt](file:///Users/guilherme/git/presensor/app/src/main/java/com/example/presensor/MainActivity.kt)

- Update to use `AndroidPreviewProvider` and `AndroidDialogProvider`.

---

### [Unit Tests]

#### [NEW] [ToastProviderTest.kt](file:///Users/guilherme/git/presensor/app/src/test/java/com/example/presensor/tools/providers/ToastProviderTest.kt)

- Verify that `AndroidToastProvider` correctly calls `Toast.makeText().show()`.

#### [NEW] [DataProcessorProviderTest.kt](file:///Users/guilherme/git/presensor/app/src/test/java/com/example/presensor/tools/providers/DataProcessorProviderTest.kt)

- Verify that `AndroidDataProcessorProvider` delegates all calls to `DataProcessor`.

#### [NEW] [PreviewProviderTest.kt](file:///Users/guilherme/git/presensor/app/src/test/java/com/example/presensor/tools/providers/PreviewProviderTest.kt)

- Verify that `AndroidPreviewProvider` correctly displays `BottomSheetDialog` and triggers callbacks on button clicks.

#### [NEW] [DialogProviderTest.kt](file:///Users/guilherme/git/presensor/app/src/test/java/com/example/presensor/tools/providers/DialogProviderTest.kt)

- Verify that `AndroidDialogProvider` delegates correctly to `DialogFactory` and `PreviewProvider`.

## Verification Plan

### Automated Tests

- Run all unit tests for providers:
  `./gradlew :app:testDebugUnitTest --tests com.example.presensor.tools.providers.*`
- Run JaCoCo report to verify coverage:
  `./gradlew :app:jacocoTestReport`

### Manual Verification
- None required as these are architectural/testability improvements.
