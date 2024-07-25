package com.ruoyi.gateway.config

import org.apache.commons.lang3.ObjectUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.server.WebFilter


@Configuration
open class CorsConfig {
    @Value("\${cors.orgins}")
    private val corsOrgins: String? = null

    @Bean
    open fun corsFilter(): WebFilter {
        return WebFilter { exchange, chain ->
            val response = exchange.response
            val request = exchange.request
            if (!ObjectUtils.isEmpty(corsOrgins)) {
                response.headers["Access-Control-Allow-Origin"] = corsOrgins
            } else {
                response.headers["Access-Control-Allow-Origin"] = "*"
            }
            response.headers["Access-Control-Allow-Credentials"] = "true"
            response.headers["Access-Control-Max-Age"] = "3600"
            response.headers["Access-Control-Allow-Methods"] = "GET,POST,PUT,DELETE,OPTIONS,HEAD"
            response.headers["Access-Control-Allow-Headers"] = "X-Requested-With, Content-Type, Authorization, credential, X-XSRF-TOKEN, token, Admin-Token, App-Token"
            chain.filter(exchange.mutate().request(request.mutate().build()).build())
        }
    }
}