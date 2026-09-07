package com.mola.cmd.proxy.app.acp.task.api;
import com.mola.cmd.proxy.app.acp.task.service.TaskService;
import com.mola.cmd.proxy.app.acp.task.model.TaskException;
/** Lifecycle registration owned by the application composition root. */
public final class TaskApiBridge {
    private static volatile TaskService service;
    private TaskApiBridge(){}
    public static synchronized void install(TaskService value){if(value==null)throw new IllegalArgumentException("service");service=value;}
    public static synchronized void clear(TaskService expected){if(service==expected)service=null;}
    public static TaskService getService(){TaskService value=service;if(value==null)throw new TaskException("NOT_READY","Task service is not ready",503);return value;}
}
