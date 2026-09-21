fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = properties("plugin.group").get()
version = properties("plugin.version").get()

repositories {
    mavenLocal()
    maven {
        setUrl("https://maven.aliyun.com/nexus/content/groups/public/")
    }
    maven {
        setUrl("https://maven.aliyun.com/nexus/content/repositories/jcenter")
    }
    maven {
        setUrl("https://maven.aliyun.com/nexus/content/repositories/google")
    }
    maven {
        setUrl("https://maven.aliyun.com/nexus/content/repositories/gradle-plugin")
    }
    mavenCentral()
}

dependencies {
    implementation("cn.hutool:hutool-all:" + properties("dependencies.hutool.version").get())
    // https://mvnrepository.com/artifact/com.alibaba.nacos/nacos-client
    implementation("com.alibaba.nacos:nacos-client:" + properties("dependencies.nacos.client.version").get()) {
        exclude(group = "org.slf4j")
    }


    // https://mvnrepository.com/artifact/org.projectlombok/lombok
    compileOnly("org.projectlombok:lombok:" + properties("dependencies.lombok.version").get())
    annotationProcessor("org.projectlombok:lombok:" + properties("dependencies.lombok.version").get())

    testCompileOnly("org.projectlombok:lombok:" + properties("dependencies.lombok.version").get())
    testAnnotationProcessor("org.projectlombok:lombok:" + properties("dependencies.lombok.version").get())
    // https://mvnrepository.com/artifact/org.yaml/snakeyaml
    implementation("org.yaml:snakeyaml:" + properties("dependencies.snakeyaml.version").get())
    // https://mvnrepository.com/artifact/com.fasterxml.jackson.dataformat/jackson-dataformat-yaml
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:" + properties("dependencies.jackson-dataformat-yaml.version").get())

    // 阿里云 STS 临时凭证（P1 core/cloud）
    implementation("com.aliyun:sts20150401:" + properties("dependencies.aliyun.sts.version").get())
    implementation("com.aliyun:credentials-java:" + properties("dependencies.aliyun.credentials.version").get())
    // https://mvnrepository.com/artifact/net.lingala.zip4j/zip4j （P2 工具安装用）
    implementation("net.lingala.zip4j:zip4j:" + properties("dependencies.zip4j.version").get())
    // pom.xml 正则改写引擎（core/maven MavenUtil）
    implementation("org.apache.maven:maven-model:" + properties("dependencies.maven.model.version").get())

    // JUnit5 最小单测（ConfigManager / PortUtil）
    testImplementation(platform("org.junit:junit-bom:5.9.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set(properties("intellij.version").get())
    type.set(properties("intellij.type").get()) // Target IDE Platform

    plugins.set(listOf("com.intellij.java", "org.jetbrains.kotlin", "com.intellij.database", "com.intellij.spring.boot", "org.jetbrains.plugins.terminal"))
}

tasks {
    test {
        // JUnit5 平台（P0 最小单测：ConfigManager/PortUtil）
        useJUnitPlatform()
    }

    runIde{
        // 启用热重载功能，使用Build菜单编译项目后无需重启调试进程即可完成, 仅支持JBR
//        jvmArgs = listOf(
//                "-XX:+AllowEnhancedClassRedefinition",
//        )
    }

    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = JavaVersion.VERSION_17.toString()
        targetCompatibility = JavaVersion.VERSION_17.toString()
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = JavaVersion.VERSION_17.toString()
    }

    patchPluginXml {
        sinceBuild.set("231")
        untilBuild.set("262.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}

