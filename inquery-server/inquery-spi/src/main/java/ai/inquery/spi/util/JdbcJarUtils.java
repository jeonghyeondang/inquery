
package ai.inquery.spi.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 */
public class JdbcJarUtils {

    private static final OkHttpClient async_client = new OkHttpClient.Builder()
        .dispatcher(new Dispatcher(Executors.newFixedThreadPool(20))) // Set thread pool size
        .build();

    private static final OkHttpClient client = new OkHttpClient();

    public static final String PATH = System.getProperty("user.home") + File.separator + ".inquery" + File.separator
        + "jdbc-lib" + File.separator;

    static {
        File file = new File(PATH);
        if (!file.exists()) {
            file.mkdirs();
        }
    }

    public static void asyncDownload(List<String> urls) throws Exception {
        for (String url : urls) {
            String outputPath = PATH + url.substring(url.lastIndexOf("/") + 1);
            File file = new File(outputPath);
            if (file.exists()) {
                continue;
            }
            asyncDownload(url);
        }
    }

    public static void asyncDownload(String url) throws Exception {
        String outputPath = PATH + url.substring(url.lastIndexOf("/") + 1);
        File file = new File(outputPath);
        if (file.exists()) {
            file.delete();
        }
        Request request = new Request.Builder()
            .url(url)
            .build();
        async_client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected code " + response);
                }
                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(outputPath)) {
                    byte[] buffer = new byte[2048];
                    int length;
                    while ((length = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, length);
                    }
                    fos.flush();
                }
            }
        });
    }

    public static void download(String url) throws IOException {
        String outputPath = PATH + url.substring(url.lastIndexOf("/") + 1);
        File file = new File(outputPath);
        if (file.exists()) {
            file.delete();
        }
        Request request = new Request.Builder()
            .url(url)
            .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            try (InputStream is = response.body().byteStream();
                 FileOutputStream fos = new FileOutputStream(outputPath)) {

                byte[] buffer = new byte[2048];
                int length;
                while ((length = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, length);
                }
                fos.flush();
            }
        }
        // Some drivers (e.g. BigQuery) are distributed as a zip bundle of many jars.
        // Extract the contained jar files into the lib directory so the driver loader
        // can find each jar by its exact name, then remove the downloaded archive.
        if (outputPath.toLowerCase().endsWith(".zip")) {
            extractJars(file);
            file.delete();
        }
    }

    /**
     * Extracts all .jar entries from a downloaded driver archive directly into the
     * jdbc-lib directory (flattened, ignoring any internal folder structure).
     */
    private static void extractJars(File zipFile) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
                String jarName = slash >= 0 ? name.substring(slash + 1) : name;
                if (!jarName.toLowerCase().endsWith(".jar")) {
                    continue;
                }
                File target = new File(PATH + jarName);
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int length;
                    while ((length = zis.read(buffer)) != -1) {
                        fos.write(buffer, 0, length);
                    }
                    fos.flush();
                }
            }
        }
    }

    public static String getNewFullPath(String jarPath) {
        String path = PATH + jarPath;
        File file = new File(path);
        if (file.exists()) {
            file.delete();
        }
        return getFullPath(jarPath);
    }

    public static String getFullPath(String jarPath) {
        String path = PATH + jarPath;
        File file = new File(path);
        if (!file.exists()) {
            String url = getDownloadUrl(jarPath);
            // Automatic download on connect is disabled for security. When the driver
            // jar is missing, fail with a clear, actionable message instead of a cryptic
            // network error so users know exactly what to do.
            if (url == null || url.isEmpty()) {
                throw new RuntimeException(
                    "JDBC driver not installed: '" + jarPath + "' is missing. "
                        + "Open the connection settings and click \"Download Driver\", "
                        + "or place the driver jar(s) manually in: " + PATH);
            }
            try {
                download(url);
            } catch (IOException e) {
                try {
                    download(url);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
        return path;
    }

    // External CDN disabled for security - drivers should be manually downloaded to ~/.inquery/jdbc-lib/
    public static final String DOWNLOAD_URL_HOST = "";
    private static String getDownloadUrl(String jarPath) {
       // Return empty string to disable external downloads
       return "";
    }
}
