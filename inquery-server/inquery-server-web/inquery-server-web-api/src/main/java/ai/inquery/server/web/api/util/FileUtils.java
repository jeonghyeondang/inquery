package ai.inquery.server.web.api.util;

/**
 * FileUtil
 *
 **/
public class FileUtils {

    public enum ConfigFile {
        // navicat connection information file
        NCX,
        //dbeaver connection information file
        DBP
    }

    public static String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf(".");
        if (dotIndex > 0) {
            return fileName.substring(dotIndex + 1).toLowerCase();
        } else {
            return "";
        }
    }

}
