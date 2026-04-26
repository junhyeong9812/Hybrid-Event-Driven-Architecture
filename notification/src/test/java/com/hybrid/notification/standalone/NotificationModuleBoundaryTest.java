package com.hybrid.notification.standalone;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.hybrid.notification")
class NotificationModuleBoundaryTest {

    @ArchTest
    static final ArchRule notification은_order_payment에_의존하지_않는다 =
            noClasses().that().resideInAPackage("com.hybrid.notification..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.hybrid.order..", "com.hybrid.payment..");

    @ArchTest
    static final ArchRule 통신은_kafka_inbox를_통해서만 =
            classes().that().resideInAPackage("com.hybrid.notification..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage(
                            "com.hybrid.notification..",
                            "com.hybrid.common.event..",
                            "java..", "jakarta..", "org.springframework..",
                            "org.apache.kafka..", "com.fasterxml..", "org.slf4j..");
}