package com.mola.cmd.proxy.app.acp.configui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScheduleAdminUiContractTest {
    @Test
    public void exposesPaginatedResponsiveScheduleAdministration() throws Exception {
        String html = loadConfigUi();
        assertTrue(html.contains("data-page=\"schedules\""));
        assertTrue(html.contains("id=\"page-schedules\""));
        assertTrue(html.contains("id=\"schedulePagination\""));
        assertTrue(html.contains("function loadSchedules("));
        assertTrue(html.contains("function openScheduleEdit("));
        assertTrue(html.contains("function deleteScheduleTask("));
        assertTrue(html.contains("function openScheduleExecutions("));
        assertTrue(html.contains("function renderScheduleExecutionLoading()"));
        assertTrue(html.contains("renderScheduleExecutionLoading();showDialog('scheduleExecutionsDialog');loadScheduleExecutions(1)"));
        assertTrue(html.contains("height:min(720px,calc(100dvh - 48px));max-width:none;max-height:none"));
        assertTrue(html.contains(".schedule-execution-dialog .toolbar,.schedule-execution-dialog #scheduleExecutionPagination{flex:0 0 auto}"));
        assertTrue(html.contains(".schedule-execution-list>.filter-empty{flex:1 1 auto;display:flex;flex-direction:column;align-items:center;justify-content:center}"));
        assertFalse(html.contains("if(scheduleExecutions.totalPages<=1){box.innerHTML='';return}"));
        assertTrue(html.contains("/api/schedules/v1"));
        assertTrue(html.contains("已受理（最终结果未记录）"));
        assertTrue(html.contains("if(item.status==='SUCCEEDED')return ['执行完成'"));
        assertTrue(html.contains("包含已删除的一次性任务和历史记录"));
        assertTrue(html.contains(".schedule-list-card{container:schedule-list/inline-size}"));
        assertTrue(html.contains("grid-template-columns:minmax(0,1.15fr) minmax(0,.85fr) minmax(0,.7fr) minmax(72px,.55fr)"));
        assertTrue(html.contains("@container schedule-list (max-width:1000px)"));
        assertTrue(html.contains(".schedule-list-row>.schedule-last-run{display:none}"));
        assertTrue(html.contains("@container schedule-list (max-width:800px)"));
        assertTrue(html.contains(".schedule-list-row>.schedule-owner{display:none}"));
        assertTrue(html.contains("@container schedule-list (max-width:620px)"));
        assertTrue(html.contains(".schedule-list-row>.schedule-rule{display:none}"));
        assertTrue(html.contains("@media(max-width:700px){.schedule-stats"));
        assertTrue(html.contains(".schedule-list-row>.schedule-owner,.schedule-list-row>.schedule-rule,.schedule-list-row>.schedule-last-run,.schedule-list-row>.schedule-next-run{display:grid"));
        assertTrue(html.contains("class=\"schedule-last-run\""));
        assertTrue(html.contains("class=\"schedule-next-run\""));
        assertTrue(html.contains(".schedule-last-run:before{content:'上次执行'"));
        assertTrue(html.contains(".schedule-next-run:before{content:'下次执行'"));
        assertTrue(html.contains(".schedule-stat,.schedule-list-row,.schedule-execution"));
    }

    private static String loadConfigUi() throws Exception {
        return ConfigUiTestResources.loadBundle();
    }
}
