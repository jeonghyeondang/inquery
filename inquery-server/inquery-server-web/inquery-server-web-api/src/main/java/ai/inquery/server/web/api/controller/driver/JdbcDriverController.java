package ai.inquery.server.web.api.controller.driver;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import ai.inquery.server.domain.api.service.JdbcDriverService;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.web.api.controller.driver.request.JdbcDriverRequest;
import ai.inquery.spi.config.DBConfig;
import ai.inquery.spi.util.JdbcJarUtils;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * JDBC driver management
 *
 * @version JdbcDriverController.java, v 0.1 September 16, 2022 17:41 moji Exp $
 */
@RequestMapping("/api/jdbc/driver")
@RestController
public class JdbcDriverController {

    @Autowired
    private JdbcDriverService jdbcDriverService;

    /**
     * Query current DB driver information
     *
     * @param dbType
     * @return
     */
    @GetMapping("/list")
    public DataResult<DBConfig> list(@RequestParam String dbType) {
        return jdbcDriverService.getDrivers(dbType);
    }

    /**
     * Download driver
     *
     * @param dbType
     * @return
     */

    @GetMapping("/download")
    public ActionResult download(@RequestParam String dbType) {
        return jdbcDriverService.download(dbType);
    }

    /**
     * Upload driver
     *
     * @param multipartFiles
     * @return
     */
    @PostMapping("/upload")
    public ListResult<String> upload(@RequestParam MultipartFile[] multipartFiles) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < multipartFiles.length; i++) {

            MultipartFile multipartFile = multipartFiles[i];
            String originalFilename = FilenameUtils.getName(multipartFile.getOriginalFilename());
            String location = JdbcJarUtils.PATH + originalFilename;
            try {
                multipartFile.transferTo(new File(location));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            list.add(originalFilename);
        }
        return ListResult.of(list);
    }

    /**
     * save
     *
     * @param request
     * @return
     */
    @PostMapping("/save")
    public ActionResult save(@RequestBody JdbcDriverRequest request) {

        return jdbcDriverService.upload(request.getDbType(), request.getJdbcDriverClass(),
            String.join(",", request.getJdbcDriver()));
    }

    ///**
    // * Delete driver
    // *
    // * @param request
    // * @return
    // */
    //@DeleteMapping("/delete")
    //public ActionResult delete(@RequestBody KeyDeleteRequest request) {
    //    return null;
    //}
}
