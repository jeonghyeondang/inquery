package ai.inquery.server.domain.api.service;

import ai.inquery.server.domain.api.model.Task;
import ai.inquery.server.domain.api.param.TaskCreateParam;
import ai.inquery.server.domain.api.param.TaskPageParam;
import ai.inquery.server.domain.api.param.TaskUpdateParam;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;

public interface TaskService {

    /**
     * create task
     *
     * @param param task param
     * @return task id
     */
    DataResult<Long> create(TaskCreateParam param);

    /**
     * update task status
     *
     * @param param task param
     * @return action result
     */
    ActionResult updateStatus(TaskUpdateParam param);


    /**
     * get task list
     *
     * @param param task id
     * @return task
     */
    PageResult<Task> page(TaskPageParam param);

    /**
     * get task
     *
     * @param id task id
     * @return task
     */
    DataResult<Task> get(Long id);
}
