package ai.inquery.server.web.api.controller.redis;

import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.web.api.controller.redis.request.KeyCreateRequest;
import ai.inquery.server.web.api.controller.redis.request.KeyDeleteRequest;
import ai.inquery.server.web.api.controller.redis.request.KeyQueryRequest;
import ai.inquery.server.web.api.controller.redis.request.KeyUpdateRequest;
import ai.inquery.server.web.api.controller.redis.vo.KeyVO;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * redis key operation and maintenance class
 *
 * @version MysqlTableManageController.java, v 0.1 September 16, 2022 17:41 moji Exp $
 */
@RequestMapping("/api/redis/key")
@RestController
public class RedisKeyManageController {

    /**
     * Query the key list under the current DB
     *
     * @param request
     * @return
     */
    @GetMapping("/list")
    public ListResult<KeyVO> list(KeyQueryRequest request) {
        return null;
    }

    /**
     * Add Key
     *
     * @param request
     * @return
     */
    @PostMapping("/create")
    public ActionResult create(@RequestBody KeyCreateRequest request) {
        return null;
    }

    /**
     * Modify key information
     *
     * @param request
     * @return
     */
    @RequestMapping(value = "/update",method = {RequestMethod.POST, RequestMethod.PUT})
    public ActionResult update(@RequestBody KeyUpdateRequest request) {
        return null;
    }


    /**
     * Delete key
     *
     * @param request
     * @return
     */
    @DeleteMapping("/delete")
    public ActionResult delete(@RequestBody KeyDeleteRequest request) {
        return null;
    }
}
