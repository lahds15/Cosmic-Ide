plugins {
    id(BuildPlugins.JAVA_LIBRARY)
    id(BuildPlugins.LINT)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    compileOnly Dependencies.GUAVA
    implementation("com.google.auto.value:auto-value-annotations:1.9")
    annotationProcessor("com.google.auto.value:auto-value:1.9")
    implementation(Dependencies.JAVAC)
}
