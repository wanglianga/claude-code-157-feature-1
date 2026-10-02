package com.hospital.escort.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** OpenAPI 3.x 元数据: 接口文档 /v3/api-docs, Swagger UI /swagger-ui/index.html */
@Configuration
class OpenApiConfig {

    @Bean
    fun escortOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("医院陪护证申请核验进出记录退证服务 API")
            .version("1.0.0")
            .description(
                "住院患者陪护全生命周期: 申请/审批/发证同步/进出核验/替换/违规/退证, " +
                    "以及普通病房转重症过渡病区的陪护证迁移(暂停原证/家属确认/新病区审批/必要交接/旧证退回/押金释放)。"
            )
            .license(License().name("Hospital Internal"))
    )
}
