# Android interface

- Use the global mobile-android-design and material3-theming skills for Android UI, and clarify for copy.
- Use native Jetpack Compose + Material 3 components. Keep the interface simple, with one primary action per screen and settings outside the home screen.
- Use semantic theme colors, paired with their on-colors. Follow system light/dark mode; offer optional Android 12+ Dynamic Color with complete static fallbacks.
- Use standard Material typography, system fonts, 48 dp minimum touch targets, and consistent 8/16/24 dp spacing. Support large font sizes, keyboard insets, and landscape.
- Use short, natural Chinese: concrete action labels, visible field labels, one useful sentence of help. Move recovery instructions to a dedicated help view. Do not expose exception messages or implementation terms in UI.
- Keep authentication, local encryption, accessibility permission state, and diagnostic logging behavior intact during UI changes.
- Build preview with applicationId com.mike.campusautofill.preview and label 校园认证助手·新版. Install it alongside the existing app; never overwrite the original package for this redesign request.
- Verify light/dark, large text, navigation, validation, and at least the confirmation/cancellation flow. Document what was tested and what was not.
