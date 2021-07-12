package me.libraryaddict.disguise.utilities.mineskin;

import com.google.gson.Gson;
import lombok.Getter;
import lombok.Setter;
import me.libraryaddict.disguise.utilities.DisguiseUtilities;
import me.libraryaddict.disguise.utilities.SkinUtils;
import me.libraryaddict.disguise.utilities.translations.LibsMsg;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Created by libraryaddict on 28/12/2019.
 * <p>
 * This isn't a stanealone class
 */
public class MineSkinAPI {
    private class APIError {
        int code;
        String error;
    }

    /**
     * Time in millis until next request can be made
     */
    private long nextRequest;
    private final ReentrantLock lock = new ReentrantLock();
    @Getter
    @Setter
    private boolean debugging;
    @Getter
    @Setter
    private String apiKey;

    public boolean isInUse() {
        return lock.isLocked();
    }

    public int nextRequestIn() {
        long timeTillNext = nextRequest - System.currentTimeMillis();

        if (timeTillNext < 0) {
            return 0;
        }

        return (int) Math.ceil(timeTillNext / 1000D);
    }

    /**
     * Fetches image from the provided url
     *
     * @param url
     */
    public MineSkinResponse generateFromUrl(SkinUtils.SkinCallback callback, String url, SkinUtils.ModelType modelType) {
        return doPost(callback, "/generate/url", url, null, modelType);
    }

    private void printDebug(String message) {
        if (!isDebugging()) {
            return;
        }

        System.out.println("[MineSkinAPI] " + message);
    }

    private MineSkinResponse doPost(SkinUtils.SkinCallback callback, String path, String skinUrl, File file, SkinUtils.ModelType modelType) {
        lock.lock();

        long sleep = nextRequest - System.currentTimeMillis();

        if (file != null) {
            printDebug("Grabbing a skin from file at " + file.getPath());
        } else if (skinUrl != null) {
            printDebug("Grabbing a skin from url '" + skinUrl + "'");
        }

        if (getApiKey() != null) {
            printDebug("Using a MineSkin api key!");
        }

        if (sleep > 0) {
            printDebug("Sleeping for " + sleep + "ms before calling the API due to a recent request");

            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        HttpURLConnection connection = null;
        long nextRequestIn = TimeUnit.SECONDS.toMillis(10);

        try {
            if (getApiKey() != null) {
                path += (path.contains("?") ? '&' : '?') + "key=" + getApiKey();
            }

            URL url = new URL("https://api.mineskin.org" + path);
            // Creating a connection
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(19000);
            connection.setReadTimeout(19000);
            connection.setDoOutput(true);

            connection.setRequestProperty("User-Agent", "LibsDisguises");

            String boundary = "LD@" + Long.toHexString(System.currentTimeMillis()); // Just generate some unique random value.
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            String charset = "UTF-8";
            String CRLF = "\r\n"; // Line separator required by multipart/form-data.

            try (OutputStream output = connection.getOutputStream(); PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, charset), true)) {
                // Send normal param.
                writer.append("--").append(boundary).append(CRLF);
                writer.append("Content-Disposition: form-data; name=\"visibility\"").append(CRLF);
                writer.append("Content-Type: text/plain; charset=").append(charset).append(CRLF);
                writer.append(CRLF).append("1").append(CRLF).flush();

                if (file != null) {
                    // Send binary file.
                    writer.append("--").append(boundary).append(CRLF);
                    writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(file.getName()).append("\"").append(CRLF);
                    writer.append("Content-Type: image/png").append(CRLF);
                    writer.append("Content-Transfer-Encoding: binary").append(CRLF);
                    writer.append(CRLF).flush();
                    Files.copy(file.toPath(), output);
                    output.flush(); // Important before continuing with writer!
                    writer.append(CRLF).flush(); // CRLF is important! It indicates end of boundary.
                } else if (skinUrl != null) {
                    // Send normal param.
                    writer.append("--").append(boundary).append(CRLF);
                    writer.append("Content-Disposition: form-data; name=\"url\"").append(CRLF);
                    writer.append(CRLF).append(skinUrl).append(CRLF).flush();
                }

                if (modelType == SkinUtils.ModelType.SLIM) {
                    writer.append("--").append(boundary).append(CRLF);
                    writer.append("Content-Disposition: form-data; name=\"model\"").append(CRLF);
                    writer.append(CRLF).append("slim").append(CRLF).flush();
                }

                // End of multipart/form-data.
                writer.append("--").append(boundary).append("--").append(CRLF).flush();
            }

            printDebug("Received status code: " + connection.getResponseCode());

            if (connection.getResponseCode() == 500) {
                String errorMessage = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8)).lines()
                        .collect(Collectors.joining("\n"));

                APIError error = new Gson().fromJson(errorMessage, APIError.class);

