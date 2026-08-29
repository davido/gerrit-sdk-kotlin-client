# gerrit-sdk-kotlin-client

A standalone **Bazel** consumer of the generated Gerrit Kotlin SDK
([`davido/gerrit-sdk-kotlin`](https://github.com/davido/gerrit-sdk-kotlin)), fetched
**from JitPack** via `rules_jvm_external` / `maven.install` and built with
`rules_kotlin`. It implements a colored, Web-UI-style `get-change-detail` — the Kotlin
twin of the Rust/Go/Python/TypeScript/Java examples — using only the generated SDK code.

This is the Bazel-consumption half of the Kotlin story: exactly how a **Gerrit plugin**
(itself a Bazel project) would depend on the SDK.

```
  gerrit-sdk-kotlin      JitPack                    this repo (Bazel)
  (Gradle SDK)    -->    builds tag -> Maven   -->  rules_jvm_external / maven.install
                        artifact on demand         -> kt_jvm_binary get-change-detail
```

## Run it (this is the test)

No global toolchain needed — `bazelisk` bootstraps Bazel 9.2.0, `rules_kotlin`, and the
JDK 21 toolchain; the SDK is pulled from JitPack on first run. The command *is* the
end-to-end test: it proves the JitPack-built Kotlin artifact compiles and runs against a
live Gerrit.

```bash
bazelisk run //:get-change-detail -- --change 622261
#   other change / server:
bazelisk run //:get-change-detail -- --url https://gerrit-review.googlesource.com --change 621763
#   plain (no color): add  --no-color
```

Bazel resolves `com.github.davido:gerrit-sdk-kotlin` from JitPack (and its
okhttp/gson/kotlin-stdlib transitive deps from Maven Central), compiles
`GetChangeDetail.kt` against the generated `com.google.gerrit.client.*` classes, and runs
it against a live Gerrit. The `)]}'` XSSI guard is stripped by the SDK's
`GerritXssiInterceptor` (`GerritXssiInterceptor.client()`).

Expected: a colored change summary (status badge, owner/author, submit requirements,
votes, files) — byte-for-byte the same layout as the Rust/Go/Python/TypeScript/Java
examples.

## Wiring

`MODULE.bazel` (pins the **tag** — the Kotlin SDK's tag built cleanly on JitPack the
first try, unlike the Java client which had to pin a commit around a cached failure):
```python
bazel_dep(name = "rules_kotlin", version = "2.4.10")
bazel_dep(name = "rules_jvm_external", version = "7.1")
bazel_dep(name = "rules_java", version = "9.9.0")

maven.install(
    artifacts = ["com.github.davido:gerrit-sdk-kotlin:v3.15.0-SNAPSHOT"],
    repositories = ["https://jitpack.io", "https://repo1.maven.org/maven2"],
)
```

`BUILD.bazel`:
```python
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_binary")

kt_jvm_binary(
    name = "get-change-detail",
    srcs = ["GetChangeDetail.kt"],
    main_class = "GetChangeDetailKt",
    deps = ["@maven//:com_github_davido_gerrit_sdk_kotlin"],
)
```

`.bazelrc` sets the Java language level to 21 (Gerrit's level). `MODULE.bazel.lock` is
committed for a reproducible resolution.

## License

Apache 2.0. See [LICENSE.txt](LICENSE.txt).
