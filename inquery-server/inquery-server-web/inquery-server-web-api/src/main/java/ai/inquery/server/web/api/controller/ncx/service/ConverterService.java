package ai.inquery.server.web.api.controller.ncx.service;

import ai.inquery.server.web.api.controller.ncx.vo.UploadVO;

import java.io.File;
import java.io.InputStream;

/**
 * ConverterService
 *
 **/
public interface ConverterService {

    UploadVO uploadFile(File file);

    UploadVO dbpUploadFile(File file);

    UploadVO datagripUploadFile(String text);
}
