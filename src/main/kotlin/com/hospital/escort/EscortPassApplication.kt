package com.hospital.escort

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class EscortPassApplication

fun main(args: Array<String>) {
    runApplication<EscortPassApplication>(*args)
}
