package ai.inquery.server.domain.core.converter;

import ai.inquery.server.domain.api.model.Task;
import ai.inquery.server.domain.api.param.TaskCreateParam;
import ai.inquery.server.domain.repository.entity.TaskDO;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

import java.util.List;
@Slf4j
@Mapper(componentModel = "spring")
public abstract class TaskConverter {

    @Mappings({
        @Mapping(target = "id", ignore = true),
        @Mapping(target = "gmtCreate", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
        @Mapping(target = "deleted", ignore = true),
        @Mapping(target = "taskStatus", ignore = true),
        @Mapping(target = "downloadUrl", ignore = true),
        @Mapping(target = "content", ignore = true),
    })
    public abstract TaskDO todo(TaskCreateParam param);


    public abstract Task toModel(TaskDO param);


    public abstract List<Task> toModel(List<TaskDO> param);
}
