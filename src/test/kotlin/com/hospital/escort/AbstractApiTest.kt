package com.hospital.escort

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.hospital.escort.api.ApplyRequest
import com.hospital.escort.api.DecisionRequest
import com.hospital.escort.domain.ScreeningType
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 测试辅助: 常用 REST 调用与造数 */
abstract class AbstractApiTest : AbstractIntegrationTest() {

    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var rest: TestRestTemplate

    @Autowired
    protected lateinit var mapper: ObjectMapper

    protected fun url(path: String) = "http://localhost:$port$path"

    protected fun post(path: String, body: Any): ResponseEntity<JsonNode> =
        rest.exchange(url(path), HttpMethod.POST, HttpEntity(body), JsonNode::class.java)

    protected fun get(path: String): ResponseEntity<JsonNode> =
        rest.getForEntity(url(path), JsonNode::class.java)

    protected fun exchange(path: String, method: HttpMethod, body: Any): ResponseEntity<JsonNode> =
        rest.exchange(url(path), method, HttpEntity(body), JsonNode::class.java)

    protected fun screeningValid(): Instant = Instant.now().plus(2, ChronoUnit.DAYS)

    protected fun applyRequest(
        patientNo: String = "IP-0001",
        wardCode: String = "W3A",
        bedNo: String? = "12-3",
        escortName: String = "张三",
        escortIdNo: String = "ID10000001",
        escortPhone: String = "13800000001",
        relation: String? = "儿子",
        screening: ScreeningType = ScreeningType.NAT,
        needNightCare: Boolean = false,
        daysFromNow: Long = 7
    ) = ApplyRequest(
        patientInpatientNo = patientNo, wardCode = wardCode, bedNo = bedNo,
        escortName = escortName, escortIdNo = escortIdNo, escortPhone = escortPhone,
        relationToPatient = relation, healthCommitment = true,
        screeningType = screening, screeningValidUntil = screeningValid(),
        periodStart = Instant.now().minusSeconds(3600),
        periodEnd = Instant.now().plus(daysFromNow, ChronoUnit.DAYS),
        needNightCare = needNightCare
    )

    /** 提交申请并审批通过, 返回(caseNo, permitNo, applicationNo) */
    protected fun applyAndApprove(
        req: ApplyRequest = applyRequest(),
        decidedBy: String = "NURSE01",
        approveNightCare: Boolean = false
    ): Triple<String, String, String> {
        val created = post("/api/applications", req)
        assert(created.statusCode.is2xxSuccessful) { "apply failed: ${created.body}" }
        val appNo = created.body!!["applicationNo"].asText()
        val caseNo = created.body!!["caseNo"].asText()
        val decision = post(
            "/api/applications/$appNo/decision",
            DecisionRequest(approved = true, decidedBy = decidedBy, approveNightCare = approveNightCare, note = "符合陪护条件")
        )
        assert(decision.statusCode.is2xxSuccessful) { "decide failed: ${decision.body}" }
        val archive = get("/api/cases/$caseNo/archive").body!!
        val permitNo = archive["permits"][0]["permitNo"].asText()
        return Triple(caseNo, permitNo, appNo)
    }
}
