package com.monimo.api.common.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.dao.QueryTimeoutException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.SQLExceptionTranslator
import java.net.SocketTimeoutException
import java.sql.Connection
import java.time.Duration
import javax.sql.DataSource

// API server 는 DB 두 개에 붙는다.
// - PostgreSQL (기본, @Primary): 설정 · 규칙 · 채널 · 사용자. JPA가 쓴다 (ADR #42)
// - ClickHouse: 스팬 · 지표 · 로그는 조회만, thread_dumps 는 쓰기도 한다 (ADR #36). clickHouseJdbcTemplate 으로 SQL을 직접 쓴다
@Configuration(proxyBeanMethods = false)
class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    fun postgresDataSourceProperties() = DataSourceProperties()

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    fun postgresDataSource(postgresDataSourceProperties: DataSourceProperties): HikariDataSource =
        postgresDataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource::class.java).build()

    @Bean
    @ConfigurationProperties("monimo.clickhouse")
    fun clickHouseDataSource(@Value("\${monimo.clickhouse-wait-timeout}") waitTimeout: Duration): HikariDataSource =
        WaitLimitedDataSource(waitTimeout.toMillis().toInt()).apply { poolName = "clickhouse" }

    @Bean
    fun clickHouseJdbcTemplate(@Qualifier("clickHouseDataSource") clickHouseDataSource: DataSource) =
        NamedParameterJdbcTemplate(JdbcTemplate(clickHouseDataSource).apply { exceptionTranslator = timeoutAware(exceptionTranslator) })

    // 빌려줄 때마다 연결에 대기 상한을 건다 (#287). 드라이버 socket_timeout 은 Hikari 가 연결 점검 뒤 0(무제한)으로 되돌려 안 먹는다
    private class WaitLimitedDataSource(private val waitMs: Int) : HikariDataSource() {
        override fun getConnection(): Connection = super.getConnection().apply { setNetworkTimeout(Runnable::run, waitMs) }
    }

    // ClickHouse 시간 초과 둘(서버 상한 TIMEOUT_EXCEEDED, 대기 상한)을 QueryTimeoutException 으로 바꿔 503 으로 응답하게 한다.
    // 그냥 두면 서버 쪽은 SQLState 22000 이라 DataIntegrityViolationException, 대기 쪽은 분류가 안 돼 둘 다 500 이 된다
    private fun timeoutAware(fallback: SQLExceptionTranslator) = SQLExceptionTranslator { task, sql, ex ->
        val timedOut = ex.errorCode == CH_TIMEOUT_EXCEEDED || generateSequence<Throwable>(ex) { it.cause }.any { it is SocketTimeoutException }
        if (timedOut) QueryTimeoutException("$task: ${ex.message}", ex) else fallback.translate(task, sql, ex)
    }

    private companion object {
        const val CH_TIMEOUT_EXCEEDED = 159
    }
}
