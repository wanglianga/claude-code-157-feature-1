package com.hospital.escort

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 集成测试基类: zonky 嵌入式 PostgreSQL(无需外部数据库),
 * Flyway 自动建表。Kafka 默认关闭(本地模拟同步)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {

    companion object {
        private val pg: EmbeddedPostgres = EmbeddedPostgres.builder()
            .setServerConfig("timezone", "UTC")
            .start()

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { pg.getJdbcUrl("postgres", "postgres") }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "postgres" }
        }
    }
}
