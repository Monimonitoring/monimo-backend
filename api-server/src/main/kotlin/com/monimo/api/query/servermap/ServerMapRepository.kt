package com.monimo.api.query.servermap

import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.servermap.dto.ServerMapEdge
import com.monimo.api.query.servermap.dto.ServerMapNode
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

// 간선은 server_map_1m(SummingMergeTree), 노드는 service_health_1m(AggregatingMergeTree)에서 읽는다
// 결과 별칭을 원래 컬럼 이름(cnt 등)과 다르게 짓는다. 같으면 뒤의 sum(cnt) 가 합계를 다시 합치려다 실패한다
@Repository
class ServerMapRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {

    // 서비스를 고르면 그 서비스의 간선과 **이웃이 나가는 간선**까지 두 겹을 가져온다 (#259 문제 1)
    //
    // 전에는 한 겹(그 서비스가 부르거나 불리는 간선)만 봤다. 그러면 이웃이 쓰는 DB · 외부가 통째로 사라진다 :
    // 같은 창에서 실측 2026-10-28 에 필터 없음 간선 13개 · shop-order 로 걸면 4개였고, shop-gateway 로 걸면
    // DB 노드가 0개였다(gateway 는 DB 를 직접 안 쓴다). 주문 한 건이 어디까지 번지는지 보려고 쓰는 화면인데
    // 이웃의 MySQL · 결제사가 안 보이면 그 일을 못 한다.
    //
    // 이웃 집합을 서브쿼리로 만들어 **질의 한 번**에 끝낸다. 이웃 목록을 먼저 받아 와 두 번째 질의에 넣는 방법은
    // 버렸다 : 두 질의 사이에 1분 창이 넘어가면 이웃은 앞 창, 간선은 뒷 창에서 나와 어긋난다.
    // 화면이 전체를 받아 거르는 방법도 버렸다 : 서비스가 늘면 받는 양이 같이 는다.
    //
    // 이웃이 **나가는** 간선만 더한다(caller_service IN neighbors). 이웃으로 **들어오는** 간선까지 더하면
    // 고른 서비스와 상관없는 호출자가 끌려 들어온다. 되돌림 : 서비스가 수십 개가 되면 이 깊이를 설정으로 뺀다
    fun findEdges(serviceName: String?, range: TimeRange): List<ServerMapEdge> {
        val window = "ts_min >= toDateTime(:from) AND ts_min < toDateTime(:to)"
        // 고른 서비스가 부르는 쪽 · 불리는 쪽을 한 집합으로. 자기 자신이 섞여도 아래 OR 가 이미 덮으므로 그대로 둔다
        val neighborCte = if (serviceName == null) {
            ""
        } else {
            """
            WITH neighbors AS (
                SELECT callee_service AS s FROM server_map_1m WHERE $window AND caller_service = :serviceName
                UNION DISTINCT
                SELECT caller_service AS s FROM server_map_1m WHERE $window AND callee_service = :serviceName
            )

            """.trimIndent()
        }
        val serviceFilter = if (serviceName == null) {
            ""
        } else {
            "AND (caller_service = :serviceName OR callee_service = :serviceName OR caller_service IN (SELECT s FROM neighbors))"
        }
        // SummingMergeTree 는 합치기가 언제 끝날지 몰라 같은 간선이 여러 줄일 수 있다. 읽을 때 sum 으로 다시 합친다
        val sql = neighborCte + """
            SELECT
                caller_service,
                callee_service,
                toString(callee_kind)   AS kind,
                sum(cnt)                AS total_cnt,
                sum(err_cnt)            AS total_err,
                sum(sum_duration_ns)    AS total_dur_ns
            FROM server_map_1m
            WHERE $window $serviceFilter
            GROUP BY caller_service, callee_service, callee_kind
            ORDER BY caller_service, callee_service
        """.trimIndent()
        return jdbc.query(sql, params(serviceName, range)) { rs, _ ->
            val cnt = rs.getLong("total_cnt")
            ServerMapEdge(
                callerService = rs.getString("caller_service"),
                calleeService = rs.getString("callee_service"),
                calleeKind = rs.getString("kind"),
                cnt = cnt,
                errCnt = rs.getLong("total_err"),
                avgDurationMs = averageMs(rs.getLong("total_dur_ns"), cnt),
            )
        }
    }

    fun findNodes(range: TimeRange): List<ServerMapNode> {
        val sql = """
            SELECT
                service_name,
                countMerge(cnt)     AS total_cnt,
                sumMerge(err_cnt)   AS total_err
            FROM service_health_1m
            WHERE ts_min >= toDateTime(:from) AND ts_min < toDateTime(:to)
            GROUP BY service_name
            ORDER BY service_name
        """.trimIndent()
        return jdbc.query(sql, params(null, range)) { rs, _ ->
            ServerMapNode(rs.getString("service_name"), rs.getLong("total_cnt"), rs.getLong("total_err"))
        }
    }

    private fun params(serviceName: String?, range: TimeRange) = mapOf(
        "from" to range.from.epochSecond,
        "to" to range.to.epochSecond,
        "serviceName" to serviceName,
    )

    // 소수 첫째 자리까지 (명세 예: 214.7)
    private fun averageMs(totalNs: Long, cnt: Long): Double =
        if (cnt == 0L) 0.0 else Math.round(totalNs.toDouble() / cnt / 100_000) / 10.0
}
