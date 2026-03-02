plugins {
  id("java-library")
  id("application")
  alias(libs.plugins.versions)
  alias(libs.plugins.spotless)
  alias(libs.plugins.checkerFramework)
}

group = "oscarvarto.mx"
version = "1.0.0-SNAPSHOT"
description = "Playwright Practice"

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(25))
  }
  withSourcesJar()
}

application {
    mainClass = "oscarvarto.mx.perf.BifurcanExercises"
}

dependencies {
  // Lombok should be annotation-processor based (no delombok needed for Checker Framework)
  compileOnly(libs.lombok)
  annotationProcessor(libs.lombok)
  testCompileOnly(libs.lombok)
  testAnnotationProcessor(libs.lombok)

  // Plugin 1.0.x manages checker + checker-qual dependencies automatically

  implementation(libs.functionaljava)
  implementation(libs.bifurcan)
  implementation(libs.playwright)
  implementation(platform(libs.jackson.bom))
  implementation(libs.jackson.databind)
  implementation(libs.jackson.module.parameter.names)
  implementation(libs.jackson.datatype.jsr310)
  implementation(libs.jackson.datatype.jdk8)
  implementation(libs.typesafe.config)
  implementation(libs.slf4j.api)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.junit.platform.launcher)
  testImplementation(libs.assertj)
  testImplementation(libs.logback.classic)
  testImplementation(libs.turso)
  testImplementation(libs.flyway.core)
  testImplementation(libs.sqlite.jdbc)
  testImplementation(platform(libs.aws.bom))
  testImplementation(libs.aws.secretsmanager)
  testImplementation(platform(libs.testcontainers.bom))
  testImplementation(libs.testcontainers.localstack)
  testImplementation(libs.testcontainers.junit.jupiter)
}

// Compile project-defined qualifier annotations FIRST so SubtypingChecker can load them
val compileQualifiers by tasks.registering(JavaCompile::class) {
  description = "Compiles Checker Framework qualifier annotations so checkers can load them."
  group = "build"

  source = fileTree("src/main/java") {
    include("**/qual/*.java")
  }

  // Needs Checker Framework annotation types on classpath (e.g., org.checkerframework.framework.qual.*)
  classpath = configurations.compileClasspath.get()

  destinationDirectory.set(layout.buildDirectory.dir("qualifiers-classes"))

  options.encoding = "UTF-8"
  options.release.set(25)

  // Prevent Checker Framework (and any other processors) from running on this bootstrap compile
  options.compilerArgs.add("-proc:none")
}

// Make qualifier classes visible to both main and test compilation
for (taskName in listOf("compileJava", "compileTestJava")) {
  tasks.named<JavaCompile>(taskName) {
    dependsOn(compileQualifiers)

    val qualifierOut = files(compileQualifiers.map { it.destinationDirectory })

    // Make qualifier classes visible to compilation
    classpath += qualifierOut

    // Critical: make qualifier classes visible to the checker (processor classloader)
    // In plugin 1.0.x, annotationProcessor already extends checkerFramework,
    // so we only need to append our compiled qualifiers.
    options.annotationProcessorPath = files(
      options.annotationProcessorPath,                  // already includes checker deps via plugin
      qualifierOut                                      // your compiled qualifiers
    )
  }
}

tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
  options.release.set(25)
  options.compilerArgs.addAll(
    listOf(
      "-parameters",
      "-Xmaxerrs",
      "10000",
      "-Xmaxwarns",
      "10000",
    )
  )

  // Silence "sun.misc.Unsafe::objectFieldOffset" warnings from Lombok on JDK 24+.
  // Lombok uses sun.misc.Unsafe internally (lombok.permit.Permit) and hasn't migrated
  // to VarHandle yet. Forking javac lets us pass the JEP 498 flag to the compiler JVM.
  //   - JEP 471 (deprecation):   https://openjdk.org/jeps/471
  //   - JEP 498 (warn phase):    https://openjdk.org/jeps/498
  //   - Lombok tracking issue:   https://github.com/projectlombok/lombok/issues/3852
  // NOTE: JDK 26 moves to Phase 3 (throws by default). This workaround still opts in,
  //       but should be removed once Lombok migrates off sun.misc.Unsafe.
  options.isFork = true
  options.forkOptions.jvmArgs = listOf("--sun-misc-unsafe-memory-access=allow")
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("playwrightCli") {
  group = "playwright"
  description = "Runs the Playwright CLI. Pass commands via --args, e.g. --args=\"codegen https://example.com\""
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("com.microsoft.playwright.CLI")
}

spotless {
  format("misc") {
    target(".gitattributes", ".gitignore")
    trimTrailingWhitespace()
    endWithNewline()
    leadingSpacesToTabs(2)
  }
  java {
    toggleOffOn()
    encoding("UTF-8")
    palantirJavaFormat(libs.versions.palantirJavaFormat.get()).apply {
      style("PALANTIR")
      formatJavadoc(false)
    }
    importOrder("", "\\#")
    removeUnusedImports()
    formatAnnotations()
  }
}

checkerFramework {
  checkers = listOf(
    "org.checkerframework.common.subtyping.SubtypingChecker",
    // "org.checkerframework.checker.nullness.NullnessChecker",
  )
  extraJavacArgs =
    listOf(
      "-Aquals="
        + "oscarvarto.mx.qual.ErrorMsg,oscarvarto.mx.qual.NotErrorMsg,"
        + "oscarvarto.mx.qual.Name,oscarvarto.mx.qual.NotName",
      "-Awarns",
    )
  excludeTests = false
  version = libs.versions.checkerframework.get()
}
