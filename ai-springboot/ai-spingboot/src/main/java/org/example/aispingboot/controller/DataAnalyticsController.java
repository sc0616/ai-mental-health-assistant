package org.example.aispingboot.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.aispingboot.common.Result;
import org.example.aispingboot.service.DataAnalyticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 后台数据统计接口
 * <p>
 * 补齐前端 dashboard.vue（管理端"数据分析"页）调用但后端不存在的接口：
 * <pre>GET /api/data-analytics/overview</pre>
 * 此前该路径返回 404，axios 走 reject 分支，页面上所有卡片与图表都是空的。
 * <p>
 * 该路径不在 SecurityConfig 的公开白名单内，必须携带有效 token 才能访问。
 */
@Slf4j
@RestController
@RequestMapping("/api/data-analytics")
public class DataAnalyticsController {

    @Autowired
    private DataAnalyticsService dataAnalyticsService;

    /**
     * 数据分析总览
     *
     * @param days 趋势统计天数，默认 7 天
     */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(
            @RequestParam(value = "days", required = false) Integer days) {
        return Result.ok(dataAnalyticsService.overview(days));
    }
}
