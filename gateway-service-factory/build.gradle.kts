plugins {
  `java-library`
}

dependencies {
  api("org.hypertrace.core.serviceframework:platform-grpc-service-framework:0.1.59")

  implementation(project(":gateway-service-impl"))
}
