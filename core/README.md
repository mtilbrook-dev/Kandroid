# core

Shared modules used by more than one Kandroid plugin live here, one directory per module:

```
core/
  <module-name>/
    build.gradle.kts
    src/main/kotlin/dev/tilbrook/kandroid/core/<module>/...
```

Rules:

- A module goes in `core/` only once a **second** plugin needs it. Until then it stays inside the plugin
  that owns it (for example, typed-manifest's Kotlin/Java source scanner lives in
  `typed-manifest/gradle-plugin/.../internal/source` and is the first candidate to move here).
- Register it in the root `settings.gradle.kts` as `include(":core:<module-name>")` and depend on it
  with `implementation(projects.core.<moduleName>)`.
- Core modules are published alongside the plugins (group `dev.tilbrook`, artifactId
  `kandroid-core-<module-name>`) because plugin artifacts depend on them at runtime. Give each one the
  same `testRepo` publishing repository the plugins use so integration tests can resolve it.
- Keep them free of AGP/Kotlin Gradle Plugin implementation classes: compile against `gradle-api`
  only, as the plugins do.
- Packages: `dev.tilbrook.kandroid.core.<module>`.

There are no core modules yet.
