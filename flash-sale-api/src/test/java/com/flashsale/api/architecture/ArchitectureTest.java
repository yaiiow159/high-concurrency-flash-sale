package com.flashsale.api.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.Architectures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架構規則的自動化驗證。
 *
 * <p><b>為什麼架構要寫成測試？</b>
 * 寫在 README 裡的分層約定，會在專案的第三個月被某個趕死線的人打破——
 * 通常只是「先 import 一下 JPA，之後再重構」，然後就永遠留在那裡了。
 * 分層腐化從來不是一次大崩壞，而是一連串看似無害的小妥協。
 *
 * <p>把約定寫成會失敗的測試，違規在 code review 之前就會被 CI 擋下，
 * 而且擋下的理由清楚寫在錯誤訊息裡，不需要有人在 PR 下面留言解釋一次。
 */
@DisplayName("架構約束")
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.flashsale");
    }

    @Test
    @DisplayName("分層依賴只能由外往內：api → infrastructure → application → domain")
    void layersMustNotBeViolated() {
        Architectures.layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Domain").definedBy("com.flashsale.domain..")
                .layer("Application").definedBy("com.flashsale.application..")
                .layer("Infrastructure").definedBy("com.flashsale.infrastructure..")
                .layer("Api").definedBy("com.flashsale.api..")

                .whereLayer("Api").mayNotBeAccessedByAnyLayer()
                .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Api")
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Infrastructure", "Api")
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Infrastructure", "Api")

                .check(classes);
    }

    @Test
    @DisplayName("領域層必須零框架依賴——這是它能被快速測試的前提")
    void domainMustNotDependOnFrameworks() {
        noClasses().that().resideInAPackage("com.flashsale.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "com.fasterxml.jackson..",
                        "org.apache.kafka..",
                        "org.redisson..",
                        "io.micrometer..")
                .because("領域層一旦沾上框架，業務規則就再也無法脫離基礎設施被測試")
                .check(classes);
    }

    @Test
    @DisplayName("應用層不得認得任何具體的技術選型")
    void applicationMustNotDependOnInfrastructureTechnology() {
        noClasses().that().resideInAPackage("com.flashsale.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.flashsale.infrastructure..",
                        "org.springframework.data..",
                        "org.springframework.web..",
                        "org.apache.kafka..",
                        "org.redisson..",
                        "jakarta.persistence..")
                .because("Use Case 應該只認得 Port 介面；換掉 Redis 或 Kafka 不該需要改動應用層")
                .check(classes);
    }

    @Test
    @DisplayName("Controller 只能依賴入站埠，不得直接呼叫 Repository")
    void controllersMustGoThroughUseCases() {
        noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .because("Controller 直接存取 Repository 會讓業務邏輯漏進 Web 層，並繞過交易邊界")
                .check(classes);
    }

    @Test
    @DisplayName("JPA Entity 只能存在於 persistence 套件內，不得外洩")
    void entitiesMustStayInPersistencePackage() {
        classes().that().areAnnotatedWith(jakarta.persistence.Entity.class)
                .should().resideInAPackage("com.flashsale.infrastructure.adapter.out.persistence.entity")
                .because("Entity 外洩會讓延遲載入代理跑到交易之外，並把資料庫結構變成對外契約")
                .check(classes);
    }

    @Test
    @DisplayName("禁止使用 System.out／System.err 輸出")
    void mustUseLoggerInsteadOfSystemOut() {
        noClasses().should().accessField(System.class, "out")
                .orShould().accessField(System.class, "err")
                .because("繞過日誌框架的輸出無法分級、無法收集，在生產環境等同於什麼都沒印")
                .check(classes);
    }

    @Test
    @DisplayName("Port 介面必須是介面，且不得依賴具體實作")
    void portsMustBeInterfaces() {
        classes().that().resideInAPackage("com.flashsale.application.port..")
                .and().haveSimpleNameNotEndingWith("Command")
                .and().areNotRecords()
                .and().areNotNestedClasses()
                .should().beInterfaces()
                .because("Port 是抽象邊界，出現具體類別代表邊界已被穿透")
                .check(classes);
    }
}
