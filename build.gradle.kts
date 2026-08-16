// Root build file. Plugins are declared here (not applied) so the :app module
// can apply them without repeating version numbers.
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