                printDebug("Received error: " + errorMessage);

                if (error.code == 403) {
                    callback.onError(LibsMsg.SKIN_API_FAIL_CODE, "" + error.code, LibsMsg.SKIN_API_403.get());
                    return null;
                } else if (error.code == 404) {
                    callback.onError(LibsMsg.SKIN_API_FAIL_CODE, "" + error.code, LibsMsg.SKIN_API_404.get());
                    return null;
                } else if (error.code == 408 || error.code == 504 || error.code == 599) {
                    callback.onError(LibsMsg.SKIN_API_FAIL_CODE, "" + error.code, LibsMsg.SKIN_API_TIMEOUT.get());
                    return null;
                } else {
                    callback.onError(LibsMsg.SKIN_API_FAIL_CODE, "" + error.code, LibsMsg.SKIN_API_IMAGE_HAS_ERROR.get(error.error));
                    return null;
                }
            } else if (connection.getResponseCode() == 400) {
                if (skinUrl != null) {
                    callback.onError(LibsMsg.SKIN_API_BAD_URL);
                    return null;
                } else if (file != null) {
                    callback.onError(LibsMsg.SKIN_API_BAD_FILE);
                    return null;
                }
            } else if (connection.getResponseCode() == 429) {
                callback.onError(LibsMsg.SKIN_API_FAIL_TOO_FAST);
                return null;
            }

            // Get the input stream, what we receive
            try (InputStream input = connection.getInputStream()) {
                // Read it to string
                String response = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));

                printDebug("Received: " + response);

                MineSkinResponse skinResponse = new Gson().fromJson(response, MineSkinResponse.class);

                nextRequestIn = (long) (skinResponse.getNextRequest() * 1000);

                return skinResponse;
            }
        } catch (SocketTimeoutException ex) {
            if (isDebugging()) {
                ex.printStackTrace();
            }

            callback.onError(skinUrl == null ? LibsMsg.SKIN_API_TIMEOUT_ERROR : LibsMsg.SKIN_API_IMAGE_TIMEOUT);
            return null;
        } catch (Exception ex) {
            try {
                if (connection != null && (connection.getResponseCode() == 524 || connection.getResponseCode() == 408 || connection.getResponseCode() == 504 ||
                        connection.getResponseCode() == 599)) {
                    if (getApiKey() != null && connection.getResponseCode() == 504) {
                        callback.onError(LibsMsg.SKIN_API_TIMEOUT_API_KEY_ERROR);
                    } else {
                        callback.onError(LibsMsg.SKIN_API_TIMEOUT_ERROR);
                    }

                    return null;
                }
            } catch (IOException ignored) {
            }

            if (DisguiseUtilities.getLogger() != null) {
                DisguiseUtilities.getLogger().warning("Failed to access MineSkin.org");
            }

            ex.printStackTrace();

            callback.onError(LibsMsg.SKIN_API_FAIL);
        } finally {
            nextRequest = System.currentTimeMillis() + nextRequestIn + 1000;
            lock.unlock();
        }

        return null;
    }

    public MineSkinResponse generateFromUUID(UUID uuid, SkinUtils.ModelType modelType) throws IllegalArgumentException {
        lock.lock();

        long sleep = nextRequest - System.currentTimeMillis();

        if (sleep > 0) {
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        long nextRequestIn = TimeUnit.SECONDS.toMillis(10);

        try {
            String siteUrl = "https://api.mineskin.org/generate/user/:" + uuid.toString();

            if (modelType == SkinUtils.ModelType.SLIM) {
                siteUrl += "?model=slim";
            }

            URL url = new URL(siteUrl);
            // Creating a connection
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestProperty("User-Agent", "LibsDisguises");
            con.setDoOutput(true);

            // Get the input stream, what we receive
            try (InputStream input = con.getInputStream()) {
                // Read it to string
                String response = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));

                MineSkinResponse skinResponse = new Gson().fromJson(response, MineSkinResponse.class);

                nextRequestIn = (long) (skinResponse.getNextRequest() * 1000);

                return skinResponse;
            }
        } catch (Exception ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("Server returned HTTP response code: 400 for URL")) {
                throw new IllegalArgumentException();
            }

            if (DisguiseUtilities.getLogger() != null) {
                DisguiseUtilities.getLogger().warning("Failed to access MineSkin.org");
            }
            ex.printStackTrace();
        } finally {
            nextRequest = System.currentTimeMillis() + nextRequestIn + 1000;
            lock.unlock();
        }

        return null;
    }

    /**
     * Uploads png file
     *
     * @param file
     */
    public MineSkinResponse generateFromFile(SkinUtils.SkinCallback callback, File file, SkinUtils.ModelType modelType) {
        return doPost(callback, "/generate/upload", null, file, modelType);
    }
}
